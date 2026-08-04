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
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlin.math.*
import kotlin.random.Random

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

    val stories: StateFlow<List<UiStory>> = combine(dao.getStories(), _mediaMap) { ents, map ->
        ents.mapNotNull { e ->
            val ids = try {
                e.mediaIdsJson.removePrefix("[").removeSuffix("]").split(",").mapNotNull { it.trim().toLongOrNull() }
            } catch (ex: Exception) {
                emptyList()
            }

            val items = ids.mapNotNull { map[it] }

            if (!isStoryValid(items.size, ids.size)) null
            else UiStory(e.id, e.title, e.subtitle ?: "", Uri.parse(e.coverUri), items)
        }.sortedByDescending { it.items.size }
    }.distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun updateMediaMap(map: Map<Long, MediaItem>) {
        _mediaMap.value = map
        if (map.size > 20) {
            triggerOfflineStoryGeneration(map.values.toList())
        }
    }

    fun createManualStory(mediaIds: List<Long>, title: String) {
        if (mediaIds.isEmpty() || title.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val items = mediaIds.mapNotNull { _mediaMap.value[it] }
                if (items.isEmpty()) return@launch

                val bestCover = selectBestCover(items)
                val coverUri = bestCover?.uri?.toString() ?: ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaIds.first()).toString()

                val entity = StoryEntity(
                    id = "manual_${System.currentTimeMillis()}_${UUID.randomUUID()}",
                    title = title,
                    subtitle = "${mediaIds.size} selected items",
                    coverUri = coverUri,
                    mediaIdsJson = mediaIds.joinToString(",", "[", "]"),
                    createdAt = System.currentTimeMillis(),
                    storyType = "MANUAL"
                )

                dao.insertStory(entity)
                _events.trySend(GalleryEvent.ShowToast("Memory created successfully!"))
            } catch (e: Exception) {
                _events.trySend(GalleryEvent.ShowToast("Failed to create memory"))
            }
        }
    }

    fun deleteStory(storyId: String) = viewModelScope.launch(Dispatchers.IO) {
        try {
            dao.deleteStory(storyId)
            _events.trySend(GalleryEvent.ShowToast("Memory removed"))
        } catch (e: Exception) {}
    }

    fun refreshMemories() = viewModelScope.launch(Dispatchers.IO) {
        try {
            val prefs = getApplication<Application>().getSharedPreferences("gallery_engine_prefs", Context.MODE_PRIVATE)
            prefs.edit().putLong("last_memory_scan_time", 0L).apply()
            triggerOfflineStoryGeneration(_mediaMap.value.values.toList(), force = true)
        } catch (e: Exception) {}
    }

    private fun triggerOfflineStoryGeneration(mediaList: List<MediaItem>, force: Boolean = false) {
        if (_isGenerating.value) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val prefs = getApplication<Application>().getSharedPreferences("gallery_engine_prefs", Context.MODE_PRIVATE)
                val lastScanned = prefs.getLong("last_memory_scan_time", 0L)

                // Only generate once every 24 hours unless forced
                if (!force && (System.currentTimeMillis() - lastScanned < 24 * 60 * 60 * 1000L)) {
                    return@launch
                }

                _isGenerating.value = true
                _generationProgress.value = 0
                _generationTotal.value = 100

                var memoryIndex = 1
                fun nextMemoryName(): String = "Memory ${memoryIndex++}"

                val sortedMedia = mediaList.sortedByDescending { it.dateAdded }
                yield()

                val generatedStories = mutableListOf<StoryEntity>()
                val cal = Calendar.getInstance()
                val currentYear = cal.get(Calendar.YEAR)
                val currentDay = cal.get(Calendar.DAY_OF_YEAR)

                fun addChunkedStories(baseId: String, items: List<MediaItem>, minItems: Int) {
                    if (items.size < minItems) return
                    items.chunked(35).forEachIndexed { chunkIndex, chunk ->
                        if (chunk.isNotEmpty()) {
                            generatedStories.add(
                                buildEntity(
                                    idString = "${baseId}_$chunkIndex",
                                    title = nextMemoryName(),
                                    subtitle = "",
                                    items = chunk,
                                    isManual = false
                                )
                            )
                        }
                    }
                }

                val favorites = mutableListOf<MediaItem>()
                val videos = mutableListOf<MediaItem>()
                val screenshots = mutableListOf<MediaItem>()
                val whatsapp = mutableListOf<MediaItem>()
                val camera = mutableListOf<MediaItem>()
                val selfies = mutableListOf<MediaItem>()
                val downloads = mutableListOf<MediaItem>()
                val rain = mutableListOf<MediaItem>()
                val bursts = mutableListOf<MediaItem>()

                val sunrise = mutableListOf<MediaItem>()
                val morning = mutableListOf<MediaItem>()
                val afternoon = mutableListOf<MediaItem>()
                val sunset = mutableListOf<MediaItem>()
                val evening = mutableListOf<MediaItem>()
                val night = mutableListOf<MediaItem>()

                val portraits = mutableListOf<MediaItem>()
                val landscapes = mutableListOf<MediaItem>()
                val fourK = mutableListOf<MediaItem>()
                val screenRecords = mutableListOf<MediaItem>()

                val bluetooth = mutableListOf<MediaItem>()
                val telegram = mutableListOf<MediaItem>()
                val instagram = mutableListOf<MediaItem>()
                val facebook = mutableListOf<MediaItem>()
                val snapchat = mutableListOf<MediaItem>()

                val favoriteVideos = mutableListOf<MediaItem>()
                val editedPhotos = mutableListOf<MediaItem>()

                val last7Days = mutableListOf<MediaItem>()
                val last30Days = mutableListOf<MediaItem>()

                val yearBuckets = mutableMapOf<Int, MutableList<MediaItem>>()
                val monthBuckets = mutableMapOf<String, MutableList<MediaItem>>()
                val festivalBuckets = mutableMapOf<String, MutableList<MediaItem>>()
                val todayInHistory = mutableListOf<MediaItem>()
                val weekends = mutableListOf<MediaItem>()

                val clusters = mutableListOf<List<MediaItem>>()
                var currentCluster = mutableListOf<MediaItem>()
                var lastItemTime = 0L

                val monthFormat = SimpleDateFormat("MMMM yyyy", Locale.US)
                val currentTimeMs = System.currentTimeMillis()

                _generationTotal.value = sortedMedia.size

                for ((idx, item) in sortedMedia.withIndex()) {
                    if (idx % 100 == 0) {
                        _generationProgress.value = idx
                        yield()
                    }

                    val timeMs = item.dateAdded * 1000L
                    cal.timeInMillis = timeMs

                    val itemYear = cal.get(Calendar.YEAR)
                    val itemDay = cal.get(Calendar.DAY_OF_YEAR)
                    val itemMonth = cal.get(Calendar.MONTH)
                    val itemDayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
                    val itemHour = cal.get(Calendar.HOUR_OF_DAY)

                    val pathLower = item.path.lowercase()
                    val isVid = item.duration > 0 || item.mimeType.startsWith("video")

                    if (item.isFavorite) favorites.add(item)
                    if (isVid) videos.add(item)
                    if (item.isFavorite && isVid) favoriteVideos.add(item)

                    if (pathLower.contains("screenshot")) screenshots.add(item)
                    if (pathLower.contains("whatsapp")) whatsapp.add(item)
                    if (pathLower.contains("dcim/camera")) camera.add(item)
                    if (pathLower.contains("download")) downloads.add(item)
                    if (pathLower.contains("front") || pathLower.contains("selfie")) selfies.add(item)
                    if (pathLower.contains("rain") || pathLower.contains("monsoon")) rain.add(item)

                    if (pathLower.contains("screenrecord")) screenRecords.add(item)
                    if (pathLower.contains("bluetooth")) bluetooth.add(item)
                    if (pathLower.contains("telegram")) telegram.add(item)
                    if (pathLower.contains("instagram")) instagram.add(item)
                    if (pathLower.contains("facebook")) facebook.add(item)
                    if (pathLower.contains("snapchat")) snapchat.add(item)
                    if (pathLower.contains("edit") || pathLower.contains("snapseed") || pathLower.contains("lightroom") || pathLower.contains("gallerybox")) editedPhotos.add(item)

                    when (itemHour) {
                        in 5..7 -> sunrise.add(item)
                        in 8..11 -> morning.add(item)
                        in 12..16 -> afternoon.add(item)
                        in 17..18 -> sunset.add(item)
                        in 19..21 -> evening.add(item)
                        else -> night.add(item)
                    }

                    if (!isVid && item.width > 0 && item.height > 0) {
                        if (item.height > item.width * 1.1) portraits.add(item)
                        else if (item.width > item.height * 1.1) landscapes.add(item)
                    }

                    if (isVid && item.width * item.height >= 7_000_000) fourK.add(item)

                    val diffMs = currentTimeMs - timeMs
                    if (diffMs <= 7L * 24 * 60 * 60 * 1000) last7Days.add(item)
                    if (diffMs <= 30L * 24 * 60 * 60 * 1000) last30Days.add(item)

                    yearBuckets.getOrPut(itemYear) { mutableListOf() }.add(item)
                    monthBuckets.getOrPut(monthFormat.format(cal.time)) { mutableListOf() }.add(item)

                    if (itemDayOfWeek == Calendar.SATURDAY || itemDayOfWeek == Calendar.SUNDAY) weekends.add(item)
                    if (itemDay == currentDay && itemYear < currentYear) todayInHistory.add(item)

                    when {
                        itemMonth == Calendar.DECEMBER && cal.get(Calendar.DAY_OF_MONTH) in 24..26 -> festivalBuckets.getOrPut("Christmas Memories") { mutableListOf() }.add(item)
                        itemMonth == Calendar.DECEMBER && cal.get(Calendar.DAY_OF_MONTH) == 31 || itemMonth == Calendar.JANUARY && cal.get(Calendar.DAY_OF_MONTH) == 1 -> festivalBuckets.getOrPut("New Year Celebrations") { mutableListOf() }.add(item)
                        itemMonth == Calendar.OCTOBER || itemMonth == Calendar.NOVEMBER -> {
                            if (pathLower.contains("diwali") || pathLower.contains("festival")) festivalBuckets.getOrPut("Festival of Lights") { mutableListOf() }.add(item)
                        }
                    }

                    if (currentCluster.isEmpty()) {
                        currentCluster.add(item)
                    } else {
                        val timeDiff = abs(lastItemTime - timeMs)
                        if (timeDiff <= EVENT_THRESHOLD_MS) {
                            currentCluster.add(item)
                            if (timeDiff <= BURST_TIME_WINDOW_MS) bursts.add(item)
                        } else {
                            if (currentCluster.size >= 4) clusters.add(currentCluster.toList())
                            currentCluster = mutableListOf(item)
                        }
                    }
                    lastItemTime = timeMs
                }
                if (currentCluster.size >= 4) clusters.add(currentCluster)
                yield()

                _generationProgress.value = sortedMedia.size

                for ((index, cluster) in clusters.withIndex()) {
                    addChunkedStories("auto_trip_$index", cluster, 4)
                }

                addChunkedStories("auto_fav", favorites, 5)
                addChunkedStories("auto_vid", videos, 3)
                addChunkedStories("auto_screenshots", screenshots, 5)
                addChunkedStories("auto_whatsapp", whatsapp, 5)
                addChunkedStories("auto_camera", camera, 8)
                addChunkedStories("auto_selfies", selfies, 3)
                addChunkedStories("auto_rain", rain, 2)
                addChunkedStories("auto_bursts", bursts, 4)
                addChunkedStories("auto_today", todayInHistory, 2)
                addChunkedStories("auto_weekends", weekends, 6)

                addChunkedStories("auto_sunrise", sunrise, 3)
                addChunkedStories("auto_morning", morning, 5)
                addChunkedStories("auto_afternoon", afternoon, 5)
                addChunkedStories("auto_sunset", sunset, 3)
                addChunkedStories("auto_evening", evening, 5)
                addChunkedStories("auto_night", night, 4)

                addChunkedStories("auto_portraits", portraits, 5)
                addChunkedStories("auto_landscapes", landscapes, 5)
                addChunkedStories("auto_4k", fourK, 2)
                addChunkedStories("auto_screenrecords", screenRecords, 3)

                addChunkedStories("auto_downloads", downloads, 5)
                addChunkedStories("auto_bluetooth", bluetooth, 2)
                addChunkedStories("auto_telegram", telegram, 5)
                addChunkedStories("auto_instagram", instagram, 5)
                addChunkedStories("auto_facebook", facebook, 5)
                addChunkedStories("auto_snapchat", snapchat, 5)

                addChunkedStories("auto_fav_videos", favoriteVideos, 2)
                addChunkedStories("auto_edited", editedPhotos, 3)

                addChunkedStories("auto_last7", last7Days, 5)
                addChunkedStories("auto_last30", last30Days, 10)

                val randomMemories = sortedMedia.filter { (currentTimeMs - it.dateAdded * 1000L) > EVENT_THRESHOLD_MS * 10 }.shuffled()
                addChunkedStories("auto_random", randomMemories, 10)

                monthBuckets.forEach { (month, items) ->
                    addChunkedStories("auto_month_${month.replace(" ", "_")}", items, 8)
                }

                yearBuckets.forEach { (year, items) ->
                    if (year < currentYear) addChunkedStories("auto_year_$year", items, 15)
                }

                festivalBuckets.forEach { (name, items) ->
                    addChunkedStories("auto_fest_${name.replace(" ", "_")}", items, 2)
                }

                yield()

                // Final Selection Logic: Score, Sort, Take Top 60, Select Random 10-30
                val candidatesWithScore = generatedStories.map { entity ->
                    val ids = try {
                        entity.mediaIdsJson.removePrefix("[").removeSuffix("]").split(",").mapNotNull { it.trim().toLongOrNull() }
                    } catch (e: Exception) { emptyList() }

                    val items = ids.mapNotNull { _mediaMap.value[it] }
                    val photos = items.count { !it.isVideo }
                    val videos = items.count { it.isVideo }
                    val days = items.map { it.dateAdded / 86400 }.distinct().size
                    val favs = items.count { it.isFavorite }

                    val score = calculateStoryScore(photos, videos, days, favs)
                    Pair(entity, score)
                }

                val top60 = candidatesWithScore.sortedByDescending { it.second }.take(60).map { it.first }
                val countToPick = Random.nextInt(10, 31)
                val selectedStories = top60.shuffled().take(countToPick)

                // Delete old generated memories
                val existing = dao.getStoriesSync()
                existing.filter { it.storyType != "MANUAL" }.forEach { dao.deleteStory(it.id) }

                // Insert newly selected memories
                selectedStories.forEach { entity ->
                    try { dao.insertStory(entity) } catch (_: Exception) {}
                }

                prefs.edit().putLong("last_memory_scan_time", System.currentTimeMillis()).apply()

            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isGenerating.value = false
            }
        }
    }

    private fun buildEntity(idString: String, title: String, subtitle: String, items: List<MediaItem>, isManual: Boolean = false): StoryEntity {
        val validItems = items.filter { it.id > 0 }
        val bestCover = selectBestCover(validItems)
        val coverUri = bestCover?.uri?.toString() ?: validItems.firstOrNull()?.uri?.toString() ?: ""

        return StoryEntity(
            id = idString,
            title = title,
            subtitle = subtitle,
            coverUri = coverUri,
            mediaIdsJson = validItems.map { it.id }.joinToString(",", "[", "]"),
            createdAt = System.currentTimeMillis(),
            storyType = if (isManual) "MANUAL" else "AUTO"
        )
    }

    private fun selectBestCover(items: List<MediaItem>): MediaItem? {
        if (items.isEmpty()) return null
        return items.maxByOrNull { item ->
            var score = 0.0

            if (item.isFavorite) score += 50.0

            if (!item.mimeType.startsWith("video")) score += 20.0

            val resolution = (item.width * item.height) / 100000.0
            score += min(resolution, 40.0)

            val ratio = if (item.height > 0) item.width.toDouble() / item.height else 1.0
            if (ratio in 0.5..0.8 || ratio in 1.3..1.8) score += 15.0

            if (item.path.lowercase().contains("screenshot")) score -= 50.0

            score
        }
    }

    companion object {
        const val EVENT_THRESHOLD_MS = 21600000L
        const val BURST_TIME_WINDOW_MS = 2000L

        fun calculateStoryScore(photos: Int, videos: Int, days: Int, favorites: Int): Double {
            val photoScore = 2.0 * photos
            val videoScore = 5.0 * videos
            val dayScore = 3.0 * days
            val favoriteScore = 8.0 * favorites
            return photoScore + videoScore + dayScore + favoriteScore
        }

        fun isStoryValid(existing: Int, original: Int): Boolean {
            if (original <= 0) return false
            val ratio = existing.toDouble() / original
            return ratio >= 0.3
        }
    }
}