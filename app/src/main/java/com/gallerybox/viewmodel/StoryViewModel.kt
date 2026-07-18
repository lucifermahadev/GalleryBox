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
                val sortedMedia = mediaList.sortedByDescending { it.dateAdded }
                yield()

                val generatedStories = mutableListOf<StoryEntity>()
                val cal = Calendar.getInstance()
                val currentYear = cal.get(Calendar.YEAR)
                val currentDay = cal.get(Calendar.DAY_OF_YEAR)

                val favorites = mutableListOf<MediaItem>()
                val videos = mutableListOf<MediaItem>()
                val screenshots = mutableListOf<MediaItem>()
                val whatsapp = mutableListOf<MediaItem>()
                val camera = mutableListOf<MediaItem>()
                val selfies = mutableListOf<MediaItem>()
                val downloads = mutableListOf<MediaItem>()
                val rain = mutableListOf<MediaItem>()
                val bursts = mutableListOf<MediaItem>()

                val yearBuckets = mutableMapOf<Int, MutableList<MediaItem>>()
                val monthBuckets = mutableMapOf<String, MutableList<MediaItem>>()
                val festivalBuckets = mutableMapOf<String, MutableList<MediaItem>>()
                val todayInHistory = mutableListOf<MediaItem>()
                val weekends = mutableListOf<MediaItem>()

                val clusters = mutableListOf<List<MediaItem>>()
                var currentCluster = mutableListOf<MediaItem>()
                var lastItemTime = 0L

                val monthFormat = SimpleDateFormat("MMMM yyyy", Locale.US)

                for (item in sortedMedia) {
                    val timeMs = item.dateAdded * 1000L
                    cal.timeInMillis = timeMs

                    val itemYear = cal.get(Calendar.YEAR)
                    val itemDay = cal.get(Calendar.DAY_OF_YEAR)
                    val itemMonth = cal.get(Calendar.MONTH)
                    val itemDayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
                    val pathLower = item.path.lowercase()

                    if (item.isFavorite) favorites.add(item)
                    if (item.duration > 0 || item.mimeType.startsWith("video")) videos.add(item)

                    if (pathLower.contains("screenshot")) screenshots.add(item)
                    if (pathLower.contains("whatsapp")) whatsapp.add(item)
                    if (pathLower.contains("dcim/camera")) camera.add(item)
                    if (pathLower.contains("download")) downloads.add(item)
                    if (pathLower.contains("front") || pathLower.contains("selfie")) selfies.add(item)
                    if (pathLower.contains("rain") || pathLower.contains("monsoon")) rain.add(item)

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
                            if (currentCluster.size >= 10) clusters.add(currentCluster.toList())
                            currentCluster = mutableListOf(item)
                        }
                    }
                    lastItemTime = timeMs
                }
                if (currentCluster.size >= 10) clusters.add(currentCluster)
                yield()

                for (cluster in clusters) {
                    val durationMs = abs(cluster.first().dateAdded - cluster.last().dateAdded) * 1000L
                    val (title, subtitle) = when {
                        durationMs >= VACATION_THRESHOLD_MS -> "Summer Vacation" to "A long getaway"
                        durationMs >= TRIP_THRESHOLD_MS -> "Weekend Trip" to "Short adventure"
                        else -> getTimeOfDayTitle(cluster.first().dateAdded * 1000L) to "Moments together"
                    }
                    val clusterId = "auto_trip_${cluster.first().dateAdded}"
                    generatedStories.add(buildEntity(clusterId, title, subtitle, cluster))
                }

                if (favorites.size >= 15) generatedStories.add(buildEntity("auto_fav", "Your Favorites", "Handpicked moments", favorites.take(50)))
                if (videos.size >= 10) generatedStories.add(buildEntity("auto_vid", "Recent Videos", "Caught on camera", videos.take(30)))
                if (screenshots.size >= 20) generatedStories.add(buildEntity("auto_screenshots", "Recent Screenshots", "Saved for later", screenshots.take(40)))
                if (whatsapp.size >= 20) generatedStories.add(buildEntity("auto_whatsapp", "WhatsApp Memories", "Shared with friends", whatsapp.take(40)))
                if (camera.size >= 30) generatedStories.add(buildEntity("auto_camera", "Camera Roll", "Shot by you", camera.take(50)))
                if (selfies.size >= 10) generatedStories.add(buildEntity("auto_selfies", "Selfies", "Looking good", selfies.take(20)))
                if (rain.size >= 5) generatedStories.add(buildEntity("auto_rain", "Rainy Days", "Cozy weather", rain))
                if (bursts.size >= 15) generatedStories.add(buildEntity("auto_bursts", "Action Shots", "Burst captures", bursts.take(40)))

                if (todayInHistory.size >= 5) generatedStories.add(buildEntity("auto_today", "On This Day", "Look back in time", todayInHistory.take(20)))
                if (weekends.size >= 30) generatedStories.add(buildEntity("auto_weekends", "Weekend Vibes", "Saturday & Sunday", weekends.take(40)))

                val randomMemories = sortedMedia.filter { (System.currentTimeMillis() - it.dateAdded * 1000L) > EVENT_THRESHOLD_MS * 10 }.shuffled().take(20)
                if (randomMemories.size >= 10) generatedStories.add(buildEntity("auto_random", "Random Memories", "Rediscover the past", randomMemories))

                monthBuckets.forEach { (month, items) ->
                    if (items.size >= 20) {
                        generatedStories.add(buildEntity("auto_month_${month.replace(" ", "_")}", month, "Month in review", items.take(40)))
                    }
                }

                yearBuckets.forEach { (year, items) ->
                    if (items.size >= 50 && year < currentYear) {
                        generatedStories.add(buildEntity("auto_year_$year", "$year Memories", "A year to remember", items.take(50)))
                    }
                }

                festivalBuckets.forEach { (name, items) ->
                    if (items.size >= 5) {
                        generatedStories.add(buildEntity("auto_fest_${name.replace(" ", "_")}", name, "Celebrations", items.take(30)))
                    }
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

    private fun getTimeOfDayTitle(timeMs: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = timeMs }
        return when (cal.get(Calendar.HOUR_OF_DAY)) {
            in 5..11 -> "Morning Light"
            in 12..16 -> "Afternoon Memories"
            in 17..20 -> "Evening Walk"
            else -> "Night Owls"
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