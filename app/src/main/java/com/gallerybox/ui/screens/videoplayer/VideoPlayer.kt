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
import android.os.Build
import android.provider.Settings
import android.util.LruCache
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.*
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
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
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
    private val cache = object : LruCache<Long, Bitmap>(20480) { override fun sizeOf(key: Long, value: Bitmap) = value.byteCount / 1024 }
    private var sourceUri: Uri? = null

    fun setSource(uri: Uri) {
        if (sourceUri == uri) return
        sourceUri = uri
        if (retriever == null) retriever = MediaMetadataRetriever()
        try { retriever?.setDataSource(context, uri); cache.evictAll() } catch (e: Exception) { try { retriever?.setDataSource(uri.path ?: ""); cache.evictAll() } catch (e2: Exception) { release() } }
    }

    suspend fun getFrame(timeMs: Long, durationMs: Long = 0L, width: Int = 320, height: Int = 180): Bitmap? = withContext(Dispatchers.IO) {
        val safeTime = timeMs.coerceAtLeast(0L)
        val resolution = (durationMs / 500L).coerceIn(60L, 5000L)
        val cacheKey = (safeTime / resolution) * resolution

        cache.get(cacheKey)?.let { return@withContext it }
        return@withContext try {
            val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) retriever?.getScaledFrameAtTime(safeTime * 1000L, MediaMetadataRetriever.OPTION_CLOSEST, width, height) else retriever?.getFrameAtTime(safeTime * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
            frame?.copy(Bitmap.Config.ARGB_8888, false)?.also { cache.put(cacheKey, it) }
        } catch (e: Exception) { null }
    }

    fun release() { try { cache.evictAll(); retriever?.release(); retriever = null } catch (_: Exception) {} }
}

class PlayerGestureState {
    var mode by mutableStateOf(GestureMode.NONE); var seekPosition by mutableFloatStateOf(0f); var isSeeking by mutableStateOf(false)
    var showVolume by mutableStateOf(false); var volume by mutableFloatStateOf(1f)
    var showBrightness by mutableStateOf(false); var brightness by mutableFloatStateOf(0.5f); var gestureText by mutableStateOf("")
}

class PlayerGestureEngine(private val context: Context, private val activity: Activity?, private val player: Player, private val totalDuration: () -> Long) {
    private var accumulatedX = 0f; private var accumulatedY = 0f; private val touchSlop = 26f
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat()

    fun onStart(state: PlayerGestureState) {
        state.mode = GestureMode.NONE; accumulatedX = 0f; accumulatedY = 0f
        state.volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) / maxVolume
        val currentBright = activity?.window?.attributes?.screenBrightness ?: -1f
        state.brightness = if (currentBright >= 0f) currentBright else { try { Settings.System.getInt(activity?.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f } catch (_: Exception) { 0.5f } }
    }

    fun onDrag(state: PlayerGestureState, change: PointerInputChange, dragAmount: Offset, width: Float, height: Float) {
        if (width <= 0f || height <= 0f) return
        accumulatedX += dragAmount.x; accumulatedY += dragAmount.y
        if (state.mode == GestureMode.NONE) {
            val absX = kotlin.math.abs(accumulatedX); val absY = kotlin.math.abs(accumulatedY)
            if (absX < touchSlop && absY < touchSlop) return
            state.mode = when { absX > absY -> GestureMode.SCRUB; change.position.x < width / 2f -> GestureMode.BRIGHTNESS; else -> GestureMode.VOLUME }
            if (state.mode == GestureMode.SCRUB) { state.isSeeking = true; state.seekPosition = player.currentPosition.toFloat() }
        }
        val delta = dragAmount.y
        when (state.mode) {
            GestureMode.SCRUB -> {
                val duration = totalDuration().coerceAtLeast(1L)
                val velocity = when { duration > 7200000L -> 4f; duration > 3600000L -> 3f; duration > 1800000L -> 2f; else -> 1.2f }
                state.seekPosition = (state.seekPosition + (dragAmount.x / width) * duration * velocity).coerceIn(0f, duration.toFloat())
                state.gestureText = formatTime((state.seekPosition / 1000f).roundToInt() * 1000L)
            }
            GestureMode.VOLUME -> {
                state.volume = (state.volume - (delta / height) * 1.25f).coerceIn(0f, 1f)
                val newVolumeInt = (state.volume * maxVolume).roundToInt()
                if (newVolumeInt != audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolumeInt, 0) }
                state.showVolume = true
            }
            GestureMode.BRIGHTNESS -> {
                val newBrightness = (state.brightness - (delta / height) * 1.25f).coerceIn(0.01f, 1f)
                activity?.window?.attributes = activity?.window?.attributes?.apply { screenBrightness = newBrightness }
                state.brightness = newBrightness; state.showBrightness = true
            }
            else -> Unit
        }
        change.consume()
    }
    fun onEnd(state: PlayerGestureState) { if (state.mode == GestureMode.SCRUB) { player.seekTo(state.seekPosition.toLong()); state.isSeeking = false }; state.mode = GestureMode.NONE; state.showVolume = false; state.showBrightness = false }
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
    val startIndex by viewModel.currentVideoIndex.collectAsState()

    VideoPlayerContent(
        player = player,
        initialVideoUrl = initialVideoUrl,
        playlistUrls = playlistUrls,
        startIndex = startIndex,
        viewModel = viewModel,
        onBackPress = onBackPress,
        onLockApp = onLockApp
    )
}
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerContent(player: Player, initialVideoUrl: String, playlistUrls: List<String>, startIndex: Int, viewModel: GalleryViewModel, onBackPress: () -> Unit, onLockApp: () -> Unit) {
    val context = LocalContext.current; val activity = remember { context.findActivity() }; val view = LocalView.current; val scope = rememberCoroutineScope(); val haptic = LocalHapticFeedback.current; val prefs = remember { context.getSharedPreferences("media3_prefs", Context.MODE_PRIVATE) }; val lifecycleOwner = LocalLifecycleOwner.current; val insetsController = remember { activity?.window?.let { WindowCompat.getInsetsController(it, view) } }
    val gestureState = remember { PlayerGestureState() }; val gestureEngine = remember(player) { PlayerGestureEngine(context, activity, player) { player.duration.coerceAtLeast(1L) } }; val frameLoader = remember { VideoFrameLoader(context) }; var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val configuration = LocalConfiguration.current; val isSystemLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE; var manualRotateOverride by remember { mutableStateOf(false) }; val effectivelyRotated = isSystemLandscape || manualRotateOverride; var originalBrightness by remember { mutableFloatStateOf(-1f) }

    var currentVideoUri by remember { mutableStateOf(initialVideoUrl) }

    DisposableEffect(activity) { originalBrightness = activity?.window?.attributes?.screenBrightness ?: -1f; onDispose { activity?.window?.attributes = activity?.window?.attributes?.apply { screenBrightness = originalBrightness } } }
    LaunchedEffect(manualRotateOverride) { if (manualRotateOverride) { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE } else { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED } }
    DisposableEffect(Unit) { onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED; frameLoader.release(); previewBitmap = null; manualRotateOverride = false } }

    // Optimization 3: Lazy FrameLoader Initialization
    LaunchedEffect(gestureState.isSeeking, currentVideoUri) {
        if (gestureState.isSeeking && currentVideoUri.isNotBlank()) {
            withContext(Dispatchers.IO) {
                frameLoader.setSource(Uri.parse(currentVideoUri))
            }
        }
    }

    LaunchedEffect(gestureState) { snapshotFlow { gestureState.isSeeking to gestureState.seekPosition }.debounce(80).collect { (isSeeking, pos) -> if (isSeeking) { previewBitmap = frameLoader.getFrame(pos.toLong(), player.duration) } else { previewBitmap = null } } }

    var playbackState by remember { mutableIntStateOf(Player.STATE_IDLE) }; var isPlaying by remember { mutableStateOf(player.isPlaying) }; var currentTimeState by remember { mutableLongStateOf(0L) }; var bufferedPositionState by remember { mutableLongStateOf(0L) }; var totalDuration by remember { mutableLongStateOf(0L) }
    var isLongPressing by remember { mutableStateOf(false) }; var resizeMode by remember { mutableStateOf(PremiumResizeMode.FIT) }; var isMirrored by remember { mutableStateOf(false) }; var currentSpeed by remember { mutableFloatStateOf(prefs.getFloat("speed", 1f)) }; var currentPitch by remember { mutableFloatStateOf(prefs.getFloat("pitch", 1f)) }

    var autoPlayNext by remember { mutableStateOf(prefs.getBoolean("autoPlayNext", true)) }
    var autoRepeat by remember { mutableStateOf(PremiumRepeatMode.entries[prefs.getInt("autoRepeat", 0)]) }
    var backgroundPlay by remember { mutableStateOf(prefs.getBoolean("backgroundPlay", false)) }

    var showControls by remember { mutableStateOf(true) }; var isLocked by remember { mutableStateOf(false) }; var hideJob by remember { mutableStateOf<Job?>(null) }; var isInPiPMode by remember { mutableStateOf(false) }
    var showDoubleTapText by remember { mutableStateOf("") }; var doubleTapAlignment by remember { mutableStateOf(Alignment.Center) }; var doubleTapJob by remember { mutableStateOf<Job?>(null) }; var showMenuSheet by remember { mutableStateOf(false) }
    var controlsTopBound by remember { mutableFloatStateOf(Float.MAX_VALUE) }

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
        val l = Consumer<PictureInPictureModeChangedInfo> { info -> isInPiPMode = info.isInPictureInPictureMode }
        compActivity?.addOnPictureInPictureModeChangedListener(l)
        onDispose {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try { compActivity?.setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build()) } catch (_: Exception) {}
            }
            compActivity?.removeOnPictureInPictureModeChangedListener(l)
        }
    }

    LaunchedEffect(isPlaying, playbackState) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val shouldAutoEnter = isPlaying && playbackState == Player.STATE_READY
                (activity as? ComponentActivity)?.setPictureInPictureParams(
                    PictureInPictureParams.Builder()
                        .setAutoEnterEnabled(shouldAutoEnter)
                        .build()
                )
            } catch (_: Exception) {}
        }
    }

    val triggerHideJob: (Boolean) -> Unit = { c -> hideJob?.cancel(); hideJob = scope.launch { delay(2500); if (c) showControls = false } }
    LaunchedEffect(playbackState, showControls) { if (playbackState == Player.STATE_READY && player.isPlaying && showControls) triggerHideJob(true) }
    val updateSpeedAndPitch: (Float, Float) -> Unit = { s, p -> player.playbackParameters = PlaybackParameters(s, p) }

    // Optimization 1, 5 & 7: Optimized Playlist Reloading
    LaunchedEffect(playlistUrls, initialVideoUrl) {
        if (playlistUrls.isNotEmpty()) {
            try {
                (player as? ExoPlayer)?.let { exo ->
                    exo.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                    exo.setHandleAudioBecomingNoisy(true)
                }
                updateSpeedAndPitch(currentSpeed, currentPitch)

                val actualIndex = playlistUrls.indexOf(initialVideoUrl).takeIf { it >= 0 } ?: startIndex
                val savedPos = prefs.getLong(initialVideoUrl, 0L)

                // Only rebuild the playlist if it actually changed
                if (player.mediaItemCount != playlistUrls.size) {
                    val items = playlistUrls.map { MediaItem.fromUri(Uri.parse(it)) }
                    player.setMediaItems(items)
                    player.prepare()
                }

                // Avoid stopping/clearing the player, just seek seamlessly
                player.seekTo(actualIndex, savedPos)
                player.playWhenReady = true
            } catch (_: Exception) {}
        }
    }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(p: Boolean) { isPlaying = p }
            override fun onPlaybackStateChanged(s: Int) {
                playbackState = s
                isPlaying = player.isPlaying
                if (s == Player.STATE_READY) totalDuration = player.duration.coerceAtLeast(0L)
                if (s == Player.STATE_ENDED && player.repeatMode == Player.REPEAT_MODE_OFF && autoPlayNext && player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.playWhenReady = true
                }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentVideoUri = mediaItem?.localConfiguration?.uri?.toString() ?: ""
            }
        }
        player.addListener(l); onDispose { player.removeListener(l) }
    }

    LaunchedEffect(player, lifecycleOwner.lifecycle) { lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { while (isActive) { if (!gestureState.isSeeking) { currentTimeState = player.currentPosition; bufferedPositionState = player.bufferedPosition }; delay(if (player.isPlaying) 500 else 1000) } } }

    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_PAUSE) {
                viewModel.updatePlaybackState(currentVideoUri, player.currentPosition, currentSpeed, player.playWhenReady)
                prefs.edit().putLong(currentVideoUri, currentTimeState).apply()
            }
            if (e == Lifecycle.Event.ON_STOP && !backgroundPlay && !isInPiPMode) {
                player.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs); activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs); activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); insetsController?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    LaunchedEffect(showControls, isLocked, isInPiPMode) { if (showControls && !isLocked && !isInPiPMode) insetsController?.show(WindowInsetsCompat.Type.systemBars()) else { insetsController?.hide(WindowInsetsCompat.Type.systemBars()); insetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE } }
    BackHandler { if (!isInPiPMode) onBackPress() }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx -> PlayerView(ctx).apply { useController = false; setShutterBackgroundColor(android.graphics.Color.BLACK) } },
            update = { v -> if (v.player != player) v.player = player; v.resizeMode = when(resizeMode) { PremiumResizeMode.FIT->AspectRatioFrameLayout.RESIZE_MODE_FIT; PremiumResizeMode.ZOOM->AspectRatioFrameLayout.RESIZE_MODE_ZOOM; PremiumResizeMode.FILL,PremiumResizeMode.STRETCH->AspectRatioFrameLayout.RESIZE_MODE_FILL; else->AspectRatioFrameLayout.RESIZE_MODE_FIT } },
            modifier = Modifier.fillMaxSize().then(if (effectivelyRotated) Modifier.aspectRatio(16f / 9f) else Modifier).then(if (isMirrored) Modifier.graphicsLayer { scaleX = -1f } else Modifier).then(when(resizeMode){ PremiumResizeMode.RATIO_16_9->Modifier.aspectRatio(16f/9f); PremiumResizeMode.RATIO_4_3->Modifier.aspectRatio(4f/3f); else->Modifier })
                .pointerInput(isLocked) {
                    if (isLocked) return@pointerInput
                    detectTapGestures(
                        onPress = { _ ->
                            val job = scope.launch { delay(400); isLongPressing = true; player.playbackParameters = PlaybackParameters((currentSpeed * 2f).coerceAtMost(8f), currentPitch); haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
                            tryAwaitRelease(); job.cancel(); if (isLongPressing) { isLongPressing = false; player.playbackParameters = PlaybackParameters(currentSpeed, currentPitch) }
                        },
                        onTap = { offset -> if (offset.y >= controlsTopBound) return@detectTapGestures; showControls = !showControls; if (showControls && playbackState != Player.STATE_ENDED) triggerHideJob(isPlaying) },
                        onDoubleTap = { offset ->
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            val dynamicSeekMs = maxOf(10000L, (totalDuration * 0.01).toLong())
                            if (offset.x < size.width / 2f) { player.seekTo((player.currentPosition - dynamicSeekMs).coerceAtLeast(0)); showDoubleTapText = "-${dynamicSeekMs / 1000}s"; doubleTapAlignment = Alignment.CenterStart }
                            else { player.seekTo((player.currentPosition + dynamicSeekMs).coerceAtMost(totalDuration)); showDoubleTapText = "+${dynamicSeekMs / 1000}s"; doubleTapAlignment = Alignment.CenterEnd }
                            doubleTapJob?.cancel(); doubleTapJob = scope.launch { delay(600); showDoubleTapText = "" }
                        }
                    )
                }
                .pointerInput(isLocked) { if (isLocked) return@pointerInput; detectDragGestures(onDragStart = { gestureEngine.onStart(gestureState) }, onDragEnd = { gestureEngine.onEnd(gestureState) }, onDragCancel = { gestureState.mode = GestureMode.NONE; gestureState.isSeeking = false }) { change, dragAmount -> gestureEngine.onDrag(gestureState, change, dragAmount, size.width.toFloat(), size.height.toFloat()) } }
        )
        if (!isInPiPMode) {
            val currentTitle = player.currentMediaItem?.mediaMetadata?.displayTitle?.toString() ?: player.currentMediaItem?.mediaMetadata?.title?.toString() ?: currentVideoUri.substringAfterLast("/")

            androidx.compose.animation.AnimatedVisibility(visible = showControls && !isLocked, enter = fadeIn(), exit = fadeOut()) {
                VideoControlsOverlay(
                    title = currentTitle, isPlaying = isPlaying, playbackState = playbackState, currentTimeStr = formatTime(currentTimeState), totalTimeStr = formatTime(totalDuration), sliderValue = currentTimeState.toFloat(), bufferedValue = bufferedPositionState.toFloat(), totalDuration = maxOf(1f, totalDuration.toFloat()), isLocked = isLocked, playbackSpeed = if (isLongPressing) (currentSpeed * 2f).coerceAtMost(8f) else currentSpeed, isSeeking = gestureState.isSeeking, previewBitmap = previewBitmap, seekPosition = gestureState.seekPosition,
                    hasNext = player.hasNextMediaItem(),
                    hasPrev = player.hasPreviousMediaItem() || player.currentPosition > 3000L,
                    currentRepeatMode = autoRepeat,
                    onTogglePlay = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); if (isPlaying) player.pause() else player.play(); triggerHideJob(isPlaying) },
                    onNext = {
                        if (player.hasNextMediaItem()) {
                            player.seekToNextMediaItem()
                            player.playWhenReady = true
                        }
                        triggerHideJob(isPlaying)
                    },
                    onPrev = {
                        if (player.currentPosition > 3000) {
                            player.seekTo(0)
                        } else if (player.hasPreviousMediaItem()) {
                            player.seekToPreviousMediaItem()
                        }
                        triggerHideJob(isPlaying)
                    },
                    onSeek = { gestureState.seekPosition = it; gestureState.isSeeking = true }, onSeekFinished = { player.seekTo(gestureState.seekPosition.toLong()); gestureState.isSeeking = false; triggerHideJob(isPlaying) }, onBack = onBackPress, onLock = { isLocked = true; showControls = false }, onResizeToggle = { val m = PremiumResizeMode.entries.toTypedArray(); resizeMode = m[(resizeMode.ordinal + 1) % m.size]; triggerHideJob(isPlaying) }, onOrientationToggle = { manualRotateOverride = !manualRotateOverride }, onOpenMenu = { showMenuSheet = true; showControls = false },
                    onPipClick = {
                        if (isPlaying && playbackState == Player.STATE_READY) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                try {
                                    val videoSize = player.videoSize; val ratio = if (videoSize.width > 0 && videoSize.height > 0) Rational(videoSize.width, videoSize.height) else Rational(16, 9)
                                    val b = PictureInPictureParams.Builder().setAspectRatio(ratio)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) b.setSeamlessResizeEnabled(true)
                                    activity?.enterPictureInPictureMode(b.build())
                                } catch (_: Exception) {}
                            }
                        }
                    },
                    onRepeatToggle = {
                        val entries = PremiumRepeatMode.entries.toTypedArray()
                        autoRepeat = entries[(autoRepeat.ordinal + 1) % entries.size]
                    },
                    onSpeedToggle = {
                        showMenuSheet = true
                        showControls = false
                    },
                    onControlsPositioned = { controlsTopBound = it }
                )
            }
        }
        if (isLocked) IconButton(onClick = { isLocked = false; showControls = true; triggerHideJob(isPlaying) }, Modifier.align(Alignment.CenterStart).padding(start = 24.dp).background(Color.Black.copy(0.4f), CircleShape)) { Icon(Icons.Outlined.Lock, "Unlock", tint = Color.White) }

        AnimatedVisibility(visible = showDoubleTapText.isNotEmpty(), enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(doubleTapAlignment).padding(horizontal = 48.dp)) {
            Surface(color = Color.Black.copy(0.6f), shape = RoundedCornerShape(24.dp)) { Text(showDoubleTapText, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) }
        }
    }

    if (showMenuSheet) {
        PlaybackMenuSheet(
            currentSpeed = currentSpeed, autoPlayNext = autoPlayNext, autoRepeat = autoRepeat, backgroundPlay = backgroundPlay,
            onDismissRequest = { showMenuSheet = false; showControls = true; triggerHideJob(isPlaying) },
            onSpeedChange = { currentSpeed = it; updateSpeedAndPitch(currentSpeed, currentPitch); prefs.edit().putFloat("speed", currentSpeed).apply() },
            onToggleAutoPlay = { autoPlayNext = it; prefs.edit().putBoolean("autoPlayNext", it).apply() },
            onToggleAutoRepeat = { autoRepeat = it },
            onToggleBackgroundPlay = { backgroundPlay = it; prefs.edit().putBoolean("backgroundPlay", it).apply() },
            onShare = { /* Handle Share */ }, onDetails = { /* Handle Details */ }
        )
    }
}

@Composable fun VideoControlsOverlay(title: String, isPlaying: Boolean, playbackState: Int, currentTimeStr: String, totalTimeStr: String, sliderValue: Float, bufferedValue: Float, totalDuration: Float, isLocked: Boolean, playbackSpeed: Float, isSeeking: Boolean, previewBitmap: Bitmap?, seekPosition: Float, hasNext: Boolean, hasPrev: Boolean, currentRepeatMode: PremiumRepeatMode, onTogglePlay: () -> Unit, onNext: () -> Unit, onPrev: () -> Unit, onSeek: (Float) -> Unit, onSeekFinished: () -> Unit, onBack: () -> Unit, onLock: () -> Unit, onResizeToggle: () -> Unit, onOrientationToggle: () -> Unit, onOpenMenu: () -> Unit, onPipClick: () -> Unit, onRepeatToggle: () -> Unit, onSpeedToggle: () -> Unit, onControlsPositioned: (Float) -> Unit) {
    var thumbXOffset by remember { mutableFloatStateOf(0f) }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(120.dp).align(Alignment.TopCenter).background(Brush.verticalGradient(listOf(Color.Black.copy(0.65f), Color.Transparent))))
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) }
            Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
            IconButton(onClick = onSpeedToggle) { Icon(Icons.Outlined.Speed, null, tint = Color.White) }
            AnimatedVisibility(visible = isPlaying) {
                IconButton(onClick = onPipClick) { Icon(Icons.Outlined.PictureInPictureAlt, null, tint = Color.White) }
            }
            IconButton(onClick = onOpenMenu) { Icon(Icons.Outlined.MoreVert, null, tint = Color.White) }
        }
        androidx.compose.animation.AnimatedVisibility(visible = playbackSpeed > 1f && isPlaying, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut(), modifier = Modifier.align(Alignment.TopCenter).padding(top = 100.dp)) { Surface(color = Color.Black.copy(0.6f), shape = CircleShape) { Text("▶▶ ${playbackSpeed}x Speed", color = Color.White, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) } }
        Box(Modifier.fillMaxWidth().height(220.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.65f)))).onGloballyPositioned { onControlsPositioned(it.boundsInWindow().top) })
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onResizeToggle) { Icon(Icons.Outlined.AspectRatio, null, tint = Color.White) } }
            Box(Modifier.fillMaxWidth().height(80.dp)) {
                if (isSeeking && previewBitmap != null) { Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.align(Alignment.BottomStart).offset(x = with(LocalDensity.current) { thumbXOffset.toDp() } - 60.dp, y = (-20).dp)) { Image(bitmap = previewBitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.size(120.dp, 68.dp).clip(RoundedCornerShape(8.dp))); Text(formatTime(seekPosition.toLong()), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp)) } }
                SamsungSeekBar(if (isSeeking) seekPosition else sliderValue, bufferedValue, totalDuration, Modifier.fillMaxWidth().height(24.dp).align(Alignment.BottomCenter), onSeek = { pos, xOffset -> thumbXOffset = xOffset; onSeek(pos) }, onSeekFinished = onSeekFinished)
            }
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(currentTimeStr, color = Color.White, style = MaterialTheme.typography.labelMedium); Text(totalTimeStr, color = Color.White.copy(0.7f), style = MaterialTheme.typography.labelMedium) }
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onLock) { Icon(if (isLocked) Icons.Outlined.Lock else Icons.Outlined.LockOpen, null, tint = Color.White, modifier = Modifier.size(24.dp)) }
                IconButton(onClick = onPrev, enabled = hasPrev) { Icon(Icons.Rounded.SkipPrevious, null, tint = if (hasPrev) Color.White else Color.White.copy(0.3f), modifier = Modifier.size(32.dp)) }
                Surface(modifier = Modifier.size(64.dp), shape = CircleShape, color = Color.White.copy(alpha = 0.15f), tonalElevation = 0.dp, shadowElevation = 0.dp, onClick = onTogglePlay) { Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) { Icon(imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = "Play Pause", tint = Color.White, modifier = Modifier.size(42.dp)) } }
                IconButton(onClick = onNext, enabled = hasNext) { Icon(Icons.Rounded.SkipNext, null, tint = if (hasNext) Color.White else Color.White.copy(0.3f), modifier = Modifier.size(32.dp)) }
                IconButton(onClick = onRepeatToggle) {
                    val repeatIcon = when (currentRepeatMode) {
                        PremiumRepeatMode.OFF -> Icons.Rounded.Repeat
                        PremiumRepeatMode.ONE -> Icons.Rounded.RepeatOne
                        PremiumRepeatMode.ALL -> Icons.Rounded.RepeatOn
                    }
                    val repeatTint = if (currentRepeatMode == PremiumRepeatMode.OFF) Color.White.copy(0.6f) else MaterialTheme.colorScheme.primaryContainer
                    Icon(repeatIcon, null, tint = repeatTint, modifier = Modifier.size(24.dp))
                }
                IconButton(onClick = onOrientationToggle) { Icon(Icons.Outlined.ScreenRotation, null, tint = Color.White, modifier = Modifier.size(24.dp)) }
            }
        }
    }
}

@Composable fun SamsungSeekBar(current: Float, buffered: Float, total: Float, modifier: Modifier, onSeek: (Float, Float) -> Unit, onSeekFinished: () -> Unit) {
    var width by remember { mutableFloatStateOf(0f) }; val safeTotal = total.coerceAtLeast(1f)
    Canvas(modifier.pointerInput(safeTotal) { detectTapGestures { tapOffset -> if(width <= 0f) return@detectTapGestures; val safeX = tapOffset.x.coerceIn(0f, width); val p = (safeX / width) * safeTotal; onSeek(p, safeX); onSeekFinished() } }.pointerInput(safeTotal) { detectDragGestures(onDragEnd = onSeekFinished) { dragChange, _ -> if(width <= 0f) return@detectDragGestures; val safeX = dragChange.position.x.coerceIn(0f, width); val p = (safeX / width) * safeTotal; onSeek(p, safeX) } }) {
        width = size.width; val h = 3.dp.toPx(); val y = size.height / 2; val bW = (buffered / safeTotal).coerceIn(0f, 1f) * width; val cW = (current / safeTotal).coerceIn(0f, 1f) * width
        drawRoundRect(Color.White.copy(0.25f), Offset(0f, y - h/2), Size(width, h), CornerRadius(h/2)); drawRoundRect(Color.White.copy(0.5f), Offset(0f, y - h/2), Size(bW, h), CornerRadius(h/2)); drawRoundRect(Color.White, Offset(0f, y - h/2), Size(cW, h), CornerRadius(h/2)); drawCircle(Color.White, radius = 6.dp.toPx(), center = Offset(cW, y))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaybackMenuSheet(currentSpeed: Float, autoPlayNext: Boolean, autoRepeat: PremiumRepeatMode, backgroundPlay: Boolean, onDismissRequest: () -> Unit, onSpeedChange: (Float) -> Unit, onToggleAutoPlay: (Boolean) -> Unit, onToggleAutoRepeat: (PremiumRepeatMode) -> Unit, onToggleBackgroundPlay: (Boolean) -> Unit, onShare: () -> Unit, onDetails: () -> Unit) {
    var showingSpeedList by remember { mutableStateOf(false) }
    @kotlin.OptIn(ExperimentalMaterial3Api::class)
    ModalBottomSheet(onDismissRequest = onDismissRequest) {
        if (showingSpeedList) {
            LazyColumn(Modifier.padding(24.dp)) {
                item { Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = { showingSpeedList = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }; Text("Playback Speed", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.titleLarge) }; Spacer(Modifier.height(16.dp)) }
                val speeds = listOf(0.25f, 0.50f, 0.75f, 1f, 1.25f, 1.50f, 1.75f, 2f, 3f, 4f, 6f, 8f)
                items(speeds) { speed -> ListItem(headlineContent = { Text("${speed}x") }, trailingContent = { if (speed == currentSpeed) Icon(Icons.Default.Check, null) }, modifier = Modifier.clickable { onSpeedChange(speed); showingSpeedList = false }) }
            }
        } else {
            LazyColumn(Modifier.padding(24.dp)) {
                item { Text("Options", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.titleLarge); Spacer(Modifier.height(16.dp)) }
                item { ListItem(headlineContent = { Text("Share") }, leadingContent = { Icon(Icons.Default.Share, null) }, modifier = Modifier.clickable { onShare() }) }
                item { ListItem(headlineContent = { Text("Details") }, leadingContent = { Icon(Icons.Default.Info, null) }, modifier = Modifier.clickable { onDetails() }) }
                item { ListItem(headlineContent = { Text("Playback Speed") }, supportingContent = { Text("${currentSpeed}x") }, leadingContent = { Icon(Icons.Outlined.Speed, null) }, modifier = Modifier.clickable { showingSpeedList = true }) }
                item { ListItem(headlineContent = { Text("Auto Play Next") }, leadingContent = { Icon(Icons.Rounded.SkipNext, null) }, trailingContent = { Switch(checked = autoPlayNext, onCheckedChange = onToggleAutoPlay) }) }
                item { ListItem(headlineContent = { Text("Repeat Mode") }, supportingContent = { Text(autoRepeat.name) }, leadingContent = { Icon(Icons.Rounded.Repeat, null) }, modifier = Modifier.clickable { val entries = PremiumRepeatMode.entries.toTypedArray(); onToggleAutoRepeat(entries[(autoRepeat.ordinal + 1) % entries.size]) }) }
                item { ListItem(headlineContent = { Text("Background Play") }, leadingContent = { Icon(Icons.Rounded.PlayCircleOutline, null) }, trailingContent = { Switch(checked = backgroundPlay, onCheckedChange = onToggleBackgroundPlay) }) }
            }
        }
    }
}

fun formatTime(ms: Long): String { val ts = ms / 1000; val s = ts % 60; val m = (ts / 60) % 60; val h = ts / 3600; return if (h > 0) String.format(Locale.US, "%02d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s) }
fun Context.findActivity(): Activity? { var c = this; while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }; return null }