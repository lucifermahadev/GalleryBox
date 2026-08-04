@file:Suppress("unused", "OPT_IN_USAGE", "UNCHECKED_CAST", "ObsoleteSdkInt", "DEPRECATION")

package com.gallerybox.viewmodel

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gallerybox.data.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltViewModel
class TrashViewModel @Inject constructor(
    application: Application,
    private val galleryDao: GalleryDao
) : AndroidViewModel(application) {

    private val TAG = "TrashViewModel"
    private val resolver = application.contentResolver

    private val _events = Channel<GalleryEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val _operationProgress = MutableStateFlow<Float?>(null)
    val operationProgress = _operationProgress.asStateFlow()

    @Volatile private var pendingTrashEntities: List<TrashEntity> = emptyList()
    @Volatile private var pendingDeleteEntities: List<TrashEntity> = emptyList()
    @Volatile private var pendingDeleteAction: (() -> Unit)? = null

    @Volatile private var pendingRestoreEntities: List<TrashEntity> = emptyList()
    @Volatile private var pendingRestoreOnComplete: (() -> Unit)? = null

    var onRefreshGallery: (suspend () -> Unit)? = null
    var onRefreshMusic: (suspend () -> Unit)? = null
    var onRefreshDocuments: (suspend () -> Unit)? = null

    fun calculateDaysLeft(deletedTimestamp: Long): Int {
        val currentTime = System.currentTimeMillis()
        val daysPassed = TimeUnit.MILLISECONDS.toDays(currentTime - deletedTimestamp).toInt()
        return (30 - daysPassed).coerceAtLeast(0)
    }

    fun confirmPendingAlbumTrash(albums: List<Album>, allMedia: List<MediaItem>) {
        val albumIds = albums.map { it.id }

        // Properly match contents for both physical folders AND virtual albums
        val itemsToTrash = allMedia.filter { item ->
            albumIds.any { albumId ->
                when (albumId) {
                    "virtual_favorites" -> item.isFavorite
                    "virtual_videos" -> item.isVideo
                    "virtual_screenshots" -> item.path.contains("Screenshot", true) || item.path.contains("Screenshots", true)
                    "virtual_downloads" -> item.path.contains("Download", true)
                    "virtual_whatsapp" -> item.path.contains("WhatsApp", true)
                    "virtual_instagram" -> item.path.contains("Instagram", true)
                    "virtual_recent" -> true // Recent includes all media
                    "virtual_camera" -> item.bucketName.contains("Camera", true) || item.bucketName.contains("DCIM", true)
                    else -> item.bucketId == albumId
                }
            }
        }

        if (itemsToTrash.isNotEmpty()) {
            confirmPendingGalleryTrash(itemsToTrash)
        }
    }

    fun onPermissionResultGlobal(granted: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        if (granted) {
            when {
                pendingTrashEntities.isNotEmpty() -> {
                    galleryDao.insertTrashItemsBulk(pendingTrashEntities)
                    withContext(Dispatchers.Main) {
                        onRefreshGallery?.invoke()
                        onRefreshMusic?.invoke()
                        onRefreshDocuments?.invoke()
                        _events.send(GalleryEvent.OperationSuccess)
                    }
                }
                pendingRestoreEntities.isNotEmpty() -> {
                    galleryDao.deleteTrashItems(pendingRestoreEntities.map { it.id })
                    withContext(Dispatchers.Main) {
                        onRefreshGallery?.invoke()
                        onRefreshMusic?.invoke()
                        onRefreshDocuments?.invoke()
                        pendingRestoreOnComplete?.invoke()
                        _events.send(GalleryEvent.OperationSuccess)
                    }
                }
                pendingDeleteEntities.isNotEmpty() -> {
                    galleryDao.deleteTrashItems(pendingDeleteEntities.map { it.id })
                    withContext(Dispatchers.Main) {
                        onRefreshGallery?.invoke()
                        onRefreshMusic?.invoke()
                        onRefreshDocuments?.invoke()
                        pendingDeleteAction?.invoke()
                        _events.send(GalleryEvent.OperationSuccess)
                    }
                }
            }
        } else {
            withContext(Dispatchers.Main) {
                when {
                    pendingDeleteEntities.isNotEmpty() -> _events.send(GalleryEvent.ShowToast("Delete permission denied"))
                    pendingRestoreEntities.isNotEmpty() -> _events.send(GalleryEvent.ShowToast("Restore permission denied"))
                    pendingTrashEntities.isNotEmpty() -> _events.send(GalleryEvent.ShowToast("Trash permission denied"))
                }
            }
        }
        pendingTrashEntities = emptyList()
        pendingRestoreEntities = emptyList()
        pendingDeleteEntities = emptyList()
        pendingDeleteAction = null
        pendingRestoreOnComplete = null
    }

    fun moveStoriesToTrash(stories: List<UiStory>) = viewModelScope.launch(Dispatchers.IO) {
        val trashItems = stories.map { story ->
            TrashEntity(
                deletedTimestamp = System.currentTimeMillis(),
                originalPath = "${story.subtitle}|||${story.items.joinToString(",") { it.id.toString() }}",
                contentUri = story.coverUri.toString(),
                mediaType = "story",
                name = "${story.id}|||${story.title}",
                size = 0L
            )
        }
        galleryDao.insertTrashItemsBulk(trashItems)
        galleryDao.deleteStories(stories.map { it.id })

        withContext(Dispatchers.Main) {
            onRefreshGallery?.invoke()
            _events.send(GalleryEvent.OperationSuccess)
        }
    }

    fun restoreTrashItems(items: List<TrashEntity>) = viewModelScope.launch(Dispatchers.IO) {
        if (items.isEmpty()) return@launch

        val stories = items.filter { it.mediaType == "story" }
        val mediaItems = items.filter { it.mediaType != "story" }

        // Stories are virtual, restore immediately, no OS permission involved
        if (stories.isNotEmpty()) {
            val restored = mutableListOf<Long>()
            stories.forEach { item ->
                try {
                    val nameParts = item.name.split("|||")
                    val storyId = nameParts.getOrElse(0) { "story_${System.currentTimeMillis()}" }
                    val title = nameParts.getOrElse(1) { "Restored Story" }
                    val pathParts = item.originalPath.split("|||")
                    val subtitle = pathParts.getOrElse(0) { "" }
                    val mediaIdsJson = "[" + pathParts.getOrElse(1) { "" } + "]"
                    galleryDao.insertStory(StoryEntity(storyId, title, subtitle, item.contentUri, mediaIdsJson, System.currentTimeMillis(), "MANUAL"))
                    restored.add(item.id)
                } catch (e: Exception) { Log.e(TAG, "Failed to restore story", e) }
            }
            if (restored.isNotEmpty()) galleryDao.deleteTrashItems(restored)
        }

        if (mediaItems.isEmpty()) {
            withContext(Dispatchers.Main) { onRefreshGallery?.invoke(); _events.send(GalleryEvent.OperationSuccess) }
            return@launch
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val uris = mediaItems.map { Uri.parse(it.contentUri) }
                val intentSender = MediaStore.createTrashRequest(resolver, uris, false).intentSender // false = untrash
                pendingRestoreEntities = mediaItems
                _events.send(GalleryEvent.RequestPermission(intentSender))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create restore request", e)
                withContext(Dispatchers.Main) { _events.send(GalleryEvent.ShowToast("Failed to restore items")) }
            }
        } else {
            withContext(Dispatchers.Main) { _events.send(GalleryEvent.ShowToast("These items can't be restored on this Android version")) }
        }
    }

    fun permanentlyDeleteTrash(items: List<TrashEntity>) = viewModelScope.launch(Dispatchers.IO) {
        if (items.isEmpty()) return@launch

        val stories = items.filter { it.mediaType == "story" }
        val mediaItems = items.filter { it.mediaType != "story" }

        // Stories are virtual, delete them immediately from Room
        if (stories.isNotEmpty()) {
            galleryDao.deleteTrashItems(stories.map { it.id })
        }

        if (mediaItems.isEmpty()) {
            withContext(Dispatchers.Main) {
                onRefreshGallery?.invoke()
                _events.send(GalleryEvent.OperationSuccess)
            }
            return@launch
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val uris = mediaItems.map { Uri.parse(it.contentUri) }
                val intentSender = MediaStore.createDeleteRequest(resolver, uris).intentSender
                pendingDeleteEntities = mediaItems
                _events.send(GalleryEvent.RequestPermission(intentSender))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create delete request", e)
                _events.send(GalleryEvent.ShowToast("Failed to initiate delete request: ${e.javaClass.simpleName}"))
            }
        } else {
            // Android 10 and below fallback
            var successCount = 0
            val successfulDeletes = mutableListOf<Long>()
            mediaItems.forEach { item ->
                try {
                    val deletedRows = resolver.delete(Uri.parse(item.contentUri), null, null)
                    if (deletedRows > 0) {
                        successCount++
                        successfulDeletes.add(item.id)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Delete failed for ${item.contentUri}", e)
                }
            }

            if (successfulDeletes.isNotEmpty()) {
                galleryDao.deleteTrashItems(successfulDeletes)
            }

            withContext(Dispatchers.Main) {
                onRefreshGallery?.invoke()
                if (successCount == mediaItems.size) {
                    _events.send(GalleryEvent.OperationSuccess)
                } else {
                    _events.send(GalleryEvent.ShowToast("Deleted $successCount out of ${mediaItems.size} items"))
                }
            }
        }
    }

    fun confirmPendingGalleryTrash(itemsToTrash: List<MediaItem>) = viewModelScope.launch(Dispatchers.IO) {
        if (itemsToTrash.isEmpty()) return@launch
        val mappedEntities = itemsToTrash.map { media ->
            TrashEntity(
                deletedTimestamp = System.currentTimeMillis(),
                originalPath = media.path,
                contentUri = media.uri.toString(),
                mediaType = if (media.isVideo) "video" else "image",
                name = media.name,
                size = media.size
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intentSender = MediaStore.createTrashRequest(resolver, itemsToTrash.map { it.uri }, true).intentSender
                pendingTrashEntities = mappedEntities
                _events.send(GalleryEvent.RequestPermission(intentSender))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create gallery trash request", e)
                _events.send(GalleryEvent.ShowToast("Failed to initiate trash request"))
            }
        } else {
            urisFallbackDelete(uris = itemsToTrash.map { it.uri }, mappedEntities) { onRefreshGallery?.invoke() }
        }
    }

    fun confirmPendingMusicTrash(itemsToTrash: List<AudioTrack>, onPermissionRequested: (IntentSenderRequest) -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        if (itemsToTrash.isEmpty()) return@launch
        val mappedEntities = itemsToTrash.map { track ->
            TrashEntity(
                deletedTimestamp = System.currentTimeMillis(),
                originalPath = track.path,
                contentUri = track.uri,
                mediaType = "audio",
                name = track.title,
                size = 0L
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intentSender = MediaStore.createTrashRequest(resolver, itemsToTrash.map { Uri.parse(it.uri) }, true).intentSender
                pendingTrashEntities = mappedEntities
                withContext(Dispatchers.Main) {
                    onPermissionRequested(IntentSenderRequest.Builder(intentSender).build())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create music trash request", e)
            }
        } else {
            urisFallbackDelete(uris = itemsToTrash.map { Uri.parse(it.uri) }, mappedEntities) { onRefreshMusic?.invoke() }
        }
    }

    fun onPermissionResultMusic(granted: Boolean) {
        onPermissionResultGlobal(granted)
        if (granted) {
            viewModelScope.launch { onRefreshMusic?.invoke() }
        }
    }

    fun moveSongsToTrash(songs: List<AudioTrack>) = viewModelScope.launch(Dispatchers.IO) {
        if (songs.isEmpty()) return@launch
        val mappedEntities = songs.map {
            TrashEntity(
                deletedTimestamp = System.currentTimeMillis(),
                originalPath = it.path,
                contentUri = it.uri,
                mediaType = "audio",
                name = it.title,
                size = 0L
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intentSender = MediaStore.createTrashRequest(resolver, songs.map { Uri.parse(it.uri) }, true).intentSender
                pendingTrashEntities = mappedEntities
                _events.send(GalleryEvent.RequestPermission(intentSender))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create music trash request", e)
            }
        } else {
            urisFallbackDelete(uris = songs.map { Uri.parse(it.uri) }, mappedEntities) { onRefreshMusic?.invoke() }
        }
    }

    fun restoreSongs(items: List<TrashEntity>, onComplete: (() -> Unit)? = null) = viewModelScope.launch(Dispatchers.IO) {
        if (items.isEmpty()) return@launch
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intentSender = MediaStore.createTrashRequest(resolver, items.map { Uri.parse(it.contentUri) }, false).intentSender
                pendingRestoreEntities = items
                pendingRestoreOnComplete = onComplete
                _events.send(GalleryEvent.RequestPermission(intentSender))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create music restore request", e)
                withContext(Dispatchers.Main) { onComplete?.invoke() }
            }
        } else {
            withContext(Dispatchers.Main) {
                _events.send(GalleryEvent.ShowToast("These items can't be restored on this Android version"))
                onComplete?.invoke()
            }
        }
    }

    fun permanentlyDeleteSongs(items: List<TrashEntity>, onComplete: (() -> Unit)? = null) = viewModelScope.launch(Dispatchers.IO) {
        if (items.isEmpty()) return@launch

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val uris = items.map { Uri.parse(it.contentUri) }
                val intentSender = MediaStore.createDeleteRequest(resolver, uris).intentSender
                pendingDeleteEntities = items
                pendingDeleteAction = onComplete
                _events.send(GalleryEvent.RequestPermission(intentSender))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create delete request for music", e)
                withContext(Dispatchers.Main) { onComplete?.invoke() }
            }
        } else {
            val successfulDeletes = mutableListOf<Long>()
            items.forEach { item ->
                try {
                    val deleted = resolver.delete(Uri.parse(item.contentUri), null, null)
                    if (deleted > 0) successfulDeletes.add(item.id)
                } catch (e: Exception) {
                    Log.e(TAG, "Delete failed for music", e)
                }
            }

            if (successfulDeletes.isNotEmpty()) {
                galleryDao.deleteTrashItems(successfulDeletes)
            }

            withContext(Dispatchers.Main) {
                onRefreshMusic?.invoke()
                onComplete?.invoke()
            }
        }
    }

    private suspend fun urisFallbackDelete(uris: List<Uri>, entities: List<TrashEntity>, onExecuted: suspend () -> Unit) {
        val successfulUris = mutableListOf<Uri>()

        uris.forEach { uri ->
            try {
                if (resolver.delete(uri, null, null) > 0) {
                    successfulUris.add(uri)
                }
            } catch (_: Exception) {}
        }

        val verifiedEntities = entities.filter { entity ->
            successfulUris.any { it.toString() == entity.contentUri }
        }

        if (verifiedEntities.isNotEmpty()) {
            galleryDao.insertTrashItemsBulk(verifiedEntities)
        }

        withContext(Dispatchers.Main) { onExecuted() }
    }
}