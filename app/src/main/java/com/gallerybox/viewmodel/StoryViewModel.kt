@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package com.gallerybox.viewmodel

import android.app.Application
import android.content.Context
import android.content.ContentUris
import android.content.SharedPreferences
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gallerybox.data.GalleryDao
import com.gallerybox.data.MediaItem
import com.gallerybox.data.StoryEntity
import com.gallerybox.data.UiStory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.util.Calendar
import java.util.UUID
import javax.inject.Inject
import kotlin.math.*

@HiltViewModel
class StoryViewModel @Inject constructor(
    application: Application,
    private val dao: GalleryDao
) : AndroidViewModel(application) {

    private val _events = Channel<GalleryEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val enginePrefs = application.getSharedPreferences("gallery_engine_prefs", Context.MODE_PRIVATE)

    private val _hiddenAlbums = MutableStateFlow(loadHiddenAlbumsFromPrefs())
    val hiddenAlbums = _hiddenAlbums.asStateFlow()

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "hidden_albums") {
            _hiddenAlbums.value = loadHiddenAlbumsFromPrefs()
        }
    }

    private fun loadHiddenAlbumsFromPrefs(): Set<String> =
        enginePrefs.getStringSet("hidden_albums", emptySet()) ?: emptySet()

    // Now securely holds the COMPLETE gallery metadata, independent of the Paging UI
    private val _mediaMap = MutableStateFlow<Map<Long, MediaItem>>(emptyMap())

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating = _isGenerating.asStateFlow()

    private val _generationProgress = MutableStateFlow(0)
    val generationProgress = _generationProgress.asStateFlow()

    private val _generationTotal = MutableStateFlow(0)
    val generationTotal = _generationTotal.asStateFlow()

    val stories: StateFlow<List<UiStory>> =
        combine(dao.getStories(), _mediaMap, _hiddenAlbums) { entities, map, hidden ->
            entities
                .mapNotNull { entity ->
                    val ids = try {
                        entity.mediaIdsJson
                            .removePrefix("[")
                            .removeSuffix("]")
                            .split(",")
                            .mapNotNull { it.trim().toLongOrNull() }
                            .distinct()
                    } catch (_: Exception) {
                        emptyList()
                    }

                    val items = ids
                        .mapNotNull { map[it] }
                        .filterNot { hidden.contains(it.bucketId) }
                        .sortedBy { it.dateAdded }

                    if (items.isEmpty()) {
                        null
                    } else {
                        UiStory(
                            entity.id,
                            entity.title,
                            entity.subtitle ?: "",
                            entity.coverUri.toUri(), // Fixed: KTX String.toUri()
                            items
                        )
                    }
                }
                .sortedByDescending { story ->
                    story.items.maxOfOrNull { it.dateAdded } ?: 0L
                }
        }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                emptyList()
            )

    init {
        enginePrefs.registerOnSharedPreferenceChangeListener(prefsListener)
        // 1. Initial metadata fetch independent of UI
        loadStoryMedia()
        // 2. Start the watcher for background daily refreshes
        startPeriodicMemoryWatcher()
    }

    override fun onCleared() {
        super.onCleared()
        enginePrefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
    }

    /** Filters out items belonging to a currently hidden album. */
    private fun excludeHidden(items: List<MediaItem>): List<MediaItem> {
        val hidden = _hiddenAlbums.value
        if (hidden.isEmpty()) return items
        return items.filterNot { hidden.contains(it.bucketId) }
    }

    private fun loadStoryMedia() {
        viewModelScope.launch(Dispatchers.IO) {
            val items = excludeHidden(scanMediaStoreMetadata())
            _mediaMap.value = items.associateBy { it.id }

            if (items.size > 20) {
                triggerOfflineStoryGeneration(items, force = false)
            }
        }
    }

    private fun startPeriodicMemoryWatcher() {
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                delay(PERIODIC_CHECK_MS)

                if (!_isGenerating.value) {
                    val prefs = getApplication<Application>()
                        .getSharedPreferences(
                            "gallery_engine_prefs",
                            Context.MODE_PRIVATE
                        )

                    val lastScanned = prefs.getLong(
                        "last_memory_scan_time",
                        0L
                    )

                    if (System.currentTimeMillis() - lastScanned >= DAILY_REFRESH_MS) {
                        // Fetch fresh metadata before daily refresh
                        val items = excludeHidden(scanMediaStoreMetadata())
                        _mediaMap.value = items.associateBy { it.id }
                        triggerOfflineStoryGeneration(
                            items,
                            force = true
                        )
                    }
                }
            }
        }
    }

    /**
     * Lightweight scanner for Story generation.
     * Extracts only the metadata needed to build memories completely offline.
     */
    private fun scanMediaStoreMetadata(): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        val context = getApplication<Application>()

        val uri = MediaStore.Files.getContentUri("external")

        // Fixed: Removed SDK_INT checks because app minSdk >= 31
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.WIDTH,
            MediaStore.Files.FileColumns.HEIGHT,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.BUCKET_ID,
            MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME, // Fixed: added bucket name
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.IS_FAVORITE
        )

        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?"
        val selectionArgs = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
        )
        val sortOrder = "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"

        try {
            context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
                val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.WIDTH)
                val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.HEIGHT)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_ID)
                val bucketNameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
                val relPathCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
                val favCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_FAVORITE)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val mimeType = cursor.getString(mimeCol) ?: ""
                    val isVideo = mimeType.startsWith("video")
                    val contentUri = if (isVideo) {
                        ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                    } else {
                        ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    }

                    items.add(
                        MediaItem(
                            id = id,
                            uri = contentUri,
                            path = cursor.getString(dataCol) ?: "",
                            name = cursor.getString(nameCol) ?: "",
                            bucketId = cursor.getString(bucketIdCol) ?: "",
                            bucketName = cursor.getString(bucketNameCol) ?: "", // Fixed
                            mimeType = mimeType,
                            dateAdded = cursor.getLong(dateAddedCol),
                            // Fixed: Removed missing dateModified param
                            width = cursor.getInt(widthCol),
                            height = cursor.getInt(heightCol),
                            size = cursor.getLong(sizeCol),
                            duration = cursor.getLong(durationCol),
                            isVideo = isVideo,
                            isFavorite = cursor.getInt(favCol) == 1,
                            relativePath = cursor.getString(relPathCol) ?: ""
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return items
    }

    /**
     * Preserved in case the UI is still calling it, but it no longer
     * intercepts the Paging snapshot and overrides the global media map.
     */
    fun updateMediaMap(map: Map<Long, MediaItem>) {
        // Ignored. StoryViewModel manages its own complete metadata map.
    }

    fun createManualStory(
        mediaIds: List<Long>,
        title: String
    ) {
        val distinctIds = mediaIds.distinct()

        if (distinctIds.isEmpty() || title.isBlank()) {
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val items = distinctIds.mapNotNull {
                    _mediaMap.value[it]
                }

                if (items.isEmpty()) {
                    return@launch
                }

                val bestCover = selectBestCover(items)

                val coverUri = bestCover?.uri?.toString()
                    ?: ContentUris
                        .withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            distinctIds.first()
                        )
                        .toString()

                val entity = StoryEntity(
                    id = "manual_${System.currentTimeMillis()}_${UUID.randomUUID()}",
                    title = title,
                    subtitle = "${distinctIds.size} selected items",
                    coverUri = coverUri,
                    mediaIdsJson = distinctIds.joinToString(",", "[", "]"),
                    createdAt = System.currentTimeMillis(),
                    storyType = "MANUAL"
                )

                dao.insertStory(entity)

                _events.trySend(GalleryEvent.ShowToast("Memory created successfully!"))
            } catch (_: Exception) {
                _events.trySend(GalleryEvent.ShowToast("Failed to create memory"))
            }
        }
    }

    fun deleteStory(storyId: String) =
        viewModelScope.launch(Dispatchers.IO) {
            try {
                dao.deleteStory(storyId)
                _events.trySend(GalleryEvent.ShowToast("Memory removed"))
            } catch (_: Exception) {}
        }

    fun refreshMemories() =
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val prefs = getApplication<Application>()
                    .getSharedPreferences(
                        "gallery_engine_prefs",
                        Context.MODE_PRIVATE
                    )

                // Fixed: KTX SharedPreferences.edit
                prefs.edit {
                    putLong("last_memory_scan_time", 0L)
                }

                val items = excludeHidden(scanMediaStoreMetadata())
                _mediaMap.value = items.associateBy { it.id }

                triggerOfflineStoryGeneration(
                    items,
                    force = true
                )
            } catch (_: Exception) {}
        }

    private fun triggerOfflineStoryGeneration(
        mediaList: List<MediaItem>,
        force: Boolean = false
    ) {
        if (_isGenerating.value) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val prefs = getApplication<Application>()
                    .getSharedPreferences(
                        "gallery_engine_prefs",
                        Context.MODE_PRIVATE
                    )

                val lastScanned = prefs.getLong(
                    "last_memory_scan_time",
                    0L
                )

                if (
                    !force &&
                    System.currentTimeMillis() - lastScanned < DAILY_REFRESH_MS
                ) {
                    return@launch
                }

                _isGenerating.value = true
                _generationProgress.value = 0
                _generationTotal.value = mediaList.size

                val manualMediaIds = dao.getStoriesSync()
                    .filter { it.storyType == "MANUAL" }
                    .flatMap { entity ->
                        entity.mediaIdsJson.removePrefix("[").removeSuffix("]")
                            .split(",").mapNotNull { it.trim().toLongOrNull() }
                    }
                    .toSet()

                val uniqueMedia = mediaList
                    .filter { it.id > 0 && it.id !in manualMediaIds }
                    .distinctBy { it.id }

                if (uniqueMedia.isEmpty()) {
                    _generationProgress.value = 0
                    return@launch
                }

                val chronologicalMedia = uniqueMedia.sortedBy { it.dateAdded }

                val naturalClusters = mutableListOf<List<MediaItem>>()
                var currentCluster = mutableListOf<MediaItem>()

                var lastItemTime = 0L
                var lastItemDay = -1
                var lastItemYear = -1

                val calendar = Calendar.getInstance()

                for (item in chronologicalMedia) {

                    val timeMs = item.dateAdded * 1000L
                    calendar.timeInMillis = timeMs

                    val currentDay = calendar.get(Calendar.DAY_OF_YEAR)
                    val currentYear = calendar.get(Calendar.YEAR)

                    if (currentCluster.isEmpty()) {
                        currentCluster.add(item)
                        lastItemTime = timeMs
                        lastItemDay = currentDay
                        lastItemYear = currentYear
                        continue
                    }

                    val timeDiff = abs(timeMs - lastItemTime)
                    val sameDay = currentDay == lastItemDay && currentYear == lastItemYear

                    if (sameDay && timeDiff <= EVENT_THRESHOLD_MS) {
                        currentCluster.add(item)
                        lastItemTime = timeMs
                    } else {
                        if (currentCluster.isNotEmpty()) {
                            naturalClusters.add(currentCluster.distinctBy { it.id })
                        }

                        currentCluster = mutableListOf(item)
                        lastItemTime = timeMs
                        lastItemDay = currentDay
                        lastItemYear = currentYear
                    }
                }

                if (currentCluster.isNotEmpty()) {
                    naturalClusters.add(currentCluster.distinctBy { it.id })
                }

                // ---------------------------------------------------------
                // Convert natural events into Story-sized chunks.
                //
                // Every automatic Story:
                // MIN = 10 media
                // MAX = 50 media
                //
                // Never create a 1/2/3-item Story.
                // Never create a 100/119/257-item Story.
                // ---------------------------------------------------------
                val storyClusters = mutableListOf<List<MediaItem>>()
                var pendingSmall = mutableListOf<MediaItem>()

                for (naturalCluster in naturalClusters) {
                    val items = naturalCluster.distinctBy { it.id }

                    if (items.isEmpty()) {
                        continue
                    }

                    if (pendingSmall.isNotEmpty()) {
                        val gapMs = abs((items.first().dateAdded - pendingSmall.last().dateAdded)) * 1000L
                        if (gapMs > MAX_MERGE_GAP_MS) {
                            pendingSmall = mutableListOf() // too stale/unrelated — discard instead of stitching
                        }
                    }

                    /*
                     * Add small clusters to pending media.
                     *
                     * This prevents Stories with 1–9 items.
                     */
                    if (items.size < MIN_ITEMS_PER_STORY) {
                        pendingSmall.addAll(items)
                        continue
                    }

                    /*
                     * First use pending items to make a valid Story.
                     */
                    if (pendingSmall.isNotEmpty()) {
                        val combined = pendingSmall + items

                        if (combined.size >= MIN_ITEMS_PER_STORY) {
                            var offset = 0

                            while (combined.size - offset >= MIN_ITEMS_PER_STORY) {
                                val remaining = combined.size - offset

                                val chunkSize = when {
                                    remaining >= MAX_ITEMS_PER_STORY -> MAX_ITEMS_PER_STORY
                                    remaining >= MIN_ITEMS_PER_STORY -> remaining
                                    else -> break
                                }

                                val chunk = combined.subList(offset, offset + chunkSize)

                                storyClusters.add(chunk.distinctBy { it.id })
                                offset += chunkSize
                            }

                            pendingSmall = if (offset < combined.size) {
                                combined.subList(offset, combined.size).toMutableList()
                            } else {
                                mutableListOf()
                            }

                        } else {
                            pendingSmall = combined.toMutableList()
                        }

                        continue
                    }

                    /*
                     * Normal cluster.
                     *
                     * Split large natural events into
                     * chronological 10–50 item Stories.
                     */
                    var offset = 0

                    while (offset < items.size) {
                        val remaining = items.size - offset

                        if (remaining < MIN_ITEMS_PER_STORY) {
                            pendingSmall.addAll(items.subList(offset, items.size))
                            break
                        }

                        val chunkSize = min(MAX_ITEMS_PER_STORY, remaining)

                        storyClusters.add(
                            items
                                .subList(offset, offset + chunkSize)
                                .distinctBy { it.id }
                        )

                        offset += chunkSize
                    }
                }

                /*
                 * Try to make one final Story from leftovers.
                 *
                 * Only create it if it reaches 10 items.
                 */
                if (pendingSmall.size >= MIN_ITEMS_PER_STORY) {
                    var offset = 0

                    while (pendingSmall.size - offset >= MIN_ITEMS_PER_STORY) {
                        val remaining = pendingSmall.size - offset
                        val chunkSize = min(MAX_ITEMS_PER_STORY, remaining)

                        storyClusters.add(
                            pendingSmall
                                .subList(offset, offset + chunkSize)
                                .distinctBy { it.id }
                        )

                        offset += chunkSize
                    }
                }

                /*
                 * Only valid 10–50 item Stories survive.
                 */
                val validClusters = storyClusters
                    .map { it.distinctBy { item -> item.id } }
                    .filter { it.size in MIN_ITEMS_PER_STORY..MAX_ITEMS_PER_STORY }

                _generationProgress.value = uniqueMedia.size
                yield()

                val finalClustersToSave = validClusters
                    .sortedByDescending { cluster -> cluster.maxOfOrNull { it.dateAdded } ?: 0L }
                    .take(MAX_AUTO_STORIES)

                val generationId = System.currentTimeMillis()

                val generatedStories = finalClustersToSave
                    .mapIndexed { index, cluster ->
                        buildEntity(
                            idString = "auto_event_${generationId}_$index",
                            title = "Memory ${index + 1}",
                            subtitle = "",
                            items = cluster,
                            isManual = false
                        )
                    }

                val existing = dao.getStoriesSync()

                existing
                    .filter { it.storyType != "MANUAL" }
                    .forEach { dao.deleteStory(it.id) }

                generatedStories.forEach { entity ->
                    try {
                        dao.insertStory(entity)
                    } catch (_: Exception) {}
                }

                // Fixed: KTX SharedPreferences.edit
                prefs.edit {
                    putLong("last_memory_scan_time", System.currentTimeMillis())
                }

            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isGenerating.value = false
            }
        }
    }

    private fun buildEntity(
        idString: String,
        title: String,
        subtitle: String,
        items: List<MediaItem>,
        isManual: Boolean = false
    ): StoryEntity {
        val validItems = items
            .filter { it.id > 0 }
            .distinctBy { it.id }

        val bestCover = selectBestCover(validItems)

        val coverUri = bestCover?.uri?.toString()
            ?: validItems.firstOrNull()?.uri?.toString()
            ?: ""

        return StoryEntity(
            id = idString,
            title = title,
            subtitle = subtitle,
            coverUri = coverUri,
            mediaIdsJson = validItems.joinToString(",", "[", "]") { it.id.toString() },
            createdAt = System.currentTimeMillis(),
            storyType = if (isManual) "MANUAL" else "AUTO"
        )
    }

    private fun selectBestCover(items: List<MediaItem>): MediaItem? {
        if (items.isEmpty()) {
            return null
        }

        return items.maxByOrNull { item ->
            var score = 0.0

            if (item.isFavorite) score += 50.0
            if (!item.mimeType.startsWith("video")) score += 20.0

            val resolution = (item.width * item.height) / 100000.0
            score += min(resolution, 40.0)

            val ratio = if (item.height > 0) {
                item.width.toDouble() / item.height
            } else {
                1.0
            }

            if (ratio in 0.5..0.8 || ratio in 1.3..1.8) score += 15.0
            if (item.path.lowercase().contains("screenshot")) score -= 50.0

            score
        }
    }

    companion object {
        const val MAX_AUTO_STORIES = 30
        const val MIN_ITEMS_PER_STORY = 10
        const val MAX_ITEMS_PER_STORY = 50
        const val EVENT_THRESHOLD_MS = 60L * 60L * 1000L
        const val DAILY_REFRESH_MS = 24L * 60L * 60L * 1000L
        const val PERIODIC_CHECK_MS = 60L * 60L * 1000L
        const val MAX_MERGE_GAP_MS = 3L * 24L * 60L * 60L * 1000L
    }
}