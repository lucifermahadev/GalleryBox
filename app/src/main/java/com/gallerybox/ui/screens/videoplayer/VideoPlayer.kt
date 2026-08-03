@file:Suppress("unused", "UnsafeOptInUsageError")
@file:OptIn(ExperimentalMaterial3Api::class, kotlinx.coroutines.FlowPreview::class)

package com.gallerybox.ui.screens.videoplayer

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.gallerybox.findActivity
import android.os.Build
import android.provider.Settings
import android.util.LruCache
import android.util.Rational
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.gallerybox.viewmodel.GalleryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.debounce
import java.util.Locale
import kotlin.math.roundToInt

enum class GestureMode { NONE, SCRUB, VOLUME, BRIGHTNESS }
enum class PremiumResizeMode { FIT, ZOOM, FILL, STRETCH, RATIO_16_9, RATIO_4_3 }
enum class PremiumRepeatMode { OFF, ONE, ALL }

class VideoFrameLoader(private val context: Context) {
    private var retriever: MediaMetadataRetriever? = MediaMetadataRetriever()
    private val cache = object : LruCache<Long, Bitmap>(6144) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount / 1024
    }
    private var sourceUri: Uri? = null

    fun setSource(uri: Uri) {
        if (sourceUri == uri) return
        sourceUri = uri
        if (retriever == null) retriever = MediaMetadataRetriever()
        try {
            retriever?.setDataSource(context, uri)
            cache.evictAll()
        } catch (e: Exception) {
            try {
                retriever?.setDataSource(uri.path ?: "")
                cache.evictAll()
            } catch (e2: Exception) {
                release()
            }
        }
    }

    suspend fun getFrame(timeMs: Long, durationMs: Long = 0L, width: Int = 240, height: Int = 135): Bitmap? = withContext(Dispatchers.IO) {
        val safeTime = timeMs.coerceAtLeast(0L)
        val resolution = (durationMs / 500L).coerceIn(60L, 5000L)
        val cacheKey = (safeTime / resolution) * resolution

        cache.get(cacheKey)?.let { return@withContext it }
        return@withContext try {
            val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever?.getScaledFrameAtTime(safeTime * 1000L, MediaMetadataRetriever.OPTION_CLOSEST, width, height)
            } else {
                retriever?.getFrameAtTime(safeTime * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
            }
            frame?.copy(Bitmap.Config.RGB_565, false)?.also { cache.put(cacheKey, it) }
        } catch (e: Exception) {
            null
        }
    }

    fun release() {
        try {
            cache.evictAll()
            retriever?.release()
            retriever = null
        } catch (_: Exception) {}
    }
}

@Stable
class PlayerGestureState {
    var mode by mutableStateOf(GestureMode.NONE)
    var seekPosition by mutableFloatStateOf(0f)
    var isSeeking by mutableStateOf(false)
    var showVolume by mutableStateOf(false)
    var volume by mutableFloatStateOf(1f)
    var showBrightness by mutableStateOf(false)
    var brightness by mutableFloatStateOf(0.5f)
    var gestureText by mutableStateOf("")
}

@Stable
class PlayerProgressState {
    var currentMs by mutableLongStateOf(0L)
    var bufferedMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
}

class PlayerGestureEngine(private val context: Context, private val activity: Activity?, private val player: Player, private val totalDuration: () -> Long) {
    private var accumulatedX = 0f
    private var accumulatedY = 0f
    private val touchSlop = 26f
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat()

    fun onStart(state: PlayerGestureState) {
        state.mode = GestureMode.NONE
        accumulatedX = 0f
        accumulatedY = 0f
        state.volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) / maxVolume
        val currentBright = activity?.window?.attributes?.screenBrightness ?: -1f
        state.brightness = if (currentBright >= 0f) currentBright else {
            try {
                Settings.System.getInt(activity?.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
            } catch (_: Exception) { 0.5f }
        }
    }

    fun onDrag(state: PlayerGestureState, change: PointerInputChange, dragAmount: Offset, width: Float, height: Float) {
        if (width <= 0f || height <= 0f) return
        accumulatedX += dragAmount.x
        accumulatedY += dragAmount.y
        if (state.mode == GestureMode.NONE) {
            val absX = kotlin.math.abs(accumulatedX)
            val absY = kotlin.math.abs(accumulatedY)
            if (absX < touchSlop && absY < touchSlop) return
            state.mode = when {
                absX > absY -> GestureMode.SCRUB
                change.position.x < width / 2f -> GestureMode.BRIGHTNESS
                else -> GestureMode.VOLUME
            }
            if (state.mode == GestureMode.SCRUB) {
                state.isSeeking = true
                state.seekPosition = player.currentPosition.toFloat()
            }
        }

        val delta = dragAmount.y
        when (state.mode) {
            GestureMode.SCRUB -> {
                val duration = totalDuration().coerceAtLeast(1L)
                val velocity = when {
                    duration > 7200000L -> 4f
                    duration > 3600000L -> 3f
                    duration > 1800000L -> 2f
                    else -> 1.2f
                }
                state.seekPosition = (state.seekPosition + (dragAmount.x / width) * duration * velocity).coerceIn(0f, duration.toFloat())
                state.gestureText = formatTime((state.seekPosition / 1000f).roundToInt() * 1000L)
            }
            GestureMode.VOLUME -> {
                state.volume = (state.volume - (delta / height) * 1.25f).coerceIn(0f, 1f)
                val newVolumeInt = (state.volume * maxVolume).roundToInt()
                if (newVolumeInt != audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolumeInt, 0)
                }
                state.showVolume = true
            }
            GestureMode.BRIGHTNESS -> {
                val newBrightness = (state.brightness - (delta / height) * 1.25f).coerceIn(0.01f, 1f)
                activity?.window?.attributes = activity?.window?.attributes?.apply { screenBrightness = newBrightness }
                state.brightness = newBrightness
                state.showBrightness = true
            }
            else -> Unit
        }
        change.consume()
    }

    fun onEnd(state: PlayerGestureState) {
        if (state.mode == GestureMode.SCRUB) {
            player.seekTo(state.seekPosition.toLong())
            state.isSeeking = false
        }
        state.mode = GestureMode.NONE
        state.showVolume = false
        state.showBrightness = false
    }
}

@Composable
fun VideoPlayerScreen(
    initialVideoUrl: String,
    viewModel: GalleryViewModel = hiltViewModel(),
    onBackPress: () -> Unit,
    onLockApp: () -> Unit = {}
) {
    val player = remember(viewModel) { viewModel.getPlayer() }
    var lastOpenedUrl by remember { mutableStateOf("") }

    LaunchedEffect(initialVideoUrl) {
        if (lastOpenedUrl != initialVideoUrl) {
            viewModel.openVideo(initialVideoUrl)
            lastOpenedUrl = initialVideoUrl
        }
    }

    val playlistUrls by viewModel.videoPlaylist.collectAsState()

    VideoPlayerContent(
        player = player,
        initialVideoUrl = initialVideoUrl,
        playlistUrls = playlistUrls,
        viewModel = viewModel,
        onBackPress = onBackPress,
        onLockApp = onLockApp
    )
}

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerContent(
    player: Player,
    initialVideoUrl: String,
    playlistUrls: List<String>,
    viewModel: GalleryViewModel,
    onBackPress: () -> Unit,
    onLockApp: () -> Unit
) {
    val context = LocalContext.current
    val activity = remember { context.findActivity() }
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val prefs = remember { context.getSharedPreferences("media3_prefs", Context.MODE_PRIVATE) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val insetsController = remember { activity?.window?.let { WindowCompat.getInsetsController(it, view) } }

    val gestureState = remember { PlayerGestureState() }
    val gestureEngine = remember(player) { PlayerGestureEngine(context, activity, player) { player.duration.coerceAtLeast(1L) } }
    val frameLoader = remember { VideoFrameLoader(context) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }

    val configuration = LocalConfiguration.current
    var manualRotateOverride by remember { mutableStateOf(false) }
    var originalBrightness by remember { mutableFloatStateOf(-1f) }

    var currentVideoUri by remember { mutableStateOf(initialVideoUrl) }
    var videoSize by remember { mutableStateOf(VideoSize.UNKNOWN) }

    DisposableEffect(activity) {
        originalBrightness = activity?.window?.attributes?.screenBrightness ?: -1f
        onDispose {
            activity?.window?.attributes = activity?.window?.attributes?.apply { screenBrightness = originalBrightness }
        }
    }

    LaunchedEffect(manualRotateOverride) {
        if (manualRotateOverride) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            frameLoader.release()
            previewBitmap = null
            manualRotateOverride = false
        }
    }

    var playbackState by remember { mutableIntStateOf(Player.STATE_IDLE) }
    var isPlaying by remember { mutableStateOf(player.isPlaying) }

    LaunchedEffect(gestureState) {
        snapshotFlow { gestureState.isSeeking to gestureState.seekPosition }
            .debounce(100)
            .collect { (isSeeking, pos) ->
                if (isSeeking) {
                    previewBitmap = frameLoader.getFrame(pos.toLong(), player.duration)
                } else {
                    previewBitmap = null
                }
            }
    }

    val progress = remember { PlayerProgressState() }

    var isLongPressing by remember { mutableStateOf(false) }
    var resizeMode by remember { mutableStateOf(PremiumResizeMode.FIT) }
    var isMirrored by remember { mutableStateOf(false) }
    var currentSpeed by remember { mutableFloatStateOf(prefs.getFloat("speed", 1f)) }
    var currentPitch by remember { mutableFloatStateOf(prefs.getFloat("pitch", 1f)) }

    var sleepTimerMs by remember { mutableStateOf<Long?>(null) }
    var videoFormat by remember { mutableStateOf<Format?>(null) }

    val isHdr = remember(videoFormat) {
        val transfer = videoFormat?.colorInfo?.colorTransfer
        transfer == C.COLOR_TRANSFER_ST2084 || transfer == C.COLOR_TRANSFER_HLG
    }
    var audioDelayMs by remember { mutableFloatStateOf(0f) }

    var autoPlayNext by remember { mutableStateOf(prefs.getBoolean("autoPlayNext", true)) }
    var autoRepeat by remember { mutableStateOf(PremiumRepeatMode.entries[prefs.getInt("autoRepeat", 0)]) }
    var backgroundPlay by remember { mutableStateOf(prefs.getBoolean("backgroundPlay", false)) }

    var showControls by remember { mutableStateOf(true) }
    var isLocked by remember { mutableStateOf(false) }
    var hideJob by remember { mutableStateOf<Job?>(null) }
    var isInPiPMode by remember { mutableStateOf(false) }
    var showDoubleTapText by remember { mutableStateOf("") }
    var doubleTapAlignment by remember { mutableStateOf(Alignment.Center) }
    var doubleTapJob by remember { mutableStateOf<Job?>(null) }
    var showMenuSheet by remember { mutableStateOf(false) }

    LaunchedEffect(autoRepeat) {
        player.repeatMode = when (autoRepeat) {
            PremiumRepeatMode.OFF -> Player.REPEAT_MODE_OFF
            PremiumRepeatMode.ONE -> Player.REPEAT_MODE_ONE
            PremiumRepeatMode.ALL -> Player.REPEAT_MODE_ALL
        }
        prefs.edit().putInt("autoRepeat", autoRepeat.ordinal).apply()
    }

    DisposableEffect(activity) {
        val compActivity = activity as? ComponentActivity
        val listener = Consumer<PictureInPictureModeChangedInfo> { info ->
            isInPiPMode = info.isInPictureInPictureMode
        }
        compActivity?.addOnPictureInPictureModeChangedListener(listener)
        onDispose {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    compActivity?.setPictureInPictureParams(
                        PictureInPictureParams.Builder().setAutoEnterEnabled(false).build()
                    )
                } catch (_: Exception) {}
            }
            compActivity?.removeOnPictureInPictureModeChangedListener(listener)
        }
    }

    // PiP parameters mapping taking into account true video sizes and limits.
    LaunchedEffect(isPlaying, playbackState, videoSize) {
        val compActivity = activity as? ComponentActivity
        if (compActivity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val builder = PictureInPictureParams.Builder()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val shouldAutoEnter = isPlaying && playbackState == Player.STATE_READY
                    builder.setAutoEnterEnabled(shouldAutoEnter)
                }

                if (videoSize.width > 0 && videoSize.height > 0) {
                    val ratio = (videoSize.width.toFloat() / videoSize.height.toFloat())
                    // Picture-in-Picture aspect ratio bounds limit (min 1/2.39, max 2.39)
                    val safeRatio = ratio.coerceIn(0.4185f, 2.389f)

                    val safeWidth = (safeRatio * 10000).toInt()
                    builder.setAspectRatio(Rational(safeWidth, 10000))
                }

                compActivity.setPictureInPictureParams(builder.build())
            } catch (_: Exception) {}
        }
    }

    val resetControlsTimer: () -> Unit = {
        showControls = true
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(3000)
            if (player.isPlaying && !isLocked && !isInPiPMode) {
                showControls = false
            }
        }
    }

    LaunchedEffect(playbackState, isPlaying, showControls) {
        if (playbackState == Player.STATE_READY && isPlaying && showControls) {
            resetControlsTimer()
        }
    }

    val updateSpeedAndPitch: (Float, Float) -> Unit = { speed, pitch ->
        player.playbackParameters = PlaybackParameters(speed, pitch)
    }

    var hasInitializedPlaylist by remember { mutableStateOf(false) }

    LaunchedEffect(initialVideoUrl, playlistUrls) {
        if (hasInitializedPlaylist) return@LaunchedEffect

        if (playlistUrls.isNotEmpty() || playlistUrls.isEmpty()) {
            withContext(Dispatchers.Default) {
                val absoluteIndex = playlistUrls.indexOfFirst { it == initialVideoUrl || Uri.parse(it) == Uri.parse(initialVideoUrl) }

                val items: List<MediaItem>
                val relativeIndex: Int

                if (absoluteIndex != -1) {
                    val fromIndex = maxOf(0, absoluteIndex - 20)
                    val toIndex = minOf(playlistUrls.size, absoluteIndex + 40)
                    items = playlistUrls.subList(fromIndex, toIndex).map { MediaItem.fromUri(Uri.parse(it)) }
                    relativeIndex = absoluteIndex - fromIndex
                    hasInitializedPlaylist = true
                } else {
                    items = listOf(MediaItem.fromUri(Uri.parse(initialVideoUrl)))
                    relativeIndex = 0
                }

                val savedPos = prefs.getLong(initialVideoUrl, 0L)

                withContext(Dispatchers.Main) {
                    try {
                        (player as? ExoPlayer)?.let { exo ->
                            exo.setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(C.USAGE_MEDIA)
                                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                                    .build(),
                                true
                            )
                            exo.setHandleAudioBecomingNoisy(true)
                            exo.volume = 1f
                        }

                        updateSpeedAndPitch(currentSpeed, currentPitch)

                        player.setMediaItems(items)
                        player.prepare()
                        player.seekTo(relativeIndex, savedPos)
                        player.playWhenReady = true
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onVideoSizeChanged(newSize: VideoSize) {
                videoSize = newSize
            }

            override fun onPlaybackStateChanged(state: Int) {
                playbackState = state
                isPlaying = player.isPlaying
                if (state == Player.STATE_READY) {
                    progress.durationMs = player.duration.coerceAtLeast(0L)
                }
                if (state == Player.STATE_ENDED && player.repeatMode == Player.REPEAT_MODE_OFF && autoPlayNext && player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.playWhenReady = true
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentVideoUri = mediaItem?.localConfiguration?.uri?.toString() ?: ""
            }

            override fun onTracksChanged(tracks: Tracks) {
                val selectedVideoFormat = tracks.groups
                    .firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
                    ?.let { group ->
                        (0 until group.length)
                            .firstOrNull { group.isTrackSelected(it) }
                            ?.let { trackIndex -> group.getTrackFormat(trackIndex) }
                    }

                if (selectedVideoFormat != null) {
                    videoFormat = selectedVideoFormat
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(player, lifecycleOwner.lifecycle) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                if (!gestureState.isSeeking) {
                    progress.currentMs = player.currentPosition
                    progress.bufferedMs = player.bufferedPosition

                    if (sleepTimerMs != null) {
                        if (System.currentTimeMillis() >= sleepTimerMs!!) {
                            player.pause()
                            sleepTimerMs = null
                            Toast.makeText(context, "Sleep timer ended", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                delay(if (player.isPlaying) 200L else 500L)
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                viewModel.updatePlaybackState(currentVideoUri, player.currentPosition, currentSpeed, player.playWhenReady)
                prefs.edit().putLong(currentVideoUri, progress.currentMs).apply()
            }
            if (event == Lifecycle.Event.ON_STOP && !backgroundPlay && !isInPiPMode) {
                player.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    LaunchedEffect(showControls, isLocked, isInPiPMode) {
        if (showControls && !isLocked && !isInPiPMode) {
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
        } else {
            insetsController?.hide(WindowInsetsCompat.Type.systemBars())
            insetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    BackHandler { if (!isInPiPMode) onBackPress() }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { viewParams ->
                if (viewParams.player != player) viewParams.player = player
                viewParams.resizeMode = when(resizeMode) {
                    PremiumResizeMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    PremiumResizeMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    PremiumResizeMode.FILL, PremiumResizeMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            modifier = Modifier.fillMaxSize()
                .then(if (isMirrored) Modifier.graphicsLayer { scaleX = -1f } else Modifier)
                .then(when(resizeMode){
                    PremiumResizeMode.RATIO_16_9 -> Modifier.aspectRatio(16f/9f)
                    PremiumResizeMode.RATIO_4_3 -> Modifier.aspectRatio(4f/3f)
                    else -> Modifier
                })
                .pointerInput(isLocked, configuration.orientation) {
                    if (isLocked) {
                        detectTapGestures(
                            onTap = {
                                if (showControls) {
                                    showControls = false
                                } else {
                                    showControls = true
                                    hideJob?.cancel()
                                    hideJob = scope.launch {
                                        delay(3000)
                                        showControls = false
                                    }
                                }
                            }
                        )
                        return@pointerInput
                    }
                    detectTapGestures(
                        onPress = { _ ->
                            val job = scope.launch {
                                delay(400)
                                isLongPressing = true
                                player.playbackParameters = PlaybackParameters((currentSpeed * 2f).coerceAtMost(8f), currentPitch)
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                            tryAwaitRelease()
                            job.cancel()
                            if (isLongPressing) {
                                isLongPressing = false
                                player.playbackParameters = PlaybackParameters(currentSpeed, currentPitch)
                            }
                        },
                        onTap = {
                            if (showControls) {
                                showControls = false
                                hideJob?.cancel()
                            } else {
                                resetControlsTimer()
                            }
                        },
                        onDoubleTap = { offset ->
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            val duration = progress.durationMs
                            val dynamicSeekMs = maxOf(10000L, (duration * 0.01).toLong())
                            if (offset.x < size.width / 2f) {
                                player.seekTo((player.currentPosition - dynamicSeekMs).coerceAtLeast(0))
                                showDoubleTapText = "-${dynamicSeekMs / 1000}s"
                                doubleTapAlignment = Alignment.CenterStart
                            } else {
                                player.seekTo((player.currentPosition + dynamicSeekMs).coerceAtMost(duration))
                                showDoubleTapText = "+${dynamicSeekMs / 1000}s"
                                doubleTapAlignment = Alignment.CenterEnd
                            }
                            doubleTapJob?.cancel()
                            doubleTapJob = scope.launch { delay(600); showDoubleTapText = "" }
                        }
                    )
                }
                .pointerInput(isLocked, configuration.orientation) {
                    if (isLocked) return@pointerInput
                    detectDragGestures(
                        onDragStart = {
                            gestureEngine.onStart(gestureState)
                            scope.launch(Dispatchers.IO) { frameLoader.setSource(Uri.parse(currentVideoUri)) }
                        },
                        onDragEnd = { gestureEngine.onEnd(gestureState) },
                        onDragCancel = {
                            gestureState.mode = GestureMode.NONE
                            gestureState.isSeeking = false
                        }
                    ) { change, dragAmount ->
                        gestureEngine.onDrag(gestureState, change, dragAmount, size.width.toFloat(), size.height.toFloat())
                    }
                }
        )

        if (!isInPiPMode) {
            val currentTitle = player.currentMediaItem?.mediaMetadata?.displayTitle?.toString() ?: player.currentMediaItem?.mediaMetadata?.title?.toString() ?: currentVideoUri.substringAfterLast("/")

            AnimatedVisibility(visible = showControls && !isLocked, enter = fadeIn(), exit = fadeOut()) {
                VideoControlsOverlay(
                    title = currentTitle,
                    isPlaying = isPlaying,
                    playbackState = playbackState,
                    progress = progress,
                    playbackSpeed = if (isLongPressing) (currentSpeed * 2f).coerceAtMost(8f) else currentSpeed,
                    isSeeking = gestureState.isSeeking,
                    previewBitmap = previewBitmap,
                    seekPosition = gestureState.seekPosition,
                    hasNext = player.hasNextMediaItem(),
                    hasPrev = player.hasPreviousMediaItem() || player.currentPosition > 3000L,
                    isHdr = isHdr,
                    resizeMode = resizeMode,
                    onTogglePlay = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (playbackState == Player.STATE_ENDED) {
                            player.seekTo(0)
                            player.play()
                        } else if (isPlaying) {
                            player.pause()
                        } else {
                            player.play()
                        }
                        resetControlsTimer()
                    },
                    onNext = {
                        if (player.hasNextMediaItem()) {
                            player.seekToNextMediaItem()
                            player.playWhenReady = true
                        }
                        resetControlsTimer()
                    },
                    onPrev = {
                        if (player.currentPosition > 3000) {
                            player.seekTo(0)
                        } else if (player.hasPreviousMediaItem()) {
                            player.seekToPreviousMediaItem()
                        }
                        resetControlsTimer()
                    },
                    onSeek = {
                        gestureState.seekPosition = it
                        gestureState.isSeeking = true
                        resetControlsTimer()
                    },
                    onSeekFinished = {
                        player.seekTo(gestureState.seekPosition.toLong())
                        gestureState.isSeeking = false
                        resetControlsTimer()
                    },
                    onBack = onBackPress,
                    onLock = {
                        isLocked = true
                        showControls = false
                    },
                    onRotateToggle = {
                        manualRotateOverride = !manualRotateOverride
                        resetControlsTimer()
                    },
                    onAspectRatioToggle = {
                        val modes = PremiumResizeMode.entries.toTypedArray()
                        resizeMode = modes[(resizeMode.ordinal + 1) % modes.size]
                        resetControlsTimer()
                    },
                    onOpenMenu = {
                        showMenuSheet = true
                        showControls = false
                    }
                )
            }
        }

        AnimatedVisibility(visible = isLocked && showControls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.Center)) {
            IconButton(
                onClick = {
                    isLocked = false
                    showControls = true
                    resetControlsTimer()
                },
                modifier = Modifier.background(Color.Black.copy(0.6f), CircleShape).padding(8.dp)
            ) {
                Icon(Icons.Outlined.LockOpen, "Unlock", tint = Color.White, modifier = Modifier.size(32.dp))
            }
        }

        AnimatedVisibility(visible = showDoubleTapText.isNotEmpty(), enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(doubleTapAlignment).padding(horizontal = 48.dp)) {
            Surface(color = Color.Black.copy(0.6f), shape = RoundedCornerShape(24.dp)) {
                Text(showDoubleTapText, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
            }
        }
    }

    if (showMenuSheet) {
        PlaybackMenuSheet(
            currentSpeed = currentSpeed,
            autoPlayNext = autoPlayNext,
            autoRepeat = autoRepeat,
            backgroundPlay = backgroundPlay,
            sleepTimerActive = sleepTimerMs != null,
            audioDelayMs = audioDelayMs,
            onDismissRequest = {
                showMenuSheet = false
                resetControlsTimer()
            },
            onSpeedChange = {
                currentSpeed = it
                updateSpeedAndPitch(currentSpeed, currentPitch)
                prefs.edit().putFloat("speed", currentSpeed).apply()
            },
            onToggleAutoPlay = {
                autoPlayNext = it
                prefs.edit().putBoolean("autoPlayNext", it).apply()
                if (it) {
                    autoRepeat = PremiumRepeatMode.OFF
                    prefs.edit().putInt("autoRepeat", PremiumRepeatMode.OFF.ordinal).apply()
                    player.repeatMode = Player.REPEAT_MODE_OFF
                }
            },
            onToggleAutoRepeat = {
                autoRepeat = it
                prefs.edit().putInt("autoRepeat", it.ordinal).apply()
                player.repeatMode = when(it) {
                    PremiumRepeatMode.OFF -> Player.REPEAT_MODE_OFF
                    PremiumRepeatMode.ONE -> Player.REPEAT_MODE_ONE
                    PremiumRepeatMode.ALL -> Player.REPEAT_MODE_ALL
                }
                if (it != PremiumRepeatMode.OFF) {
                    autoPlayNext = false
                    prefs.edit().putBoolean("autoPlayNext", false).apply()
                }
            },
            onToggleBackgroundPlay = {
                backgroundPlay = it
                prefs.edit().putBoolean("backgroundPlay", it).apply()
            },
            onSetSleepTimer = { minutes ->
                sleepTimerMs = System.currentTimeMillis() + (minutes * 60 * 1000L)
                Toast.makeText(context, "Timer set for $minutes min", Toast.LENGTH_SHORT).show()
                showMenuSheet = false
                resetControlsTimer()
            },
            onCancelSleepTimer = {
                sleepTimerMs = null
                Toast.makeText(context, "Timer canceled", Toast.LENGTH_SHORT).show()
            },
            onSetAudioDelay = { delayOffset ->
                audioDelayMs = delayOffset
                Toast.makeText(context, "Audio Delay: ${delayOffset.toInt()} ms", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

@Composable
fun VideoControlsOverlay(
    title: String,
    isPlaying: Boolean,
    playbackState: Int,
    progress: PlayerProgressState,
    playbackSpeed: Float,
    isSeeking: Boolean,
    previewBitmap: Bitmap?,
    seekPosition: Float,
    hasNext: Boolean,
    hasPrev: Boolean,
    isHdr: Boolean,
    resizeMode: PremiumResizeMode,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onSeek: (Float) -> Unit,
    onSeekFinished: () -> Unit,
    onBack: () -> Unit,
    onLock: () -> Unit,
    onRotateToggle: () -> Unit,
    onAspectRatioToggle: () -> Unit,
    onOpenMenu: () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(120.dp).align(Alignment.TopCenter).background(Brush.verticalGradient(listOf(Color.Black.copy(0.65f), Color.Transparent))))

        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) }

            Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isHdr) {
                        Surface(color = Color.White.copy(alpha = 0.2f), shape = RoundedCornerShape(4.dp), modifier = Modifier.padding(end = 6.dp)) {
                            Text("HDR", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                        }
                    }
                    Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            IconButton(onClick = onOpenMenu) { Icon(Icons.Default.MoreVert, null, tint = Color.White) }
        }

        AnimatedVisibility(visible = playbackSpeed > 1f && isPlaying, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut(), modifier = Modifier.align(Alignment.TopCenter).padding(top = 100.dp)) {
            Surface(color = Color.Black.copy(0.6f), shape = CircleShape) {
                Text("▶▶ ${playbackSpeed}x Speed", color = Color.White, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }

        Box(Modifier.fillMaxWidth().height(260.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.65f)))))

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 16.dp)) {

            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onAspectRatioToggle) {
                    Icon(Icons.Outlined.AspectRatio, contentDescription = "Aspect Ratio", tint = Color.White)
                }
            }

            PlayerTimelineSection(
                progress = progress,
                isSeeking = isSeeking,
                previewBitmap = previewBitmap,
                seekPosition = seekPosition,
                onSeek = onSeek,
                onSeekFinished = onSeekFinished
            )

            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onLock) {
                    Icon(Icons.Outlined.Lock, null, tint = Color.White, modifier = Modifier.size(28.dp))
                }

                IconButton(onClick = onPrev, enabled = hasPrev) {
                    Icon(Icons.Rounded.SkipPrevious, null, tint = if (hasPrev) Color.White else Color.White.copy(0.3f), modifier = Modifier.size(36.dp))
                }

                Surface(modifier = Modifier.size(64.dp), shape = CircleShape, color = Color.White.copy(alpha = 0.15f), tonalElevation = 0.dp, shadowElevation = 0.dp, onClick = onTogglePlay) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        val playIcon = if (playbackState == Player.STATE_ENDED) Icons.Rounded.Replay else if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow
                        Icon(imageVector = playIcon, contentDescription = "Play/Pause", tint = Color.White, modifier = Modifier.size(42.dp))
                    }
                }

                IconButton(onClick = onNext, enabled = hasNext) {
                    Icon(Icons.Rounded.SkipNext, null, tint = if (hasNext) Color.White else Color.White.copy(0.3f), modifier = Modifier.size(36.dp))
                }

                IconButton(onClick = onRotateToggle) {
                    Icon(Icons.Outlined.ScreenRotation, null, tint = Color.White, modifier = Modifier.size(28.dp))
                }
            }
        }
    }
}

@Composable
private fun PlayerTimelineSection(
    progress: PlayerProgressState,
    isSeeking: Boolean,
    previewBitmap: Bitmap?,
    seekPosition: Float,
    onSeek: (Float) -> Unit,
    onSeekFinished: () -> Unit
) {
    var thumbXOffset by remember { mutableFloatStateOf(0f) }

    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(60.dp)) {
            if (isSeeking && previewBitmap != null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.align(Alignment.BottomStart)
                        .offset(x = with(LocalDensity.current) { thumbXOffset.toDp() } - 60.dp, y = (-20).dp)
                ) {
                    Image(bitmap = previewBitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.size(120.dp, 68.dp).clip(RoundedCornerShape(8.dp)))
                    Text(formatTime(seekPosition.toLong()), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
                }
            }
            SamsungSeekBar(
                current = if (isSeeking) seekPosition else progress.currentMs.toFloat(),
                buffered = progress.bufferedMs.toFloat(),
                total = maxOf(1f, progress.durationMs.toFloat()),
                modifier = Modifier.fillMaxWidth().height(24.dp).align(Alignment.BottomCenter),
                onSeek = { pos, xOffset -> thumbXOffset = xOffset; onSeek(pos) },
                onSeekFinished = onSeekFinished
            )
        }

        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(progress.currentMs), color = Color.White, style = MaterialTheme.typography.labelMedium)
            Text(formatTime(progress.durationMs), color = Color.White.copy(0.7f), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun SamsungSeekBar(current: Float, buffered: Float, total: Float, modifier: Modifier, onSeek: (Float, Float) -> Unit, onSeekFinished: () -> Unit) {
    var width by remember { mutableFloatStateOf(0f) }
    val safeTotal = total.coerceAtLeast(1f)

    Canvas(modifier
        .pointerInput(safeTotal) {
            detectTapGestures { tapOffset ->
                if (width <= 0f) return@detectTapGestures
                val safeX = tapOffset.x.coerceIn(0f, width)
                val p = (safeX / width) * safeTotal
                onSeek(p, safeX)
                onSeekFinished()
            }
        }
        .pointerInput(safeTotal) {
            detectDragGestures(onDragEnd = onSeekFinished) { dragChange, _ ->
                if (width <= 0f) return@detectDragGestures
                val safeX = dragChange.position.x.coerceIn(0f, width)
                val p = (safeX / width) * safeTotal
                onSeek(p, safeX)
            }
        }
    ) {
        width = size.width
        val h = 3.dp.toPx()
        val y = size.height / 2
        val bW = (buffered / safeTotal).coerceIn(0f, 1f) * width
        val cW = (current / safeTotal).coerceIn(0f, 1f) * width

        drawRoundRect(Color.White.copy(0.25f), Offset(0f, y - h/2), Size(width, h), CornerRadius(h/2))
        drawRoundRect(Color.White.copy(0.5f), Offset(0f, y - h/2), Size(bW, h), CornerRadius(h/2))
        drawRoundRect(Color.White, Offset(0f, y - h/2), Size(cW, h), CornerRadius(h/2))
        drawCircle(Color.White, radius = 6.dp.toPx(), center = Offset(cW, y))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaybackMenuSheet(
    currentSpeed: Float,
    autoPlayNext: Boolean,
    autoRepeat: PremiumRepeatMode,
    backgroundPlay: Boolean,
    sleepTimerActive: Boolean,
    audioDelayMs: Float,
    onDismissRequest: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onToggleAutoPlay: (Boolean) -> Unit,
    onToggleAutoRepeat: (PremiumRepeatMode) -> Unit,
    onToggleBackgroundPlay: (Boolean) -> Unit,
    onSetSleepTimer: (Int) -> Unit,
    onCancelSleepTimer: () -> Unit,
    onSetAudioDelay: (Float) -> Unit
) {
    var currentSubMenu by remember { mutableStateOf<String?>(null) }

    @kotlin.OptIn(ExperimentalMaterial3Api::class)
    ModalBottomSheet(onDismissRequest = onDismissRequest) {
        AnimatedContent(targetState = currentSubMenu, label = "MenuTransition") { subMenu ->
            when (subMenu) {
                "SPEED" -> {
                    LazyColumn(Modifier.padding(24.dp)) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { currentSubMenu = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                                Text("Playback Speed", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.titleLarge)
                            }
                            Spacer(Modifier.height(16.dp))
                        }
                        val speeds = listOf(0.25f, 0.50f, 0.75f, 1f, 1.25f, 1.50f, 1.75f, 2f, 3f, 4f, 6f, 8f)
                        items(speeds) { speed ->
                            ListItem(
                                headlineContent = { Text("${speed}x") },
                                trailingContent = { if (speed == currentSpeed) Icon(Icons.Default.Check, null) },
                                modifier = Modifier.clickable {
                                    onSpeedChange(speed)
                                    currentSubMenu = null
                                }
                            )
                        }
                    }
                }
                "SLEEP_TIMER" -> {
                    LazyColumn(Modifier.padding(24.dp)) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { currentSubMenu = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                                Text("Sleep Timer", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.titleLarge)
                            }
                            Spacer(Modifier.height(16.dp))
                        }
                        if (sleepTimerActive) {
                            item {
                                ListItem(
                                    headlineContent = { Text("Cancel Timer", color = MaterialTheme.colorScheme.error) },
                                    leadingContent = { Icon(Icons.Rounded.TimerOff, null, tint = MaterialTheme.colorScheme.error) },
                                    modifier = Modifier.clickable {
                                        onCancelSleepTimer()
                                        currentSubMenu = null
                                    }
                                )
                            }
                        }
                        val times = listOf(15, 30, 45, 60, 90, 120)
                        items(times) { time ->
                            ListItem(
                                headlineContent = { Text("$time Minutes") },
                                leadingContent = { Icon(Icons.Rounded.Timer, null) },
                                modifier = Modifier.clickable { onSetSleepTimer(time) }
                            )
                        }
                    }
                }
                "AUDIO_DELAY" -> {
                    Column(Modifier.padding(24.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { currentSubMenu = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                            Text("Audio Delay Sync", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.titleLarge)
                        }
                        Spacer(Modifier.height(32.dp))
                        Text("Offset: ${audioDelayMs.toInt()} ms", fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterHorizontally))
                        Slider(
                            value = audioDelayMs,
                            onValueChange = { onSetAudioDelay(it) },
                            valueRange = -1000f..1000f,
                            steps = 39,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("-1000ms")
                            Text("+1000ms")
                        }
                    }
                }
                else -> {
                    LazyColumn(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp, top = 8.dp)) {

                        item { Text("Playback", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 8.dp)) }
                        item {
                            ListItem(
                                headlineContent = { Text("Playback Speed") },
                                supportingContent = { Text("${currentSpeed}x") },
                                leadingContent = { Icon(Icons.Outlined.Speed, null) },
                                modifier = Modifier.clickable { currentSubMenu = "SPEED" }
                            )
                        }
                        item {
                            ListItem(
                                headlineContent = { Text("Sleep Timer") },
                                supportingContent = { if (sleepTimerActive) Text("Active", color = MaterialTheme.colorScheme.primary) else null },
                                leadingContent = { Icon(Icons.Rounded.Timer, null) },
                                modifier = Modifier.clickable { currentSubMenu = "SLEEP_TIMER" }
                            )
                        }
                        item {
                            ListItem(
                                headlineContent = { Text("Audio Delay Sync") },
                                leadingContent = { Icon(Icons.Rounded.Sync, null) },
                                modifier = Modifier.clickable { currentSubMenu = "AUDIO_DELAY" }
                            )
                        }
                        item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

                        item { Text("Playback Controls", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 8.dp)) }
                        item {
                            ListItem(
                                headlineContent = { Text("Repeat Mode") },
                                supportingContent = { Text(autoRepeat.name) },
                                leadingContent = { Icon(Icons.Rounded.Repeat, null) },
                                modifier = Modifier.clickable {
                                    val entries = PremiumRepeatMode.entries.toTypedArray()
                                    onToggleAutoRepeat(entries[(autoRepeat.ordinal + 1) % entries.size])
                                }
                            )
                        }
                        item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

                        item {
                            ListItem(
                                headlineContent = { Text("Auto Play Next") },
                                trailingContent = { Switch(checked = autoPlayNext, onCheckedChange = onToggleAutoPlay) }
                            )
                        }
                        item {
                            ListItem(
                                headlineContent = { Text("Background Play") },
                                trailingContent = { Switch(checked = backgroundPlay, onCheckedChange = onToggleBackgroundPlay) }
                            )
                        }
                    }
                }
            }
        }
    }
}

fun formatTime(ms: Long): String {
    val ts = ms / 1000
    val s = ts % 60
    val m = (ts / 60) % 60
    val h = ts / 3600
    return if (h > 0) String.format(Locale.US, "%02d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
}