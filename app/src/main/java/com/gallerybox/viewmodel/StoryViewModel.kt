@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package com.gallerybox.viewmodel

import android.app.Application
import android.content.Context
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
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
import java.io.File
import java.util.Calendar
import java.util.Locale
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

    private val _mediaMap = MutableStateFlow<Map<Long, MediaItem>>(emptyMap())

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating = _isGenerating.asStateFlow()

    private val _generationProgress = MutableStateFlow(0)
    val generationProgress = _generationProgress.asStateFlow()

    private val _generationTotal = MutableStateFlow(0)
    val generationTotal = _generationTotal.asStateFlow()

    val stories: StateFlow<List<UiStory>> =
        combine(dao.getStories(), _mediaMap) { entities, map ->
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
                        .sortedBy { it.dateAdded }

                    if (items.isEmpty()) {
                        null
                    } else {
                        UiStory(
                            entity.id,
                            entity.title,
                            entity.subtitle ?: "",
                            Uri.parse(entity.coverUri),
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
        startPeriodicMemoryWatcher()
    }

    private fun startPeriodicMemoryWatcher() {
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                val currentMedia = _mediaMap.value.values.toList()

                if (currentMedia.size > 20 && !_isGenerating.value) {
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
                        triggerOfflineStoryGeneration(
                            currentMedia,
                            force = true
                        )
                    }
                }

                delay(PERIODIC_CHECK_MS)
            }
        }
    }

    fun updateMediaMap(map: Map<Long, MediaItem>) {
        _mediaMap.value = map

        if (map.size > 20) {
            triggerOfflineStoryGeneration(map.values.toList())
        }
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

                prefs.edit()
                    .putLong("last_memory_scan_time", 0L)
                    .apply()

                triggerOfflineStoryGeneration(
                    _mediaMap.value.values.toList(),
                    force = true
                )
            } catch (_: Exception) {}
        }

    private fun triggerOfflineStoryGeneration(
        mediaList: List<MediaItem>,
        force: Boolean = false
    ) {
        if (_isGenerating.value) {
            return
        }

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

                if (!force && System.currentTimeMillis() - lastScanned < DAILY_REFRESH_MS) {
                    return@launch
                }

                _isGenerating.value = true
                _generationProgress.value = 0
                _generationTotal.value = mediaList.size

                val uniqueMedia = mediaList
                    .filter { it.id > 0 }
                    .distinctBy { it.id }

                if (uniqueMedia.isEmpty()) {
                    _generationProgress.value = 0
                    return@launch
                }

                val sourceKeys = uniqueMedia
                    .map { storySourceKey(it) }
                    .filter { it.isNotBlank() }
                    .distinct()

                val useSourceBoundary = sourceKeys.size > 1

                val nameCounts = mutableMapOf<String, Int>()

                for (item in uniqueMedia) {
                    val nameKey = normalizeStoryName(item.name)

                    if (nameKey.isNotBlank()) {
                        nameCounts[nameKey] = (nameCounts[nameKey] ?: 0) + 1
                    }
                }

                val repeatedNameKeys = nameCounts
                    .filterValues { count -> count >= MIN_NAME_OCCURRENCES }
                    .keys

                val logicalGroups = uniqueMedia.groupBy { item ->
                    val sourcePart = if (useSourceBoundary) {
                        storySourceKey(item)
                    } else {
                        COMMON_SOURCE_KEY
                    }

                    val normalizedName = normalizeStoryName(item.name)

                    val namePart = if (
                        normalizedName.isNotBlank() &&
                        repeatedNameKeys.contains(normalizedName)
                    ) {
                        "NAME:$normalizedName"
                    } else {
                        "TIME_ONLY"
                    }

                    "$sourcePart|$namePart"
                }

                val clusters = mutableListOf<List<MediaItem>>()

                for ((_, groupItems) in logicalGroups) {
                    val chronologicalMedia = groupItems.sortedBy { it.dateAdded }

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
                        } else {
                            val timeDiff = abs(timeMs - lastItemTime)
                            val isSameDay = currentDay == lastItemDay && currentYear == lastItemYear

                            if (isSameDay && timeDiff <= EVENT_THRESHOLD_MS) {
                                currentCluster.add(item)
                                lastItemTime = timeMs
                            } else {
                                clusters.add(currentCluster.distinctBy { it.id })
                                currentCluster = mutableListOf(item)
                                lastItemTime = timeMs
                                lastItemDay = currentDay
                                lastItemYear = currentYear
                            }
                        }
                    }

                    if (currentCluster.isNotEmpty()) {
                        clusters.add(currentCluster.distinctBy { it.id })
                    }
                }

                val validClusters = clusters
                    .map { cluster ->
                        cluster
                            .filter { it.id > 0 }
                            .distinctBy { it.id }
                    }
                    .filter { it.isNotEmpty() }

                _generationProgress.value = uniqueMedia.size
                yield()

                val selectedClusters = validClusters
                    .shuffled()
                    .take(MAX_AUTO_STORIES)

                val finalClustersToSave = selectedClusters
                    .sortedByDescending { cluster ->
                        cluster.maxOfOrNull { it.dateAdded } ?: 0L
                    }

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

                prefs.edit()
                    .putLong("last_memory_scan_time", System.currentTimeMillis())
                    .apply()

            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isGenerating.value = false
            }
        }
    }

    private fun storySourceKey(item: MediaItem): String {
        if (item.bucketId.isNotBlank()) {
            return "BUCKET:${item.bucketId}"
        }

        val relativePath = item.relativePath
            .trim()
            .lowercase(Locale.ROOT)

        if (relativePath.isNotBlank()) {
            return "PATH:$relativePath"
        }

        val parent = try {
            File(item.path)
                .parent
                ?.trim()
                ?.lowercase(Locale.ROOT)
                ?: ""
        } catch (_: Exception) {
            ""
        }

        if (parent.isNotBlank()) {
            return "PARENT:$parent"
        }

        return "UNKNOWN_SOURCE"
    }

    private fun normalizeStoryName(name: String): String {
        if (name.isBlank()) {
            return ""
        }

        var baseName = name.trim()
        val lastDot = baseName.lastIndexOf('.')

        if (lastDot > 0) {
            baseName = baseName.substring(0, lastDot)
        }

        baseName = baseName.replace(Regex("""\s*\(\d+\)\s*$"""), "")
        baseName = baseName.replace(Regex("""\s*-\s*copy(?:\s*\(\d+\))?\s*$"""), "")

        return baseName
            .trim()
            .lowercase(Locale.ROOT)
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
        const val EVENT_THRESHOLD_MS = 60L * 60L * 1000L
        const val DAILY_REFRESH_MS = 24L * 60L * 60L * 1000L
        const val PERIODIC_CHECK_MS = 60L * 60L * 1000L
        const val MIN_NAME_OCCURRENCES = 2
        const val COMMON_SOURCE_KEY = "COMMON_SOURCE"
    }
}