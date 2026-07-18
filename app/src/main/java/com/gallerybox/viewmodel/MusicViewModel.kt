@file:SuppressLint("UnsafeOptInUsageError", "StaticFieldLeak")
@file:Suppress("unused")
@file:OptIn(kotlinx.coroutines.FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.gallerybox.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.compose.runtime.*
import androidx.lifecycle.*
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.paging.*
import com.gallerybox.data.*
import com.gallerybox.engine.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.ByteBuffer
import javax.inject.*
import kotlin.math.*

// --- MODELS & ENUMS ---
data class AudioTrack(
    val id: Long,
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val duration: Long,
    val genre: String = "Unknown",
    val path: String = "",
    val year: Int = 0,
    val dateAdded: Long = 0L,
    val composer: String = "Unknown"
)

data class Playlist(
    val id: Long = System.currentTimeMillis(),
    val name: String,
    val tracks: List<AudioTrack> = emptyList()
)

data class PlayerState(
    val track: AudioTrack? = null,
    val isPlaying: Boolean = false,
    val position: Long = 0L
)

enum class Preset(val levels: List<Float>) {
    NORMAL(List(5){0.5f}),
    CLASSICAL(listOf(0.6f,0.6f,0.6f,0.6f,0.6f)),
    DANCE(listOf(0.4f,0.5f,0.6f,0.7f,0.8f)),
    FLAT(List(5){0.5f}),
    FOLK(listOf(0.5f,0.5f,0.5f,0.6f,0.6f)),
    HEAVY_METAL(listOf(0.7f,0.7f,0.7f,0.5f,0.5f)),
    HIP_HOP(listOf(0.7f,0.7f,0.6f,0.6f,0.5f)),
    JAZZ(listOf(0.5f,0.5f,0.6f,0.6f,0.6f)),
    POP(listOf(0.5f,0.6f,0.7f,0.8f,0.7f)),
    ROCK(listOf(0.7f,0.7f,0.6f,0.5f,0.4f))
}

enum class SortOption { TITLE, ARTIST, DATE_ADDED, DURATION }
enum class ChannelMode { STEREO, LEFT_ONLY, RIGHT_ONLY }

// --- REPOSITORY & MAPPERS ---
object MediaStoreMapper {
    fun mapCursorToTracks(cursor: Cursor, uri: Uri, pathColumn: String, expectedSize: Int): List<AudioTrack> {
        val list = ArrayList<AudioTrack>(expectedSize)
        val idC = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val titleC = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val artistC = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val albumC = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
        val albumIdC = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
        val durC = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
        val pathC = cursor.getColumnIndexOrThrow(pathColumn)
        val dateC = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
        val composerC = cursor.getColumnIndex(MediaStore.Audio.Media.COMPOSER)
        val yearC = cursor.getColumnIndex(MediaStore.Audio.Media.YEAR)

        while (cursor.moveToNext()) {
            list.add(AudioTrack(
                id = cursor.getLong(idC),
                uri = ContentUris.withAppendedId(uri, cursor.getLong(idC)).toString(),
                title = cursor.getString(titleC) ?: "Unknown",
                artist = cursor.getString(artistC) ?: "Unknown",
                album = cursor.getString(albumC) ?: "Unknown",
                albumId = cursor.getLong(albumIdC),
                duration = cursor.getLong(durC),
                path = cursor.getString(pathC) ?: "",
                year = if (yearC != -1) cursor.getInt(yearC) else 0,
                dateAdded = cursor.getLong(dateC),
                composer = if (composerC != -1) cursor.getString(composerC) ?: "Unknown" else "Unknown"
            ))
        }
        return list
    }
}

@Singleton
class MusicRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val musicDao: MusicDao
) {
    private val prefs = context.getSharedPreferences("gallerybox_library", Context.MODE_PRIVATE)

    suspend fun getLocalQueue(sortOption: SortOption = SortOption.DATE_ADDED): List<AudioTrack> = withContext(Dispatchers.IO) {
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Audio.Media.RELATIVE_PATH else @Suppress("DEPRECATION") MediaStore.Audio.Media.DATA
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, pathColumn, MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.COMPOSER, MediaStore.Audio.Media.YEAR)
        val sortOrder = when (sortOption) {
            SortOption.TITLE -> "${MediaStore.Audio.Media.TITLE} ASC"
            SortOption.ARTIST -> "${MediaStore.Audio.Media.ARTIST} ASC"
            SortOption.DURATION -> "${MediaStore.Audio.Media.DURATION} DESC"
            SortOption.DATE_ADDED -> "${MediaStore.Audio.Media.DATE_ADDED} DESC"
        }
        val selectionStr = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.SIZE} > 0"

        return@withContext try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bundle = Bundle().apply {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selectionStr)
                    putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
                    putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_EXCLUDE)
                }
                context.contentResolver.query(uri, projection, bundle, null)?.use {
                    MediaStoreMapper.mapCursorToTracks(it, uri, pathColumn, it.count)
                } ?: emptyList()
            } else {
                context.contentResolver.query(uri, projection, selectionStr, null, sortOrder)?.use {
                    MediaStoreMapper.mapCursorToTracks(it, uri, pathColumn, it.count)
                } ?: emptyList()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun getTracksByIds(ids: List<Long>): List<AudioTrack> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyList()
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Audio.Media.RELATIVE_PATH else @Suppress("DEPRECATION") MediaStore.Audio.Media.DATA
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, pathColumn, MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.COMPOSER, MediaStore.Audio.Media.YEAR)

        val selection = "${MediaStore.Audio.Media._ID} IN (${ids.joinToString(",")}) AND ${MediaStore.Audio.Media.SIZE} > 0"
        return@withContext try {
            context.contentResolver.query(uri, projection, selection, null, null)?.use {
                MediaStoreMapper.mapCursorToTracks(it, uri, pathColumn, it.count)
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun saveEqBands(bands: List<Float>) = withContext(Dispatchers.IO) {
        prefs.edit().putString("eq_bands", bands.joinToString(",")).apply()
    }

    fun loadEqBands(): List<Float>? = prefs.getString("eq_bands", null)?.split(",")?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.isNotEmpty() }
}

// --- PAGING ENGINE ---
class AudioPagingSource(private val resolver: ContentResolver, private val query: String, private val sortOption: SortOption) : PagingSource<Int, AudioTrack>() {
    override fun getRefreshKey(state: PagingState<Int, AudioTrack>): Int? = state.anchorPosition?.let {
        state.closestPageToPosition(it)?.prevKey?.plus(state.config.pageSize) ?: state.closestPageToPosition(it)?.nextKey?.minus(state.config.pageSize)
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, AudioTrack> = withContext(Dispatchers.IO) {
        try {
            val pos = params.key ?: 0
            val size = params.loadSize
            val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val pathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Audio.Media.RELATIVE_PATH else @Suppress("DEPRECATION") MediaStore.Audio.Media.DATA
            val proj = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, pathCol, MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.COMPOSER, MediaStore.Audio.Media.YEAR)
            val baseSel = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.SIZE} > 0"
            val finalSel = if (query.isNotBlank()) "$baseSel AND (${MediaStore.Audio.Media.TITLE} LIKE ? OR ${MediaStore.Audio.Media.ARTIST} LIKE ?)" else baseSel
            val selArgs = if (query.isNotBlank()) arrayOf("$query%", "$query%") else null
            val sortOrder = when (sortOption) {
                SortOption.TITLE -> "${MediaStore.Audio.Media.TITLE} ASC"
                SortOption.ARTIST -> "${MediaStore.Audio.Media.ARTIST} ASC"
                SortOption.DURATION -> "${MediaStore.Audio.Media.DURATION} DESC"
                SortOption.DATE_ADDED -> "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            }

            var trackList: List<AudioTrack> = emptyList()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val bundle = Bundle().apply {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, finalSel)
                    selArgs?.let { putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, it) }
                    putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
                    putInt(ContentResolver.QUERY_ARG_LIMIT, size)
                    putInt(ContentResolver.QUERY_ARG_OFFSET, pos)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_EXCLUDE)
                }
                resolver.query(uri, proj, bundle, null)?.use {
                    trackList = MediaStoreMapper.mapCursorToTracks(it, uri, pathCol, size)
                }
            } else {
                resolver.query(uri, proj, finalSel, selArgs, "$sortOrder LIMIT $size OFFSET $pos")?.use {
                    trackList = MediaStoreMapper.mapCursorToTracks(it, uri, pathCol, size)
                }
            }
            LoadResult.Page(trackList, if (pos == 0) null else pos - size, if (trackList.size < size) null else pos + size)
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }
}

// --- EXO-PLAYER EXTENSIONS ---
class DynamicStereoProcessor(initialMode: ChannelMode) : BaseAudioProcessor() {
    private var currentMode = initialMode
    fun setMode(mode: ChannelMode) { if (currentMode != mode) { currentMode = mode; flush() } }

    override fun onConfigure(fmt: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (fmt.encoding != C.ENCODING_PCM_16BIT || fmt.channelCount != 2) AudioProcessor.AudioFormat.NOT_SET else fmt

    override fun queueInput(buffer: ByteBuffer) {
        val rem = buffer.remaining()
        if (rem == 0) return
        val out = replaceOutputBuffer(rem)
        while (buffer.hasRemaining()) {
            val l1 = buffer.get()
            val l2 = buffer.get()
            val r1 = buffer.get()
            val r2 = buffer.get()
            when (currentMode) {
                ChannelMode.STEREO -> { out.put(l1); out.put(l2); out.put(r1); out.put(r2) }
                ChannelMode.LEFT_ONLY -> { out.put(l1); out.put(l2); out.put(0); out.put(0) }
                ChannelMode.RIGHT_ONLY -> { out.put(0); out.put(0); out.put(r1); out.put(r2) }
            }
        }
        buffer.position(buffer.limit()); out.flip()
    }
}

// --- MAIN MUSIC VIEWMODEL ---
@HiltViewModel
class MusicViewModel @Inject constructor(
    private val repository: MusicRepository,
    private val musicDao: MusicDao,
    private val playerManager: PlayerManager,
    application: Application
) : AndroidViewModel(application) {

    private val settingsPrefs = application.getSharedPreferences("gallerybox_music_prefs", Context.MODE_PRIVATE)
    private var observeJob: Job? = null
    private var eqSaveJob: Job? = null

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _currentSortOption = MutableStateFlow(SortOption.DATE_ADDED)
    val currentSortOption = _currentSortOption.asStateFlow()

    private val _allAudioTracks = MutableStateFlow<List<AudioTrack>>(emptyList())
    val allAudioTracks = _allAudioTracks.asStateFlow()

    fun loadAllAudioTracks() {
        viewModelScope.launch {
            _allAudioTracks.value = repository.getLocalQueue(_currentSortOption.value)
        }
    }

    val pagedAudio: Flow<PagingData<AudioTrack>> = combine(_searchQuery.debounce(300), _currentSortOption, ::Pair).flatMapLatest { (q, s) ->
        Pager(PagingConfig(pageSize = 50, enablePlaceholders = false)) {
            AudioPagingSource(getApplication<Application>().contentResolver, q, s)
        }.flow
    }.cachedIn(viewModelScope)

    private val _playCounts = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val playCounts: StateFlow<Map<Long, Int>> = _playCounts.asStateFlow()

    fun setSearchQuery(q: String) { _searchQuery.value = q }
    fun setSortOption(o: SortOption) { _currentSortOption.value = o }

    private val _favoriteIds = MutableStateFlow<Set<Long>>(emptySet())
    val favoriteIds = _favoriteIds.asStateFlow()

    private val _recentHistory = MutableStateFlow<ArrayDeque<Long>>(ArrayDeque())
    val recentHistory = _recentHistory.asStateFlow()

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists = _playlists.asStateFlow()

    private val _originalQueue = MutableStateFlow<List<AudioTrack>>(emptyList())
    private val _queue = MutableStateFlow<List<AudioTrack>>(emptyList())
    val currentQueue = _queue.asStateFlow()
    private val _currentQueueIndex = MutableStateFlow(0)

    private val _currentTrack1 = MutableStateFlow<AudioTrack?>(null)
    val currentTrack = _currentTrack1.asStateFlow()
    private val _isPlaying1 = MutableStateFlow(false)
    val isPlaying = _isPlaying1.asStateFlow()
    private val _currentPosition1 = MutableStateFlow(0L)
    val currentPosition = _currentPosition1.asStateFlow()

    private val _currentTrack2 = MutableStateFlow<AudioTrack?>(null)
    val currentTrack2 = _currentTrack2.asStateFlow()
    private val _isPlaying2 = MutableStateFlow(false)
    val isPlaying2 = _isPlaying2.asStateFlow()
    private val _currentPosition2 = MutableStateFlow(0L)
    val currentPosition2 = _currentPosition2.asStateFlow()

    private val _duration1 = MutableStateFlow(0L)
    val duration1 = _duration1.asStateFlow()
    private val _duration2 = MutableStateFlow(0L)
    val duration2 = _duration2.asStateFlow()

    val isAnimationActive: StateFlow<Boolean> = combine(_isPlaying1, _isPlaying2) { p1, p2 ->
        p1 || p2
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _speedPlayer1 = MutableStateFlow(1f); val speedPlayer1 = _speedPlayer1.asStateFlow()
    private val _pitchPlayer1 = MutableStateFlow(1f); val pitchPlayer1 = _pitchPlayer1.asStateFlow()
    private val _speedPlayer2 = MutableStateFlow(1f); val speedPlayer2 = _speedPlayer2.asStateFlow()
    private val _pitchPlayer2 = MutableStateFlow(1f); val pitchPlayer2 = _pitchPlayer2.asStateFlow()

    private val _eqBands1 = MutableStateFlow(repository.loadEqBands() ?: List(9) { 0.5f })
    val eqBands1 = _eqBands1.asStateFlow()
    private val _eqBands2 = MutableStateFlow(repository.loadEqBands() ?: List(9) { 0.5f })
    val eqBands2 = _eqBands2.asStateFlow()

    private val _currentPreset = MutableStateFlow(Preset.NORMAL)
    val currentPreset = _currentPreset.asStateFlow()
    private val _eqEnabled = MutableStateFlow(true)
    val eqEnabled = _eqEnabled.asStateFlow()

    private val _volume1 = MutableStateFlow(1.0f); val volume1 = _volume1.asStateFlow()
    private val _volume2 = MutableStateFlow(1.0f); val volume2 = _volume2.asStateFlow()

    private val _balance1 = MutableStateFlow(0f); val balance1 = _balance1.asStateFlow()
    private val _balance2 = MutableStateFlow(0f); val balance2 = _balance2.asStateFlow()

    private val _crossfeed = MutableStateFlow(0f); val crossfeed = _crossfeed.asStateFlow()
    private val _bassBoost = MutableStateFlow(0f); val bassBoost = _bassBoost.asStateFlow()
    private val _virtualizer = MutableStateFlow(0f); val virtualizer = _virtualizer.asStateFlow()
    private val _reverbPreset = MutableStateFlow(0.toShort()); val reverbPreset = _reverbPreset.asStateFlow()

    private val _isGaplessEnabled = MutableStateFlow(settingsPrefs.getBoolean("gapless_playback", true))
    val isGaplessEnabled = _isGaplessEnabled.asStateFlow()

    private val _isCrossfadeEnabled = MutableStateFlow(settingsPrefs.getBoolean("crossfade_enabled", false))
    val isCrossfadeEnabled = _isCrossfadeEnabled.asStateFlow()

    private val _isPauseOnUnplugEnabled = MutableStateFlow(settingsPrefs.getBoolean("pause_on_unplug", true))
    val isPauseOnUnplugEnabled = _isPauseOnUnplugEnabled.asStateFlow()

    var isShuffleEnabled by mutableStateOf(false)
    var repeatMode by mutableIntStateOf(Player.REPEAT_MODE_OFF)
    var isDuoModeActive by mutableStateOf(false)

    private var sleepTimerJob: Job? = null
    var sleepTimeRemaining by mutableLongStateOf(0L)

    private val _outputDevice = MutableStateFlow("Speaker")
    val outputDevice = _outputDevice.asStateFlow()

    private val outputDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(d: Array<out AudioDeviceInfo>?) = updateOutput()
        override fun onAudioDevicesRemoved(d: Array<out AudioDeviceInfo>?) = updateOutput()
    }

    init {
        observePlayerManager()
        loadRoomData()
        _eqBands1.value.forEachIndexed { i, v -> playerManager.updateEq(i, v, false) }
        _eqBands2.value.forEachIndexed { i, v -> playerManager.updateEq(i, v, true) }
    }

    private fun loadRoomData() {
        viewModelScope.launch(Dispatchers.IO) {
            val stats = musicDao.getAllStats()
            _favoriteIds.value = stats.filter { it.isFavorite }.map { it.trackId }.toSet()

            val deque = ArrayDeque<Long>()
            stats.sortedByDescending { it.lastPlayed }.take(50).forEach { deque.add(it.trackId) }
            _recentHistory.value = deque

            musicDao.getPlaylistsWithTracks().collectLatest { entities ->
                // Optimized Playlist Resolution: Batch track fetch to reduce DB lookups
                val uniqueTrackIds = entities.flatMap { it.trackRefs.map { ref -> ref.trackId } }.distinct()
                val allTracksMap = repository.getTracksByIds(uniqueTrackIds).associateBy { it.id }

                val resolvedPlaylists = entities.map { entity ->
                    val tracks = entity.trackRefs.mapNotNull { allTracksMap[it.trackId] }
                    Playlist(entity.playlist.id, entity.playlist.name, tracks)
                }
                _playlists.value = resolvedPlaylists
            }
        }
    }

    private fun observePlayerManager() {
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            launch { playerManager.currentTrack.collect { _currentTrack1.value = it } }
            launch { playerManager.currentTrack2.collect { _currentTrack2.value = it } }
            launch { playerManager.isPlaying.collect { _isPlaying1.value = it } }
            launch { playerManager.isPlaying2.collect { _isPlaying2.value = it } }

            launch {
                while (isActive) {
                    val p1Playing = _isPlaying1.value
                    val p2Playing = _isPlaying2.value

                    // Optimized Polling: Only poll position rapidly if actively playing
                    if (p1Playing || p2Playing) {
                        _currentPosition1.value = playerManager.player1Position
                        _currentPosition2.value = playerManager.player2Position

                        val d1 = playerManager.player1Duration
                        if (d1 > 0 && d1 != C.TIME_UNSET) _duration1.value = d1

                        val d2 = playerManager.player2Duration
                        if (d2 > 0 && d2 != C.TIME_UNSET) _duration2.value = d2

                        delay(250) // Frequent UI update tick while playing
                    } else {
                        delay(1000) // Sleep/Background saving CPU overhead
                    }
                }
            }
        }
    }

    fun startOutputMonitoring() {
        val am = getApplication<Application>().getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.registerAudioDeviceCallback(outputDeviceCallback, null)
        updateOutput()
    }

    fun stopOutputMonitoring() {
        val am = getApplication<Application>().getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.unregisterAudioDeviceCallback(outputDeviceCallback)
    }

    private fun updateOutput() {
        val am = getApplication<Application>().getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val d = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        _outputDevice.value = when {
            d.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP } -> "Bluetooth"
            d.any { it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET } -> "Headset"
            else -> "Speaker"
        }
    }

    fun playTrack(track: AudioTrack, secondary: Boolean = false) {
        if (!secondary) {
            setDuoMode(false)
            updateStats(track.id)

            _currentPosition1.value = 0L
            _duration1.value = track.duration

            viewModelScope.launch {
                // Lazy initialize complete queue for shuffle functionality to prevent massive memory footprints at startup
                val full = if (_allAudioTracks.value.isNotEmpty()) _allAudioTracks.value else {
                    val loaded = repository.getLocalQueue(_currentSortOption.value)
                    _allAudioTracks.value = loaded
                    loaded
                }

                _originalQueue.value = full

                val q = if (isShuffleEnabled) {
                    listOf(track) + (full - track).shuffled()
                } else {
                    val idx = full.indexOfFirst { it.id == track.id }
                    if (idx != -1) full.drop(idx) + full.take(idx) else listOf(track)
                }

                _queue.value = q
                _currentQueueIndex.value = 0
                playerManager.setPlaylist(q, 0)
            }
        } else {
            _currentPosition2.value = 0L
            _duration2.value = track.duration
            playerManager.playTrack(track, true)
        }
    }

    fun playNext(track: AudioTrack) {
        val currentList = _queue.value.toMutableList()
        if (currentList.none { it.id == track.id }) {
            val insertIndex = if (_currentQueueIndex.value + 1 <= currentList.size) _currentQueueIndex.value + 1 else currentList.size
            currentList.add(insertIndex, track)
            _queue.value = currentList
            playerManager.setPlaylist(currentList, _currentQueueIndex.value.coerceAtLeast(0))
        }
    }

    fun addToQueue(track: AudioTrack) {
        val currentList = _queue.value.toMutableList()
        if (currentList.none { it.id == track.id }) {
            currentList.add(track)
            _queue.value = currentList
            playerManager.setPlaylist(currentList, _currentQueueIndex.value)
        }
    }

    fun removeFromQueue(track: AudioTrack) {
        val currentList = _queue.value.toMutableList()
        val indexToRemove = currentList.indexOfFirst { it.id == track.id }
        if (indexToRemove != -1) {
            currentList.removeAt(indexToRemove)
            _queue.value = currentList
            if (indexToRemove < _currentQueueIndex.value) _currentQueueIndex.value -= 1
            playerManager.setPlaylist(currentList, _currentQueueIndex.value)
        }
    }

    fun playBothSynced() {
        play(false)
        play(true)
    }

    fun pauseBothSynced() {
        pause(false)
        pause(true)
    }

    fun playQueue(tracks: List<AudioTrack>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        setDuoMode(false)
        _originalQueue.value = tracks
        val q = if (isShuffleEnabled) {
            listOf(tracks[startIndex]) + (tracks - tracks[startIndex]).shuffled()
        } else if (startIndex > 0) {
            tracks.drop(startIndex) + tracks.take(startIndex)
        } else {
            tracks
        }
        _queue.value = q
        _currentQueueIndex.value = 0
        q.firstOrNull()?.let { updateStats(it.id) }
        playerManager.setPlaylist(q, 0)
    }

    fun playDuoTrack(track: AudioTrack, isPlayer2: Boolean) {
        setDuoMode(true)
        if (isPlayer2) {
            _currentTrack2.value = track
            _currentPosition2.value = 0L
            _duration2.value = track.duration
        } else {
            _currentTrack1.value = track
            _currentPosition1.value = 0L
            _duration1.value = track.duration
        }
        playerManager.playTrack(track, isPlayer2)
        updateVolume(1f, isPlayer2)
    }

    fun setPlaying(play: Boolean, secondary: Boolean = false) {
        if (play) playerManager.play(secondary) else playerManager.pause(secondary)
    }

    fun play(secondary: Boolean = false) = setPlaying(true, secondary)
    fun pause(secondary: Boolean = false) = setPlaying(false, secondary)

    fun togglePlayPause(secondary: Boolean = false) {
        playerManager.togglePlayPause(secondary)
    }

    fun skipNext() {
        val qSize = _queue.value.size
        if (qSize > 0) {
            playerManager.seekToNext()
            _currentQueueIndex.value = (_currentQueueIndex.value + 1) % qSize
        }
    }

    fun skipPrevious() {
        val qSize = _queue.value.size
        if (qSize > 0) {
            playerManager.seekToPrevious()
            _currentQueueIndex.value = (_currentQueueIndex.value - 1 + qSize) % qSize
        }
    }

    fun toggleShuffle() {
        isShuffleEnabled = !isShuffleEnabled
        playerManager.setShuffleMode(isShuffleEnabled)
        if (!isShuffleEnabled && _originalQueue.value.isNotEmpty()) {
            _queue.value = _originalQueue.value
        }
    }

    fun toggleRepeat() {
        repeatMode = when (repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        playerManager.setRepeatMode(repeatMode)
    }

    fun seekToFraction(fraction: Float, secondary: Boolean = false) {
        playerManager.seekToFraction(fraction, secondary)
    }

    fun seekTo(ms: Long, secondary: Boolean = false) {
        playerManager.seekTo(ms, secondary)
    }

    fun seekDynamic(forward: Boolean, isPlayer2: Boolean = false) {
        val currentPos = if (isPlayer2) _currentPosition2.value else _currentPosition1.value
        val duration = if (isPlayer2) _duration2.value else _duration1.value
        val seekAmount = max(10000L, (duration * 0.01).toLong())
        val targetPos = if (forward) currentPos + seekAmount else currentPos - seekAmount
        playerManager.seekTo(targetPos.coerceIn(0L, duration), isPlayer2)
    }

    fun crossfadePlayers() = playerManager.triggerCrossfade()

    fun setDuoMode(enabled: Boolean) {
        isDuoModeActive = enabled
        playerManager.setDuoMode(enabled)
    }

    fun toggleGaplessPlayback(enabled: Boolean) {
        _isGaplessEnabled.value = enabled
        settingsPrefs.edit().putBoolean("gapless_playback", enabled).apply()
        playerManager.setGaplessPlayback(enabled)
    }

    fun toggleCrossfade(enabled: Boolean) {
        _isCrossfadeEnabled.value = enabled
        settingsPrefs.edit().putBoolean("crossfade_enabled", enabled).apply()
        val duration = _duration1.value
        val fadeDurationMs = if (enabled) min(5000L, (duration * 0.03).toLong()).toInt() else 0
        playerManager.setCrossfadeDuration(fadeDurationMs)
    }

    fun togglePauseOnUnplug(enabled: Boolean) {
        _isPauseOnUnplugEnabled.value = enabled
        settingsPrefs.edit().putBoolean("pause_on_unplug", enabled).apply()
        playerManager.setPauseOnUnplug(enabled)
    }

    fun resetAudioEffects() {
        _speedPlayer1.value = 1f
        _pitchPlayer1.value = 1f
        _speedPlayer2.value = 1f
        _pitchPlayer2.value = 1f
        playerManager.resetPlaybackParameters()
    }

    fun setPlayerSpeed(isPlayer2: Boolean, speed: Float) {
        if (isPlayer2) _speedPlayer2.value = speed else _speedPlayer1.value = speed
        playerManager.setSpeed(speed, isPlayer2)
    }

    fun setPlayerPitch(isPlayer2: Boolean, pitch: Float) {
        if (isPlayer2) _pitchPlayer2.value = pitch else _pitchPlayer1.value = pitch
        playerManager.setPitch(pitch, isPlayer2)
    }

    fun setPlaybackEffect(isPlayer2: Boolean, effectName: String) {
        val params = when (effectName) {
            "Nightcore" -> Pair(1.25f, 1.25f)
            "Slowed" -> Pair(0.85f, 0.85f)
            "Vaporwave" -> Pair(0.8f, 0.6f)
            else -> Pair(1.0f, 1.0f)
        }
        setPlayerSpeed(isPlayer2, params.first)
        setPlayerPitch(isPlayer2, params.second)
    }

    fun setPreampGain(millibels: Int) = playerManager.setPreampGain(millibels)

    fun toggleEq(enabled: Boolean) {
        _eqEnabled.value = enabled
        playerManager.setEqEnabled(enabled)
    }

    fun updateEq(index: Int, value: Float, isPlayer2: Boolean = false) {
        if (isPlayer2) {
            val list = _eqBands2.value.toMutableList()
            if (index in list.indices) {
                list[index] = value
                _eqBands2.value = list
                playerManager.updateEq(index, value, true)
            }
        } else {
            val list = _eqBands1.value.toMutableList()
            if (index in list.indices) {
                list[index] = value
                _eqBands1.value = list
                eqSaveJob?.cancel()
                eqSaveJob = viewModelScope.launch {
                    delay(300)
                    repository.saveEqBands(list)
                }
                playerManager.updateEq(index, value, false)
                if (_currentPreset.value != Preset.NORMAL) _currentPreset.value = Preset.NORMAL
            }
        }
    }

    fun applyPreset(p: Preset) {
        _currentPreset.value = p
        val tSize = _eqBands1.value.size
        val safeLevels = if (p.levels.size == tSize) p.levels else resampleLevels(p.levels, tSize)
        _eqBands1.value = safeLevels
        viewModelScope.launch { repository.saveEqBands(safeLevels) }
        safeLevels.forEachIndexed { i, level -> playerManager.updateEq(i, level, false) }
    }

    private fun resampleLevels(input: List<Float>, targetSize: Int): List<Float> {
        if (input.isEmpty()) return List(targetSize) { 0.5f }
        if (targetSize == 1) return listOf(input.first())
        return List(targetSize) { i ->
            val pos = i * (input.size - 1).toFloat() / (targetSize - 1)
            val left = pos.toInt()
            val right = minOf(left + 1, input.lastIndex)
            val frac = pos - left
            input[left] * (1 - frac) + input[right] * frac
        }
    }

    fun updateVolume(v: Float, isPlayer2: Boolean = false) {
        if (isPlayer2) _volume2.value = v else _volume1.value = v
        val v1 = _volume1.value
        val v2 = _volume2.value
        val limiterScale = if (isDuoModeActive) 1f / max(1f, v1 + v2) else 1f
        val logV1 = (ln(1.0 + 9.0 * v1) / ln(10.0)).toFloat() * limiterScale
        val logV2 = (ln(1.0 + 9.0 * v2) / ln(10.0)).toFloat() * limiterScale
        playerManager.setVolume(logV1, false)
        if (isDuoModeActive) playerManager.setVolume(logV2, true)
    }

    fun updateBalance(balance: Float, isPlayer2: Boolean = false) {
        if (isPlayer2) _balance2.value = balance else _balance1.value = balance
        playerManager.setStereoBalance(balance, isPlayer2)
    }

    fun updateCrossfeed(amount: Float) {
        _crossfeed.value = amount
        playerManager.setCrossfeed(amount)
    }

    fun updateBass(v: Float) {
        _bassBoost.value = v
        playerManager.updateBass(v)
    }

    fun updateVirtualizer(v: Float) {
        _virtualizer.value = v
        playerManager.updateVirtualizer(v)
    }

    fun setReverb(p: Short) {
        _reverbPreset.value = p
        playerManager.setReverb(p)
    }

    fun createPlaylist(name: String) {
        val cleanName = name.trim()
        if(cleanName.isNotBlank()) viewModelScope.launch(Dispatchers.IO) {
            musicDao.insertPlaylist(PlaylistEntity(name = cleanName))
        }
    }

    fun deletePlaylist(playlist: Playlist) {
        viewModelScope.launch(Dispatchers.IO) {
            musicDao.deletePlaylist(PlaylistEntity(playlist.id, playlist.name))
        }
    }

    fun renamePlaylist(playlist: Playlist, newName: String) {
        val cleanName = newName.trim()
        if(cleanName.isNotBlank()) viewModelScope.launch(Dispatchers.IO) {
            musicDao.renamePlaylist(playlist.id, cleanName)
        }
    }

    fun addSongToPlaylist(playlist: Playlist, track: AudioTrack) {
        if (playlist.tracks.any { it.id == track.id }) return
        viewModelScope.launch(Dispatchers.IO) {
            musicDao.addTrackToPlaylist(PlaylistTrackCrossRef(playlist.id, track.id))
        }
    }

    fun removeSongFromPlaylist(playlist: Playlist, track: AudioTrack) {
        viewModelScope.launch(Dispatchers.IO) {
            musicDao.removeTrackFromPlaylist(PlaylistTrackCrossRef(playlist.id, track.id))
        }
    }

    fun startSleepTimer(minutes: Int) {
        cancelSleepTimer()
        val ms = minutes * 60000L
        val end = System.currentTimeMillis() + ms
        sleepTimeRemaining = ms
        sleepTimerJob = viewModelScope.launch {
            while (System.currentTimeMillis() < end) {
                sleepTimeRemaining = end - System.currentTimeMillis()
                delay(minOf(5000L, sleepTimeRemaining.coerceAtLeast(100L)))
            }
            playerManager.stopAll()
            sleepTimeRemaining = 0
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        sleepTimeRemaining = 0
    }

    private fun updateStats(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = musicDao.getStat(id)
            val newStat = existing?.copy(playCount = existing.playCount + 1, lastPlayed = System.currentTimeMillis())
                ?: TrackStatEntity(id, 1, System.currentTimeMillis(), false)
            musicDao.insertStat(newStat)
            val h = _recentHistory.value
            h.remove(id)
            h.addFirst(id)
            if (h.size > 50) h.removeLast()
            _recentHistory.value = h
        }
    }

    fun toggleFavorite(ids: List<Long>) {
        val currentFavs = _favoriteIds.value.toMutableSet()
        ids.forEach { id ->
            if (currentFavs.contains(id)) currentFavs.remove(id) else currentFavs.add(id)
        }
        _favoriteIds.value = currentFavs

        viewModelScope.launch(Dispatchers.IO) {
            val currentStats = musicDao.getAllStats().associateBy { it.trackId }
            val statsToInsert = ids.map { id ->
                val existing = currentStats[id]
                val newFavState = currentFavs.contains(id)
                existing?.copy(isFavorite = newFavState) ?: TrackStatEntity(id, 0, 0L, newFavState)
            }
            musicDao.insertStats(statsToInsert)
        }
    }

    override fun onCleared() {
        super.onCleared()
        observeJob?.cancel()
        eqSaveJob?.cancel()
        stopOutputMonitoring()
    }
}