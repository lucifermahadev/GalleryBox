@file:Suppress("unused", "DEPRECATION", "UnstableApiUsage", "MemberVisibilityCanBePrivate")
@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.gallerybox.engine

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.content.edit
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.gallerybox.data.MediaItem
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.floor
import kotlin.math.log2

@UnstableApi
@Singleton
class GalleryEngine @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs = context.getSharedPreferences("gallery_engine_prefs", Context.MODE_PRIVATE)
    private val TAG = "GalleryEngine"

    suspend fun fetchAllMedia(): List<MediaItem> = fetchMedia(null)
    suspend fun fetchIncrementalMedia(lastGeneration: Long): List<MediaItem> = fetchMedia(lastGeneration)

    private suspend fun fetchMedia(minGeneration: Long?): List<MediaItem> = withContext(Dispatchers.IO) {
        val mediaList = mutableListOf<MediaItem>(); val resolver = context.contentResolver
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Files.getContentUri("external")
        val proj = mutableListOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME, MediaStore.Files.FileColumns.DATE_ADDED, MediaStore.Files.FileColumns.SIZE, MediaStore.Files.FileColumns.MIME_TYPE, MediaStore.Files.FileColumns.MEDIA_TYPE, MediaStore.Files.FileColumns.BUCKET_ID, MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME, MediaStore.MediaColumns.DURATION, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.Files.FileColumns.DATA).apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { add(MediaStore.MediaColumns.IS_FAVORITE); add(MediaStore.MediaColumns.IS_TRASHED); add(MediaStore.MediaColumns.WIDTH); add(MediaStore.MediaColumns.HEIGHT) } }
        var sel = "(${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"
        val args = mutableListOf<String>(); if (minGeneration != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { sel += " AND ${MediaStore.MediaColumns.GENERATION_ADDED} > ?"; args.add(minGeneration.toString()) }
        try {
            resolver.query(uri, proj.toTypedArray(), sel, if (args.isEmpty()) null else args.toTypedArray(), "${MediaStore.Files.FileColumns.DATE_ADDED} DESC")?.use { c ->
                val idC = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID); val nameC = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME); val dateC = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED); val sizeC = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE); val mimeC = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE); val typeC = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE); val bIdC = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_ID); val bNameC = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME); val durC = c.getColumnIndex(MediaStore.MediaColumns.DURATION); val relC = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH); val dataC = c.getColumnIndex(MediaStore.Files.FileColumns.DATA); val trashC = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) c.getColumnIndex(MediaStore.MediaColumns.IS_TRASHED) else -1; val wC = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) c.getColumnIndex(MediaStore.MediaColumns.WIDTH) else -1; val hC = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) c.getColumnIndex(MediaStore.MediaColumns.HEIGHT) else -1
                val hidden = getHiddenItems(); val cal = Calendar.getInstance(); var lastCode = -1L
                while (c.moveToNext()) {
                    if (trashC != -1 && c.getInt(trashC) == 1) continue
                    val id = c.getLong(idC); val type = c.getInt(typeC); val isV = type == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    val cUri = when (type) { MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id); MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id); else -> ContentUris.withAppendedId(uri, id) }
                    val relP = if (relC != -1 && c.getString(relC) != null) c.getString(relC) else File(c.getString(dataC) ?: "").parent ?: ""
                    val dSec = c.getLong(dateC); cal.timeInMillis = dSec * 1000L; val code = (cal.get(Calendar.YEAR) * 100L) + cal.get(Calendar.MONTH); if (code != lastCode) lastCode = code
                    mediaList.add(MediaItem(id = id, uri = cUri, path = c.getString(dataC) ?: "", relativePath = relP, name = c.getString(nameC) ?: "Unknown", mimeType = c.getString(mimeC) ?: "", size = c.getLong(sizeC), dateAdded = dSec, isVideo = isV, isPdf = false, isDocument = false, isHidden = hidden.contains(id.toString()), isFavorite = false, bucketId = c.getString(bIdC) ?: "unknown", bucketName = c.getString(bNameC) ?: "Internal", duration = if (isV && durC != -1) c.getLong(durC) else 0L, width = if (wC != -1) c.getInt(wC) else 0, height = if (hC != -1) c.getInt(hC) else 0))
                }
            }
        } catch (e: Exception) { Log.e(TAG, "Media fetch error", e) }
        return@withContext mediaList
    }

    fun getHiddenItems(): Set<String> = prefs.getStringSet("hidden_items", emptySet()) ?: emptySet()
    fun getHiddenAlbums(): Set<String> = prefs.getStringSet("hidden_albums", emptySet()) ?: emptySet()
    fun hideItems(ids: List<Long>) { prefs.edit { putStringSet("hidden_items", (getHiddenItems() + ids.map { it.toString() }).toSet()) } }
    fun hideAlbums(ids: List<String>) { prefs.edit { putStringSet("hidden_albums", (getHiddenAlbums() + ids).toSet()) } }

    fun calculateInSampleSize(origW: Int, origH: Int, targetW: Int, targetH: Int): Int {
        if (origH <= targetH && origW <= targetW) return 1
        val ratio = Math.max(origW.toFloat() / targetW, origH.toFloat() / targetH)
        return Math.pow(2.0, floor(log2(ratio.toDouble()))).toInt().coerceAtLeast(1)
    }

    suspend fun findDuplicates(media: List<MediaItem>): List<List<MediaItem>> = withContext(Dispatchers.IO) {
        val bySize = media.groupBy { it.size }.filter { it.value.size > 1 }.values
        val byRes = bySize.flatMap { group -> group.groupBy { "${it.width}x${it.height}" }.filter { it.value.size > 1 }.values }
        val duplicates = mutableListOf<List<MediaItem>>()
        byRes.forEach { group ->
            val byHash = ConcurrentHashMap<String, MutableList<MediaItem>>()
            group.parallelStream().forEach { item ->
                try {
                    val md = MessageDigest.getInstance("SHA-256"); val f = File(item.path)
                    if (f.exists() && f.length() < 20_000_000) {
                        f.inputStream().use { inp -> val buf = ByteArray(8192); var read: Int; while (inp.read(buf).also { read = it } != -1) md.update(buf, 0, read) }
                        byHash.getOrPut(md.digest().joinToString("") { "%02x".format(it) }) { mutableListOf() }.add(item)
                    }
                } catch (e: Exception) {}
            }
            duplicates.addAll(byHash.values.filter { it.size > 1 })
        }
        return@withContext duplicates
    }
}

class VideoPlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null; private var player: ExoPlayer? = null
    @OptIn(UnstableApi::class) override fun onCreate() {
        super.onCreate()
        val rFactory = DefaultRenderersFactory(this).setEnableDecoderFallback(true).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        val loadControl = DefaultLoadControl.Builder().setBufferDurationsMs(2000, 5000, 500, 1000).build()
        player = ExoPlayer.Builder(this).setRenderersFactory(rFactory).setLoadControl(loadControl).setSeekBackIncrementMs(10000).setSeekForwardIncrementMs(10000).build().apply { setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true); setHandleAudioBecomingNoisy(true) }
        val callback = object : MediaSession.Callback { override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<Media3Item>): ListenableFuture<MutableList<Media3Item>> { return Futures.immediateFuture(mediaItems.map { if (it.localConfiguration != null) it else Media3Item.fromUri(it.mediaId) }.toMutableList()) } }
        mediaSession = MediaSession.Builder(this, player!!).setId("GalleryBox_Video").setCallback(callback).build()
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession
    override fun onDestroy() { mediaSession?.run { player.release(); release() }; mediaSession = null; player = null; super.onDestroy() }
}