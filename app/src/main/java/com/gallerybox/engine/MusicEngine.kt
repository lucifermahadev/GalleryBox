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

enum class PlaybackMode {
    NONE,
    LOCAL_MUSIC,
    FM_RADIO
}

private data class PlaybackSettings(
    val crossfade: Int,
    val gapless: Boolean,
    val pause: Boolean,
    val resume: Boolean
)

@Singleton
class FmRadioEngine @Inject constructor(@ApplicationContext private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val prefs = context.getSharedPreferences("gallerybox_fm_radio", Context.MODE_PRIVATE)
    private var audioFocusRequest: AudioFocusRequest? = null

    var pauseOnUnplug = true
    var resumeOnPlug = false

    private var wasPausedByUnplug = false
    private var wasPausedByFocus = false
    private var lastScanTime = 0L

    private val minFreqInt = 875
    private val maxFreqInt = 1080
    private val stepInt = 1

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private var currentFreqInt = (prefs.getFloat("freq", 98.0f) * 10).roundToInt()
    private val _frequency = MutableStateFlow(currentFreqInt / 10f)
    val frequency = _frequency.asStateFlow()

    private val _isHeadsetConnected = MutableStateFlow(false)
    val isHeadsetConnected = _isHeadsetConnected.asStateFlow()

    private val favoritesSet = loadFavorites().toMutableSet()
    private val _favorites = MutableStateFlow(favoritesSet.map { it / 10f }.sorted())
    val favoriteStations = _favorites.asStateFlow()

    private val _signalStrength = MutableStateFlow(0)
    val signalStrength = _signalStrength.asStateFlow()

    private var isCallbackRegistered = false

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                stop()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (_isPlaying.value) {
                    stop()
                    wasPausedByFocus = true
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (wasPausedByFocus) {
                    start()
                    wasPausedByFocus = false
                }
            }
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            updateHeadsetState()
            if (resumeOnPlug && wasPausedByUnplug && _isHeadsetConnected.value) {
                start()
                wasPausedByUnplug = false
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            updateHeadsetState()
            if (!_isHeadsetConnected.value) {
                if (pauseOnUnplug && _isPlaying.value) {
                    stop()
                    wasPausedByUnplug = true
                }
                _signalStrength.value = 0
            }
        }
    }

    init {
        updateHeadsetState()
    }

    private fun registerCallbacks() {
        if (!isCallbackRegistered) {
            audioManager.registerAudioDeviceCallback(deviceCallback, null)
            isCallbackRegistered = true
        }
    }

    private fun unregisterCallbacks() {
        if (isCallbackRegistered) {
            audioManager.unregisterAudioDeviceCallback(deviceCallback)
            isCallbackRegistered = false
        }
    }

    fun start(freq: Float = _frequency.value): Boolean {
        if (!isHeadsetAvailable() || _isPlaying.value) {
            return false
        }
        registerCallbacks()
        requestAudioFocus()
        tune(freq)
        _isPlaying.value = true
        return true
    }

    fun stop() {
        abandonAudioFocus()
        unregisterCallbacks()
        _isPlaying.value = false
        _signalStrength.value = 0
    }

    fun tune(freq: Float) {
        tuneInt((freq * 10f).roundToInt())
    }

    private fun tuneInt(freqInt: Int) {
        currentFreqInt = freqInt.coerceIn(minFreqInt, maxFreqInt)
        val safeFreq = currentFreqInt / 10f
        _frequency.value = safeFreq
        prefs.edit().putFloat("freq", safeFreq).apply()

        if (isHeadsetAvailable()) {
            _signalStrength.value = (30..95).random(Random(currentFreqInt))
        }
    }

    fun seekUp() {
        tuneInt(currentFreqInt + stepInt)
    }

    fun seekDown() {
        tuneInt(currentFreqInt - stepInt)
    }

    fun scanNext() {
        if (System.currentTimeMillis() - lastScanTime < 250L) {
            return
        }
        lastScanTime = System.currentTimeMillis()
        tuneInt((currentFreqInt + 12).coerceAtMost(maxFreqInt))
    }

    fun scanPrevious() {
        if (System.currentTimeMillis() - lastScanTime < 250L) {
            return
        }
        lastScanTime = System.currentTimeMillis()
        tuneInt((currentFreqInt - 12).coerceAtLeast(minFreqInt))
    }

    fun setSpeakerEnabled(enabled: Boolean) {
        audioManager.isSpeakerphoneOn = enabled
    }

    fun setMute(mute: Boolean) {
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
            0
        )
    }

    fun addFavorite(freq: Float) {
        if (favoritesSet.add((freq * 10f).roundToInt())) {
            updateFavoritesFlowAndPrefs()
        }
    }

    fun removeFavorite(freq: Float) {
        if (favoritesSet.remove((freq * 10f).roundToInt())) {
            updateFavoritesFlowAndPrefs()
        }
    }

    private fun updateFavoritesFlowAndPrefs() {
        _favorites.value = favoritesSet.map { it / 10f }.sorted()
        prefs.edit().putString("favorites", favoritesSet.joinToString(",")).apply()
    }

    private fun loadFavorites(): Set<Int> {
        val savedData = prefs.getString("favorites", "")
        if (savedData.isNullOrEmpty()) {
            return emptySet()
        }
        return savedData.split(",").mapNotNull { it.toIntOrNull() }.toSet()
    }

    private fun isHeadsetAvailable(): Boolean {
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
    }

    private fun updateHeadsetState() {
        _isHeadsetConnected.value = isHeadsetAvailable()
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()
            audioManager.requestAudioFocus(audioFocusRequest!!)
        } else {
            audioManager.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let {
                audioManager.abandonAudioFocusRequest(it)
            }
        } else {
            audioManager.abandonAudioFocus(focusChangeListener)
        }
    }

    fun release() {
        stop()
    }
}

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

    override fun onConfigure(fmt: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (fmt.encoding != C.ENCODING_PCM_16BIT || fmt.channelCount != 2) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        return fmt
    }

    override fun queueInput(buffer: ByteBuffer) {
        val remaining = buffer.remaining()
        if (remaining == 0) {
            return
        }

        val outputBuffer = replaceOutputBuffer(remaining)
        val crossfeedScale = 1f / (1f + crossfeed)

        while (buffer.hasRemaining()) {
            var leftShort = buffer.getShort()
            var rightShort = buffer.getShort()

            when (currentMode) {
                ChannelMode.LEFT_ONLY -> rightShort = 0
                ChannelMode.RIGHT_ONLY -> leftShort = 0
                ChannelMode.STEREO -> {}
            }

            var leftFloat = leftShort.toFloat()
            var rightFloat = rightShort.toFloat()

            if (crossfeed > 0f) {
                val tempLeft = leftFloat
                val tempRight = rightFloat
                leftFloat = (tempLeft + tempRight * crossfeed) * crossfeedScale
                rightFloat = (tempRight + tempLeft * crossfeed) * crossfeedScale
            }

            leftFloat *= leftGain
            rightFloat *= rightGain

            val finalLeft = leftFloat.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            val finalRight = rightFloat.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

            outputBuffer.putShort(finalLeft)
            outputBuffer.putShort(finalRight)
        }

        buffer.position(buffer.limit())
        outputBuffer.flip()
    }
}

@UnstableApi
@Singleton
class PlayerManager @Inject constructor(@ApplicationContext private val context: Context) {

    private val prefs = context.getSharedPreferences("gallerybox_music_prefs", Context.MODE_PRIVATE)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var crossfadeJob: Job? = null

    var crossfadeDurationMs = 2000
    var resumeOnPlug = prefs.getBoolean("resume_on_plug", false)
    var pauseOnUnplug = prefs.getBoolean("pause_on_unplug", true)
    var gaplessPlayback = prefs.getBoolean("gapless_playback", true)

    private var wasPausedByUnplug = false

    private val stereoProcessor1 = DynamicStereoProcessor(ChannelMode.STEREO)
    private val stereoProcessor2 = DynamicStereoProcessor(ChannelMode.STEREO)

    val player = createExoPlayer(stereoProcessor1)
    val player2 = createExoPlayer(stereoProcessor2)

    val player1Position: Long get() = player.currentPosition
    val player2Position: Long get() = player2.currentPosition
    val player1Duration: Long get() = player.duration
    val player2Duration: Long get() = player2.duration

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var presetReverb: PresetReverb? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _currentTrack = MutableStateFlow<AudioTrack?>(null)
    val currentTrack = _currentTrack.asStateFlow()

    private val _queue = MutableStateFlow<List<AudioTrack>>(emptyList())
    val queue = _queue.asStateFlow()

    private var queueMap = emptyMap<String, AudioTrack>()

    private val _audioSessionId = MutableStateFlow(C.AUDIO_SESSION_ID_UNSET)
    val audioSessionId = _audioSessionId.asStateFlow()

    private val _isPlaying2 = MutableStateFlow(false)
    val isPlaying2 = _isPlaying2.asStateFlow()

    private val _currentTrack2 = MutableStateFlow<AudioTrack?>(null)
    val currentTrack2 = _currentTrack2.asStateFlow()

    private val _volume1 = MutableStateFlow(1f)
    val volume1 = _volume1.asStateFlow()

    private val _volume2 = MutableStateFlow(1f)
    val volume2 = _volume2.asStateFlow()

    private val _balance1 = MutableStateFlow(0f)
    val balance1 = _balance1.asStateFlow()

    private val _balance2 = MutableStateFlow(0f)
    val balance2 = _balance2.asStateFlow()

    private val _crossfeed = MutableStateFlow(0f)
    val crossfeed = _crossfeed.asStateFlow()

    private val _softLimiterEnabled = MutableStateFlow(true)
    val softLimiterEnabled = _softLimiterEnabled.asStateFlow()

    private var isDuoModeActive = false
    private var isCallbackRegistered = false

    private val noisyAudioReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && pauseOnUnplug) {
                wasPausedByUnplug = true
                player.pause()
                player2.pause()
            }
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) {
            if (resumeOnPlug && wasPausedByUnplug) {
                val hasHeadset = added?.any {
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                } == true

                if (hasHeadset) {
                    play()
                    wasPausedByUnplug = false
                }
            }
        }
    }

    init {
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {}
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                mediaItem?.mediaId?.let { id ->
                    queueMap[id]?.let { track ->
                        _currentTrack.value = track
                    }
                }
            }
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                if (audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
                    _audioSessionId.value = audioSessionId
                    initAudioFx(audioSessionId)
                }
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
                    wasPausedByUnplug = false
                }
            }
        })

        player2.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {}
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying2.value = isPlaying
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                mediaItem?.mediaId?.let { id ->
                    queueMap[id]?.let { track ->
                        _currentTrack2.value = track
                    }
                }
            }
        })
    }

    private fun createExoPlayer(processor: DynamicStereoProcessor): ExoPlayer {
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean) =
                DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(processor))
                    .setEnableFloatOutput(true)
                    .build()
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        return ExoPlayer.Builder(context, renderersFactory)
            .setAudioAttributes(audioAttributes, false)
            .setWakeMode(C.WAKE_MODE_NONE)
            .setHandleAudioBecomingNoisy(false)
            .build()
    }

    private fun registerCallbacks() {
        if (!isCallbackRegistered) {
            audioManager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
            context.registerReceiver(noisyAudioReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            isCallbackRegistered = true
        }
    }

    private fun unregisterCallbacks() {
        if (isCallbackRegistered) {
            try {
                audioManager.unregisterAudioDeviceCallback(deviceCallback)
            } catch (e: Exception) {}
            try {
                context.unregisterReceiver(noisyAudioReceiver)
            } catch (e: Exception) {}
            isCallbackRegistered = false
        }
    }

    private fun initAudioFx(sessionId: Int) {
        equalizer?.release()
        bassBoost?.release()
        virtualizer?.release()
        presetReverb?.release()
        loudnessEnhancer?.release()

        try {
            equalizer = Equalizer(0, sessionId).apply { enabled = true }
        } catch (e: Exception) {}

        try {
            bassBoost = BassBoost(0, sessionId).apply { enabled = true }
        } catch (e: Exception) {}

        try {
            virtualizer = Virtualizer(0, sessionId).apply { enabled = true }
        } catch (e: Exception) {}

        try {
            presetReverb = PresetReverb(0, sessionId).apply { enabled = true }
        } catch (e: Exception) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            try {
                loudnessEnhancer = LoudnessEnhancer(sessionId).apply { enabled = true }
            } catch (e: Exception) {}
        }
    }

    fun play(secondary: Boolean = false) {
        registerCallbacks()
        if (secondary) {
            player2.play()
        } else {
            player.play()
        }
    }

    fun pause(secondary: Boolean = false) {
        if (secondary) {
            player2.pause()
        } else {
            player.pause()
        }
    }

    fun togglePlayPause(secondary: Boolean = false) {
        if (secondary) {
            if (player2.isPlaying) {
                player2.pause()
            } else {
                registerCallbacks()
                player2.play()
            }
        } else {
            if (player.isPlaying) {
                player.pause()
            } else {
                registerCallbacks()
                player.play()
            }
        }
    }

    fun seekToNext() {
        if (player.hasNextMediaItem()) {
            player.seekToNext()
        }
    }

    fun seekToPrevious() {
        if (player.hasPreviousMediaItem()) {
            player.seekToPrevious()
        } else {
            player.seekTo(0)
        }
    }

    fun seekTo(ms: Long, secondary: Boolean = false) {
        if (secondary) {
            player2.seekTo(ms)
        } else {
            player.seekTo(ms)
        }
    }

    fun seekToFraction(fraction: Float, secondary: Boolean = false) {
        val targetPlayer = if (secondary) player2 else player
        if (targetPlayer.duration > 0) {
            targetPlayer.seekTo((targetPlayer.duration * fraction).toLong())
        }
    }

    fun setRepeatMode(mode: Int) {
        player.repeatMode = mode
    }

    fun setShuffleMode(enabled: Boolean) {
        player.shuffleModeEnabled = enabled
    }

    fun setVolume(volume: Float, secondary: Boolean = false) {
        if (secondary) {
            _volume2.value = volume
        } else {
            _volume1.value = volume
        }
        applyVolumes()
    }

    fun setSoftLimiter(enabled: Boolean) {
        _softLimiterEnabled.value = enabled
        applyVolumes()
    }

    private fun applyVolumes() {
        val volumeVal1 = _volume1.value
        val volumeVal2 = _volume2.value
        val limiterScale = if (_softLimiterEnabled.value && isDuoModeActive) 1f / max(1f, volumeVal1 + volumeVal2) else 1f
        val logVolume1 = (ln(1.0 + 9.0 * volumeVal1) / ln(10.0)).toFloat() * limiterScale
        val logVolume2 = (ln(1.0 + 9.0 * volumeVal2) / ln(10.0)).toFloat() * limiterScale

        player.volume = logVolume1
        player2.volume = logVolume2
    }

    fun setStereoBalance(balance: Float, secondary: Boolean = false) {
        if (secondary) {
            _balance2.value = balance
        } else {
            _balance1.value = balance
        }

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
    fun setGaplessPlayback(enabled: Boolean) {
        gaplessPlayback = enabled
    }

    fun setCrossfadeDuration(durationMs: Int) {
        crossfadeDurationMs = durationMs
    }

    @JvmName("updatePauseOnUnplug")
    fun setPauseOnUnplug(enabled: Boolean) {
        pauseOnUnplug = enabled
    }

    fun resetPlaybackParameters() {
        player.playbackParameters = PlaybackParameters.DEFAULT
        player2.playbackParameters = PlaybackParameters.DEFAULT
    }

    fun setSpeed(speed: Float, isPlayer2: Boolean) {
        val targetPlayer = if (isPlayer2) player2 else player
        targetPlayer.playbackParameters = PlaybackParameters(speed, targetPlayer.playbackParameters.pitch)
    }

    fun setPitch(pitch: Float, isPlayer2: Boolean) {
        val targetPlayer = if (isPlayer2) player2 else player
        targetPlayer.playbackParameters = PlaybackParameters(targetPlayer.playbackParameters.speed, pitch)
    }

    fun setPreampGain(millibels: Int) {
        try {
            loudnessEnhancer?.setTargetGain(millibels)
        } catch (e: Exception) {}
    }

    fun setEqEnabled(enabled: Boolean) {
        try {
            equalizer?.enabled = enabled
        } catch (e: Exception) {}
    }

    fun updateEq(index: Int, level: Float, isPlayer2: Boolean = false) {
        try {
            val convertedLevel = ((level - 0.5f) * 3000f).toInt().toShort()
            equalizer?.setBandLevel(index.toShort(), convertedLevel)
        } catch (e: Exception) {}
    }

    fun updateBass(strength: Float) {
        try {
            bassBoost?.setStrength((strength * 1000).toInt().toShort())
        } catch (e: Exception) {}
    }

    fun updateVirtualizer(strength: Float) {
        try {
            virtualizer?.setStrength((strength * 1000).toInt().toShort())
        } catch (e: Exception) {}
    }

    fun setReverb(preset: Short) {
        try {
            presetReverb?.preset = preset
        } catch (e: Exception) {}
    }

    fun playTrack(track: AudioTrack, secondary: Boolean = false) {
        registerCallbacks()

        val targetPlayer = if (secondary) player2 else player

        if (secondary) {
            _currentTrack2.value = track
        } else {
            _currentTrack.value = track
        }

        queueMap = queueMap + (track.id.toString() to track)

        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .setArtworkUri(Uri.parse("content://media/external/audio/albumart/${track.albumId}"))
            .build()

        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(track.uri))
            .setMediaId(track.id.toString())
            .setMediaMetadata(metadata)
            .build()

        targetPlayer.setMediaItem(mediaItem)
        targetPlayer.prepare()
        targetPlayer.playWhenReady = true
    }

    fun setPlaylist(tracks: List<AudioTrack>, startIndex: Int = 0) {
        registerCallbacks()
        _queue.value = tracks
        queueMap = tracks.associateBy { it.id.toString() }

        val mediaItems = tracks.map { track ->
            val metadata = MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist)
                .setAlbumTitle(track.album)
                .setArtworkUri(Uri.parse("content://media/external/audio/albumart/${track.albumId}"))
                .build()

            MediaItem.Builder()
                .setUri(Uri.parse(track.uri))
                .setMediaId(track.id.toString())
                .setMediaMetadata(metadata)
                .build()
        }

        player.setMediaItems(mediaItems, startIndex, C.TIME_UNSET)
        player.prepare()
        player.play()
    }

    fun setDuoMode(enabled: Boolean) {
        isDuoModeActive = enabled
        applyVolumes()

        if (enabled) {
            stereoProcessor1.setMode(ChannelMode.LEFT_ONLY)
            stereoProcessor2.setMode(ChannelMode.RIGHT_ONLY)
        } else {
            stereoProcessor1.setMode(ChannelMode.STEREO)
            player2.stop()
            player2.clearMediaItems()
            _currentTrack2.value = null
        }
    }

    fun triggerCrossfade() {
        if (crossfadeDurationMs <= 0 || !player.hasNextMediaItem()) {
            seekToNext()
            return
        }

        crossfadeJob?.cancel()
        crossfadeJob = engineScope.launch {
            val steps = 20
            val initialVolume = player.volume
            val delayDuration = (crossfadeDurationMs / 2 / steps).toLong()

            repeat(steps) { stepIndex ->
                player.volume = initialVolume * (1 - (stepIndex + 1) / steps.toFloat())
                delay(delayDuration)
            }

            player.volume = 0f
            seekToNext()

            repeat(steps) { stepIndex ->
                player.volume = initialVolume * ((stepIndex + 1) / steps.toFloat())
                delay(delayDuration)
            }

            player.volume = initialVolume
        }
    }

    fun stopAll() {
        unregisterCallbacks()

        player.stop()
        player.clearMediaItems()
        player2.stop()
        player2.clearMediaItems()

        player.pause()
        player2.pause()

        player.playWhenReady = false
        player2.playWhenReady = false

        _currentTrack.value = null
        _currentTrack2.value = null
        _queue.value = emptyList()

        _isPlaying.value = false
        _isPlaying2.value = false

        try { equalizer?.enabled = false } catch (e: Exception) {}
        try { bassBoost?.enabled = false } catch (e: Exception) {}
        try { virtualizer?.enabled = false } catch (e: Exception) {}
        try { presetReverb?.enabled = false } catch (e: Exception) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            try { loudnessEnhancer?.enabled = false } catch (e: Exception) {}
        }
    }

    fun release() {
        stopAll()
        engineScope.cancel()

        equalizer?.release()
        bassBoost?.release()
        virtualizer?.release()
        presetReverb?.release()
        loudnessEnhancer?.release()

        player.release()
        player2.release()
    }
}

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

    private val _playbackMode = MutableStateFlow(PlaybackMode.NONE)
    val playbackMode = _playbackMode.asStateFlow()

    inner class MusicBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

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
        val isActive = playerManager.player.isPlaying ||
                playerManager.player.playWhenReady ||
                playerManager.player2.isPlaying ||
                playerManager.player2.playWhenReady ||
                fmRadioEngine.isPlaying.value

        if (!isActive) {
            stopServiceAndPlayback()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    private fun scheduleAutoStop() {
        autoStopJob?.cancel()
        autoStopJob = serviceScope.launch {
            delay(15000)
            val isActive = playerManager.player.isPlaying ||
                    playerManager.player2.isPlaying ||
                    fmRadioEngine.isPlaying.value

            if (!isActive) {
                stopServiceAndPlayback()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManagerService = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(CHANNEL_ID, "Media Playback", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Background music and radio playback controls"
                setShowBadge(false)
            }
            notificationManagerService.createNotificationChannel(channel)
        }
    }

    private fun observeSettings() {
        serviceScope.launch {
            combine(
                settingsRepository.crossfadeDuration,
                settingsRepository.gaplessPlayback,
                settingsRepository.pauseOnUnplug,
                settingsRepository.resumeOnPlug
            ) { crossfade, gapless, pause, resume ->
                PlaybackSettings(crossfade, gapless, pause, resume)
            }.collect { settings ->
                playerManager.crossfadeDurationMs = settings.crossfade * 1000
                playerManager.gaplessPlayback = settings.gapless
                playerManager.pauseOnUnplug = settings.pause
                playerManager.resumeOnPlug = settings.resume
            }
        }
    }

    private fun coordinateEngines() {
        serviceScope.launch {
            playerManager.isPlaying.collect { isPlaying ->
                if (isPlaying) {
                    autoStopJob?.cancel()
                    fmRadioEngine.stop()
                    _playbackMode.value = PlaybackMode.LOCAL_MUSIC
                    updateNotificationActions(false)
                } else {
                    if (_playbackMode.value == PlaybackMode.LOCAL_MUSIC) {
                        _playbackMode.value = PlaybackMode.NONE
                    }
                    scheduleAutoStop()
                }
            }
        }

        serviceScope.launch {
            fmRadioEngine.isPlaying.collect { isPlaying ->
                if (isPlaying) {
                    autoStopJob?.cancel()
                    playerManager.pause()
                    _playbackMode.value = PlaybackMode.FM_RADIO
                    updateNotificationActions(true)
                    currentNotification?.let { notification ->
                        startForegroundSafe(NOTIFICATION_ID, notification)
                    }
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

    private fun updateNotificationActions(isFm: Boolean) {
        notificationManager?.setUseNextAction(!isFm)
        notificationManager?.setUsePreviousAction(!isFm)
    }

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
        notificationManager = PlayerNotificationManager.Builder(this, NOTIFICATION_ID, CHANNEL_ID)
            .setChannelNameResourceId(com.gallerybox.R.string.app_name)
            .setMediaDescriptionAdapter(DescriptionAdapter())
            .setNotificationListener(object : PlayerNotificationManager.NotificationListener {
                override fun onNotificationPosted(notificationId: Int, notification: Notification, ongoing: Boolean) {
                    currentNotification = notification
                    val isPlaying = playerManager.isPlaying.value || fmRadioEngine.isPlaying.value

                    if (ongoing && isPlaying) {
                        startForegroundSafe(notificationId, notification)
                    } else if (!isPlaying && _playbackMode.value != PlaybackMode.NONE) {
                        stopForeground(STOP_FOREGROUND_DETACH)
                    }
                }

                override fun onNotificationCancelled(notificationId: Int, dismissed: Boolean) {
                    stopServiceAndPlayback()
                }
            })
            .build()
            .apply {
                setPlayer(playerManager.player)
                nativeMediaSession?.let { setMediaSessionToken(it.sessionToken) }
            }
    }

    private fun startForegroundSafe(notificationId: Int, notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(notificationId, notification)
            }
        } catch (e: Exception) {
            Log.e("MusicService", "Foreground service start rejected: ${e.message}")
        }
    }

    fun togglePlayPause() {
        when (_playbackMode.value) {
            PlaybackMode.LOCAL_MUSIC -> playerManager.togglePlayPause()
            PlaybackMode.FM_RADIO -> {
                if (fmRadioEngine.isPlaying.value) {
                    fmRadioEngine.stop()
                } else {
                    fmRadioEngine.start(98.0f)
                }
            }
            PlaybackMode.NONE -> {}
        }
    }

    fun stopServiceAndPlayback() {
        playerManager.stopAll()
        fmRadioEngine.stop()
        notificationManager?.setPlayer(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        mediaSession?.release()
        nativeMediaSession?.isActive = false
        nativeMediaSession?.release()
        notificationManager?.setPlayer(null)
        super.onDestroy()
    }

    private inner class DescriptionAdapter : PlayerNotificationManager.MediaDescriptionAdapter {
        override fun getCurrentContentTitle(player: Player): CharSequence {
            return if (_playbackMode.value == PlaybackMode.FM_RADIO) {
                "FM Radio"
            } else {
                playerManager.currentTrack.value?.title ?: "Music Player"
            }
        }

        override fun createCurrentContentIntent(player: Player): PendingIntent? {
            val intent = Intent(this@MusicService, MainActivity::class.java)
            return PendingIntent.getActivity(this@MusicService, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        override fun getCurrentContentText(player: Player): CharSequence? {
            return if (_playbackMode.value == PlaybackMode.FM_RADIO) {
                "${fmRadioEngine.frequency.value} MHz"
            } else {
                playerManager.currentTrack.value?.artist ?: "Unknown Artist"
            }
        }

        override fun getCurrentLargeIcon(player: Player, callback: PlayerNotificationManager.BitmapCallback): Bitmap? {
            val track = playerManager.currentTrack.value?.takeIf { it.id.toString() == player.currentMediaItem?.mediaId }
                ?: playerManager.queue.value.find { it.id.toString() == player.currentMediaItem?.mediaId }

            if (_playbackMode.value != PlaybackMode.FM_RADIO && track != null && track.albumId > 0) {
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        val request = ImageRequest.Builder(this@MusicService)
                            .data(ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), track.albumId))
                            .size(256, 256)
                            .bitmapConfig(Bitmap.Config.RGB_565)
                            .allowHardware(false)
                            .build()

                        val result = imageLoader.execute(request)
                        val bitmap = (result.drawable as? BitmapDrawable)?.bitmap

                        bitmap?.let {
                            withContext(Dispatchers.Main) {
                                callback.onBitmap(it)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            return null
        }
    }

    companion object {
        const val NOTIFICATION_ID = 101
        const val CHANNEL_ID = "gallerybox_music_channel"
    }
}