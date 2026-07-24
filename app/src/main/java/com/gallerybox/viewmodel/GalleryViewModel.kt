@file:Suppress("UnsafeOptInUsageError", "UnstableApiUsage", "OPT_IN_USAGE", "unused", "DEPRECATION", "BlockingMethodInNonBlockingContext", "MemberVisibilityCanBePrivate", "OVERRIDE_DEPRECATION")
@file:SuppressLint("UnsafeOptInUsageError")
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.gallerybox.viewmodel

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Application
import android.content.*
import android.database.ContentObserver
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import android.util.LruCache
import androidx.core.net.toUri
import com.gallerybox.data.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.paging.*
import androidx.work.*
import com.gallerybox.engine.*
import com.gallerybox.ui.screens.picture.GalleryGridItem
import com.gallerybox.ui.screens.trash.TrashCleanupWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.*
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.exp
import kotlin.math.log2

// --- Constants & Enums ---
const val ID_CAMERA = "virtual_camera"
const val ID_RECENT = "virtual_recent"
const val ID_FAVORITES = "virtual_favorites"
const val ID_SCREENSHOTS = "virtual_screenshots"
const val ID_DOWNLOADS = "virtual_downloads"
const val ID_WHATSAPP = "virtual_whatsapp"
const val ID_INSTAGRAM = "virtual_instagram"

fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    val (height: Int, width: Int) = options.outHeight to options.outWidth
    var inSampleSize = 1
    if (height > reqHeight || width > reqWidth) {
        val halfHeight: Int = height / 2
        val halfWidth: Int = width / 2
        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}

// --- States & Events ---
sealed class GalleryEvent {
    data class ShowToast(val message: String) : GalleryEvent()
    data class RequestPermission(val intentSender: IntentSender) : GalleryEvent()
    data object OperationSuccess : GalleryEvent()
    data class LaunchIntent(val intent: Intent) : GalleryEvent()
}

sealed interface GalleryViewerState {
    data object Closed : GalleryViewerState
    data object Loading : GalleryViewerState
    data class Open(
        val mediaId: Long,
        val isVideo: Boolean,
        val uri: Uri,
        val zoom: Float = 1f,
        val rotation: Float = 0f,
        val offsetX: Float = 0f,
        val offsetY: Float = 0f,
        val playbackPosition: Long = 0L,
        val playWhenReady: Boolean = true,
        val playbackSpeed: Float = 1f,
        val controlsVisible: Boolean = true
    ) : GalleryViewerState
    data class Error(val message: String) : GalleryViewerState
}

sealed class FileOperationState {
    data object Idle : FileOperationState()
    data class Processing(val progressPercentage: Float, val itemsProcessed: Int, val totalItems: Int) : FileOperationState()
    data object WaitingForPermission : FileOperationState()
    data class Editing(val progress: Float) : FileOperationState()
}

enum class SaveMode { SAVE_AS_NEW, REPLACE_ORIGINAL }
enum class LockType { NONE, PIN, PATTERN }
enum class AlbumSort { DateDesc, DateAsc, NameAsc, NameDesc, SizeDesc, CountDesc, Custom }
enum class PhotoSort { NameAsc, NameDesc, SizeDesc, DateAsc, DateDesc }
enum class MediaTypeFilter { ALL, PHOTOS, VIDEOS }
enum class MergeMode { MOVE, COPY, MOVE_AND_DELETE }

data class ExportAdvanced(val bitrate: Int = 10000000, val fps: Int = 30, val codec: String = "video/avc")
data class MediaIndexes(val all: List<MediaItem> = emptyList(), val photos: List<MediaItem> = emptyList(), val videos: List<MediaItem> = emptyList(), val gifs: List<MediaItem> = emptyList(), val recent: List<MediaItem> = emptyList())
private data class FilterState(val f: MediaTypeFilter, val q: String, val s: List<Long>, val t: List<TrashEntity>)

data class VideoPlaybackState(
    val uri: String? = null,
    val position: Long = 0L,
    val speed: Float = 1f,
    val playWhenReady: Boolean = true
)

// --- ViewModel ---
@HiltViewModel
class GalleryViewModel @Inject constructor(
    application: Application,
    val dao: GalleryDao,
    val editingEngine: EditingEngine,
    val engine: GalleryEngine
) : AndroidViewModel(application), ComponentCallbacks2 {

    companion object {
        private const val TAG = "GalleryViewModel"
        private val dateFormat = object : ThreadLocal<SimpleDateFormat>() { override fun initialValue() = SimpleDateFormat("yyyyMMdd", Locale.getDefault()) }
        fun sameDay(a: Long, b: Long): Boolean { val df = dateFormat.get() ?: return false; return df.format(Date(a * 1000)) == df.format(Date(b * 1000)) }
    }

    private val _events = Channel<GalleryEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val busyMutex = Mutex()
    private val _isBusy = MutableStateFlow(false)
    val isBusy = _isBusy.asStateFlow()

    private val _fileOperationState = MutableStateFlow<FileOperationState>(FileOperationState.Idle)
    val fileOperationState = _fileOperationState.asStateFlow()
    private var fileOperationJob: Job? = null

    private val _thermalState = MutableStateFlow(PowerManager.THERMAL_STATUS_NONE)
    private var lastMediaStoreGeneration = 0L
    @Volatile private var lastSyncTime = 0L
    @Volatile private var lastFullScanTime = 0L
    @Volatile private var currentCacheSizeMB = 128
    @Volatile private var currentPageSize = 64

    private val activePagingSources = CopyOnWriteArrayList<MediaPagingSource>()

    // --- Single Reusable Player ---
    private var sharedPlayer: ExoPlayer? = null

    fun getPlayer(): ExoPlayer {
        if (sharedPlayer == null) {
            sharedPlayer = ExoPlayer.Builder(getApplication())
                .setSeekBackIncrementMs(5000)
                .setSeekForwardIncrementMs(5000)
                .build()
                .apply {
                    repeatMode = Player.REPEAT_MODE_OFF
                    playWhenReady = true
                    setHandleAudioBecomingNoisy(true)
                }
        }
        return sharedPlayer!!
    }

    fun resetPlayer(player: ExoPlayer) {
        if (sharedPlayer === player) {
            sharedPlayer?.pause()
            sharedPlayer?.playWhenReady = false
            sharedPlayer?.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        }
    }

    fun stepFrame(player: Player, forward: Boolean) {
        try {
            val fps = player.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO }
                ?.mediaTrackGroup?.getFormat(0)?.frameRate?.takeIf { it > 0f } ?: 30f

            val frameDuration = (1000f / fps).toLong()
            val newPosition = if (forward) player.currentPosition + frameDuration else player.currentPosition - frameDuration
            val target = newPosition.coerceIn(0L, player.duration.coerceAtLeast(0L))

            player.seekTo(target)
            player.pause()
        } catch (e: Exception) {
            Log.e(TAG, "Frame step failed", e)
        }
    }
    // ------------------------------

    val usageStatsMap = dao.getAllUsageStats().map { list -> list.associateBy { it.mediaId } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private val _activeFilter = MutableStateFlow(MediaTypeFilter.ALL); val activeFilter = _activeFilter.asStateFlow()
    private val _activeSort = MutableStateFlow(PhotoSort.DateDesc); val activeSort = _activeSort.asStateFlow()
    private val _albumSort = MutableStateFlow(AlbumSort.Custom); val albumSort = _albumSort.asStateFlow()
    private val _searchQuery = MutableStateFlow(""); val searchQuery = _searchQuery.asStateFlow()
    private val _viewerState = MutableStateFlow<GalleryViewerState>(GalleryViewerState.Closed); val viewerState = _viewerState.asStateFlow()
    private val _hiddenAlbums = MutableStateFlow<Set<String>>(emptySet()); val hiddenAlbums = _hiddenAlbums.asStateFlow()

    private val _rawMedia = MutableStateFlow<List<MediaItem>>(emptyList()); val rawMedia = _rawMedia.asStateFlow()
    private val _mediaIndexes = MutableStateFlow(MediaIndexes())
    private val _mediaMap = MutableStateFlow<HashMap<Long, MediaItem>>(HashMap()); val mediaMap = _mediaMap.asStateFlow()

    private val searchCache = LruCache<String, List<MediaItem>>(50)
    private val sortedCache = LruCache<String, List<MediaItem>>(20)
    private val albumPreviewCacheMap = ConcurrentHashMap<String, MutableList<Uri>>()
    private val _albumPreviewCache = MutableStateFlow<Map<String, List<Uri>>>(emptyMap()); val albumPreviewMap = _albumPreviewCache.asStateFlow()

    private val _allAlbumsState = MutableStateFlow<List<Album>>(emptyList()); val allAlbumsState = _allAlbumsState.asStateFlow()
    private val _albumsState = MutableStateFlow<List<Album>>(emptyList()); val albumsState = _albumsState.asStateFlow()

    val trashBin = dao.getTrash().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val favoriteIds = dao.getFavoriteIds().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val secureIds = dao.getSecureMediaIds().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val hiddenMedia = combine(_rawMedia, secureIds) { raw, secure ->
        val secureSet = secure.toSet()
        raw.filter { secureSet.contains(it.id) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val albumMeta = dao.getAlbumMeta().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val manualAlbums = dao.getManualAlbums().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _duplicates = MutableStateFlow<List<List<MediaItem>>>(emptyList()); val duplicates = _duplicates.asStateFlow()

    val media: StateFlow<List<MediaItem>> = _mediaIndexes.map { it.all }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // --- Video Playlist States ---
    private val _videoPlaylist = MutableStateFlow<List<String>>(emptyList())
    val videoPlaylist = _videoPlaylist.asStateFlow()

    private val _currentVideo = MutableStateFlow<String?>(null)
    val currentVideo = _currentVideo.asStateFlow()

    private val _currentVideoIndex = MutableStateFlow(0)
    val currentVideoIndex = _currentVideoIndex.asStateFlow()

    private val _playbackState = MutableStateFlow(VideoPlaybackState())
    val playbackState = _playbackState.asStateFlow()

    fun updatePlaybackState(uri: String, position: Long, speed: Float, playWhenReady: Boolean) {
        _playbackState.value = VideoPlaybackState(uri, position, speed, playWhenReady)
    }

    fun openVideo(uri: String) {
        _currentVideo.value = uri
        _currentVideoIndex.value = _videoPlaylist.value.indexOf(uri).takeIf { it >= 0 } ?: 0
        if (_playbackState.value.uri != uri) {
            _playbackState.value = VideoPlaybackState(
                uri = uri,
                position = 0L,
                speed = 1f,
                playWhenReady = true
            )
        }
    }
    // -----------------------------

    private fun getThermalFactor(): Double { return when (_thermalState.value) { PowerManager.THERMAL_STATUS_NONE -> 1.0; PowerManager.THERMAL_STATUS_LIGHT -> 0.9; PowerManager.THERMAL_STATUS_MODERATE -> 0.75; PowerManager.THERMAL_STATUS_SEVERE -> 0.5; PowerManager.THERMAL_STATUS_CRITICAL -> 0.25; PowerManager.THERMAL_STATUS_EMERGENCY, PowerManager.THERMAL_STATUS_SHUTDOWN -> 0.1; else -> 1.0 } }
    private fun getMemoryFactor(): Double { val actManager = getApplication<Application>().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager; val memInfo = ActivityManager.MemoryInfo(); actManager.getMemoryInfo(memInfo); return memInfo.availMem.toDouble() / memInfo.totalMem.toDouble() }

    private fun recalculateDynamicScaling() { val actManager = getApplication<Application>().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager; val memInfo = ActivityManager.MemoryInfo(); actManager.getMemoryInfo(memInfo); val ramMB = (memInfo.totalMem / (1024 * 1024)).toDouble(); val thermal = getThermalFactor(); val memory = memInfo.availMem.toDouble() / memInfo.totalMem.toDouble(); currentCacheSizeMB = minOf((ramMB * thermal * memory).toInt(), 512); currentPageSize = maxOf(32, ((ramMB / 64) * thermal).toInt()); if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) { searchCache.resize((currentCacheSizeMB * 2).coerceIn(50, 500)); sortedCache.resize(currentCacheSizeMB.coerceIn(20, 250)) } }

    val processedMedia: StateFlow<List<MediaItem>> = combine(_mediaIndexes, _searchQuery.debounce(300), _activeFilter, _activeSort, usageStatsMap) { indexes, query, filter, sort, usageStats ->
        val q = query.trim().lowercase()
        val cacheKey = "${lastMediaStoreGeneration}_${filter.name}_${sort.name}_$q"
        val cached = sortedCache.get(cacheKey)
        if (cached != null) return@combine cached

        var result = when (filter) {
            MediaTypeFilter.ALL -> indexes.all
            MediaTypeFilter.PHOTOS -> indexes.photos
            MediaTypeFilter.VIDEOS -> indexes.videos
        }

        if (q.isNotBlank()) {
            result = result.mapNotNull { item ->
                var score = 0.0
                if (item.name.lowercase().contains(q)) score += 5.0
                if (item.bucketName.lowercase().contains(q)) score += 3.0
                if (item.isFavorite) score += 2.0

                val ageDays = maxOf(0L, (System.currentTimeMillis() / 1000L - item.dateAdded)) / 86400.0
                val recencyScore = exp(-ageDays / 30.0)
                score += (2.0 * recencyScore)

                val usageData = usageStats[item.id]
                if (usageData != null) {
                    val daysSinceOpen = maxOf(0L, System.currentTimeMillis() - usageData.lastOpened) / 86400000.0
                    val decayedUsage = usageData.openCount * exp(-daysSinceOpen / 90.0)
                    score += log2(decayedUsage + 1.0)
                }
                if (score > 0) Pair(item, score) else null
            }.sortedByDescending { it.second }.map { it.first }
        }

        result = when (sort) {
            PhotoSort.NameAsc -> result.sortedBy { it.name }
            PhotoSort.NameDesc -> result.sortedByDescending { it.name }
            PhotoSort.SizeDesc -> result.sortedByDescending { it.size }
            PhotoSort.DateAsc -> result.sortedBy { it.dateAdded }
            PhotoSort.DateDesc -> result.sortedByDescending { it.dateAdded }
        }

        sortedCache.put(cacheKey, result)
        result
    }.distinctUntilChanged().flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pagedMedia: Flow<PagingData<GalleryGridItem>> = combine(_activeFilter, _searchQuery, secureIds, trashBin) { f, q, sec, trash ->
        FilterState(f, q, sec, trash)
    }.distinctUntilChanged().flatMapLatest { state ->
        val pageSize = currentPageSize
        val prefetch = maxOf(20, (pageSize * 0.25 * getThermalFactor()).toInt())
        activePagingSources.removeAll { it.invalid }
        Pager(PagingConfig(pageSize = pageSize, prefetchDistance = prefetch, enablePlaceholders = true, initialLoadSize = pageSize, maxSize = pageSize * 4)) {
            MediaPagingSource(getApplication<Application>().contentResolver, state.f, state.q).also { activePagingSources.add(it) }
        }.flow.map { pd ->
            val secSet = state.s.toHashSet()
            val trashSet = state.t.map { it.contentUri }.toHashSet()
            pd.filter { item -> item.uri != Uri.EMPTY && !secSet.contains(item.id) && !trashSet.contains(item.uri.toString()) }
                .map { GalleryGridItem.Media(it) }
                .insertSeparators { b, a ->
                    if (a == null) null
                    else if (b == null || !sameDay(b.item.dateAdded, a.item.dateAdded)) GalleryGridItem.Header("header_${a.item.dateAdded}", a.item.dateHeader, 0)
                    else null
                }
        }
    }.cachedIn(viewModelScope)

    private var reloadJob: Job? = null
    private val mediaObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        @Deprecated("Deprecated in Java")
        override fun onChange(selfChange: Boolean) {
            reloadJob?.cancel()
            reloadJob = viewModelScope.launch { delay(500); safeLoadLibrary() }
        }
    }

    init {
        application.registerComponentCallbacks(this)
        recalculateDynamicScaling()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val powerManager = application.getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.addThermalStatusListener { status -> _thermalState.value = status; recalculateDynamicScaling() }
        }
        _hiddenAlbums.value = engine.getHiddenAlbums()

        val resolver = application.contentResolver
        resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, mediaObserver)
        resolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, mediaObserver)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.registerContentObserver(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), true, mediaObserver)
        }

        clearTempVaultCache()
        viewModelScope.launch { safeLoadLibrary() }
        viewModelScope.launch(Dispatchers.IO) {
            try { schedulePeriodicTrashCleanup() } catch (e: Exception) { Log.e(TAG, "Worker schedule fail", e) }
        }

        viewModelScope.launch(Dispatchers.Default) {
            combine(trashBin, favoriteIds, secureIds, albumMeta, _hiddenAlbums, _albumSort, manualAlbums) { _ ->
                safeLoadLibrary()
            }.collect()
        }

        viewModelScope.launch(Dispatchers.Default) {
            processedMedia.collect { list ->
                val newVideoPlaylist = list.filter { it.isVideo }.map { it.uri.toString() }
                if (_videoPlaylist.value != newVideoPlaylist) {
                    _videoPlaylist.value = newVideoPlaylist
                }
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        recalculateDynamicScaling()
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            searchCache.evictAll()
            sortedCache.evictAll()
            albumPreviewCacheMap.clear()
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        recalculateDynamicScaling()
    }

    override fun onLowMemory() {
        onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }

    suspend fun forceSync() { safeLoadLibrary(forceRefresh = true) }
    fun refreshData() = viewModelScope.launch { forceSync() }

    @Volatile private var isReloading = false
    private suspend fun safeLoadLibrary(forceRefresh: Boolean = false) {
        if (isReloading) return
        val now = System.currentTimeMillis()
        if (now - lastSyncTime < 150 && !forceRefresh) return

        val memoryPressure = 1.0 - getMemoryFactor()
        if (!forceRefresh && (_thermalState.value >= PowerManager.THERMAL_STATUS_SEVERE || memoryPressure > 0.85)) return

        lastSyncTime = now
        isReloading = true

        busyMutex.withLock {
            _isBusy.value = true
            try {
                val isAndroid11Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                val needsFullScan = forceRefresh || (now - lastFullScanTime > 30 * 60 * 1000L)

                val localMedia = if (isAndroid11Plus && !needsFullScan && _rawMedia.value.isNotEmpty()) {
                    val fetched = engine.fetchIncrementalMedia(lastMediaStoreGeneration)
                    val merged = java.util.HashMap<Long, MediaItem>(_rawMedia.value.size + fetched.size)
                    _rawMedia.value.forEach { merged[it.id] = it }
                    fetched.forEach { merged[it.id] = it }
                    merged.values.toList()
                } else {
                    lastFullScanTime = now
                    engine.fetchAllMedia().also {
                        if (isAndroid11Plus) lastMediaStoreGeneration = MediaStore.getGeneration(getApplication<Application>(), MediaStore.VOLUME_EXTERNAL)
                    }
                }

                _rawMedia.value = localMedia
                rebuildIndexesAndAlbums(localMedia)
                invalidatePagingSources()
            } catch (e: Exception) {
                _events.trySend(GalleryEvent.ShowToast("Library load failed: ${e.localizedMessage}"))
            } finally {
                _isBusy.value = false
                isReloading = false
            }
        }
    }

    fun scanForDuplicates() = viewModelScope.launch(Dispatchers.Default) {
        val memoryPressure = 1.0 - getMemoryFactor()
        if (_thermalState.value >= PowerManager.THERMAL_STATUS_MODERATE || memoryPressure > 0.85) {
            _events.send(GalleryEvent.ShowToast("System busy or warm. Scan paused."))
            return@launch
        }

        _isBusy.value = true
        val allImages = _mediaIndexes.value.photos
        val cores = Runtime.getRuntime().availableProcessors()
        val activeThreads = maxOf(1, ((cores - 1) * getThermalFactor()).toInt())
        val sizeGrouped = allImages.groupBy { it.size }.filter { it.value.size > 1 }.values.flatten()
        val partialHashes = ConcurrentHashMap<String, MutableList<MediaItem>>()

        withContext(Dispatchers.IO.limitedParallelism(activeThreads)) {
            sizeGrouped.map { item ->
                async {
                    try {
                        if (item.size < 25_000_000) {
                            val resolver = getApplication<Application>().contentResolver
                            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            resolver.openInputStream(item.uri)?.use { input -> BitmapFactory.decodeStream(input, null, options) }
                            val resolutionKey = "${options.outWidth}x${options.outHeight}"
                            val partialMd = MessageDigest.getInstance("SHA-256")
                            resolver.openInputStream(item.uri)?.use { input ->
                                val buffer = ByteArray(65536)
                                var bytesRead: Int
                                while (input.read(buffer).also { bytesRead = it } != -1) { partialMd.update(buffer, 0, bytesRead) }
                            }
                            val partialHash = "$resolutionKey-" + partialMd.digest().joinToString("") { "%02x".format(it) }
                            partialHashes.getOrPut(partialHash) { Collections.synchronizedList(mutableListOf()) }.add(item)
                        }
                    } catch (e: Exception) { Log.e(TAG, "Partial hash failed for ${item.name}", e) }
                }
            }.awaitAll()
        }

        val collisionCandidates = partialHashes.values.filter { it.size > 1 }
        val finalHashes = ConcurrentHashMap<String, MutableList<MediaItem>>()

        withContext(Dispatchers.IO.limitedParallelism(activeThreads)) {
            collisionCandidates.flatten().map { item ->
                async {
                    try {
                        val resolver = getApplication<Application>().contentResolver
                        val fullMd = MessageDigest.getInstance("SHA-256")
                        resolver.openInputStream(item.uri)?.use { input ->
                            val buffer = ByteArray(65536)
                            var bytesRead: Int
                            while (input.read(buffer).also { bytesRead = it } != -1) { fullMd.update(buffer, 0, bytesRead) }
                        }
                        val fullHash = fullMd.digest().joinToString("") { "%02x".format(it) }
                        finalHashes.getOrPut(fullHash) { Collections.synchronizedList(mutableListOf()) }.add(item)
                    } catch (e: Exception) { Log.e(TAG, "Full hash failed for ${item.name}", e) }
                }
            }.awaitAll()
        }

        _duplicates.value = finalHashes.values.filter { it.size > 1 }
        _isBusy.value = false
        _events.send(GalleryEvent.ShowToast("Scan complete. Found ${_duplicates.value.size} duplicate sets."))
    }

    private fun cacheAlbumPreviews(validMedia: List<MediaItem>) {
        albumPreviewCacheMap.clear()
        validMedia.sortedByDescending { item ->
            val ageDays = maxOf(0L, (System.currentTimeMillis() / 1000L - item.dateAdded)) / 86400.0
            val recencyScore = exp(-ageDays / 30.0)
            val favScore = if (favoriteIds.value.contains(item.id)) 1.0 else 0.0
            val sizeScore = minOf(item.size.toDouble() / (5 * 1024 * 1024), 1.0)
            val resolutionPixels = (item.width * item.height).toDouble()
            val qualityScore = if (resolutionPixels > 0) minOf(resolutionPixels / 1_000_000.0, 50.0) else minOf(item.size.toDouble() / (12 * 1024 * 1024), 1.0)
            (0.6 * recencyScore) + (0.2 * favScore) + (0.1 * sizeScore) + (0.1 * qualityScore)
        }.forEach { item ->
            val bList = albumPreviewCacheMap.getOrPut(item.bucketId) { mutableListOf() }
            if (bList.size < 4) bList.add(item.uri)

            val bName = item.bucketName
            if (bName.contains("Camera", true) || bName.contains("DCIM", true)) {
                val l = albumPreviewCacheMap.getOrPut(ID_CAMERA) { mutableListOf() }
                if (l.size < 4) l.add(item.uri)
            }
            if (albumPreviewCacheMap.getOrPut(ID_RECENT) { mutableListOf() }.size < 4) {
                albumPreviewCacheMap[ID_RECENT]!!.add(item.uri)
            }
            if (favoriteIds.value.contains(item.id)) {
                val l = albumPreviewCacheMap.getOrPut(ID_FAVORITES) { mutableListOf() }
                if (l.size < 4) l.add(item.uri)
            }
            if (bName.contains("Screenshot", true)) {
                val l = albumPreviewCacheMap.getOrPut(ID_SCREENSHOTS) { mutableListOf() }
                if (l.size < 4) l.add(item.uri)
            }
            if (bName.contains("WhatsApp", true)) {
                val l = albumPreviewCacheMap.getOrPut(ID_WHATSAPP) { mutableListOf() }
                if (l.size < 4) l.add(item.uri)
            }
            if (bName.contains("Instagram", true)) {
                val l = albumPreviewCacheMap.getOrPut(ID_INSTAGRAM) { mutableListOf() }
                if (l.size < 4) l.add(item.uri)
            }
            if (bName.contains("Download", true)) {
                val l = albumPreviewCacheMap.getOrPut(ID_DOWNLOADS) { mutableListOf() }
                if (l.size < 4) l.add(item.uri)
            }
        }
        _albumPreviewCache.value = albumPreviewCacheMap.toMap()
    }

    fun toggleHiddenAlbum(albumId: String) {
        _hiddenAlbums.update { if (it.contains(albumId)) it - albumId else it + albumId }
        viewModelScope.launch { forceSync() }
    }

    fun clearDuplicates() { _duplicates.value = emptyList() }

    fun getPagedMediaForAlbum(albumId: String): Flow<PagingData<GalleryGridItem>> = _mediaIndexes.map { indexes ->
        val filtered = when (albumId) {
            ID_RECENT -> indexes.recent
            ID_FAVORITES -> indexes.all.filter { favoriteIds.value.contains(it.id) }
            else -> emptyList()
        }
        PagingData.from(filtered.map { GalleryGridItem.Media(it) })
    }

    private suspend fun updateMediaLocally(id: Long, update: (MediaItem) -> MediaItem) = withContext(Dispatchers.Default) {
        val current = _mediaMap.value[id] ?: return@withContext
        val updated = update(current)
        _mediaMap.value = java.util.HashMap(_mediaMap.value).apply { put(id, updated) }
        _mediaIndexes.update { old ->
            MediaIndexes(
                all = old.all.map { if (it.id == id) updated else it },
                photos = old.photos.map { if (it.id == id) updated else it },
                videos = old.videos.map { if (it.id == id) updated else it },
                gifs = old.gifs.map { if (it.id == id) updated else it },
                recent = old.recent.map { if (it.id == id) updated else it }
            )
        }
        sortedCache.evictAll()
        searchCache.evictAll()
        invalidatePagingSources()
    }

    private suspend fun removeMediaLocally(ids: Set<Long>) = withContext(Dispatchers.Default) {
        _mediaMap.value = java.util.HashMap(_mediaMap.value).apply { ids.forEach { remove(it) } }
        _mediaIndexes.update { old ->
            MediaIndexes(
                all = old.all.filterNot { ids.contains(it.id) },
                photos = old.photos.filterNot { ids.contains(it.id) },
                videos = old.videos.filterNot { ids.contains(it.id) },
                gifs = old.gifs.filterNot { ids.contains(it.id) },
                recent = old.recent.filterNot { ids.contains(it.id) }
            )
        }
        sortedCache.evictAll()
        searchCache.evictAll()
        invalidatePagingSources()
    }

    fun saveMedia(originalMediaId: Long, saveMode: SaveMode = SaveMode.SAVE_AS_NEW, editState: EditState, exportAsSticker: Boolean = false) {
        val item = getMediaItemById(originalMediaId) ?: return _events.trySend(GalleryEvent.ShowToast("Error: Cannot find original media")).let { Unit }
        fileOperationJob?.cancel()
        fileOperationJob = viewModelScope.launch(Dispatchers.IO) {
            _fileOperationState.value = FileOperationState.Editing(0f)
            try {
                delay(100)
                val newFile = editingEngine.saveMedia(uri = item.uri, state = editState, isVideo = item.isVideo, asSticker = exportAsSticker) { progress ->
                    _fileOperationState.value = FileOperationState.Editing(progress)
                }
                if (newFile == null) {
                    _events.trySend(GalleryEvent.ShowToast("Export failed"))
                } else {
                    onMediaExported(newFile, if(saveMode == SaveMode.REPLACE_ORIGINAL) item.id else null, "Saved to Gallery!")
                }
            } catch (e: Exception) {
                _events.trySend(GalleryEvent.ShowToast("Error saving edits"))
            } finally {
                _fileOperationState.value = FileOperationState.Idle
            }
        }
    }

    fun onMediaExported(newFile: File, originalMediaId: Long? = null, customMessage: String = "Media saved to gallery successfully!") {
        MediaScannerConnection.scanFile(getApplication<Application>(), arrayOf(newFile.absolutePath), null, null)
        viewModelScope.launch(Dispatchers.Main) {
            _events.send(GalleryEvent.ShowToast(customMessage))
            if (originalMediaId != null) {
                _mediaMap.value[originalMediaId]?.let { originalItem -> moveToTrashInternal(listOf(originalItem)) }
            } else {
                forceSync()
            }
        }
    }

    internal fun moveToTrashInternal(items: List<MediaItem>) = viewModelScope.launch(Dispatchers.IO) {
        try {
            val resolver = getApplication<Application>().contentResolver
            val trashItems = items.map { media ->
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        resolver.update(media.uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 1) }, null, null)
                    }
                } catch (_: Exception) {}

                TrashEntity(
                    deletedTimestamp = System.currentTimeMillis(),
                    originalPath = media.path,
                    contentUri = media.uri.toString(),
                    mediaType = when { media.isVideo -> "video"; else -> "image" },
                    name = media.name,
                    size = media.size
                )
            }
            dao.insertTrashItemsBulk(trashItems)
            removeMediaLocally(items.map { it.id }.toSet())
        } catch (e: Exception) {
            Log.e("TRASH", "Move failed", e)
        }
    }.let { Unit }

    override fun onCleared() {
        super.onCleared()
        getApplication<Application>().contentResolver.unregisterContentObserver(mediaObserver)
        getApplication<Application>().unregisterComponentCallbacks(this)
        clearTempVaultCache()
        sharedPlayer?.release()
        sharedPlayer = null
    }

    private fun invalidatePagingSources() {
        activePagingSources.forEach { it.invalidate() }
        activePagingSources.clear()
    }

    private suspend fun rebuildIndexesAndAlbums(localMedia: List<MediaItem>) = withContext(Dispatchers.Default) {
        val map = java.util.HashMap<Long, MediaItem>(localMedia.size * 2)
        val photos = ArrayList<MediaItem>()
        val videos = ArrayList<MediaItem>()
        val gifs = ArrayList<MediaItem>()
        val recents = ArrayList<MediaItem>()

        val trashSet = trashBin.value.map { it.contentUri }.toSet()
        val secureSet = secureIds.value.map { it.toString() }.toSet()
        val favSet = favoriteIds.value.toSet()
        val hiddenAlbumSet = _hiddenAlbums.value
        val validMedia = ArrayList<MediaItem>(localMedia.size)

        for (item in localMedia) {
            if (trashSet.contains(item.uri.toString()) || secureSet.contains(item.id.toString()) || hiddenAlbumSet.contains(item.bucketId)) continue
            val mappedItem = if (item.isFavorite == favSet.contains(item.id)) item else item.copy(isFavorite = favSet.contains(item.id))
            map[mappedItem.id] = mappedItem
            validMedia.add(mappedItem)

            if (mappedItem.isVideo) {
                videos.add(mappedItem)
            } else {
                if (mappedItem.mimeType.contains("gif", true)) gifs.add(mappedItem) else photos.add(mappedItem)
            }
        }

        validMedia.sortByDescending { it.dateAdded }
        recents.addAll(validMedia)

        val favorites = validMedia.filter { favSet.contains(it.id) }
        _mediaMap.value = map
        _mediaIndexes.value = MediaIndexes(validMedia, photos, videos, gifs, recents)

        val metaMap = albumMeta.value.associateBy { it.id }
        val mappedAlbums = validMedia.groupBy { it.bucketId }.mapNotNull { (bucketId, items) ->
            val latestItem = items.maxByOrNull { it.dateAdded } ?: return@mapNotNull null
            val albumName = metaMap[bucketId]?.customName?.takeIf { it.isNotBlank() } ?: latestItem.bucketName.ifBlank { "Unknown" }
            if (albumName.matches(Regex("^\\d+$"))) return@mapNotNull null
            Album(bucketId, albumName, latestItem.uri, items.size, items.sumOf { it.size }, metaMap[bucketId]?.isPinned == true)
        }

        val emptyManualAlbums = manualAlbums.value.mapNotNull { entity ->
            val existingPhysical = mappedAlbums.find { it.name.equals(entity.name, ignoreCase = true) }
            if (existingPhysical != null) {
                if (!entity.hasBeenUsed) { launch { dao.updateAlbumUsed(entity.id, true) } }
                null
            } else {
                if (!entity.hasBeenUsed) {
                    val isPinned = metaMap[entity.id]?.isPinned == true
                    Album(id = entity.id, name = entity.name, coverUri = if (entity.coverUri.isNotEmpty()) entity.coverUri.toUri() else Uri.EMPTY, mediaCount = 0, sizeBytes = 0L, isPinned = isPinned)
                } else {
                    launch { dao.deleteManualAlbum(entity.id) }
                    null
                }
            }
        }

        val combinedMappedAlbums = mappedAlbums + emptyManualAlbums
        val virtualAlbums = listOfNotNull(
            if (recents.isNotEmpty()) Album(ID_RECENT, "Recent", recents.first().uri, recents.size, recents.sumOf { it.size }, true) else null,
            if (favorites.isNotEmpty()) Album(ID_FAVORITES, "Favorites", favorites.firstOrNull()?.uri ?: Uri.EMPTY, favorites.size, favorites.sumOf { it.size }, true) else null
        )

        val sortOption = _albumSort.value
        val allAlbums = when (sortOption) {
            AlbumSort.Custom -> { (virtualAlbums + combinedMappedAlbums).sortedBy { album -> metaMap[album.id]?.albumOrder ?: when (album.id) { ID_RECENT -> -2; ID_FAVORITES -> -1; else -> Int.MAX_VALUE } } }
            AlbumSort.NameAsc -> virtualAlbums + combinedMappedAlbums.sortedBy { it.name.lowercase() }
            AlbumSort.NameDesc -> virtualAlbums + combinedMappedAlbums.sortedByDescending { it.name.lowercase() }
            AlbumSort.SizeDesc -> virtualAlbums + combinedMappedAlbums.sortedByDescending { it.sizeBytes }
            AlbumSort.CountDesc -> virtualAlbums + combinedMappedAlbums.sortedByDescending { it.mediaCount }
            else -> virtualAlbums + combinedMappedAlbums.sortedWith(compareByDescending<Album> { it.isPinned }.thenBy { it.name.lowercase() })
        }

        _allAlbumsState.value = allAlbums
        _albumsState.value = allAlbums.filterNot { hiddenAlbumSet.contains(it.id) }
        cacheAlbumPreviews(validMedia)
    }

    fun toggleFavorite(id: Long) = viewModelScope.launch(Dispatchers.IO) {
        if (favoriteIds.value.contains(id)) {
            dao.removeFavorite(id)
        } else {
            dao.addFavorite(FavoriteEntity(mediaId = id))
        }
        sortedCache.evictAll()
        searchCache.evictAll()
        albumPreviewCacheMap.clear()
        _albumPreviewCache.value = emptyMap()
        invalidatePagingSources()
        forceSync()
    }

    fun setSearchQuery(query: String) { _searchQuery.value = query }
    fun updateFilter(filter: MediaTypeFilter) { _activeFilter.value = filter }
    fun updateSort(sort: PhotoSort) { _activeSort.value = sort }
    fun updateAlbumSort(sort: AlbumSort) {
        if (_albumSort.value == sort) return
        _albumSort.value = sort
        viewModelScope.launch { forceSync() }
    }

    fun saveCustomAlbumOrder(orderedAlbums: List<Album>) = viewModelScope.launch(Dispatchers.IO) {
        val userAlbums = orderedAlbums.filter { !it.id.startsWith("virtual_") }
        userAlbums.forEachIndexed { i, a ->
            dao.insertAlbumMeta((dao.getAlbumMetaSync(a.id) ?: AlbumEntity(id = a.id)).copy(albumOrder = i))
        }
        if (_albumSort.value != AlbumSort.Custom) {
            _albumSort.value = AlbumSort.Custom
        }
        forceSync()
    }

    fun reorderAlbums(fromAlbumId: String, toAlbumId: String) = viewModelScope.launch(Dispatchers.IO) {
        val albums = _albumsState.value.filter { !it.id.startsWith("virtual_") }.toMutableList()
        val fIdx = albums.indexOfFirst { it.id == fromAlbumId }
        val tIdx = albums.indexOfFirst { it.id == toAlbumId }
        if (fIdx == -1 || tIdx == -1 || fIdx == tIdx) return@launch

        val moved = albums.removeAt(fIdx)
        albums.add(tIdx, moved)
        albums.forEachIndexed { i, a ->
            dao.insertAlbumMeta((dao.getAlbumMetaSync(a.id) ?: AlbumEntity(id = a.id)).copy(albumOrder = i))
        }
        _albumSort.value = AlbumSort.Custom
        forceSync()
    }

    fun moveAlbumUp(id: String) {
        val a = _albumsState.value.filter { !it.id.startsWith("virtual_") }
        val i = a.indexOfFirst { it.id == id }
        if (i > 0) reorderAlbums(id, a[i - 1].id)
    }

    fun moveAlbumDown(id: String) {
        val a = _albumsState.value.filter { !it.id.startsWith("virtual_") }
        val i = a.indexOfFirst { it.id == id }
        if (i != -1 && i < a.lastIndex) reorderAlbums(id, a[i + 1].id)
    }

    fun getMediaItemById(id: Long): MediaItem? = _mediaMap.value[id]

    fun openViewer(mediaId: Long) {
        val item = getMediaItemById(mediaId) ?: return
        viewModelScope.launch(Dispatchers.IO) { dao.incrementUsageStats(mediaId, System.currentTimeMillis()) }
        if (item.uri == Uri.EMPTY) { _events.trySend(GalleryEvent.ShowToast("Media file is unavailable.")); return }

        val current = _viewerState.value
        if (current !is GalleryViewerState.Open || current.mediaId != mediaId) {
            _viewerState.value = GalleryViewerState.Open(
                mediaId = item.id,
                isVideo = item.isVideo,
                uri = item.uri
            )
        }
    }

    fun closeViewer() { _viewerState.value = GalleryViewerState.Closed }

    fun cancelCurrentOperation() {
        editingEngine.cancelExport()
        if (fileOperationJob?.isActive == true) {
            fileOperationJob?.cancel()
            _fileOperationState.value = FileOperationState.Idle
            viewModelScope.launch { _events.send(GalleryEvent.ShowToast("Operation cancelled safely")) }
        }
    }

    @Volatile private var pendingDeleteUris: List<Uri> = emptyList()

    private fun performRealFileOperation(ids: List<Long>, targetRelativePath: String, isMove: Boolean) {
        fileOperationJob?.cancel()

        fileOperationJob = viewModelScope.launch(Dispatchers.IO) {

            val actManager = getApplication<Application>().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            actManager.getMemoryInfo(memInfo)

            val totalBytesRequired = ids.sumOf { _mediaMap.value[it]?.size ?: 0L }
            val storageDir = Environment.getExternalStorageDirectory()
            if (storageDir.usableSpace < totalBytesRequired + (50 * 1024 * 1024)) {
                _events.send(GalleryEvent.ShowToast("Insufficient storage space"))
                return@launch
            }

            val total = ids.size
            var successCount = 0
            val resolver = getApplication<Application>().contentResolver
            val urisToDelete = mutableListOf<Uri>()
            val idsToDelete = mutableListOf<Long>()

            _fileOperationState.value = FileOperationState.Processing(0f, 0, total)

            val cleanRelativePath = if (targetRelativePath.endsWith("/")) targetRelativePath else "$targetRelativePath/"
            val absoluteTargetDir = File(Environment.getExternalStorageDirectory(), cleanRelativePath)

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                if (!absoluteTargetDir.exists()) absoluteTargetDir.mkdirs()
            }

            for ((index, id) in ids.withIndex()) {
                if (!isActive) break
                val item = _mediaMap.value[id] ?: continue

                try {
                    var newDisplayName = item.name
                    var destUri: Uri? = null
                    var counter = 1
                    var insertSucceeded = false

                    while (!insertSucceeded && counter < 100) {
                        val contentValues = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, newDisplayName)
                            put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType)

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                put(MediaStore.MediaColumns.RELATIVE_PATH, cleanRelativePath)
                                put(MediaStore.MediaColumns.IS_PENDING, 1)
                            } else {
                                put(MediaStore.Images.Media.DATA, File(absoluteTargetDir, newDisplayName).absolutePath)
                            }
                        }

                        val targetUri = when {
                            item.isVideo -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                            else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        }

                        try {
                            destUri = resolver.insert(targetUri, contentValues)
                            if (destUri != null) insertSucceeded = true
                        } catch (e: Exception) {
                            // MediaStore throws when names clash
                        }

                        if (!insertSucceeded) {
                            val nameWithoutExt = item.name.substringBeforeLast(".")
                            val ext = item.name.substringAfterLast(".", "")
                            newDisplayName = "${nameWithoutExt}_$counter${if (ext.isNotEmpty()) ".$ext" else ""}"
                            counter++
                        }
                    }

                    if (destUri != null && insertSucceeded) {
                        var copySuccessful = false
                        try {
                            resolver.openInputStream(item.uri)?.use { input ->
                                resolver.openOutputStream(destUri)?.use { output ->
                                    input.copyTo(output, 1024 * 1024)
                                    copySuccessful = true
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed copying stream", e)
                        }

                        if (copySuccessful) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                val cvUpdate = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                                try {
                                    resolver.update(destUri, cvUpdate, null, null)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to clear pending state", e)
                                    resolver.delete(destUri, null, null)
                                    copySuccessful = false
                                }
                            } else {
                                MediaScannerConnection.scanFile(getApplication(), arrayOf(File(absoluteTargetDir, newDisplayName).absolutePath), arrayOf(item.mimeType), null)
                            }
                        } else {
                            try { resolver.delete(destUri, null, null) } catch (e: Exception) {}
                        }

                        if (copySuccessful) {
                            if (isMove) {
                                urisToDelete.add(item.uri)
                                idsToDelete.add(item.id)
                            }
                            successCount++
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "File operation failed for ${item.name}", e)
                }

                _fileOperationState.value = FileOperationState.Processing((index + 1).toFloat() / total, index + 1, total)
            }

            if (isActive) {
                if (isMove && urisToDelete.isNotEmpty()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        _fileOperationState.value = FileOperationState.WaitingForPermission
                        val intentSender = MediaStore.createDeleteRequest(resolver, urisToDelete).intentSender
                        synchronized(this@GalleryViewModel) {
                            pendingDeleteUris = urisToDelete
                            pendingOperationType = PendingOp.DELETE
                            pendingOperationIds = idsToDelete
                        }
                        _events.send(GalleryEvent.RequestPermission(intentSender))
                    } else {
                        var deletedCount = 0
                        urisToDelete.forEach { uri ->
                            try {
                                if (resolver.delete(uri, null, null) > 0) deletedCount++
                            } catch(e:Exception){}
                        }
                        _fileOperationState.value = FileOperationState.Idle
                        _events.send(GalleryEvent.ShowToast("Moved $deletedCount items successfully"))
                        forceSync()
                    }
                } else {
                    _fileOperationState.value = FileOperationState.Idle
                    _events.send(GalleryEvent.ShowToast(if(isMove) "Move completed" else "Copied $successCount items successfully"))
                    forceSync()
                }
            }
        }
    }

    fun mergeAlbums(sourceAlbumIds: List<String>, targetAlbumId: String, mergeMode: MergeMode = MergeMode.MOVE_AND_DELETE) {
        viewModelScope.launch(Dispatchers.Default) {
            val mediaToProcess = _rawMedia.value.filter { sourceAlbumIds.contains(it.bucketId) }.map { it.id }
            if (mediaToProcess.isEmpty()) {
                _events.send(GalleryEvent.ShowToast("No media found to merge"))
                return@launch
            }

            when (mergeMode) {
                MergeMode.COPY -> copyData(ids = mediaToProcess, targetAlbumId = targetAlbumId)
                MergeMode.MOVE, MergeMode.MOVE_AND_DELETE -> moveData(ids = mediaToProcess, targetAlbumId = targetAlbumId)
            }

            if (mergeMode == MergeMode.MOVE_AND_DELETE) {
                fileOperationState.first { it is FileOperationState.Processing }
                fileOperationState.first { it is FileOperationState.Idle || it is FileOperationState.WaitingForPermission }

                if (_fileOperationState.value == FileOperationState.Idle) {
                    sourceAlbumIds.forEach { albumId -> deleteEmptyAlbum(albumId) }
                    _events.send(GalleryEvent.ShowToast("${sourceAlbumIds.size} albums merged successfully"))
                    forceSync()
                }
            }
        }
    }

    private suspend fun deleteEmptyAlbum(albumId: String) {
        val albumStillHasMedia = _rawMedia.value.any { it.bucketId == albumId }
        if (albumStillHasMedia) return

        try {
            dao.deleteAlbumMeta(albumId)
            try { dao.deleteManualAlbum(albumId) } catch (_: Exception) { }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete empty album metadata: $albumId", e)
        }
    }

    fun deleteAlbum(albumId: String) {
        if (albumId.startsWith("virtual_")) {
            _events.trySend(GalleryEvent.ShowToast("Cannot delete virtual albums"))
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val mediaToTrash = _rawMedia.value.filter { it.bucketId == albumId }
                if (mediaToTrash.isNotEmpty()) {
                    moveToTrashInternal(mediaToTrash)
                } else {
                    try { dao.deleteManualAlbum(albumId) } catch (_: Exception) {}
                    try { dao.deleteAlbumMeta(albumId) } catch (_: Exception) {}
                }
                forceSync()
                _events.trySend(GalleryEvent.ShowToast("Album moved to Trash"))
            } catch (e: Exception) {
                Log.e(TAG, "Delete album failed", e)
                _events.trySend(GalleryEvent.ShowToast("Failed to move album to Trash"))
            }
        }
    }

    fun moveData(ids: List<Long>, targetAlbumId: String) {
        val album = allAlbumsState.value.firstOrNull { it.id == targetAlbumId } ?: return
        viewModelScope.launch(Dispatchers.IO) { dao.updateAlbumUsed(targetAlbumId, true) }
        val sampleMedia = _rawMedia.value.firstOrNull { it.bucketId == targetAlbumId }
        val existingRelativePath = sampleMedia?.relativePath ?: "${Environment.DIRECTORY_PICTURES}/${album.name}/"
        performRealFileOperation(ids, existingRelativePath, true)
    }

    fun copyData(ids: List<Long>, targetAlbumId: String) {
        val album = allAlbumsState.value.firstOrNull { it.id == targetAlbumId } ?: return
        viewModelScope.launch(Dispatchers.IO) { dao.updateAlbumUsed(targetAlbumId, true) }
        val sampleMedia = _rawMedia.value.firstOrNull { it.bucketId == targetAlbumId }
        val existingRelativePath = sampleMedia?.relativePath ?: "${Environment.DIRECTORY_PICTURES}/${album.name}/"
        performRealFileOperation(ids, existingRelativePath, false)
    }

    fun createAndMove(ids: List<Long>, newAlbumName: String) {
        viewModelScope.launch {
            createAlbum(newAlbumName, false)
            delay(500)
            performRealFileOperation(ids, "${Environment.DIRECTORY_PICTURES}/$newAlbumName/", true)
        }
    }

    fun createAndCopy(ids: List<Long>, newAlbumName: String) {
        viewModelScope.launch {
            createAlbum(newAlbumName, false)
            delay(500)
            performRealFileOperation(ids, "${Environment.DIRECTORY_PICTURES}/$newAlbumName/", false)
        }
    }

    fun renameAlbum(album: Album, newName: String) = if (newName.isNotBlank()) viewModelScope.launch(Dispatchers.IO) {
        dao.insertAlbumMeta((dao.getAlbumMetaSync(album.id) ?: AlbumEntity(id = album.id)).copy(customName = newName))
        _events.trySend(GalleryEvent.ShowToast("Album renamed"))
        forceSync()
    } else Unit

    fun toggleAlbumPin(album: Album) = viewModelScope.launch(Dispatchers.IO) {
        try {
            if (album.isPinned) dao.removePinnedAlbum(album.id) else dao.addPinnedAlbum(AlbumEntity(id = album.id, isPinned = true))
        } catch (e: Exception) {} finally { forceSync() }
    }

    fun createAlbum(name: String, sdCard: Boolean) {
        if (name.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val newAlbumId = "manual_${System.currentTimeMillis()}"
                dao.insertManualAlbum(ManualAlbumEntity(id = newAlbumId, name = name.trim(), hasBeenUsed = false))
                val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val albumDir = File(root, name.trim())
                if (!albumDir.exists()) { albumDir.mkdirs() }
                forceSync()
                _events.trySend(GalleryEvent.ShowToast("Album '${name.trim()}' created!"))
            } catch (e: Exception) {
                Log.e("ALBUM_DEBUG", "Failed to create album", e)
                _events.trySend(GalleryEvent.ShowToast("Failed to create album: ${e.message}"))
            }
        }
    }

    enum class PendingOp { NONE, DELETE, FAVORITE, UNFAVORITE }
    @Volatile private var pendingOperationIds: List<Long> = emptyList()
    @Volatile private var pendingOperationType: PendingOp = PendingOp.NONE

    fun onPermissionResult(granted: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        val opType: PendingOp
        val opIds: List<Long>
        synchronized(this@GalleryViewModel) {
            opType = pendingOperationType
            opIds = pendingOperationIds.toList()
            pendingOperationType = PendingOp.NONE
            pendingOperationIds = emptyList()
        }

        if (granted) {
            if (opIds.isNotEmpty() || pendingDeleteUris.isNotEmpty()) {
                val successIds = mutableListOf<Long>()
                when (opType) {
                    PendingOp.DELETE -> {
                        val resolver = getApplication<Application>().contentResolver
                        var deletedCount = 0
                        pendingDeleteUris.forEach { uri ->
                            try {
                                if (resolver.delete(uri, null, null) > 0) deletedCount++
                            } catch (_: Exception) {}
                        }
                        pendingDeleteUris = emptyList()
                        _events.trySend(GalleryEvent.ShowToast("Successfully completed operation"))
                    }
                    PendingOp.FAVORITE -> {
                        opIds.forEach { id -> try { dao.addFavorite(FavoriteEntity(mediaId = id)) } catch (e: Exception) {} }
                    }
                    PendingOp.UNFAVORITE -> {
                        opIds.forEach { id -> try { dao.removeFavorite(id) } catch (e: Exception) {} }
                    }
                    PendingOp.NONE -> {}
                }

                if (successIds.isNotEmpty()) {
                    try { dao.deleteTrashItems(successIds) } catch (e: Exception) {}
                }

                _fileOperationState.value = FileOperationState.Idle
                _events.trySend(GalleryEvent.OperationSuccess)
                forceSync()
            }
        } else {
            pendingDeleteUris = emptyList()
            _fileOperationState.value = FileOperationState.Idle
            _events.trySend(GalleryEvent.ShowToast("Permission denied. Cannot modify file."))
        }
    }

    fun hideItems(ids: List<Long>) = viewModelScope.launch(Dispatchers.IO) {
        try { engine.hideItems(ids) } finally { forceSync() }
    }.let { Unit }

    fun hideAlbums(albumIds: List<String>) = viewModelScope.launch(Dispatchers.IO) {
        try { engine.hideAlbums(albumIds) } finally { forceSync() }
    }.let { Unit }

    fun unhideMedia(ids: List<Long>) = viewModelScope.launch(Dispatchers.IO) {
        try { ids.forEach { dao.removeFromSecure(it) } } finally { forceSync() }
    }.let { Unit }

    fun deleteSecureMedia(ids: List<Long>) = viewModelScope.launch(Dispatchers.IO) {
        ids.forEach { dao.removeFromSecure(it) }
        _events.trySend(GalleryEvent.OperationSuccess)
        forceSync()
    }

    suspend fun decryptToMemory(encryptedFilePath: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val resolver = getApplication<Application>().contentResolver
            val uri = if (encryptedFilePath.startsWith("content://")) Uri.parse(encryptedFilePath) else Uri.fromFile(File(encryptedFilePath))
            resolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) { null }
    }

    suspend fun decryptThumbnailToMemory(encryptedFilePath: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val bytes = decryptToMemory(encryptedFilePath) ?: return@withContext null
            if (bytes.size > 500 * 1024) {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                opts.inSampleSize = calculateInSampleSize(opts, 256, 256)
                opts.inJustDecodeBounds = false
                val bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                val out = ByteArrayOutputStream()
                bm.compress(Bitmap.CompressFormat.JPEG, 70, out)
                bm.recycle()
                out.toByteArray()
            } else bytes
        } catch (e: Exception) { null }
    }

    suspend fun decryptToTempFile(encryptedFilePath: String): File? = withContext(Dispatchers.IO) {
        try {
            val out = File(File(getApplication<Application>().cacheDir, "secure_vault_playback").apply { mkdirs() }, "temp_${System.currentTimeMillis()}.mp4")
            val resolver = getApplication<Application>().contentResolver
            val uri = if (encryptedFilePath.startsWith("content://")) Uri.parse(encryptedFilePath) else Uri.fromFile(File(encryptedFilePath))
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(out).use { output -> input.copyTo(output, 64 * 1024) }
            }
            out
        } catch (e: Exception) { null }
    }

    fun deleteTempFile(file: File) {
        if (file.exists() && file.absolutePath.contains("secure_vault_playback")) file.delete()
    }

    fun clearTempVaultCache() = viewModelScope.launch(Dispatchers.IO) {
        try {
            val cacheDir = File(getApplication<Application>().cacheDir, "secure_vault_playback")
            if (cacheDir.exists()) {
                val sixHoursAgo = System.currentTimeMillis() - (6 * 60 * 60 * 1000)
                cacheDir.listFiles()?.forEach { if (it.lastModified() < sixHoursAgo) it.delete() }
            }
        } catch (e: Exception) {}
    }.let { Unit }

    fun schedulePeriodicTrashCleanup() = runCatching {
        WorkManager.getInstance(getApplication()).enqueueUniquePeriodicWork(
            "TrashCleanupPeriodic",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<TrashCleanupWorker>(24, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresDeviceIdle(true).setRequiresBatteryNotLow(true).build())
                .build()
        )
    }.let { Unit }

    fun getNextMediaUri(currentUri: String): String? = processedMedia.value.indexOfFirst { it.uri.toString() == currentUri }.takeIf { it != -1 && it < processedMedia.value.lastIndex }?.let { processedMedia.value[it + 1].uri.toString() }
    fun getPreviousMediaUri(currentUri: String): String? = processedMedia.value.indexOfFirst { it.uri.toString() == currentUri }.takeIf { it > 0 }?.let { processedMedia.value[it - 1].uri.toString() }
    fun preloadNextMedia(currentUri: String, preloadCallback: (Uri) -> Unit) {
        val nextIndex = processedMedia.value.indexOfFirst { it.uri.toString() == currentUri } + 1
        if (nextIndex in 1..processedMedia.value.lastIndex) preloadCallback(processedMedia.value[nextIndex].uri)
    }

    fun getGlobalVideoPlaylist(): List<String> = videoPlaylist.value
    fun getGlobalVideoIndex(videoUri: String): Int = videoPlaylist.value.indexOf(videoUri)
    fun getNextGlobalVideo(videoUri: String): String? {
        val playlist = videoPlaylist.value
        val currentIndex = playlist.indexOf(videoUri)
        if (currentIndex == -1) return null
        return playlist.getOrNull(currentIndex + 1)
    }
    fun getPreviousGlobalVideo(videoUri: String): String? {
        val playlist = videoPlaylist.value
        val currentIndex = playlist.indexOf(videoUri)
        if (currentIndex == -1) return null
        return playlist.getOrNull(currentIndex - 1)
    }
    fun getNextGlobalVideoRepeat(videoUri: String): String? {
        val playlist = videoPlaylist.value
        if (playlist.isEmpty()) return null
        val currentIndex = playlist.indexOf(videoUri)
        if (currentIndex == -1) return playlist.first()
        return playlist[(currentIndex + 1) % playlist.size]
    }
    fun getPreviousGlobalVideoRepeat(videoUri: String): String? {
        val playlist = videoPlaylist.value
        if (playlist.isEmpty()) return null
        val currentIndex = playlist.indexOf(videoUri)
        if (currentIndex == -1) return playlist.first()
        return if (currentIndex == 0) playlist.last() else playlist[currentIndex - 1]
    }
}

// --- Paging Source ---
class MediaPagingSource(
    private val contentResolver: ContentResolver,
    private val filter: MediaTypeFilter,
    private val query: String,
    private val albumId: String? = null
) : PagingSource<Int, MediaItem>() {

    override fun getRefreshKey(state: PagingState<Int, MediaItem>): Int? = state.anchorPosition?.let {
        state.closestPageToPosition(it)?.prevKey?.plus(state.config.pageSize) ?: state.closestPageToPosition(it)?.nextKey?.minus(state.config.pageSize)
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, MediaItem> = withContext(Dispatchers.IO) {
        try {
            val position = params.key ?: 0
            val pageSize = params.loadSize
            val mediaList = ArrayList<MediaItem>(pageSize)
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Files.getContentUri("external")

            var selection = when (filter) {
                MediaTypeFilter.ALL -> "(${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (1, 3))"
                MediaTypeFilter.PHOTOS -> "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} AND ${MediaStore.Files.FileColumns.MIME_TYPE} NOT LIKE 'application/%' AND ${MediaStore.Files.FileColumns.MIME_TYPE} NOT LIKE 'text/%'"
                MediaTypeFilter.VIDEOS -> "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}"
            }

            val selectionArgsList = mutableListOf<String>()
            when (albumId) {
                ID_RECENT -> {}
                else -> if (albumId != null && !albumId.startsWith("virtual_")) {
                    selection += " AND ${MediaStore.Files.FileColumns.BUCKET_ID} = ?"
                    selectionArgsList.add(albumId)
                }
            }

            if (query.isNotBlank()) {
                selection += " AND (${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ? OR ${MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME} LIKE ?)"
                val safeQuery = "%$query%"
                selectionArgsList.add(safeQuery)
                selectionArgsList.add(safeQuery)
            }

            val selArgsArray = if (selectionArgsList.isNotEmpty()) selectionArgsList.toTypedArray() else null

            val projection = mutableListOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.MIME_TYPE,
                MediaStore.Files.FileColumns.DATE_ADDED,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.BUCKET_ID,
                MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
                MediaStore.Files.FileColumns.WIDTH,
                MediaStore.Files.FileColumns.HEIGHT
            ).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(MediaStore.Files.FileColumns.RELATIVE_PATH) else add(MediaStore.Files.FileColumns.DATA)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val bundle = Bundle().apply {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                    if (selArgsArray != null) putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selArgsArray)
                    putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Files.FileColumns.DATE_ADDED))
                    putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
                    putInt(ContentResolver.QUERY_ARG_LIMIT, pageSize)
                    putInt(ContentResolver.QUERY_ARG_OFFSET, position)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_EXCLUDE)
                }
                contentResolver.query(uri, projection.toTypedArray(), bundle, null)?.use { cursor -> processCursor(cursor, mediaList) }
            } else {
                contentResolver.query(uri, projection.toTypedArray(), selection, selArgsArray, "${MediaStore.Files.FileColumns.DATE_ADDED} DESC LIMIT $pageSize OFFSET $position")?.use { cursor -> processCursor(cursor, mediaList) }
            }

            LoadResult.Page(mediaList, if (position == 0) null else position - pageSize, if (mediaList.isEmpty() || mediaList.size < pageSize) null else position + pageSize)
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    private fun processCursor(c: Cursor, list: MutableList<MediaItem>) {
        val iC = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
        val mC = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
        val dC = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
        val sC = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
        val nC = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
        val bIC = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_ID)
        val bNC = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
        val rpC = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) c.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH) else -1
        val pC = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) c.getColumnIndex(MediaStore.Files.FileColumns.DATA) else -1
        val wC = c.getColumnIndex(MediaStore.Files.FileColumns.WIDTH)
        val hC = c.getColumnIndex(MediaStore.Files.FileColumns.HEIGHT)

        while (c.moveToNext()) {
            val p = if (rpC >= 0) (c.getString(rpC) ?: "") + (c.getString(nC) ?: "") else if (pC >= 0) c.getString(pC) ?: "" else ""
            val rp = if (rpC >= 0) c.getString(rpC) ?: "" else p.substringBeforeLast('/')
            val m = c.getString(mC) ?: ""
            val rd = c.getLong(dC)

            val isV = m.startsWith("video/")

            val id = c.getLong(iC)
            val w = if (wC >= 0) c.getInt(wC) else 0
            val h = if (hC >= 0) c.getInt(hC) else 0

            list.add(MediaItem(
                id = id,
                uri = if (isV) ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id) else ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                path = p,
                relativePath = rp,
                name = c.getString(nC) ?: "",
                dateAdded = if (rd > 1000000000000L) rd / 1000L else if (rd > 0) rd else System.currentTimeMillis() / 1000L,
                size = c.getLong(sC),
                isVideo = isV,
                duration = 0L,
                width = w,
                height = h,
                mimeType = m,
                bucketId = c.getString(bIC) ?: "",
                bucketName = c.getString(bNC) ?: "",
                isFavorite = false,
                isHidden = false
            ))
        }
    }
}