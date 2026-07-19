@file:Suppress("unused", "OPT_IN_USAGE", "UNCHECKED_CAST", "ObsoleteSdkInt", "DEPRECATION")
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.gallerybox.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.RectF
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.util.LruCache
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gallerybox.data.*
import com.gallerybox.engine.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Collections
import java.util.UUID
import javax.inject.Inject

data class LutItem(val name: String, val path: String, val category: String, val thumbnail: Bitmap? = null)

@HiltViewModel
class EditorViewModel @Inject constructor(
    application: Application,
    private val editingEngine: EditingEngine,
    private val galleryDao: GalleryDao
) : AndroidViewModel(application) {

    private val TAG = "EditorViewModel"

    private val _events = Channel<GalleryEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val _fileOperationState = MutableStateFlow<FileOperationState>(FileOperationState.Idle)
    val fileOperationState = _fileOperationState.asStateFlow()
    private var fileOperationJob: Job? = null

    // --- HISTORY MANAGEMENT ---
    private val editMutex = Mutex()
    private val editHistory = mutableListOf<EditState>()
    private var currentEditIndex = -1

    private var isGestureActive = false
    private var gestureInitialState: EditState? = null

    private val _currentEditState = MutableStateFlow(EditState())
    val currentEditState = _currentEditState.asStateFlow()

    val aspectRatio: StateFlow<Float?> = _currentEditState
        .map { it.aspectRatio }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), _currentEditState.value.aspectRatio)

    private val _previewBitmap = MutableStateFlow<Bitmap?>(null)
    val previewBitmap = _previewBitmap.asStateFlow()
    private var originalBitmap: Bitmap? = null
    private var currentMediaUri: Uri? = null

    private val _isPreviewUpdating = MutableStateFlow(false)
    val isPreviewUpdating = _isPreviewUpdating.asStateFlow()

    private val _isComparing = MutableStateFlow(false)
    val isComparing = _isComparing.asStateFlow()

    private val lutCache = LruCache<String, CubeLut>(20)
    private val _lutItems = MutableStateFlow<List<LutItem>>(emptyList())
    val lutItems = _lutItems.asStateFlow()

    private val _stickerItems = MutableStateFlow<List<StickerUiItem>>(emptyList())
    val stickerItems = _stickerItems.asStateFlow()

    // Tracks device temperature (0-7) to scale preview resolution
    private val _thermalLevel = MutableStateFlow(0)
    val thermalLevel = _thermalLevel.asStateFlow()

    object MathUtils {
        fun calculateThermalScaleFactor(level: Int): Float = 1f - (level.coerceIn(0, 7) / 7f)
    }

    init {
        loadLuts()
        loadStickers()

        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            combine(
                currentEditState,
                isComparing
            ) { state, comparing ->
                Pair(state, comparing)
            }
                .debounce { _ ->
                    80L + (thermalLevel.value * 20L)
                }
                .collectLatest { (state, comparing) ->
                    if (comparing) {
                        _previewBitmap.value = originalBitmap
                    } else {
                        generatePreview(state, thermalLevel.value)
                    }
                }
        }
    }

    fun updateThermalLevel(level: Int) {
        _thermalLevel.value = level.coerceIn(0, 7)
    }

    fun initializeEditor(mediaItem: MediaItem) = viewModelScope.launch {
        editMutex.withLock {
            editHistory.clear()
            currentEditIndex = -1
            val initialState = EditState(contrast = 1f, saturation = 1f)
            editHistory.add(initialState)
            currentEditIndex = 0
            _currentEditState.value = initialState
        }

        if (!mediaItem.isVideo) {
            // Skip re-decoding if the same media is opened
            if (currentMediaUri == mediaItem.uri && originalBitmap != null) {
                generatePreview(_currentEditState.value, _thermalLevel.value)
                return@launch
            }

            currentMediaUri = mediaItem.uri

            withContext(Dispatchers.IO) {
                try {
                    val resolver = getApplication<Application>().contentResolver
                    val maxDimen = 1080

                    val sourceBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, mediaItem.uri)) { decoder, info, _ ->
                            decoder.allocator = ImageDecoder.ALLOCATOR_DEFAULT
                            decoder.isMutableRequired = true

                            // Decode directly to target size to save memory on large images
                            if (info.size.width > maxDimen || info.size.height > maxDimen) {
                                val scale = minOf(
                                    maxDimen.toFloat() / info.size.width,
                                    maxDimen.toFloat() / info.size.height
                                )
                                decoder.setTargetSize(
                                    (info.size.width * scale).toInt(),
                                    (info.size.height * scale).toInt()
                                )
                            }
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        val fullBitmap = MediaStore.Images.Media.getBitmap(resolver, mediaItem.uri)
                        if (fullBitmap.width > maxDimen || fullBitmap.height > maxDimen) {
                            val scale = minOf(
                                maxDimen.toFloat() / fullBitmap.width,
                                maxDimen.toFloat() / fullBitmap.height
                            )
                            val scaled = Bitmap.createScaledBitmap(
                                fullBitmap,
                                (fullBitmap.width * scale).toInt(),
                                (fullBitmap.height * scale).toInt(),
                                true
                            )
                            fullBitmap.recycle()
                            scaled
                        } else {
                            fullBitmap
                        }
                    }

                    // Ensure bitmap is in ARGB_8888 for reliable processing
                    val finalBitmap = if (sourceBitmap.config != Bitmap.Config.ARGB_8888) {
                        val converted = sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)
                        sourceBitmap.recycle()
                        converted
                    } else {
                        sourceBitmap
                    }

                    setOriginalBitmap(finalBitmap)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to init editor bitmap", e)
                }
            }
        }
    }

    // --- GESTURE & HISTORY LOGIC ---
    fun beginGesture() = viewModelScope.launch {
        editMutex.withLock {
            isGestureActive = true
            gestureInitialState = _currentEditState.value
        }
    }

    fun endGesture() = viewModelScope.launch {
        editMutex.withLock {
            isGestureActive = false
            val newState = _currentEditState.value
            if (gestureInitialState != null && gestureInitialState != newState) {
                pushToHistory(newState)
            }
            gestureInitialState = null
        }
    }

    fun updateEditState(update: (EditState) -> EditState) = viewModelScope.launch {
        editMutex.withLock {
            val newState = update(_currentEditState.value)
            if (newState == _currentEditState.value) return@withLock

            _currentEditState.value = newState

            if (!isGestureActive) {
                pushToHistory(newState)
            }
        }
    }

    private fun pushToHistory(state: EditState) {
        // Prevent duplicate states
        if (editHistory.isNotEmpty() && editHistory.last() == state) return

        if (currentEditIndex < editHistory.size - 1) {
            editHistory.subList(currentEditIndex + 1, editHistory.size).clear()
        }
        editHistory.add(state)
        while (editHistory.size > 50) editHistory.removeAt(0)
        currentEditIndex = editHistory.lastIndex
    }

    fun undo() = viewModelScope.launch {
        editMutex.withLock {
            if (currentEditIndex > 0) {
                currentEditIndex--
                _currentEditState.value = editHistory[currentEditIndex]
            }
        }
    }

    fun redo() = viewModelScope.launch {
        editMutex.withLock {
            if (currentEditIndex < editHistory.size - 1) {
                currentEditIndex++
                _currentEditState.value = editHistory[currentEditIndex]
            }
        }
    }

    fun setOriginalBitmap(bitmap: Bitmap) {
        if (originalBitmap != bitmap) {
            originalBitmap?.recycle()
            originalBitmap = bitmap
        }
        viewModelScope.launch { generatePreview(_currentEditState.value, _thermalLevel.value) }
    }

    private suspend fun generatePreview(state: EditState, currentThermalLevel: Int) {
        val src = originalBitmap ?: return
        _isPreviewUpdating.value = true
        withContext(Dispatchers.Default) {
            try {
                // Dynamically downscale preview if the device is getting too hot
                val scale = MathUtils.calculateThermalScaleFactor(currentThermalLevel)
                val workingBitmap = if (scale < 1.0f) {
                    Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
                } else {
                    src
                }

                // Render overlays onto the working bitmap preview
                val newPreview = editingEngine.createPreview(
                    bitmap = workingBitmap,
                    state = state,
                    renderOverlays = true
                )

                val oldPreview = _previewBitmap.value

                _previewBitmap.value = newPreview

                // Safely recycle old bitmaps
                if (workingBitmap != src && workingBitmap != newPreview) {
                    workingBitmap.recycle()
                }
                if (oldPreview != null && oldPreview != originalBitmap && oldPreview != newPreview) {
                    oldPreview.recycle()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to generate preview", e)
            }
        }
        _isPreviewUpdating.value = false
    }

    fun setComparing(value: Boolean) {
        _isComparing.value = value
    }

    fun resetEditor() = updateEditState { EditState(contrast = 1f, saturation = 1f) }

    // --- TRANSFORMS ---
    fun resetCrop() = updateEditState { it.copy(cropRect = RectF(0f, 0f, 1f, 1f), aspectRatio = null, straightenDegrees = 0f) }
    fun setAspectRatio(ratio: Float?) = updateEditState { it.copy(aspectRatio = ratio) }
    fun updateCropRect(rect: RectF) = updateEditState { it.copy(cropRect = RectF(rect)) }
    fun rotateLeft() = updateEditState { it.copy(rotationDegrees = (it.rotationDegrees - 90f) % 360f) }
    fun rotateRight() = updateEditState { it.copy(rotationDegrees = (it.rotationDegrees + 90f) % 360f) }
    fun toggleFlipHorizontal() = updateEditState { it.copy(flipHorizontal = !it.flipHorizontal) }
    fun toggleFlipVertical() = updateEditState { it.copy(flipVertical = !it.flipVertical) }

    // --- DRAW & MOSAIC TOOLS ---
    fun addDrawStroke(stroke: DrawLayer) = updateEditState { it.copy(drawLayers = it.drawLayers + stroke) }
    fun clearDrawings() = updateEditState { it.copy(drawLayers = emptyList()) }
    fun addMosaicRegion(region: RectF, intensity: Float) = updateEditState {
        it.copy(mosaicRegions = it.mosaicRegions + MosaicLayer(id = UUID.randomUUID().toString(), region = region, intensity = intensity))
    }

    // --- TEXT ENGINE ---
    fun addText(text: String, color: Int = Color.WHITE, size: Float = 40f) = updateEditState { state ->
        val offset = (state.textLayers.size * 0.03f)
        val cx = (0.5f + offset).coerceAtMost(0.8f)
        val cy = (0.5f + offset).coerceAtMost(0.8f)

        state.copy(
            textLayers = state.textLayers + TextLayer(
                id = UUID.randomUUID().toString(),
                text = text,
                color = color,
                size = size,
                x = cx,
                y = cy,
                rotation = 0f,
                opacity = 1f,
                isVisible = true
            )
        )
    }
    fun updateText(id: String, update: (TextLayer) -> TextLayer) = updateEditState { state -> state.copy(textLayers = state.textLayers.map { if (it.id == id) update(it) else it }) }
    fun removeText(id: String) = updateEditState { state -> state.copy(textLayers = state.textLayers.filterNot { it.id == id }) }
    fun duplicateText(id: String) = updateEditState { state ->
        val layer = state.textLayers.find { it.id == id } ?: return@updateEditState state
        state.copy(
            textLayers = state.textLayers + layer.copy(
                id = UUID.randomUUID().toString(),
                x = (layer.x + 0.05f).coerceIn(0f, 1f),
                y = (layer.y + 0.05f).coerceIn(0f, 1f)
            )
        )
    }
    fun moveTextLayer(id: String, moveUp: Boolean) = updateEditState { state ->
        val list = state.textLayers.toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return@updateEditState state
        val targetIndex = if (moveUp) index + 1 else index - 1
        if (targetIndex in list.indices) Collections.swap(list, index, targetIndex)
        state.copy(textLayers = list)
    }
    fun toggleTextVisibility(id: String) = updateText(id) { it.copy(isVisible = !it.isVisible) }
    fun clearText() = updateEditState { it.copy(textLayers = emptyList()) }

    // --- STICKER ENGINE ---
    private fun loadStickers() = viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            val assets = getApplication<Application>().assets
            val allItems = mutableListOf<StickerUiItem>()
            assets.list("stickers")?.forEach { category ->
                assets.list("stickers/$category")?.forEach { subFolder ->
                    assets.list("stickers/$category/$subFolder")?.filter { it.endsWith(".svg", true) }?.forEach { file ->
                        val cleanName = file.substringBeforeLast(".").replace("-", " ").replaceFirstChar { it.uppercase() }
                        val cleanCat = category.replace("-", " ").replaceFirstChar { it.uppercase() }
                        allItems.add(StickerUiItem(name = cleanName, category = cleanCat, assetPath = "stickers/$category/$subFolder/$file", emoji = ""))
                    }
                }
            }
            _stickerItems.value = allItems
        }.onFailure { Log.e(TAG, "Failed to load stickers", it) }
    }
    fun addSticker(assetPath: String) = updateEditState { state ->
        val offset = (state.stickers.size * 0.03f)
        val cx = (0.5f + offset).coerceAtMost(0.8f)
        val cy = (0.5f + offset).coerceAtMost(0.8f)

        state.copy(
            stickers = state.stickers + StickerLayer(
                id = UUID.randomUUID().toString(),
                assetPath = assetPath,
                x = cx,
                y = cy,
                scale = 1f,
                rotation = 0f,
                opacity = 1f,
                isVisible = true
            )
        )
    }
    fun updateSticker(id: String, update: (StickerLayer) -> StickerLayer) = updateEditState { state -> state.copy(stickers = state.stickers.map { if (it.id == id) update(it) else it }) }
    fun removeSticker(id: String) = updateEditState { state -> state.copy(stickers = state.stickers.filterNot { it.id == id }) }
    fun duplicateSticker(id: String) = updateEditState { state ->
        val layer = state.stickers.find { it.id == id } ?: return@updateEditState state
        state.copy(
            stickers = state.stickers + layer.copy(
                id = UUID.randomUUID().toString(),
                x = (layer.x + 0.05f).coerceIn(0f, 1f),
                y = (layer.y + 0.05f).coerceIn(0f, 1f)
            )
        )
    }
    fun moveStickerLayer(id: String, moveUp: Boolean) = updateEditState { state ->
        val list = state.stickers.toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return@updateEditState state
        val targetIndex = if (moveUp) index + 1 else index - 1
        if (targetIndex in list.indices) Collections.swap(list, index, targetIndex)
        state.copy(stickers = list)
    }
    fun toggleStickerVisibility(id: String) = updateSticker(id) { it.copy(isVisible = !it.isVisible) }
    fun clearStickers() = updateEditState { it.copy(stickers = emptyList()) }

    // --- LUT ENGINE ---
    private fun loadLuts() = viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            val assets = getApplication<Application>().assets
            val allItems = mutableListOf<LutItem>()
            assets.list("luts")?.forEach { category ->
                assets.list("luts/$category")?.filter { it.endsWith(".cube", true) }?.forEach { file ->
                    val name = file.substringBeforeLast(".")
                    val preview = sequenceOf("thumbnails/$category/$name.jpg", "thumbnails/$category/$name.png", "thumbnails/$name.jpg")
                        .firstNotNullOfOrNull { path -> runCatching { assets.open(path).use { BitmapFactory.decodeStream(it) } }.getOrNull() }
                    allItems.add(LutItem(name.replaceFirstChar { it.uppercase() }, "luts/$category/$file", category, preview))
                }
            }
            _lutItems.value = allItems
        }.onFailure { Log.e(TAG, "Failed to load LUTs", it) }
    }
    fun applyLut(item: LutItem) = viewModelScope.launch(Dispatchers.IO) {
        try {
            val cachedLut = lutCache.get(item.path)
            if (cachedLut != null) {
                updateEditState { it.copy(lutData = cachedLut, lutIntensity = 1f, filterId = item.name) }
                return@launch
            }
            val newLut = editingEngine.loadLut(item.path)
            if (newLut != null) {
                lutCache.put(item.path, newLut)
                updateEditState { it.copy(lutData = newLut, lutIntensity = 1f, filterId = item.name) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply LUT ${item.name}", e)
        }
    }
    fun setLutIntensity(value: Float) = updateEditState { it.copy(lutIntensity = value) }
    fun clearLut() = updateEditState { it.copy(lutData = null, lutIntensity = 1f, filterId = null) }

    // --- VIDEO ENGINE ---
    fun setTrimRange(startMs: Long, endMs: Long) = updateEditState { it.copy(trimStartMs = startMs, trimEndMs = endMs) }

    fun commitTrim(startMs: Long, endMs: Long) = updateEditState {
        it.copy(trimStartMs = startMs, trimEndMs = endMs)
    }

    fun exportFrame(uri: Uri, posMs: Long) = viewModelScope.launch(Dispatchers.IO) {
        try {
            val file = editingEngine.extractFrame(uri, posMs)
            if (file != null) {
                MediaScannerConnection.scanFile(getApplication(), arrayOf(file.absolutePath), null) { _, _ -> }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export frame", e)
        }
    }

    fun cancelCurrentOperation() {
        fileOperationJob?.cancel()
        editingEngine.cancelExport()
        _fileOperationState.value = FileOperationState.Idle
    }

    fun saveMedia(mediaItem: MediaItem, targetWidth: Int = 1920, targetHeight: Int = 1080, targetFps: Int = 30, videoBitrate: Int = 15000000, exportAsSticker: Boolean = false) {
        fileOperationJob?.cancel()
        fileOperationJob = viewModelScope.launch(Dispatchers.IO) {
            _fileOperationState.value = FileOperationState.Editing(0f)
            try {
                val file = editingEngine.saveMedia(
                    uri = mediaItem.uri,
                    state = _currentEditState.value,
                    targetWidth = targetWidth,
                    targetHeight = targetHeight,
                    targetFps = targetFps,
                    videoBitrate = videoBitrate,
                    isVideo = mediaItem.isVideo,
                    asSticker = exportAsSticker
                ) { progress ->
                    _fileOperationState.value = FileOperationState.Editing(progress)
                }

                if (file != null) {
                    MediaScannerConnection.scanFile(getApplication(), arrayOf(file.absolutePath), null) { _, _ -> }
                    _events.send(GalleryEvent.ShowToast("Media saved successfully"))
                } else {
                    _events.send(GalleryEvent.ShowToast("Export failed or was cancelled."))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Export failed", e)
                _events.send(GalleryEvent.ShowToast("An error occurred during export."))
            } finally {
                _fileOperationState.value = FileOperationState.Idle
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        editingEngine.cancelExport()
        lutCache.evictAll()
        editHistory.clear()
        originalBitmap?.recycle()
        _previewBitmap.value?.recycle()
    }
}