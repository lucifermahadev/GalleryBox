@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package com.gallerybox.viewmodel

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
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

@HiltViewModel
class StoryViewModel @Inject constructor(private val dao: GalleryDao) : ViewModel() {

    private val _events = Channel<GalleryEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val _mediaMap = MutableStateFlow<Map<Long, MediaItem>>(emptyMap())
    private val _isGenerating = MutableStateFlow(false)
    val isGenerating = _isGenerating.asStateFlow()

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
                    createdAt = System.currentTimeMillis()
                )

                dao.insertStory(entity)
                _events.trySend(GalleryEvent.ShowToast("Story created successfully!"))
            } catch (e: Exception) {
                _events.trySend(GalleryEvent.ShowToast("Failed to create story"))
            }
        }
    }

    fun deleteStory(storyId: String) = viewModelScope.launch(Dispatchers.IO) {
        try {
            dao.deleteStory(storyId)
            _events.trySend(GalleryEvent.ShowToast("Story removed"))
        } catch (e: Exception) {

        }
    }

    private fun triggerOfflineStoryGeneration(mediaList: List<MediaItem>) {
        if (_isGenerating.value) return

        viewModelScope.launch(Dispatchers.IO) {
            _isGenerating.value = true
            try {
                var memoryIndex = 1
                fun nextMemoryName(): String = "Memory ${memoryIndex++}"

                val sortedMedia = mediaList.sortedByDescending { it.dateAdded }
                yield()

                val generatedStories = mutableListOf<StoryEntity>()
                val cal = Calendar.getInstance()
                val currentYear = cal.get(Calendar.YEAR)
                val currentDay = cal.get(Calendar.DAY_OF_YEAR)

                // Helper to chunk large item lists into stories of ~25 items each
                fun addChunkedStories(baseId: String, items: List<MediaItem>, minItems: Int) {
                    if (items.size < minItems) return
                    items.chunked(25).forEachIndexed { chunkIndex, chunk ->
                        if (chunk.isNotEmpty()) {
                            generatedStories.add(
                                buildEntity(
                                    idString = "${baseId}_$chunkIndex",
                                    title = nextMemoryName(),
                                    subtitle = "",
                                    items = chunk
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

                // Expanded story categories
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

                for (item in sortedMedia) {
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

                    // New category detections
                    if (pathLower.contains("screenrecord")) screenRecords.add(item)
                    if (pathLower.contains("bluetooth")) bluetooth.add(item)
                    if (pathLower.contains("telegram")) telegram.add(item)
                    if (pathLower.contains("instagram")) instagram.add(item)
                    if (pathLower.contains("facebook")) facebook.add(item)
                    if (pathLower.contains("snapchat")) snapchat.add(item)
                    if (pathLower.contains("edit") || pathLower.contains("snapseed") || pathLower.contains("lightroom") || pathLower.contains("gallerybox")) editedPhotos.add(item)

                    // Time of day
                    when (itemHour) {
                        in 5..7 -> sunrise.add(item)
                        in 8..11 -> morning.add(item)
                        in 12..16 -> afternoon.add(item)
                        in 17..18 -> sunset.add(item)
                        in 19..21 -> evening.add(item)
                        else -> night.add(item) // 22..4
                    }

                    // Metadata guesses
                    if (!isVid && item.width > 0 && item.height > 0) {
                        if (item.height > item.width * 1.1) portraits.add(item)
                        else if (item.width > item.height * 1.1) landscapes.add(item)
                    }

                    if (isVid && item.width * item.height >= 7_000_000) fourK.add(item)

                    // Time relative buckets
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

                    // Event Clusters
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

                // 1. Add cluster stories
                for ((index, cluster) in clusters.withIndex()) {
                    addChunkedStories("auto_trip_$index", cluster, 4)
                }

                // 2. Add all generic categories (Lowered Thresholds)
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

                // 3. New specific categories
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

                // 4. Date and Festival Buckets
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

                if (generatedStories.isNotEmpty()) {
                    generatedStories.forEach { entity ->
                        try {
                            dao.insertStory(entity)
                        } catch (e: Exception) {

                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isGenerating.value = false
            }
        }
    }

    private fun buildEntity(idString: String, title: String, subtitle: String, items: List<MediaItem>): StoryEntity {
        val validItems = items.filter { it.id > 0 }
        val bestCover = selectBestCover(validItems)
        val coverUri = bestCover?.uri?.toString() ?: validItems.firstOrNull()?.uri?.toString() ?: ""

        return StoryEntity(
            id = idString,
            title = title,
            subtitle = subtitle,
            coverUri = coverUri,
            mediaIdsJson = validItems.map { it.id }.joinToString(",", "[", "]"),
            createdAt = System.currentTimeMillis()
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
        const val TRIP_THRESHOLD_MS = 172800000L
        const val VACATION_THRESHOLD_MS = 1209600000L
        const val BURST_TIME_WINDOW_MS = 2000L

        fun calculateStoryScore(photos: Int, videos: Int, days: Int, favorites: Int): Double {
            val photoScore = 2.0 * photos
            val videoScore = 5.0 * videos
            val dayScore = 3.0 * days
            val favoriteScore = 8.0 * favorites
            return photoScore + videoScore + dayScore + favoriteScore
        }

        fun calculateImportance(mediaCount: Int, duration: Double, ratio: Double): Double {
            return mediaCount * duration * ratio
        }

        fun calculateCoverScore(resolution: Double, brightness: Double, aspectRatio: Double, sharpness: Double): Double {
            val resScore = 0.4 * resolution
            val brightScore = 0.3 * brightness
            val ratioScore = 0.2 * aspectRatio
            val sharpScore = 0.1 * sharpness
            return resScore + brightScore + ratioScore + sharpScore
        }

        fun calculateBurstRate(photos: Int, timeMs: Long): Double {
            if (timeMs <= 0) {
                return 0.0
            }
            return photos.toDouble() / timeMs
        }

        fun calculateSimilarity(diff: Int, limit: Int = 64): Double {
            val differenceRatio = diff.toDouble() / limit
            return 1.0 - differenceRatio
        }

        fun calculateDragOpacity(dragDist: Float, maxDist: Float = 1000f): Float {
            val opacity = 1f - (dragDist / maxDist)
            return opacity.coerceIn(0f, 1f)
        }

        fun calculateDragScale(dragDist: Float): Float {
            val scale = 1f - (0.0005f * dragDist)
            return scale.coerceIn(0.4f, 1f)
        }

        fun calculateSpringForce(displacement: Float, stiffness: Float = 300f): Float {
            return -stiffness * displacement
        }

        fun calculateDampingForce(velocity: Float, damping: Float = 25f): Float {
            return -damping * velocity
        }

        fun calculateNextFlingVelocity(velocity: Float, friction: Float = 0.92f): Float {
            return velocity * friction
        }

        fun calculateExponentialFade(timeMs: Long, tau: Float = 300f): Float {
            val decay = -timeMs.toFloat() / tau
            return exp(decay).coerceIn(0f, 1f)
        }

        fun calculateThumbnailCacheSize(ramBytes: Long): Int {
            val tenPercentRam = (0.1 * ramBytes).toLong()
            val maxLimit = 512L
            val selectedSize = min(tenPercentRam, maxLimit)
            return selectedSize.toInt() * 1048576
        }

        fun calculateThumbnailSampleSize(ratio: Double): Int {
            if (ratio <= 1.0) {
                return 1
            }
            val exponent = floor(log2(ratio))
            return 2.0.pow(exponent).toInt()
        }

        fun calculateBitmapMemory(w: Int, h: Int): Int {
            val pixels = w * h
            return pixels * 4
        }

        fun isStoryValid(existing: Int, original: Int): Boolean {
            if (original <= 0) {
                return false
            }
            val ratio = existing.toDouble() / original
            return ratio >= 0.3
        }
    }
}