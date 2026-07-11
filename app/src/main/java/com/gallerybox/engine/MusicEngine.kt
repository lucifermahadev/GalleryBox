@file:OptIn(androidx.media3.common.util.UnstableApi::class)
@file:Suppress("unused", "DEPRECATION")

package com.gallerybox.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.PresetReverb
import android.media.audiofx.Virtualizer
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.ui.PlayerNotificationManager
import coil.ImageLoader
import coil.request.ImageRequest
import com.gallerybox.MainActivity
import com.gallerybox.ui.screens.setting.SettingsRepository
import com.gallerybox.viewmodel.AudioTrack
import com.gallerybox.viewmodel.ChannelMode
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

// --- SHARED MODELS ---
enum class PlaybackMode { NONE, LOCAL_MUSIC, FM_RADIO }
private data class PlaybackSettings(val crossfade: Int, val gapless: Boolean, val pause: Boolean, val resume: Boolean)

// ==========================================
// 1. FM RADIO ENGINE
// ==========================================
@Singleton
class FmRadioEngine @Inject constructor(@ApplicationContext private val context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val prefs = context.getSharedPreferences("gallerybox_fm_radio", Context.MODE_PRIVATE)
    private var audioFocusRequest: AudioFocusRequest? = null

    var pauseOnUnplug = true; var resumeOnPlug = false
    private var wasPausedByUnplug = false; private var wasPausedByFocus = false; private var lastScanTime = 0L
    private val MIN_FREQ_INT = 875; private val MAX_FREQ_INT = 1080; private val STEP_INT = 1

    private val _isPlaying = MutableStateFlow(false); val isPlaying = _isPlaying.asStateFlow()
    private var currentFreqInt = (prefs.getFloat("freq", 98.0f) * 10).roundToInt()
    private val _frequency = MutableStateFlow(currentFreqInt / 10f); val frequency = _frequency.asStateFlow()
    private val _isHeadsetConnected = MutableStateFlow(false); val isHeadsetConnected = _isHeadsetConnected.asStateFlow()
    private val favoritesSet = loadFavorites().toMutableSet()
    private val _favorites = MutableStateFlow(favoritesSet.map { it / 10f }.sorted()); val favoriteStations = _favorites.asStateFlow()
    private val _signalStrength = MutableStateFlow(0); val signalStrength = _signalStrength.asStateFlow()

    private var isCallbackRegistered = false

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> stop()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> if (_isPlaying.value) { stop(); wasPausedByFocus = true }
            AudioManager.AUDIOFOCUS_GAIN -> if (wasPausedByFocus) { start(); wasPausedByFocus = false }
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) { updateHeadsetState(); if (resumeOnPlug && wasPausedByUnplug && _isHeadsetConnected.value) { start(); wasPausedByUnplug = false } }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) { updateHeadsetState(); if (!_isHeadsetConnected.value) { if (pauseOnUnplug && _isPlaying.value) { stop(); wasPausedByUnplug = true }; _signalStrength.value = 0 } }
    }

    init { updateHeadsetState() }

    private fun registerCallbacks() { if (!isCallbackRegistered) { audioManager.registerAudioDeviceCallback(deviceCallback, null); isCallbackRegistered = true } }
    private fun unregisterCallbacks() { if (isCallbackRegistered) { audioManager.unregisterAudioDeviceCallback(deviceCallback); isCallbackRegistered = false } }

    fun start(freq: Float = _frequency.value): Boolean {
        if (!isHeadsetAvailable() || _isPlaying.value) return false
        registerCallbacks()
        requestAudioFocus(); tune(freq); _isPlaying.value = true; return true
    }

    fun stop() {
        abandonAudioFocus()
        unregisterCallbacks()
        _isPlaying.value = false
        _signalStrength.value = 0
    }

    fun tune(freq: Float) = tuneInt((freq * 10f).roundToInt())
    private fun tuneInt(freqInt: Int) { currentFreqInt = freqInt.coerceIn(MIN_FREQ_INT, MAX_FREQ_INT); val safeFreq = currentFreqInt / 10f; _frequency.value = safeFreq; prefs.edit().putFloat("freq", safeFreq).apply(); if (isHeadsetAvailable()) _signalStrength.value = (30..95).random(Random(currentFreqInt)) }
    fun seekUp() = tuneInt(currentFreqInt + STEP_INT)
    fun seekDown() = tuneInt(currentFreqInt - STEP_INT)

    private val cores = Runtime.getRuntime().availableProcessors()
    private val adaptiveScanDelay = max(150, 1000 / cores).toLong()

    fun scanNext() { if (System.currentTimeMillis() - lastScanTime < adaptiveScanDelay) return; lastScanTime = System.currentTimeMillis(); tuneInt((currentFreqInt + 12).coerceAtMost(MAX_FREQ_INT)) }
    fun scanPrevious() { if (System.currentTimeMillis() - lastScanTime < adaptiveScanDelay) return; lastScanTime = System.currentTimeMillis(); tuneInt((currentFreqInt - 12).coerceAtLeast(MIN_FREQ_INT)) }

    fun setSpeakerEnabled(enabled: Boolean) { audioManager.isSpeakerphoneOn = enabled }
    fun setMute(mute: Boolean) = audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)

    fun addFavorite(freq: Float) { if (favoritesSet.add((freq * 10f).roundToInt())) updateFavoritesFlowAndPrefs() }
    fun removeFavorite(freq: Float) { if (favoritesSet.remove((freq * 10f).roundToInt())) updateFavoritesFlowAndPrefs() }
    private fun updateFavoritesFlowAndPrefs() { _favorites.value = favoritesSet.map { it / 10f }.sorted(); prefs.edit().putString("favorites", favoritesSet.joinToString(",")).apply() }
    private fun loadFavorites(): Set<Int> = prefs.getString("favorites", "")?.let { if (it.isEmpty()) emptySet() else it.split(",").mapNotNull { s -> s.toIntOrNull() }.toSet() } ?: emptySet()

    private fun isHeadsetAvailable() = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
    private fun updateHeadsetState() { _isHeadsetConnected.value = isHeadsetAvailable() }
    private fun requestAudioFocus() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setOnAudioFocusChangeListener(focusChangeListener).build(); audioManager.requestAudioFocus(audioFocusRequest!!) } else { @Suppress("DEPRECATION") audioManager.requestAudioFocus(focusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) }
    private fun abandonAudioFocus() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) } else @Suppress("DEPRECATION") audioManager.abandonAudioFocus(focusChangeListener)

    fun release() { stop() }
}

// ==========================================
// 2. PLAYER MANAGER (SHARED SINGLETON)
// ==========================================
class DynamicStereoProcessor(initialMode: ChannelMode) : BaseAudioProcessor() {

    private var currentMode = initialMode
    private var leftGain = 1f
    private var rightGain = 1f
    private var crossfeed = 0f

    fun setMode(mode: ChannelMode) {
        if (currentMode != mode) {
            currentMode = mode
            flush()
        }
    }

    fun setBalance(left: Float, right: Float) {
        this.leftGain = left
        this.rightGain = right
    }

    fun setCrossfeed(amount: Float) {
        this.crossfeed = amount.coerceIn(0f, 1f)
    }

    override fun onConfigure(fmt: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (fmt.encoding != C.ENCODING_PCM_16BIT || fmt.channelCount != 2) AudioProcessor.AudioFormat.NOT_SET else fmt

    override fun queueInput(buffer: ByteBuffer) {
        val rem = buffer.remaining()
        if (rem == 0) return

        val out = replaceOutputBuffer(rem)
        val cfScale = 1f / (1f + crossfeed)

        while (buffer.hasRemaining()) {
            var lShort = buffer.getShort()
            var rShort = buffer.getShort()

            when (currentMode) {
                ChannelMode.LEFT_ONLY -> rShort = 0
                ChannelMode.RIGHT_ONLY -> lShort = 0
                ChannelMode.STEREO -> {}
            }

            var lFloat = lShort.toFloat()
            var rFloat = rShort.toFloat()

            if (crossfeed > 0f) {
                val lTemp = lFloat
                val rTemp = rFloat
                lFloat = (lTemp + rTemp * crossfeed) * cfScale
                rFloat = (rTemp + lTemp * crossfeed) * cfScale
            }

            lFloat *= leftGain
            rFloat *= rightGain

            val outL = lFloat.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            val outR = rFloat.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

            out.putShort(outL)
            out.putShort(outR)
        }

        buffer.position(buffer.limit())
        out.flip()
    }
}

@UnstableApi
@Singleton
class PlayerManager @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs = context.getSharedPreferences("gallerybox_music_prefs", Context.MODE_PRIVATE)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var crossfadeJob: Job? = null; private var sleepTimerJob: Job? = null

    var crossfadeDurationMs = 2000
    var resumeOnPlug = prefs.getBoolean("resume_on_plug", false); var pauseOnUnplug = prefs.getBoolean("pause_on_unplug", true)
    var gaplessPlayback = prefs.getBoolean("gapless_playback", true); private var wasPausedByUnplug = false

    private val stereoProcessor1 = DynamicStereoProcessor(ChannelMode.STEREO)
    private val stereoProcessor2 = DynamicStereoProcessor(ChannelMode.STEREO)
    val player = createExoPlayer(stereoProcessor1); val player2 = createExoPlayer(stereoProcessor2)

    val player1Position: Long get() = player.currentPosition
    val player2Position: Long get() = player2.currentPosition
    val player1Duration: Long get() = player.duration
    val player2Duration: Long get() = player2.duration

    private var equalizer: Equalizer? = null; private var bassBoost: BassBoost? = null; private var virtualizer: Virtualizer? = null
    private var presetReverb: PresetReverb? = null; private var loudnessEnhancer: LoudnessEnhancer? = null

    private val _isPlaying = MutableStateFlow(false); val isPlaying = _isPlaying.asStateFlow()
    private val _currentTrack = MutableStateFlow<AudioTrack?>(null); val currentTrack = _currentTrack.asStateFlow()
    private val _queue = MutableStateFlow<List<AudioTrack>>(emptyList()); val queue = _queue.asStateFlow()
    private var queueMap = emptyMap<String, AudioTrack>()

    private val _audioSessionId = MutableStateFlow(C.AUDIO_SESSION_ID_UNSET); val audioSessionId = _audioSessionId.asStateFlow()
    private val _isPlaying2 = MutableStateFlow(false); val isPlaying2 = _isPlaying2.asStateFlow()
    private val _currentTrack2 = MutableStateFlow<AudioTrack?>(null); val currentTrack2 = _currentTrack2.asStateFlow()

    private val _volume1 = MutableStateFlow(1f); val volume1 = _volume1.asStateFlow()
    private val _volume2 = MutableStateFlow(1f); val volume2 = _volume2.asStateFlow()
    private val _balance1 = MutableStateFlow(0f); val balance1 = _balance1.asStateFlow()
    private val _balance2 = MutableStateFlow(0f); val balance2 = _balance2.asStateFlow()
    private val _crossfeed = MutableStateFlow(0f); val crossfeed = _crossfeed.asStateFlow()
    private val _softLimiterEnabled = MutableStateFlow(true); val softLimiterEnabled = _softLimiterEnabled.asStateFlow()

    private var isDuoModeActive = false
    private var isCallbackRegistered = false

    private val noisyAudioReceiver = object : BroadcastReceiver() { override fun onReceive(c: Context?, i: Intent?) { if (i?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && pauseOnUnplug) { wasPausedByUnplug = true; player.pause(); player2.pause() } } }
    private val deviceCallback = object : AudioDeviceCallback() { override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) { if (resumeOnPlug && wasPausedByUnplug && added?.any { it.type in listOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) } == true) { play(); wasPausedByUnplug = false } } }

    init {
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {}
            override fun onIsPlayingChanged(p: Boolean) { _isPlaying.value = p }
            override fun onMediaItemTransition(m: MediaItem?, r: Int) {
                m?.mediaId?.let { id ->
                    queueMap[id]?.let { track ->
                        _currentTrack.value = track
                    }
                }
            }
            override fun onAudioSessionIdChanged(id: Int) {
                if (id != C.AUDIO_SESSION_ID_UNSET) {
                    _audioSessionId.value = id
                    initAudioFx(id)
                }
            }
            override fun onPlayWhenReadyChanged(p: Boolean, r: Int) {
                if (p && r == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) wasPausedByUnplug = false
            }
        })

        player2.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {}
            override fun onIsPlayingChanged(p: Boolean) { _isPlaying2.value = p }
            override fun onMediaItemTransition(m: MediaItem?, r: Int) {
                m?.mediaId?.let { id ->
                    queueMap[id]?.let { track ->
                        _currentTrack2.value = track
                    }
                }
            }
        })
    }

    private fun createExoPlayer(processor: DynamicStereoProcessor): ExoPlayer = ExoPlayer.Builder(context, object : DefaultRenderersFactory(context) { override fun buildAudioSink(c: Context, f: Boolean, p: Boolean) = DefaultAudioSink.Builder(c).setAudioProcessors(arrayOf(processor)).setEnableFloatOutput(true).build() }).setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false).setWakeMode(C.WAKE_MODE_NONE).setHandleAudioBecomingNoisy(false).build()

    private fun registerCallbacks() {
        if (!isCallbackRegistered) {
            audioManager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
            context.registerReceiver(noisyAudioReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            isCallbackRegistered = true
        }
    }

    private fun unregisterCallbacks() {
        if (isCallbackRegistered) {
            try { audioManager.unregisterAudioDeviceCallback(deviceCallback) } catch (e: Exception) {}
            try { context.unregisterReceiver(noisyAudioReceiver) } catch (e: Exception) {}
            isCallbackRegistered = false
        }
    }

    private fun initAudioFx(sessionId: Int) {
        equalizer?.release(); bassBoost?.release(); virtualizer?.release(); presetReverb?.release(); loudnessEnhancer?.release()
        try { equalizer = Equalizer(0, sessionId).apply { enabled = true } } catch (e: Exception) {}
        try { bassBoost = BassBoost(0, sessionId).apply { enabled = true } } catch (e: Exception) {}
        try { virtualizer = Virtualizer(0, sessionId).apply { enabled = true } } catch (e: Exception) {}
        try { presetReverb = PresetReverb(0, sessionId).apply { enabled = true } } catch (e: Exception) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) try { loudnessEnhancer = LoudnessEnhancer(sessionId).apply { enabled = true } } catch (e: Exception) {}
    }

    // Controls
    fun play(secondary: Boolean = false) { registerCallbacks(); if (secondary) player2.play() else player.play() }
    fun pause(secondary: Boolean = false) { if (secondary) player2.pause() else player.pause() }
    fun togglePlayPause(secondary: Boolean = false) = if (secondary) { if (player2.isPlaying) player2.pause() else { registerCallbacks(); player2.play() } } else { if (player.isPlaying) player.pause() else { registerCallbacks(); player.play() } }
    fun seekToNext() = if (player.hasNextMediaItem()) player.seekToNext() else Unit
    fun seekToPrevious() = if (player.hasPreviousMediaItem()) player.seekToPrevious() else player.seekTo(0)
    fun seekTo(ms: Long, secondary: Boolean = false) = if (secondary) player2.seekTo(ms) else player.seekTo(ms)
    fun seekToFraction(f: Float, secondary: Boolean = false) { val p = if (secondary) player2 else player; if (p.duration > 0) p.seekTo((p.duration * f).toLong()) }
    fun setRepeatMode(m: Int) { player.repeatMode = m }
    fun setShuffleMode(en: Boolean) { player.shuffleModeEnabled = en }

    fun setVolume(v: Float, secondary: Boolean = false) {
        if (secondary) _volume2.value = v else _volume1.value = v
        applyVolumes()
    }

    fun setSoftLimiter(enabled: Boolean) {
        _softLimiterEnabled.value = enabled
        applyVolumes()
    }

    private fun applyVolumes() {
        val v1 = _volume1.value
        val v2 = _volume2.value
        val limiterScale = if (_softLimiterEnabled.value && isDuoModeActive) 1f / max(1f, v1 + v2) else 1f
        val logV1 = (ln(1.0 + 9.0 * v1) / ln(10.0)).toFloat() * limiterScale
        val logV2 = (ln(1.0 + 9.0 * v2) / ln(10.0)).toFloat() * limiterScale
        player.volume = logV1
        player2.volume = logV2
    }

    fun setStereoBalance(balance: Float, secondary: Boolean = false) {
        if (secondary) _balance2.value = balance else _balance1.value = balance
        val left = if (balance > 0f) 1f - balance else 1f
        val right = if (balance < 0f) 1f + balance else 1f
        if (secondary) {
            stereoProcessor2.setBalance(left, right)
        } else {
            stereoProcessor1.setBalance(left, right)
        }
    }

    fun setCrossfeed(amount: Float) {
        _crossfeed.value = amount
        stereoProcessor1.setCrossfeed(amount)
        stereoProcessor2.setCrossfeed(amount)
    }

    @JvmName("updateGaplessPlayback")
    fun setGaplessPlayback(enabled: Boolean) { gaplessPlayback = enabled }

    fun setCrossfadeDuration(durationMs: Int) { crossfadeDurationMs = durationMs }

    @JvmName("updatePauseOnUnplug")
    fun setPauseOnUnplug(enabled: Boolean) { pauseOnUnplug = enabled }

    fun resetPlaybackParameters() { player.playbackParameters = PlaybackParameters.DEFAULT; player2.playbackParameters = PlaybackParameters.DEFAULT }
    fun setSpeed(speed: Float, isPlayer2: Boolean) { val p = if (isPlayer2) player2 else player; p.playbackParameters = PlaybackParameters(speed, p.playbackParameters.pitch) }
    fun setPitch(pitch: Float, isPlayer2: Boolean) { val p = if (isPlayer2) player2 else player; p.playbackParameters = PlaybackParameters(p.playbackParameters.speed, pitch) }

    fun setPreampGain(mb: Int) { try { loudnessEnhancer?.setTargetGain(mb) } catch (e: Exception) {} }
    fun setEqEnabled(en: Boolean) { try { equalizer?.enabled = en } catch (e: Exception) {} }
    fun updateEq(idx: Int, lvl: Float, isPlayer2: Boolean = false) {
        try { equalizer?.setBandLevel(idx.toShort(), ((lvl - 0.5f) * 3000f).toInt().toShort()) } catch (e: Exception) {}
    }
    fun updateBass(str: Float) { try { bassBoost?.setStrength((str * 1000).toInt().toShort()) } catch (e: Exception) {} }
    fun updateVirtualizer(str: Float) { try { virtualizer?.setStrength((str * 1000).toInt().toShort()) } catch (e: Exception) {} }
    fun setReverb(p: Short) { try { presetReverb?.preset = p } catch (e: Exception) {} }

    fun playTrack(t: AudioTrack, secondary: Boolean = false) {
        registerCallbacks()

        val p = if (secondary) player2 else player

        if (secondary) {
            _currentTrack2.value = t
        } else {
            _currentTrack.value = t
        }
        queueMap = queueMap + (t.id.toString() to t)

        p.setMediaItem(
            MediaItem.Builder()
                .setUri(Uri.parse(t.uri))
                .setMediaId(t.id.toString())
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(t.title)
                        .setArtist(t.artist)
                        .setAlbumTitle(t.album)
                        .setArtworkUri(
                            Uri.parse(
                                "content://media/external/audio/albumart/${t.albumId}"
                            )
                        )
                        .build()
                )
                .build()
        )

        p.prepare()
        p.playWhenReady = true
    }

    fun setPlaylist(tracks: List<AudioTrack>, startIndex: Int = 0) {
        registerCallbacks()
        _queue.value = tracks
        queueMap = tracks.associateBy { it.id.toString() }
        player.setMediaItems(
            tracks.map {
                MediaItem.Builder()
                    .setUri(Uri.parse(it.uri))
                    .setMediaId(it.id.toString())
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(it.title)
                            .setArtist(it.artist)
                            .setAlbumTitle(it.album)
                            .setArtworkUri(Uri.parse("content://media/external/audio/albumart/${it.albumId}"))
                            .build()
                    ).build()
            },
            startIndex,
            C.TIME_UNSET
        )
        player.prepare()
        player.play()
    }

    fun setDuoMode(en: Boolean) {
        isDuoModeActive = en
        applyVolumes()
        if (en) {
            stereoProcessor1.setMode(ChannelMode.LEFT_ONLY);
            stereoProcessor2.setMode(ChannelMode.RIGHT_ONLY)
        } else {
            stereoProcessor1.setMode(ChannelMode.STEREO);
            player2.stop();
            player2.clearMediaItems();
            _currentTrack2.value = null
        }
    }

    fun triggerCrossfade() {
        if (crossfadeDurationMs <= 0 || !player.hasNextMediaItem()) return seekToNext()
        crossfadeJob?.cancel()
        crossfadeJob = engineScope.launch {
            val steps = 20; val v = player.volume; val d = (crossfadeDurationMs / 2 / steps).toLong()
            repeat(steps) { i -> player.volume = v * (1 - (i + 1) / steps.toFloat()); delay(d) }
            player.volume = 0f; seekToNext()
            repeat(steps) { i -> player.volume = v * ((i + 1) / steps.toFloat()); delay(d) }
            player.volume = v
        }
    }

    fun stopAll() {
        unregisterCallbacks()

        player.stop(); player.clearMediaItems(); player2.stop(); player2.clearMediaItems()
        player.pause(); player2.pause()
        player.playWhenReady = false; player2.playWhenReady = false

        _currentTrack.value = null; _currentTrack2.value = null; _queue.value = emptyList()
        _isPlaying.value = false; _isPlaying2.value = false

        try { equalizer?.enabled = false } catch (e: Exception) {}
        try { bassBoost?.enabled = false } catch (e: Exception) {}
        try { virtualizer?.enabled = false } catch (e: Exception) {}
        try { presetReverb?.enabled = false } catch (e: Exception) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) try { loudnessEnhancer?.enabled = false } catch (e: Exception) {}
    }

    fun release() { stopAll(); engineScope.cancel(); equalizer?.release(); bassBoost?.release(); virtualizer?.release(); presetReverb?.release(); loudnessEnhancer?.release(); player.release(); player2.release() }
}

// ==========================================
// 3. MUSIC SERVICE
// ==========================================
@UnstableApi
@AndroidEntryPoint
class MusicService : Service() {
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var playerManager: PlayerManager
    @Inject lateinit var fmRadioEngine: FmRadioEngine

    private val binder = MusicBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val imageLoader by lazy { ImageLoader(this) }

    private var mediaSession: androidx.media3.session.MediaSession? = null
    private var nativeMediaSession: android.media.session.MediaSession? = null
    private var notificationManager: PlayerNotificationManager? = null
    private var currentNotification: Notification? = null
    private var autoStopJob: Job? = null

    private val _playbackMode = MutableStateFlow(PlaybackMode.NONE); val playbackMode = _playbackMode.asStateFlow()

    inner class MusicBinder : Binder() { fun getService(): MusicService = this@MusicService }
    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setupMediaSession()
        setupNotification()
        coordinateEngines()
        observeSettings()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!(playerManager.player.isPlaying || playerManager.player.playWhenReady || playerManager.player2.isPlaying || playerManager.player2.playWhenReady || fmRadioEngine.isPlaying.value)) {
            stopServiceAndPlayback()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    private fun scheduleAutoStop() {
        autoStopJob?.cancel()
        autoStopJob = serviceScope.launch {
            delay(15000)
            val active = playerManager.player.isPlaying || playerManager.player2.isPlaying || fmRadioEngine.isPlaying.value
            if (!active) stopServiceAndPlayback()
        }
    }

    private fun createNotificationChannel() { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel(CHANNEL_ID, "Media Playback", NotificationManager.IMPORTANCE_LOW).apply { description = "Background music and radio playback controls"; setShowBadge(false) }) }

    private fun observeSettings() {
        serviceScope.launch {
            combine(settingsRepository.crossfadeDuration, settingsRepository.gaplessPlayback, settingsRepository.pauseOnUnplug, settingsRepository.resumeOnPlug) { c, g, p, r -> PlaybackSettings(c, g, p, r) }.collect { s ->
                playerManager.crossfadeDurationMs = s.crossfade * 1000
                playerManager.gaplessPlayback = s.gapless
                playerManager.pauseOnUnplug = s.pause
                playerManager.resumeOnPlug = s.resume
            }
        }
    }

    private fun coordinateEngines() {
        serviceScope.launch {
            playerManager.isPlaying.collect { p ->
                if (p) {
                    autoStopJob?.cancel()
                    fmRadioEngine.stop()
                    _playbackMode.value = PlaybackMode.LOCAL_MUSIC
                    updateNotificationActions(false)
                } else {
                    if (_playbackMode.value == PlaybackMode.LOCAL_MUSIC) _playbackMode.value = PlaybackMode.NONE
                    scheduleAutoStop()
                }
            }
        }
        serviceScope.launch {
            fmRadioEngine.isPlaying.collect { p ->
                if (p) {
                    autoStopJob?.cancel()
                    playerManager.pause()
                    _playbackMode.value = PlaybackMode.FM_RADIO
                    updateNotificationActions(true)
                    currentNotification?.let { startForegroundSafe(NOTIFICATION_ID, it) }
                } else {
                    if (_playbackMode.value == PlaybackMode.FM_RADIO) {
                        _playbackMode.value = PlaybackMode.NONE
                        stopForeground(STOP_FOREGROUND_DETACH)
                    }
                    scheduleAutoStop()
                }
            }
        }
    }

    private fun updateNotificationActions(isFm: Boolean) { notificationManager?.setUseNextAction(!isFm); notificationManager?.setUsePreviousAction(!isFm) }

    private fun setupMediaSession() {
        mediaSession = androidx.media3.session.MediaSession.Builder(this, playerManager.player).build()
        nativeMediaSession = android.media.session.MediaSession(this, "GalleryBoxMediaSession").apply {
            isActive = true
            setCallback(object : android.media.session.MediaSession.Callback() {
                override fun onPlay() { togglePlayPause() }
                override fun onPause() { togglePlayPause() }
                override fun onSkipToNext() { playerManager.seekToNext() }
                override fun onSkipToPrevious() { playerManager.seekToPrevious() }
                override fun onStop() { stopServiceAndPlayback() }
            })
        }
    }

    private fun setupNotification() {
        notificationManager = PlayerNotificationManager.Builder(this, NOTIFICATION_ID, CHANNEL_ID).setChannelNameResourceId(com.gallerybox.R.string.app_name).setMediaDescriptionAdapter(DescriptionAdapter()).setNotificationListener(object : PlayerNotificationManager.NotificationListener {
            override fun onNotificationPosted(id: Int, n: Notification, ongoing: Boolean) { currentNotification = n; val p = playerManager.isPlaying.value || fmRadioEngine.isPlaying.value; if (ongoing && p) startForegroundSafe(id, n) else if (!p && _playbackMode.value != PlaybackMode.NONE) stopForeground(STOP_FOREGROUND_DETACH) }
            override fun onNotificationCancelled(id: Int, dismissed: Boolean) { stopServiceAndPlayback() }
        }).build().apply { setPlayer(playerManager.player); nativeMediaSession?.let { setMediaSessionToken(it.sessionToken) } }
    }

    private fun startForegroundSafe(id: Int, n: Notification) { try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(id, n) } catch (e: Exception) { Log.e("MusicService", "Foreground service start rejected: ${e.message}") } }

    fun togglePlayPause() = when (_playbackMode.value) { PlaybackMode.LOCAL_MUSIC -> playerManager.togglePlayPause(); PlaybackMode.FM_RADIO -> if (fmRadioEngine.isPlaying.value) fmRadioEngine.stop() else fmRadioEngine.start(98.0f); PlaybackMode.NONE -> {} }

    fun stopServiceAndPlayback() {
        playerManager.stopAll()
        fmRadioEngine.stop()
        notificationManager?.setPlayer(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { serviceScope.cancel(); mediaSession?.release(); nativeMediaSession?.isActive = false; nativeMediaSession?.release(); notificationManager?.setPlayer(null); super.onDestroy() }

    private inner class DescriptionAdapter : PlayerNotificationManager.MediaDescriptionAdapter {
        override fun getCurrentContentTitle(p: Player) = if (_playbackMode.value == PlaybackMode.FM_RADIO) "FM Radio" else playerManager.currentTrack.value?.title ?: "Music Player"
        override fun createCurrentContentIntent(p: Player) = PendingIntent.getActivity(this@MusicService, 0, Intent(this@MusicService, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        override fun getCurrentContentText(p: Player) = if (_playbackMode.value == PlaybackMode.FM_RADIO) "${fmRadioEngine.frequency.value} MHz" else playerManager.currentTrack.value?.artist ?: "Unknown Artist"
        override fun getCurrentLargeIcon(p: Player, cb: PlayerNotificationManager.BitmapCallback): Bitmap? {
            val t = playerManager.currentTrack.value?.takeIf { it.id.toString() == p.currentMediaItem?.mediaId } ?: playerManager.queue.value.find { it.id.toString() == p.currentMediaItem?.mediaId }
            if (_playbackMode.value != PlaybackMode.FM_RADIO && t != null && t.albumId > 0) serviceScope.launch(Dispatchers.IO) { try { (imageLoader.execute(ImageRequest.Builder(this@MusicService).data(ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), t.albumId)).size(256, 256).bitmapConfig(Bitmap.Config.RGB_565).allowHardware(false).build()).drawable as? BitmapDrawable)?.bitmap?.let { withContext(Dispatchers.Main) { cb.onBitmap(it) } } } catch (e: Exception) { e.printStackTrace() } }
            return null
        }
    }
    companion object { const val NOTIFICATION_ID = 101; const val CHANNEL_ID = "gallerybox_music_channel" }
}