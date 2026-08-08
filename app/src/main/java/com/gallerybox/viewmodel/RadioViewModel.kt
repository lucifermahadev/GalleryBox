@file:Suppress("unused", "UnsafeOptInUsageError")

package com.gallerybox.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.gallerybox.engine.MusicService
import com.gallerybox.engine.PlaybackMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.round

@UnstableApi
@HiltViewModel
class RadioViewModel @Inject constructor(private val app: Application) : AndroidViewModel(app) {

    private var musicService: MusicService? = null
    private var isBound = false
    private var observeJob: Job? = null

    private val _isServiceConnected = MutableStateFlow(false)
    val isServiceConnected = _isServiceConnected.asStateFlow()

    private val _playbackMode = MutableStateFlow(PlaybackMode.NONE)
    val playbackMode = _playbackMode.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _currentFrequency = MutableStateFlow(98.0f)
    val currentFrequency = _currentFrequency.asStateFlow()

    private val _isHeadsetConnected = MutableStateFlow(false)
    val isHeadsetConnected = _isHeadsetConnected.asStateFlow()

    private val _favoriteStations = MutableStateFlow<List<Float>>(emptyList())
    val favoriteStations = _favoriteStations.asStateFlow()

    private val _signalStrength = MutableStateFlow(0)
    val signalStrength = _signalStrength.asStateFlow()

    private val _stereoBlend = MutableStateFlow(1.0f)
    val stereoBlend = _stereoBlend.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning = _isScanning.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _isSpeakerEnabled = MutableStateFlow(false)
    val isSpeakerEnabled = _isSpeakerEnabled.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted = _isMuted.asStateFlow()

    private val knownStations = listOf(91.1f, 92.7f, 93.5f, 98.3f, 104.8f)

    val rdsStationName = _currentFrequency.map { freq ->
        when {
            abs(freq - 98.3f) < 0.05f -> "Radio Mirchi"
            abs(freq - 93.5f) < 0.05f -> "Red FM"
            abs(freq - 92.7f) < 0.05f -> "Big FM"
            abs(freq - 104.8f) < 0.05f -> "Ishq FM"
            abs(freq - 91.1f) < 0.05f -> "Radio City"
            else -> null
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            musicService = (service as? MusicService.MusicBinder)?.getService() ?: return
            isBound = true
            _isServiceConnected.value = true
            observeRadioState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
            isBound = false
            _isServiceConnected.value = false
            observeJob?.cancel()
        }
    }

    init {
        val intent = Intent(app, MusicService::class.java)
        try {
            app.startService(intent)
        } catch (e: Exception) {
            // Ignored: Background execution limits on older Android versions
        }
        app.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun observeRadioState() {
        observeJob?.cancel()

        // Safety extraction to avoid nullability issues inside coroutines
        val service = musicService ?: return
        val engine = service.fmRadioEngine ?: return

        observeJob = viewModelScope.launch {
            _favoriteStations.value = engine.favoriteStations.value

            launch {
                service.playbackMode.collect { mode ->
                    _playbackMode.value = mode
                }
            }

            launch {
                engine.isPlaying.collect { playing ->
                    _isPlaying.value = playing
                }
            }

            launch {
                engine.favoriteStations.collect { stations ->
                    _favoriteStations.value = stations
                }
            }

            launch {
                engine.frequency.collect { freq ->
                    _currentFrequency.value = freq
                    updateDSPMetrics(freq)
                }
            }

            launch {
                engine.isHeadsetConnected.collect { connected ->
                    _isHeadsetConnected.value = connected
                    if (!connected) {
                        _isSpeakerEnabled.value = false
                        _isMuted.value = false
                        if (_isPlaying.value) {
                            _error.value = "Headset disconnected. Radio stopped."
                            engine.stop()
                        }
                    }
                }
            }
        }
    }

    private fun updateDSPMetrics(freq: Float) {
        val nearest = knownStations.minByOrNull { abs(it - freq) } ?: freq
        val diff = abs(freq - nearest)
        val calculatedSignal = (100f - (diff / 0.2f) * 100f).coerceIn(0f, 100f).toInt()
        _signalStrength.value = calculatedSignal
        _stereoBlend.value = calculatedSignal / 100f
    }

    fun toggleRadio() {
        val engine = musicService?.fmRadioEngine ?: return
        if (!engine.isHeadsetConnected.value) {
            _error.value = "Connect wired headset to use FM radio"
            return
        }
        if (engine.isPlaying.value) {
            engine.stop()
        } else {
            engine.start(_currentFrequency.value)
        }
    }

    fun startRadio(freq: Float = _currentFrequency.value) {
        val engine = musicService?.fmRadioEngine ?: return
        if (!engine.isHeadsetConnected.value) {
            _error.value = "Connect wired headset to use FM radio"
            return
        }
        engine.start(freq)
    }

    fun stopRadio() {
        musicService?.fmRadioEngine?.stop()
    }

    fun stopRadioIfNeeded() {
        val engine = musicService?.fmRadioEngine ?: return
        if (engine.isPlaying.value) {
            engine.stop()
        }
    }

    fun toggleSpeaker() {
        if (!_isHeadsetConnected.value) return
        _isSpeakerEnabled.value = !_isSpeakerEnabled.value
        musicService?.fmRadioEngine?.setSpeakerEnabled(_isSpeakerEnabled.value)
    }

    fun toggleMute() {
        if (!_isHeadsetConnected.value) return
        _isMuted.value = !_isMuted.value
        musicService?.fmRadioEngine?.setMute(_isMuted.value)
    }

    fun tuneToFrequency(freq: Float) {
        val engine = musicService?.fmRadioEngine ?: return
        engine.tune(freq)
        if (!engine.isPlaying.value) {
            if (engine.isHeadsetConnected.value) {
                engine.start(freq)
            } else {
                _error.value = "Connect wired headphones to play FM radio"
            }
        }
    }

    fun tuneUp() {
        val newFreq = (round((_currentFrequency.value + 0.1f) * 10f) / 10f).coerceAtMost(108.0f)
        tuneToFrequency(newFreq)
    }

    fun tuneDown() {
        val newFreq = (round((_currentFrequency.value - 0.1f) * 10f) / 10f).coerceAtLeast(87.5f)
        tuneToFrequency(newFreq)
    }

    fun autoScan() {
        if (_isScanning.value) return
        val engine = musicService?.fmRadioEngine ?: return
        if (!engine.isHeadsetConnected.value) {
            _error.value = "Connect wired headset to scan stations"
            return
        }

        viewModelScope.launch {
            _isScanning.value = true
            if (!engine.isPlaying.value) {
                engine.start(_currentFrequency.value)
            }
            engine.scanNext()

            // Standard FM hardware polling delay rather than CPU calculation
            delay(250L)

            _isScanning.value = false
        }
    }

    fun scanPrevious() {
        if (_isScanning.value) return
        val engine = musicService?.fmRadioEngine ?: return
        if (!engine.isHeadsetConnected.value) {
            _error.value = "Connect wired headset to scan stations"
            return
        }

        viewModelScope.launch {
            _isScanning.value = true
            if (!engine.isPlaying.value) {
                engine.start(_currentFrequency.value)
            }
            engine.scanPrevious()

            // Standard FM hardware polling delay rather than CPU calculation
            delay(250L)

            _isScanning.value = false
        }
    }

    fun autoScanAndSaveAll() {
        val engine = musicService?.fmRadioEngine ?: return
        if (!engine.isHeadsetConnected.value) {
            _error.value = "Connect wired headset to scan stations"
            return
        }

        viewModelScope.launch {
            _isScanning.value = true
            _error.value = "Scanning FM band..."

            // Hardware simulation time block
            delay(2500L)

            var count = 0
            knownStations.forEach { freq ->
                if (engine.favoriteStations.value.none { abs(it - freq) < 0.05f }) {
                    engine.addFavorite(freq)
                    count++
                }
            }

            _isScanning.value = false
            _error.value = "Scan complete! $count new stations saved."
        }
    }

    fun toggleFavorite(freq: Float) {
        val engine = musicService?.fmRadioEngine ?: return
        val existing = engine.favoriteStations.value.find { abs(it - freq) < 0.05f }
        if (existing != null) {
            engine.removeFavorite(existing)
        } else {
            engine.addFavorite(freq)
        }
    }

    fun clearError() {
        _error.value = null
    }

    override fun onCleared() {
        observeJob?.cancel()
        if (isBound) {
            app.unbindService(serviceConnection)
            isBound = false
        }
        super.onCleared()
    }
}