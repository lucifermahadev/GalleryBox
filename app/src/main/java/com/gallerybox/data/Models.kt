@file:Suppress("unused", "OPT_IN_USAGE", "UNCHECKED_CAST", "ObsoleteSdkInt")

package com.gallerybox.data

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.os.Parcelable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.room.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.gallerybox.engine.PdfRenderSession
import kotlinx.coroutines.flow.Flow
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.*

private val headerDateFormatter = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue() = SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault())
}

@Dao
interface DocumentMetadataDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(metadata: DocumentMetadata)

    @Query("SELECT * FROM document_metadata WHERE id = :id")
    suspend fun getById(id: Long): DocumentMetadata?
}

class Converters {
    @TypeConverter
    fun fromStringList(value: List<String>): String = Json.encodeToString(value)

    @TypeConverter
    fun toStringList(value: String): List<String> = try {
        Json.decodeFromString(value)
    } catch (e: Exception) {
        emptyList()
    }

    @TypeConverter
    fun fromTimestamp(value: Long?): Date? = value?.let { Date(it) }

    @TypeConverter
    fun dateToTimestamp(date: Date?): Long? = date?.time

    @TypeConverter
    fun fromUUID(uuid: UUID?): String? = uuid?.toString()

    @TypeConverter
    fun toUUID(uuid: String?): UUID? = uuid?.let { UUID.fromString(it) }

    @TypeConverter
    fun fromFloatArray(value: FloatArray?): String? = value?.joinToString(",")

    @TypeConverter
    fun toFloatArray(value: String?): FloatArray? = try {
        if (value.isNullOrEmpty()) {
            FloatArray(0)
        } else {
            value.split(",").map { it.toFloat() }.toFloatArray()
        }
    } catch (e: Exception) {
        FloatArray(0)
    }

    @TypeConverter
    fun fromUri(uri: Uri?): String? = uri?.toString()

    @TypeConverter
    fun toUri(uriString: String?): Uri? = uriString?.let { Uri.parse(it) }
}

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val path: String,
    val relativePath: String,
    val name: String,
    val dateAdded: Long,
    val size: Long,
    val isVideo: Boolean,
    val isPdf: Boolean = false,
    val isDocument: Boolean = false,
    val duration: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val mimeType: String = "",
    val bucketId: String,
    val bucketName: String,
    val isFavorite: Boolean = false,
    val isHidden: Boolean = false
) {
    val dateHeader: String
        get() = try {
            headerDateFormatter.get()?.format(Date(dateAdded * 1000)) ?: "Unknown Date"
        } catch (e: Exception) {
            "Unknown Date"
        }
}

enum class MaskType {
    RECTANGLE, CIRCLE, CUSTOM_PATH, NONE
}

@androidx.compose.runtime.Immutable
data class FrameAsset(
    val id: String,
    val name: String,
    val category: String,
    val borderSvg: String,
    val maskSvg: String?,
    val padding: Dp = 0.dp,
    val maskType: MaskType,
    val allowMove: Boolean = true,
    val allowZoom: Boolean = true
)

@androidx.compose.runtime.Immutable
data class FrameLayer(
    val assetPath: String,
    val opacity: Float = 1f,
    val isEnabled: Boolean = true,
    val isVisible: Boolean = true,
    val zIndex: Int = 0
)

@androidx.compose.runtime.Immutable
data class StickerLayer(
    val id: String,
    val assetPath: String,
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    val scale: Float = 1f,
    val rotation: Float = 0f,
    val opacity: Float = 1f,
    val isVisible: Boolean = true,
    val zIndex: Int = 0
)

@androidx.compose.runtime.Immutable
data class TextLayer(
    val id: String,
    val text: String,
    val color: Int,
    val size: Float,
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    val rotation: Float = 0f,
    val opacity: Float = 1f,
    val isVisible: Boolean = true,
    val zIndex: Int = 0
)

data class Album(
    val id: String,
    val name: String,
    val coverUri: Uri?,
    val mediaCount: Int,
    val sizeBytes: Long = 0,
    val isPinned: Boolean = false,
    val isSdCard: Boolean = false,
    val isHidden: Boolean = false,
    val sortOrder: Int = 0
)

data class UiStory(
    val id: String,
    val title: String,
    val subtitle: String,
    val coverUri: Uri,
    val items: List<MediaItem>
)

data class CubeLut(
    val size: Int,
    val data: FloatArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as CubeLut
        if (size != other.size) return false
        if (!data.contentEquals(other.data)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = size
        result = 31 * result + data.contentHashCode()
        return result
    }
}

data class LutCategory(
    val name: String,
    val files: List<String>
)

data class OpenMojiItem(
    val emoji: String = "",
    val hexcode: String = "",
    val annotation: String = "",
    val group: String = "",
    val subgroups: String = "",
    val tags: List<String> = emptyList()
)

data class StickerCategory(
    val name: String,
    val stickers: List<OpenMojiItem>
)

data class StickerUiItem(
    val name: String,
    val category: String,
    val assetPath: String,
    val emoji: String
)

data class ExportSettings(
    val format: String = "mp4",
    val quality: Int = 100,
    val resolution: Pair<Int, Int> = Pair(1920, 1080)
)

data class DrawLayer(
    val id: String,
    val points: List<Offset> = emptyList(),
    val color: Int,
    val width: Float,
    val isVisible: Boolean = true,
    val zIndex: Int = 0
)

data class MosaicLayer(
    val id: String = UUID.randomUUID().toString(),
    val region: RectF,
    val intensity: Float = 0.5f,
    val isVisible: Boolean = true
)

// --- EDITOR STATE ---

data class EditState(
    val exportSettings: ExportSettings = ExportSettings(),

    // Core Adjustments
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val exposure: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val tint: Float = 0f,
    val temperature: Float = 0f,

    // Filter/LUT
    val filterId: String? = null,
    val lutData: CubeLut? = null,
    val lutIntensity: Float = 1f,

    // Crop/Transform
    val cropRect: RectF? = null,
    val aspectRatio: Float? = null,
    val rotationDegrees: Float = 0f,
    val straightenDegrees: Float = 0f,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,

    // Trim/CutOut (Ripple Delete)
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = 0L,
    val cutOutStartMs: Long = 0L, // Added for Ripple Delete
    val cutOutEndMs: Long = 0L,   // Added for Ripple Delete
    val mosaicRegions: List<MosaicLayer> = emptyList(),

    // Audio
    val videoVolume: Float = 1f,
    val isMuted: Boolean = false,

    // Overlays
    val frames: List<FrameLayer> = emptyList(),
    val stickers: List<StickerLayer> = emptyList(),
    val textLayers: List<TextLayer> = emptyList(),
    val drawLayers: List<DrawLayer> = emptyList()
)

data class FullMediaMetadata(
    @Embedded val core: MediaMetadataCore,
    @Relation(parentColumn = "mediaId", entityColumn = "mediaId") val video: MediaMetadataVideo?,
    @Relation(parentColumn = "mediaId", entityColumn = "mediaId") val flags: MediaMetadataFlags?
)

data class MediaMetadata(
    val core: MediaMetadataCore,
    val video: MediaMetadataVideo?,
    val flags: MediaMetadataFlags?
)

fun MediaMetadata.toCore() = core
fun MediaMetadata.toVideo() = video ?: MediaMetadataVideo(core.mediaId, 0, 0.0)
fun MediaMetadata.toFlags() = flags ?: MediaMetadataFlags(core.mediaId, false, false)

data class VaultInfo(
    val version: Int,
    val transferable: Boolean,
    val dkHash: String,
    val uuid: String
)

data class PlaylistWithTracks(
    @Embedded val playlist: PlaylistEntity,
    @Relation(parentColumn = "id", entityColumn = "playlistId") val trackRefs: List<PlaylistTrackCrossRef>
)

data class PdfStroke(
    val start: Offset,
    val end: Offset,
    val color: Color,
    val width: Float
)

data class PdfAnnotationState(
    val pageIndex: Int,
    val strokes: MutableList<PdfStroke>
)
@Entity(tableName = "music_playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String
)

@Entity(
    tableName = "music_playlist_tracks",
    primaryKeys = ["playlistId", "trackId"]
)
data class PlaylistTrackCrossRef(
    val playlistId: Long,
    val trackId: Long
)

@Entity(tableName = "music_track_stats")
data class TrackStatEntity(
    @PrimaryKey val trackId: Long,
    val playCount: Int,
    val lastPlayed: Long,
    val isFavorite: Boolean
)

@Entity(
    tableName = "trash",
    indices = [
        Index(value = ["mediaType"]),
        Index(value = ["deletedTimestamp"])
    ]
)
data class TrashEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deletedTimestamp: Long,
    val originalPath: String,
    val contentUri: String,
    val mediaType: String,
    val name: String,
    val size: Long
)

@Entity(
    tableName = "stories",
    indices = [Index(value = ["createdAt"])]
)
data class StoryEntity(
    @PrimaryKey val id: String,
    val title: String,
    val subtitle: String? = null,
    val coverUri: String,
    val mediaIdsJson: String,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "AUTO_GENERATED") val storyType: String = "AUTO_GENERATED"
)

@Entity(tableName = "media_usage_stats")
data class UsageEntity(
    @PrimaryKey val mediaId: Long,
    val openCount: Int,
    val lastOpened: Long
)

@Parcelize
@Entity(
    tableName = "media_table",
    indices = [
        Index(value = ["mediaType"]),
        Index(value = ["dateAdded"]),
        Index(value = ["bucketId"])
    ]
)
data class MediaEntity(
    @PrimaryKey(autoGenerate = false) val id: Long,
    val path: String,
    val contentUri: String,
    val name: String,
    val size: Long,
    val mediaType: String,
    val mimeType: String,
    val dateAdded: Long,
    val dateModified: Long,
    val width: Int = 0,
    val height: Int = 0,
    val orientation: Int = 0,
    val duration: Long = 0,
    val bucketId: String = "",
    val bucketName: String = "",
    val isTrashed: Boolean = false,
    val trashTimestamp: Long? = null
) : Parcelable {
    @IgnoredOnParcel
    val uri: Uri
        get() = Uri.parse(contentUri)

    @IgnoredOnParcel
    val isVideo: Boolean
        get() = mediaType.equals("video", ignoreCase = true)

    @IgnoredOnParcel
    val isPdf: Boolean
        get() = mimeType == "application/pdf"

    @IgnoredOnParcel
    val isDocument: Boolean
        get() = mediaType.equals("document", ignoreCase = true) ||
                mimeType.startsWith("application/") ||
                mimeType.startsWith("text/")

    fun formatDuration(): String {
        if (duration <= 0) return ""
        val s = (duration / 1000) % 60
        val m = (duration / (1000 * 60)) % 60
        val h = (duration / (1000 * 60 * 60))
        return if (h > 0) {
            "%d:%02d:%02d".format(h, m, s)
        } else {
            "%d:%02d".format(m, s)
        }
    }
}

@Entity(tableName = "album_meta")
data class AlbumEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(defaultValue = "0") val isPinned: Boolean = false,
    @ColumnInfo(defaultValue = "0") val isHidden: Boolean = false,
    val customCoverUri: String? = null,
    val customName: String? = null,
    @ColumnInfo(defaultValue = "0") val sortOrder: Int = 0,
    @ColumnInfo(defaultValue = "0") val albumOrder: Int = 0
)

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val mediaId: Long
)

@Entity(tableName = "secure_media")
data class SecureMediaEntity(
    @PrimaryKey val mediaId: Long
)

@Entity(tableName = "album_groups")
data class AlbumGroupEntity(
    @PrimaryKey val groupName: String,
    val albumIdsJson: String,
    val coverUri: String? = null
)

@Entity(tableName = "media")
data class UriMedia(
    @PrimaryKey val id: Long,
    val label: String,
    val uri: String,
    val path: String,
    val relativePath: String,
    val albumID: Long,
    val albumLabel: String,
    val timestamp: Long,
    val fullDate: String,
    val mimeType: String,
    val orientation: Int,
    val isFavorite: Int,
    val isTrashed: Int,
    val duration: String? = null
)

@Entity(tableName = "encrypted_media")
data class EncryptedMedia2(
    @PrimaryKey val id: Long,
    val uuid: UUID,
    val bytes: ByteArray,
    val mimeType: String,
    val originalName: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EncryptedMedia2
        if (id != other.id) return false
        if (!bytes.contentEquals(other.bytes)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

@Entity(tableName = "vaults")
data class Vault(
    @PrimaryKey val uuid: UUID,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "pinned_table")
data class PinnedAlbum(
    @PrimaryKey val id: Long
)

@Entity(tableName = "blacklist")
data class IgnoredAlbum(
    @PrimaryKey val id: Long,
    val label: String
)

@Entity(tableName = "album_thumbnail")
data class AlbumThumbnail(
    @PrimaryKey val albumId: Long,
    val thumbnailUri: String
)

@Entity(tableName = "media_version")
data class MediaVersion(
    @PrimaryKey val version: String
)

@Entity(tableName = "timeline_settings")
data class TimelineSettings(
    @PrimaryKey val id: Int = 0,
    val groupContent: Boolean = true
)

@Entity(tableName = "media_metadata_core")
data class MediaMetadataCore(
    @PrimaryKey val mediaId: Long,
    val width: Int,
    val height: Int,
    val location: String? = null
)

@Entity(tableName = "media_metadata_video")
data class MediaMetadataVideo(
    @PrimaryKey val mediaId: Long,
    val duration: Long,
    val fps: Double
)

@Entity(tableName = "media_metadata_flags")
data class MediaMetadataFlags(
    @PrimaryKey val mediaId: Long,
    val isRaw: Boolean,
    val isHdr: Boolean
)

@Entity(tableName = "manual_albums")
data class ManualAlbumEntity(
    @PrimaryKey val id: String,
    val name: String,
    val coverUri: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val hasBeenUsed: Boolean = false
)
@Entity(tableName = "document_extra_metadata")
data class DocumentEntity(
    @PrimaryKey val mediaId: Long,
    val pageCount: Int,
    val wordCount: Int,
    val sheetCount: Int,
    val slideCount: Int,
    val lastOpened: Long
)

@Entity(tableName = "document_bookmarks")
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaId: Long,
    val pageIndex: Int,
    val label: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "document_search_index",
    indices = [
        Index(value = ["mediaId"]),
        Index(value = ["content"])
    ]
)
data class SearchIndexEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaId: Long,
    val pageIndex: Int,
    val content: String
)
@Dao
@JvmSuppressWildcards
interface MusicDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Delete
    suspend fun deletePlaylist(playlist: PlaylistEntity): Int

    @Query("UPDATE music_playlists SET name = :newName WHERE id = :id")
    suspend fun renamePlaylist(id: Long, newName: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addTrackToPlaylist(crossRef: PlaylistTrackCrossRef): Long

    @Delete
    suspend fun removeTrackFromPlaylist(crossRef: PlaylistTrackCrossRef): Int

    @Transaction
    @Query("SELECT * FROM music_playlists")
    fun getPlaylistsWithTracks(): Flow<List<PlaylistWithTracks>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStats(stats: List<TrackStatEntity>)

    @Query("SELECT * FROM music_playlists")
    fun getPlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT trackId FROM music_playlist_tracks WHERE playlistId = :playlistId")
    suspend fun getTrackIdsForPlaylist(playlistId: Long): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStat(stat: TrackStatEntity): Long

    @Query("SELECT * FROM music_track_stats WHERE trackId = :trackId")
    suspend fun getStat(trackId: Long): TrackStatEntity?

    @Query("SELECT * FROM music_track_stats")
    suspend fun getAllStats(): List<TrackStatEntity>
}

@Dao
@JvmSuppressWildcards
abstract class GalleryDao {

    @Query("SELECT * FROM media_usage_stats")
    abstract fun getAllUsageStats(): Flow<List<UsageEntity>>

    @Transaction
    open suspend fun incrementUsageStats(mediaId: Long, timestamp: Long) {
        val rowsUpdated = updateUsageStats(mediaId, timestamp)
        if (rowsUpdated == 0) {
            insertUsageStats(UsageEntity(mediaId, 1, timestamp))
        }
    }

    @Query("UPDATE media_usage_stats SET openCount = openCount + 1, lastOpened = :timestamp WHERE mediaId = :mediaId")
    abstract suspend fun updateUsageStats(mediaId: Long, timestamp: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertUsageStats(usage: UsageEntity): Long

    @Query("SELECT * FROM manual_albums ORDER BY createdAt DESC")
    abstract fun getManualAlbums(): Flow<List<ManualAlbumEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertManualAlbum(album: ManualAlbumEntity)

    @Query("DELETE FROM manual_albums WHERE id = :albumId")
    abstract suspend fun deleteManualAlbum(albumId: String)

    @Query("UPDATE manual_albums SET hasBeenUsed = :isUsed WHERE id = :albumId")
    abstract suspend fun updateAlbumUsed(albumId: String, isUsed: Boolean)

    @Query("UPDATE album_meta SET albumOrder = :order WHERE id = :albumId")
    abstract suspend fun updateAlbumOrder(albumId: String, order: Int)

    @Transaction
    open suspend fun updateAlbumOrders(albums: List<Pair<String, Int>>) {
        albums.forEach { updateAlbumOrder(it.first, it.second) }
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(media: List<MediaEntity>): List<Long>

    @Query("SELECT * FROM media_table ORDER BY dateAdded DESC")
    abstract fun getAllMedia(): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media_table WHERE mediaType = 'document' OR mimeType LIKE 'application/%' OR mimeType LIKE 'text/%' ORDER BY dateAdded DESC")
    abstract fun getAllDocuments(): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media_table")
    abstract fun getAllMediaSync(): List<MediaEntity>

    @Query("SELECT * FROM media_table WHERE mediaType = 'document' OR mimeType LIKE 'application/%' OR mimeType LIKE 'text/%'")
    abstract fun getDocumentsSync(): List<MediaEntity>

    @Query("SELECT * FROM media_table ORDER BY dateAdded DESC LIMIT :limit OFFSET :offset")
    abstract suspend fun getMediaChunk(limit: Int, offset: Int): List<MediaEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM stories WHERE id = :id)")
    abstract suspend fun storyExists(id: String): Boolean

    @Query("SELECT * FROM stories ORDER BY createdAt DESC")
    abstract fun getStories(): Flow<List<StoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertStory(story: StoryEntity): Long

    @Query("DELETE FROM stories")
    abstract suspend fun clearStories(): Int

    @Query("DELETE FROM stories WHERE id = :id")
    abstract suspend fun deleteStory(id: String): Int

    @Query("DELETE FROM stories WHERE id IN (:ids)")
    abstract suspend fun deleteStories(ids: List<String>): Int

    @Query("DELETE FROM stories WHERE storyType != 'MANUAL' AND createdAt < :threshold")
    abstract suspend fun deleteOldAutoStories(threshold: Long): Int

    @Query("SELECT * FROM trash WHERE id = :id LIMIT 1")
    abstract suspend fun getTrashItemById(id: Long): TrashEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM trash WHERE id = :id)")
    abstract suspend fun isInTrash(id: Long): Boolean

    @Query("DELETE FROM trash WHERE mediaType = :mediaType")
    abstract suspend fun clearTrashByType(mediaType: String): Int

    @Query("SELECT COUNT(*) FROM trash WHERE mediaType = :mediaType")
    abstract suspend fun getTrashCountByType(mediaType: String): Int

    @Query("SELECT * FROM trash WHERE mediaType IN ('image', 'video', 'audio', 'document', 'story') ORDER BY deletedTimestamp DESC")
    abstract fun getUnifiedTrash(): Flow<List<TrashEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertTrashItemsBulk(items: List<TrashEntity>): List<Long>

    @Transaction
    open suspend fun replaceTrashItems(items: List<TrashEntity>) {
        deleteTrashItems(items.map { it.id })
        insertTrashItemsBulk(items)
    }

    @Query("SELECT * FROM trash ORDER BY deletedTimestamp DESC")
    abstract fun getTrash(): Flow<List<TrashEntity>>

    @Query("SELECT * FROM trash WHERE id IN (:ids)")
    abstract suspend fun getTrashItemsByIds(ids: List<Long>): List<TrashEntity>

    @Query("DELETE FROM trash")
    abstract suspend fun emptyAllTrash(): Int

    @Query("SELECT * FROM trash")
    abstract suspend fun getAllTrashSync(): List<TrashEntity>

    @Query("SELECT (SELECT COUNT(*) FROM trash) == 0")
    abstract fun isTrashEmptyFlow(): Flow<Boolean>

    @Query("SELECT * FROM trash WHERE id = :id LIMIT 1")
    abstract fun getTrashItemByIdSync(id: Long): TrashEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun addToTrash(items: List<TrashEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertTrashItems(items: List<TrashEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertTrashItem(item: TrashEntity): Long

    @Query("DELETE FROM trash WHERE id = :id")
    abstract suspend fun removeFromTrash(id: Long): Int

    @Query("DELETE FROM trash WHERE id IN (:ids)")
    abstract suspend fun removeFromTrash(ids: List<Long>): Int

    @Query("SELECT * FROM trash WHERE deletedTimestamp < :threshold")
    abstract suspend fun getExpiredTrash(threshold: Long): List<TrashEntity>

    @Query("DELETE FROM trash WHERE id IN (:ids)")
    abstract suspend fun deleteTrashItems(ids: List<Long>): Int

    @Query("DELETE FROM trash WHERE deletedTimestamp < :threshold")
    abstract suspend fun deleteTrashOlderThan(threshold: Long): Int

    @Query("SELECT COUNT(*) FROM trash")
    abstract suspend fun getTrashCount(): Int

    @Query("SELECT * FROM trash ORDER BY deletedTimestamp ASC LIMIT :limit")
    abstract suspend fun getOldestTrashItems(limit: Int): List<TrashEntity>

    @Query("SELECT * FROM album_meta")
    abstract fun getAlbumMeta(): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM album_meta WHERE id = :id LIMIT 1")
    abstract suspend fun getAlbumMetaSync(id: String): AlbumEntity?

    @Query("SELECT id FROM album_meta WHERE isPinned = 1")
    abstract fun getPinnedAlbumIds(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertAlbumMetaDefault(album: AlbumEntity): Long

    @Query("INSERT OR IGNORE INTO album_meta (id, isPinned) VALUES (:id, 1)")
    abstract suspend fun addPinnedAlbumRaw(id: String): Long

    @Query("UPDATE album_meta SET isPinned = 1 WHERE id = :id")
    abstract suspend fun setPinnedTrue(id: String): Int

    @Transaction
    open suspend fun addPinnedAlbum(album: AlbumEntity) {
        if (addPinnedAlbumRaw(album.id) == -1L) {
            setPinnedTrue(album.id)
        }
    }

    @Query("DELETE FROM album_meta WHERE id = :albumId")
    abstract suspend fun deleteAlbumMeta(albumId: String)

    @Query("UPDATE album_meta SET isPinned = 0 WHERE id = :id")
    abstract suspend fun removePinnedAlbum(id: String): Int

    @Query("INSERT OR IGNORE INTO album_meta (id, sortOrder) VALUES (:id, :order)")
    abstract suspend fun initAlbumSortOrder(id: String, order: Int): Long

    @Query("UPDATE album_meta SET sortOrder = :order WHERE id = :id")
    abstract suspend fun updateAlbumSortOrderRaw(id: String, order: Int): Int

    @Transaction
    open suspend fun updateAlbumSortOrder(id: String, order: Int) {
        if (initAlbumSortOrder(id, order) == -1L) {
            updateAlbumSortOrderRaw(id, order)
        }
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAlbumMeta(meta: AlbumEntity): Long

    @Query("SELECT mediaId FROM favorites")
    abstract fun getFavoriteIds(): Flow<List<Long>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun addFavorite(entity: FavoriteEntity): Long

    @Query("DELETE FROM favorites WHERE mediaId = :id")
    abstract suspend fun removeFavorite(id: Long): Int

    @Query("SELECT COUNT(*) FROM favorites")
    abstract fun getFavoriteCount(): Flow<Int>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE mediaId = :id)")
    abstract suspend fun isFavorite(id: Long): Boolean

    @Query("SELECT mediaId FROM secure_media")
    abstract fun getSecureMediaIds(): Flow<List<Long>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun addToSecure(item: SecureMediaEntity): Long

    @Query("DELETE FROM secure_media WHERE mediaId = :id")
    abstract suspend fun removeFromSecure(id: Long): Int
}

@Dao
@JvmSuppressWildcards
interface AlbumThumbnailDao {
    @Upsert
    suspend fun updateAlbumThumbnail(albumThumbnail: AlbumThumbnail): Long

    @Query("DELETE FROM album_thumbnail WHERE albumId = :albumId")
    suspend fun deleteAlbumThumbnail(albumId: Long): Int

    @Query("SELECT * FROM album_thumbnail WHERE albumId = :albumId")
    fun getAlbumThumbnail(albumId: Long): Flow<AlbumThumbnail?>

    @Query("SELECT EXISTS(SELECT * FROM album_thumbnail WHERE albumId = :albumId) LIMIT 1")
    fun hasAlbumThumbnail(albumId: Long): Flow<Boolean>

    @Query("SELECT * FROM album_thumbnail")
    suspend fun getAlbumThumbnails(): List<AlbumThumbnail>

    @Query("SELECT * FROM album_thumbnail")
    fun getAlbumThumbnailsFlow(): Flow<List<AlbumThumbnail>>
}

@Dao
interface DocumentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocumentMetadata(meta: DocumentEntity)

    @Query("SELECT * FROM document_extra_metadata WHERE mediaId = :mediaId")
    suspend fun getDocumentMetadata(mediaId: Long): DocumentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: BookmarkEntity): Long

    @Query("SELECT * FROM document_bookmarks WHERE mediaId = :mediaId ORDER BY timestamp DESC")
    fun getBookmarksForMedia(mediaId: Long): Flow<List<BookmarkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSearchIndex(items: List<SearchIndexEntity>)

    @Query("SELECT mediaId FROM document_search_index WHERE content LIKE '%' || :query || '%'")
    suspend fun searchDocuments(query: String): List<Long>
}

@Entity(tableName = "document_metadata")
data class DocumentMetadata(
    @PrimaryKey val id: Long,
    val pageCount: Int = 0,
    val wordCount: Int = 0,
    val sheetCount: Int = 0,
    val slideCount: Int = 0,
    val fileSize: Long = 0,
    val lastOpened: Long = 0,
    val lastViewedPage: Int = 0
)

@Database(
    entities = [
        TrashEntity::class,
        StoryEntity::class,
        MediaEntity::class,
        AlbumEntity::class,
        FavoriteEntity::class,
        SecureMediaEntity::class,
        AlbumGroupEntity::class,
        UriMedia::class,
        EncryptedMedia2::class,
        Vault::class,
        PinnedAlbum::class,
        IgnoredAlbum::class,
        AlbumThumbnail::class,
        MediaVersion::class,
        TimelineSettings::class,
        MediaMetadataCore::class,
        MediaMetadataVideo::class,
        MediaMetadataFlags::class,
        PlaylistEntity::class,
        PlaylistTrackCrossRef::class,
        TrackStatEntity::class,
        ManualAlbumEntity::class,
        UsageEntity::class,
        DocumentMetadata::class,
        DocumentEntity::class,
        BookmarkEntity::class,
        SearchIndexEntity::class
    ],
    version = 15,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class GalleryDatabase : RoomDatabase() {
    abstract fun galleryDao(): GalleryDao
    abstract fun albumThumbnailDao(): AlbumThumbnailDao
    abstract fun musicDao(): MusicDao
    abstract fun documentMetadataDao(): DocumentMetadataDao
    abstract fun documentDao(): DocumentDao

    companion object {
        const val DATABASE_NAME = "gallerybox_db"

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE album_meta ADD COLUMN albumOrder INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `media_usage_stats` (`mediaId` INTEGER NOT NULL, `openCount` INTEGER NOT NULL, `lastOpened` INTEGER NOT NULL, PRIMARY KEY(`mediaId`))")
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `document_metadata` (`id` INTEGER NOT NULL, `pageCount` INTEGER NOT NULL, `wordCount` INTEGER NOT NULL, `sheetCount` INTEGER NOT NULL, `slideCount` INTEGER NOT NULL, `fileSize` INTEGER NOT NULL, `lastOpened` INTEGER NOT NULL, `lastViewedPage` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `document_extra_metadata` (`mediaId` INTEGER NOT NULL, `pageCount` INTEGER NOT NULL, `wordCount` INTEGER NOT NULL, `sheetCount` INTEGER NOT NULL, `slideCount` INTEGER NOT NULL, `lastOpened` INTEGER NOT NULL, PRIMARY KEY(`mediaId`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `document_bookmarks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `mediaId` INTEGER NOT NULL, `pageIndex` INTEGER NOT NULL, `label` TEXT NOT NULL, `timestamp` INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `document_search_index` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `mediaId` INTEGER NOT NULL, `pageIndex` INTEGER NOT NULL, `content` TEXT NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_document_search_index_mediaId` ON `document_search_index` (`mediaId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_document_search_index_content` ON `document_search_index` (`content`)")
            }
        }
    }
}

fun MediaItem.toEntity() = MediaEntity(
    id = id,
    path = path,
    contentUri = uri.toString(),
    name = name,
    size = size,
    mediaType = when {
        isVideo -> "video"
        isDocument || isPdf -> "document"
        else -> "image"
    },
    mimeType = mimeType,
    dateAdded = dateAdded,
    dateModified = dateAdded,
    width = width,
    height = height,
    orientation = 0,
    duration = duration,
    bucketId = bucketId,
    bucketName = bucketName,
    isTrashed = false,
    trashTimestamp = null
)

fun MediaEntity.toMediaItem() = MediaItem(
    id = id,
    uri = Uri.parse(contentUri),
    path = path,
    relativePath = File(path).parent ?: "",
    name = name,
    dateAdded = dateAdded,
    size = size,
    isVideo = mediaType.equals("video", ignoreCase = true),
    isPdf = mimeType == "application/pdf",
    isDocument = mediaType.equals("document", ignoreCase = true) ||
            mimeType.startsWith("application/") ||
            mimeType.startsWith("text/"),
    duration = duration,
    width = width,
    height = height,
    mimeType = mimeType,
    bucketId = bucketId,
    bucketName = bucketName,
    isHidden = false
)


enum class DocumentType(val priority: Int) {
    PDF(0), WORD(1), EXCEL(2), SLIDE(3), JSON(4), XML(5), HTML(6), CODE(7), TXT(8), CSV(9), EPUB(10), MARKDOWN(11), ZIP(12), RTF(13), SYSTEM(14), UNKNOWN(15)
}

enum class DocumentSortType {
    DATE, SIZE, NAME, RECENT
}

data class SearchOptions(
    val query: String,
    val caseSensitive: Boolean = false,
    val wholeWord: Boolean = false,
    val isRegex: Boolean = false
)

data class WordRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val fontSize: Int? = 16,
    val colorHex: String? = "#000000",
    val isSubscript: Boolean = false,
    val isSuperscript: Boolean = false,
    val fontFamily: String? = null,
    val isHighlighted: Boolean = false
)

data class PageMargins(
    val top: Float,
    val bottom: Float,
    val left: Float,
    val right: Float,
    val isLandscape: Boolean
)

data class TableRow(val cells: List<TableCell>)

data class TableCell(val blocks: List<WordBlock>)

sealed class WordBlock {
    data class Paragraph(
        val runs: List<WordRun>,
        val alignment: String? = null,
        val headingLevel: Int? = null,
        val isListItem: Boolean = false
    ) : WordBlock()

    data class Table(val rows: List<TableRow>) : WordBlock()

    data class Image(
        val byteArray: ByteArray,
        val extension: String,
        val fileName: String,
        val inline: Boolean = false
    ) : WordBlock() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Image
            if (!byteArray.contentEquals(other.byteArray)) return false
            return true
        }

        override fun hashCode(): Int {
            return byteArray.contentHashCode()
        }
    }

    data class Header(val blocks: List<WordBlock>) : WordBlock()

    data class Footer(val blocks: List<WordBlock>) : WordBlock()
}

data class VirtualSheet(
    val name: String,
    val rows: List<VirtualRow>,
    val rowCount: Int,
    val columnCount: Int,
    val mergedRegions: List<String> = emptyList()
)

data class VirtualRow(
    val rowIndex: Int,
    val cells: List<VirtualCell>,
    val isHidden: Boolean
)

data class VirtualCell(
    val columnIndex: Int,
    val displayValue: String,
    val rawValue: String,
    val isFormula: Boolean,
    val formulaString: String? = null,
    val comment: String? = null,
    val style: CellStyle? = null,
    val isHidden: Boolean = false
)

data class CellStyle(
    val bgColor: String?,
    val textColor: String?,
    val bold: Boolean,
    val italic: Boolean,
    val alignment: String,
    val hasBorders: Boolean
)

sealed class TextReadResult {
    data class Success(val text: String, val syntaxMode: String, val lineOffsets: List<Long>) : TextReadResult()
    data class Preview(val text: String, val syntaxMode: String, val lineOffsets: List<Long>, val totalBytes: Long) : TextReadResult()
    data class Paged(val uri: Uri, val encoding: Charset, val totalBytes: Long) : TextReadResult()
    data class BinaryFile(val confidence: Float) : TextReadResult()
    data class Error(val message: String, val exception: Throwable? = null) : TextReadResult()
}

data class ZipEntryItem(
    val name: String,
    val size: Long,
    val compressedSize: Long,
    val time: Long,
    val crc: Long,
    val isDirectory: Boolean
)

data class EpubChapter(
    val id: String,
    val title: String,
    val contentHtml: String,
    val isCover: Boolean = false
)

sealed class PdfLoadResult {
    data class Success(val pageCount: Int) : PdfLoadResult()
    data class Encrypted(val message: String) : PdfLoadResult()
    data class Error(val message: String, val exception: Throwable? = null) : PdfLoadResult()
}

sealed class EngineResult {
    data class OpenPdf(val uri: Uri, val session: PdfRenderSession) : EngineResult()
    data class OpenWord(val blocks: List<WordBlock>) : EngineResult()
    data class OpenExcel(val sheets: List<VirtualSheet>) : EngineResult()
    data class OpenCsv(val sheet: VirtualSheet) : EngineResult()
    data class OpenSlide(val pages: List<SlidePage>) : EngineResult()
    data class OpenEpub(val chapters: List<EpubChapter>) : EngineResult()
    data class OpenZip(val contents: List<ZipEntryItem>) : EngineResult()
    data class OpenText(val content: String, val type: DocumentType) : EngineResult()
    data class Unsupported(val format: String, val mimeType: String) : EngineResult()
    data class Error(val message: String) : EngineResult()
}

data class SlidePage(
    val width: Float,
    val height: Float,
    val elements: List<SlideElement>
)

sealed class SlideElement {
    abstract val x: Float
    abstract val y: Float
    abstract val width: Float
    abstract val height: Float

    data class Text(
        val text: String,
        override val x: Float,
        override val y: Float,
        override val width: Float,
        override val height: Float,
        val fontSize: Float? = null
    ) : SlideElement()

    data class Image(
        val byteArray: ByteArray,
        val extension: String,
        override val x: Float,
        override val y: Float,
        override val width: Float,
        override val height: Float
    ) : SlideElement() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Image
            if (!byteArray.contentEquals(other.byteArray)) return false
            return true
        }

        override fun hashCode(): Int {
            return byteArray.contentHashCode()
        }
    }

    data class Table(
        override val x: Float,
        override val y: Float,
        override val width: Float,
        override val height: Float,
        val rows: Int,
        val cols: Int
    ) : SlideElement()

    data class Shape(
        val shapeType: String,
        val text: String?,
        override val x: Float,
        override val y: Float,
        override val width: Float,
        override val height: Float
    ) : SlideElement()
}

sealed class CacheEntry {
    data class PdfPage(val bitmap: Bitmap, val timestamp: Long = System.currentTimeMillis()) : CacheEntry()
}

data class DocumentFile(
    val id: Long,
    val name: String,
    val uri: Uri,
    val size: Long,
    val dateModified: Long,
    val mimeType: String?,
    val type: DocumentType
)
data class ReadingState(
    val uri: Uri,
    val page: Int,
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
    val timestamp: Long = System.currentTimeMillis()
)

sealed class DocumentErrorType {
    data object PasswordProtected : DocumentErrorType()
    data object Corrupted : DocumentErrorType()
    data object Unsupported : DocumentErrorType()
    data object Timeout : DocumentErrorType()
    data class Generic(val message: String) : DocumentErrorType()
}

sealed class DocumentUiEvent {
    data class LaunchIntent(val intent: Intent) : DocumentUiEvent()
    data class ShowToast(val message: String) : DocumentUiEvent()
    data class DocumentError(val error: DocumentErrorType) : DocumentUiEvent()
    data class OpenPdf(val uri: Uri, val pageCount: Int) : DocumentUiEvent()
    data class OpenWord(val blocks: List<WordBlock>) : DocumentUiEvent()
    data class OpenExcel(val sheets: List<VirtualSheet>) : DocumentUiEvent()
    data class OpenSlide(val pages: List<SlidePage>) : DocumentUiEvent()
    data class OpenText(val content: String) : DocumentUiEvent()
    data class OpenHtml(val uri: Uri) : DocumentUiEvent()
    data class OpenEpub(val chapters: List<EpubChapter>) : DocumentUiEvent()
    data class OpenZip(val contents: List<ZipEntryItem>) : DocumentUiEvent()
}