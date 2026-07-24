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
        val itemsToTrash = allMedia.filter { it.bucketId in albumIds }
        if (itemsToTrash.isNotEmpty()) {
            confirmPendingGalleryTrash(itemsToTrash)
        }
    }

    fun onPermissionResultGlobal(granted: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        if (granted && pendingTrashEntities.isNotEmpty()) {

            val verifiedEntities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pendingTrashEntities.filter { entity ->
                    try {
                        val cursor = resolver.query(
                            Uri.parse(entity.contentUri),
                            arrayOf(MediaStore.MediaColumns.IS_TRASHED),
                            null,
                            null,
                            null
                        )
                        var isTrashed = false
                        cursor?.use {
                            if (it.moveToFirst()) {
                                isTrashed = it.getInt(0) == 1
                            }
                        }
                        isTrashed
                    } catch (e: Exception) {
                        false
                    }
                }
            } else {
                pendingTrashEntities
            }

            if (verifiedEntities.isNotEmpty()) {
                galleryDao.insertTrashItemsBulk(verifiedEntities)
            }

            withContext(Dispatchers.Main) {
                onRefreshGallery?.invoke()
                onRefreshMusic?.invoke()
                onRefreshDocuments?.invoke()

                if (verifiedEntities.size == pendingTrashEntities.size) {
                    _events.send(GalleryEvent.OperationSuccess)
                } else if (verifiedEntities.isNotEmpty()) {
                    _events.send(GalleryEvent.ShowToast("Operation partially completed. Some items failed to trash."))
                } else {
                    _events.send(GalleryEvent.ShowToast("System rejected the trash request."))
                }
            }
        }
        pendingTrashEntities = emptyList()
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

        _operationProgress.value = 0f
        val successfulRestores = mutableListOf<Long>()
        val total = items.size.toFloat()

        try {
            items.chunked(100).forEachIndexed { chunkIndex, chunk ->
                chunk.forEach { item ->
                    var success = false

                    if (item.mediaType == "story") {
                        try {
                            val nameParts = item.name.split("|||")
                            val storyId = nameParts.getOrElse(0) { "story_${System.currentTimeMillis()}" }
                            val title = nameParts.getOrElse(1) { "Restored Story" }
                            val pathParts = item.originalPath.split("|||")
                            val subtitle = pathParts.getOrElse(0) { "" }
                            val mediaIdsJson = "[" + pathParts.getOrElse(1) { "" } + "]"

                            galleryDao.insertStory(
                                StoryEntity(
                                    id = storyId,
                                    title = title,
                                    subtitle = subtitle,
                                    coverUri = item.contentUri,
                                    mediaIdsJson = mediaIdsJson,
                                    createdAt = System.currentTimeMillis(),
                                    storyType = "MANUAL"
                                )
                            )
                            success = true
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to restore virtual story", e)
                        }
                    } else {
                        try {
                            val updatedRows = resolver.update(
                                Uri.parse(item.contentUri),
                                ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 0) },
                                null,
                                null
                            )
                            if (updatedRows > 0) {
                                success = true
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Restore failed for ${item.contentUri}", e)
                        }
                    }

                    if (success) {
                        successfulRestores.add(item.id)
                    }
                }

                _operationProgress.value = ((chunkIndex * 100) + chunk.size) / total
            }

            if (successfulRestores.isNotEmpty()) {
                galleryDao.deleteTrashItems(successfulRestores)
            }

            withContext(Dispatchers.Main) {
                onRefreshGallery?.invoke()
                if (successfulRestores.size == items.size) {
                    _events.send(GalleryEvent.OperationSuccess)
                } else {
                    _events.send(GalleryEvent.ShowToast("Restored ${successfulRestores.size} out of ${items.size} items"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Restore bulk operation failed", e)
        } finally {
            _operationProgress.value = null
        }
    }

    fun permanentlyDeleteTrash(items: List<TrashEntity>) = viewModelScope.launch(Dispatchers.IO) {
        if (items.isEmpty()) return@launch

        _operationProgress.value = 0f
        val successfulDeletes = mutableListOf<Long>()
        val total = items.size.toFloat()

        try {
            items.chunked(100).forEachIndexed { chunkIndex, chunk ->
                chunk.forEach { item ->
                    var success = false

                    if (item.mediaType == "story") {
                        success = true
                    } else {
                        try {
                            val deletedRows = resolver.delete(Uri.parse(item.contentUri), null, null)
                            if (deletedRows > 0) {
                                success = true
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Delete failed for ${item.contentUri}", e)
                        }
                    }

                    if (success) {
                        successfulDeletes.add(item.id)
                    }
                }

                _operationProgress.value = ((chunkIndex * 100) + chunk.size) / total
            }

            if (successfulDeletes.isNotEmpty()) {
                galleryDao.deleteTrashItems(successfulDeletes)
            }

            withContext(Dispatchers.Main) {
                onRefreshGallery?.invoke()
                if (successfulDeletes.size == items.size) {
                    _events.send(GalleryEvent.OperationSuccess)
                } else {
                    _events.send(GalleryEvent.ShowToast("Deleted ${successfulDeletes.size} out of ${items.size} items"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Delete bulk operation failed", e)
        } finally {
            _operationProgress.value = null
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
        val trashItems = songs.map {
            TrashEntity(
                deletedTimestamp = System.currentTimeMillis(),
                originalPath = it.path,
                contentUri = it.uri,
                mediaType = "audio",
                name = it.title,
                size = 0L
            )
        }

        var successCount = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            trashItems.forEach { item ->
                try {
                    val updated = resolver.update(Uri.parse(item.contentUri), ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 1) }, null, null)
                    if (updated > 0) successCount++
                } catch (_: Exception) {}
            }
        } else {
            trashItems.forEach { item ->
                try {
                    val deleted = resolver.delete(Uri.parse(item.contentUri), null, null)
                    if (deleted > 0) successCount++
                } catch (_: Exception) {}
            }
        }

        if (successCount > 0) {
            galleryDao.insertTrashItemsBulk(trashItems)
        }

        withContext(Dispatchers.Main) { onRefreshMusic?.invoke() }
    }

    fun restoreSongs(items: List<TrashEntity>, onComplete: (() -> Unit)? = null) = viewModelScope.launch(Dispatchers.IO) {
        val successfulRestores = mutableListOf<Long>()

        items.forEach { item ->
            try {
                val updated = resolver.update(Uri.parse(item.contentUri), ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 0) }, null, null)
                if (updated > 0) successfulRestores.add(item.id)
            } catch (_: Exception) {}
        }

        if (successfulRestores.isNotEmpty()) {
            galleryDao.deleteTrashItems(successfulRestores)
        }

        withContext(Dispatchers.Main) {
            onRefreshMusic?.invoke()
            onComplete?.invoke()
        }
    }

    fun permanentlyDeleteSongs(items: List<TrashEntity>, onComplete: (() -> Unit)? = null) = viewModelScope.launch(Dispatchers.IO) {
        val successfulDeletes = mutableListOf<Long>()

        items.forEach { item ->
            try {
                val deleted = resolver.delete(Uri.parse(item.contentUri), null, null)
                if (deleted > 0) successfulDeletes.add(item.id)
            } catch (_: Exception) {}
        }

        if (successfulDeletes.isNotEmpty()) {
            galleryDao.deleteTrashItems(successfulDeletes)
        }

        withContext(Dispatchers.Main) {
            onRefreshMusic?.invoke()
            onComplete?.invoke()
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