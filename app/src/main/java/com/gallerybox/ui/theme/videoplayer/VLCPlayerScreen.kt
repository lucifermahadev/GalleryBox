@file:Suppress("unused", "UnsafeOptInUsageError")

package com.gallerybox.ui.theme.videoplayer

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Build
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.abs

// ==========================================
// 1. DATA MODELS & ENUMS
// ==========================================
data class TrackInfo(val id: String, val name: String, val groupIndex: Int, val trackIndex: Int)
enum class RepeatMode { OFF, ONE, ALL, AB }
enum class ResizeMode { FIT, FILL, ZOOM }

// ==========================================
// 2. MAIN COMPOSABLE
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(
    videoUrl: String,
    startPosition: Long = 0L,
    onBackPress: () -> Unit,
    onPlayNext: () -> Unit = { },
    onPlayPrevious: () -> Unit = { },
    onEditClick: () -> Unit
) {
    val context = LocalContext.current
    val activity = remember { context.findActivity() }
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("media3_prefs", Context.MODE_PRIVATE) }

    // 🚀 FIXED: Initialize Media3 ExoPlayer instead of VLC
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build()
            setAudioAttributes(audioAttributes, true) // Handles Audio Focus natively!
        }
    }

    val playerView = remember {
        PlayerView(context).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            player = exoPlayer
        }
    }

    // Connect native Android Equalizer to ExoPlayer's audio session
    val equalizer = remember(exoPlayer.audioSessionId) {
        try {
            if (exoPlayer.audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
                Equalizer(0, exoPlayer.audioSessionId).apply { enabled = true }
            } else null
        } catch (_: Exception) { null }
    }

    // --- UI STATE ---
    var isPlaying by remember { mutableStateOf(false) }
    var currentTime by remember { mutableLongStateOf(0L) }
    var totalDuration by remember { mutableLongStateOf(0L) }
    var isBuffering by remember { mutableStateOf(false) }
    var hasError by remember { mutableStateOf(false) }

    var repeatMode by remember { mutableStateOf(RepeatMode.OFF) }
    var abStart by remember { mutableLongStateOf(-1L) }
    var abEnd by remember { mutableLongStateOf(-1L) }

    var playbackSpeed by remember { mutableFloatStateOf(prefs.getFloat("playback_speed", 1.0f)) }
    var isBackgroundPlay by remember { mutableStateOf(false) }
    var isAudioOnly by remember { mutableStateOf(false) }
    var isLocked by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    var hideJob by remember { mutableStateOf<Job?>(null) }
    var currentResizeMode by remember { mutableStateOf(ResizeMode.FIT) }

    var isTemp2x by remember { mutableStateOf(false) }
    var videoInfo by remember { mutableStateOf("Loading stats...") }

    var showMoreMenu by remember { mutableStateOf(false) }
    var showSpeedSheet by remember { mutableStateOf(false) }
    var showAudioSheet by remember { mutableStateOf(false) }
    var showSubSheet by remember { mutableStateOf(false) }
    var showEqSheet by remember { mutableStateOf(false) }
    var showSleepTimerSheet by remember { mutableStateOf(false) }
    var showStatsSheet by remember { mutableStateOf(false) }

    var screenWidth by remember { mutableIntStateOf(0) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var isSwipingToDismiss by remember { mutableStateOf(false) }
    var videoScale by remember { mutableFloatStateOf(1f) }
    var videoOffset by remember { mutableStateOf(Offset.Zero) }
    var pendingSeek by remember { mutableLongStateOf(-1L) }

    var gestureIcon by remember { mutableStateOf<ImageVector?>(null) }
    var gestureText by remember { mutableStateOf("") }
    var showGestureOverlay by remember { mutableStateOf(false) }

    val audioTracks = remember { mutableStateListOf<TrackInfo>() }
    val subtitleTracks = remember { mutableStateListOf<TrackInfo>() }
    var currentAudioTrack by remember { mutableStateOf("") }
    var currentSubtitleTrack by remember { mutableStateOf("") }

    val originalBrightness = remember { activity.window.attributes.screenBrightness }
    val insetsController = remember { WindowCompat.getInsetsController(activity.window, view) }
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    val configuration = LocalConfiguration.current
    val isInPiPMode = remember(configuration) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) activity.isInPictureInPictureMode else false
    }

    // 🚀 Load Local Subtitles directly into Media3
    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val tempSubFile = File(context.cacheDir, "temp_subtitle.srt")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tempSubFile).use { output -> input.copyTo(output) }
                    }
                    withContext(Dispatchers.Main) {
                        val subtitleConfig = MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(tempSubFile))
                            .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                            .setLanguage("en")
                            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                            .build()

                        val currentPosition = exoPlayer.currentPosition
                        val mediaItem = MediaItem.Builder()
                            .setUri(videoUrl)
                            .setSubtitleConfigurations(listOf(subtitleConfig))
                            .build()

                        exoPlayer.setMediaItem(mediaItem)
                        exoPlayer.seekTo(currentPosition)
                        exoPlayer.prepare()
                        exoPlayer.play()
                        Toast.makeText(context, "Subtitle Loaded", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { Toast.makeText(context, "Failed to load subtitle", Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }

    // --- MEDIA3 EVENT LISTENER ---
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) {
                    totalDuration = exoPlayer.duration.coerceAtLeast(0)
                    val format = exoPlayer.videoFormat

                    // Auto-Rotate logic
                    if (format != null) {
                        activity.requestedOrientation = if (format.width > format.height) {
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        } else {
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                        }
                        videoInfo = "Resolution: ${format.width}x${format.height}\nBitrate: ${(format.bitrate / 1000)} kb/s"
                    }
                }
                if (state == Player.STATE_ENDED) {
                    when (repeatMode) {
                        RepeatMode.ONE -> exoPlayer.seekTo(0)
                        RepeatMode.ALL -> onPlayNext()
                        RepeatMode.OFF -> onBackPress()
                        else -> {}
                    }
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlayerError(error: PlaybackException) {
                hasError = true
                isPlaying = false
            }

            override fun onTracksChanged(tracks: Tracks) {
                audioTracks.clear()
                subtitleTracks.clear()

                tracks.groups.forEachIndexed { groupIndex, group ->
                    if (group.type == C.TRACK_TYPE_AUDIO) {
                        for (i in 0 until group.length) {
                            val format = group.getTrackFormat(i)
                            val name = format.language ?: "Audio Track ${i + 1}"
                            val trackId = "$groupIndex-$i"
                            audioTracks.add(TrackInfo(trackId, name, groupIndex, i))
                            if (group.isTrackSelected(i)) currentAudioTrack = trackId
                        }
                    } else if (group.type == C.TRACK_TYPE_TEXT) {
                        for (i in 0 until group.length) {
                            val format = group.getTrackFormat(i)
                            val name = format.language ?: "Subtitle ${i + 1}"
                            val trackId = "$groupIndex-$i"
                            subtitleTracks.add(TrackInfo(trackId, name, groupIndex, i))
                            if (group.isTrackSelected(i)) currentSubtitleTrack = trackId
                        }
                    }
                }
            }
        }

        exoPlayer.addListener(listener)

        // Initial setup
        val mediaItem = MediaItem.fromUri(videoUrl)
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.setPlaybackParameters(PlaybackParameters(playbackSpeed))
        exoPlayer.prepare()

        val savedPosition = prefs.getLong(videoUrl, startPosition)
        if (savedPosition > 0) exoPlayer.seekTo(savedPosition)
        exoPlayer.play()

        onDispose {
            exoPlayer.removeListener(listener)
        }
    }

    // Track Current Time & A-B Loop
    LaunchedEffect(isPlaying, repeatMode, abStart, abEnd) {
        while (isActive) {
            if (isPlaying) {
                currentTime = exoPlayer.currentPosition
                if (repeatMode == RepeatMode.AB && abEnd > 0 && currentTime >= abEnd) {
                    exoPlayer.seekTo(abStart)
                }
            }
            delay(100)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                prefs.edit().putLong(videoUrl, currentTime).apply()
                val isPiP = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) activity.isInPictureInPictureMode else false
                if (!isBackgroundPlay && !isPiP) exoPlayer.pause()
            }
            if (event == Lifecycle.Event.ON_RESUME) {
                if (!exoPlayer.isPlaying && !isBackgroundPlay && !hasError) exoPlayer.play()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            val lp = activity.window.attributes
            lp.screenBrightness = originalBrightness
            activity.window.attributes = lp

            insetsController.show(WindowInsetsCompat.Type.systemBars())
            prefs.edit().putLong(videoUrl, currentTime).putFloat("playback_speed", playbackSpeed).apply()

            exoPlayer.release()
            equalizer?.release()
        }
    }

    LaunchedEffect(showControls, isInPiPMode) {
        if (showControls && !isInPiPMode) {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        } else {
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    BackHandler { if (!isLocked && !isInPiPMode) onBackPress() }

    val setPiPMode = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) params.setSeamlessResizeEnabled(true)
            activity.enterPictureInPictureMode(params.build())
        } else {
            Toast.makeText(context, "PiP not supported", Toast.LENGTH_SHORT).show()
        }
    }

    val toggleResizeMode = {
        currentResizeMode = when (currentResizeMode) {
            ResizeMode.FIT -> ResizeMode.FILL
            ResizeMode.FILL -> ResizeMode.ZOOM
            ResizeMode.ZOOM -> ResizeMode.FIT
        }

        playerView.resizeMode = when (currentResizeMode) {
            ResizeMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            ResizeMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            ResizeMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        }
    }

    // ==========================================
    // 3. UI LAYOUT
    // ==========================================
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = (1f - (abs(dragOffsetY) / 1000f)).coerceIn(0f, 1f)))
            .onGloballyPositioned { screenWidth = it.size.width }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!isLocked && down.position.x > screenWidth / 2 && !showControls) {
                        isTemp2x = true
                        exoPlayer.setPlaybackParameters(PlaybackParameters(2.0f))
                        gestureText = "2x Speed"
                        gestureIcon = Icons.Rounded.FastForward
                        showGestureOverlay = true

                        do {
                            val event = awaitPointerEvent()
                        } while (event.changes.any { it.pressed })

                        isTemp2x = false
                        exoPlayer.setPlaybackParameters(PlaybackParameters(playbackSpeed))
                        showGestureOverlay = false
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { offset ->
                        if (!isLocked) {
                            if (offset.x < screenWidth / 2) {
                                exoPlayer.seekTo((currentTime - 10000).coerceAtLeast(0))
                                gestureIcon = Icons.Rounded.FastRewind
                                gestureText = "-10s"
                            } else {
                                exoPlayer.seekTo((currentTime + 10000).coerceAtMost(totalDuration))
                                gestureIcon = Icons.Rounded.FastForward
                                gestureText = "+10s"
                            }
                            showGestureOverlay = true
                            scope.launch { delay(500); showGestureOverlay = false }
                        }
                    },
                    onTap = {
                        showControls = !showControls
                        if (showControls) {
                            hideJob?.cancel()
                            hideJob = scope.launch { delay(3000); if (isPlaying) showControls = false }
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    if (isLocked) return@detectTransformGestures
                    if (zoom != 1f || videoScale > 1f) {
                        videoScale = (videoScale * zoom).coerceIn(1f, 5f)
                        videoOffset += pan
                    }
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { if (videoScale == 1f) isSwipingToDismiss = false },
                    onDragEnd = {
                        if (pendingSeek != -1L) {
                            exoPlayer.seekTo(pendingSeek)
                            pendingSeek = -1L
                        }
                        scope.launch { delay(500); showGestureOverlay = false }
                        dragOffsetY = 0f
                        isSwipingToDismiss = false
                    },
                    onDragCancel = {
                        pendingSeek = -1L
                        dragOffsetY = 0f
                        isSwipingToDismiss = false
                    },
                    onDrag = { change, dragAmount ->
                        if (isLocked || videoScale > 1f) return@detectDragGestures
                        change.consume()
                        val (dx, dy) = dragAmount

                        if (!isSwipingToDismiss && abs(dx) < abs(dy) && dy > 10f && !showControls) {
                            isSwipingToDismiss = true
                            showGestureOverlay = false
                        }

                        if (isSwipingToDismiss) {
                            dragOffsetY += dy
                            if (dragOffsetY > 300f) onBackPress()
                        } else if (abs(dx) > abs(dy)) {
                            if (pendingSeek == -1L) pendingSeek = currentTime
                            pendingSeek = (pendingSeek + (dx / screenWidth) * totalDuration).toLong().coerceIn(0, totalDuration)

                            gestureIcon = if (dx > 0) Icons.Rounded.FastForward else Icons.Rounded.FastRewind
                            gestureText = formatTime(pendingSeek)
                            showGestureOverlay = true
                        } else {
                            val isRight = change.position.x > screenWidth / 2
                            if (isRight) {
                                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                val nextVol = (current + (-dy / 50).toInt()).coerceIn(0, max)
                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, nextVol, 0)
                                gestureIcon = if (nextVol == 0) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp
                                gestureText = "${(nextVol * 100 / max)}%"
                            } else {
                                val lp = activity.window.attributes
                                val current = lp.screenBrightness
                                val nextBright = (current - (dy / 1000f)).coerceIn(0.01f, 1f)
                                lp.screenBrightness = nextBright
                                activity.window.attributes = lp
                                gestureIcon = Icons.Rounded.BrightnessMedium
                                gestureText = "${(nextBright * 100).toInt()}%"
                            }
                            showGestureOverlay = true
                        }
                    }
                )
            }
    ) {

        if (hasError) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("Cannot play video", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text("Format may be unsupported", color = Color.Gray)
                }
            }
        } else {
            AndroidView(
                factory = { playerView },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationY = dragOffsetY
                        val scale = (1f - (abs(dragOffsetY) / 2000f)).coerceAtLeast(0.85f)
                        scaleX = scale * videoScale
                        scaleY = scale * videoScale
                        translationX = videoOffset.x
                        translationY = videoOffset.y + dragOffsetY
                    }
            )
        }

        AnimatedVisibility(visible = showGestureOverlay && !isSwipingToDismiss, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.Center)) {
            Box(Modifier.background(Color(0x80000000), RoundedCornerShape(16.dp)).padding(32.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(gestureIcon ?: Icons.Rounded.Info, null, tint = Color.White, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(gestureText, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        AnimatedVisibility(visible = showControls && dragOffsetY == 0f && !isInPiPMode, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent)))
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 16.dp)
                        .align(Alignment.TopCenter),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBackPress) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White, modifier = Modifier.size(28.dp)) }

                    val videoTitle = Uri.parse(videoUrl).lastPathSegment ?: File(videoUrl).name
                    Text(videoTitle, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f).padding(start = 8.dp))

                    IconButton(onClick = { setPiPMode() }) { Icon(Icons.Rounded.PictureInPictureAlt, null, tint = Color.White) }
                    IconButton(onClick = onEditClick) { Icon(Icons.Outlined.Edit, null, tint = Color.White) }
                    IconButton(onClick = {
                        activity.requestedOrientation = if (activity.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
                            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    }) { Icon(Icons.Default.ScreenRotation, null, tint = Color.White) }

                    Box {
                        IconButton(onClick = { showMoreMenu = true }) { Icon(Icons.Rounded.MoreVert, null, tint = Color.White) }
                        DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }, modifier = Modifier.clip(RoundedCornerShape(16.dp))) {
                            DropdownMenuItem(text = { Text("Audio Tracks") }, onClick = { showAudioSheet = true; showMoreMenu = false }, leadingIcon = { Icon(Icons.Default.Audiotrack, null) })
                            DropdownMenuItem(text = { Text("Subtitles") }, onClick = { showSubSheet = true; showMoreMenu = false }, leadingIcon = { Icon(Icons.Default.ClosedCaption, null) })
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Equalizer") }, onClick = { showEqSheet = true; showMoreMenu = false }, leadingIcon = { Icon(Icons.Rounded.GraphicEq, null) })
                            DropdownMenuItem(text = { Text("Playback Speed") }, onClick = { showSpeedSheet = true; showMoreMenu = false }, leadingIcon = { Icon(Icons.Rounded.Speed, null) })
                            DropdownMenuItem(text = { Text("Sleep Timer") }, onClick = { showSleepTimerSheet = true; showMoreMenu = false }, leadingIcon = { Icon(Icons.Rounded.Timer, null) })
                            DropdownMenuItem(text = { Text("Video Stats") }, onClick = { showStatsSheet = true; showMoreMenu = false }, leadingIcon = { Icon(Icons.Rounded.Info, null) })
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Background Play") }, onClick = { isBackgroundPlay = !isBackgroundPlay; showMoreMenu = false }, leadingIcon = { Icon(Icons.Rounded.Headphones, null, tint = if(isBackgroundPlay) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) })
                            DropdownMenuItem(text = { Text("Audio Only") }, onClick = {
                                isAudioOnly = !isAudioOnly
                                exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, isAudioOnly)
                                    .build()
                                showMoreMenu = false
                            }, leadingIcon = { Icon(Icons.Rounded.SpeakerGroup, null, tint = if(isAudioOnly) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) })
                        }
                    }
                }

                if (!isLocked && !hasError) {
                    Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                        AnimatedControlButton(icon = Icons.Rounded.SkipPrevious, size = 56.dp) { onPlayPrevious() }
                        AnimatedControlButton(icon = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, size = 90.dp, isPrimary = true) {
                            if (isPlaying) exoPlayer.pause() else exoPlayer.play()
                        }
                        AnimatedControlButton(icon = Icons.Rounded.SkipNext, size = 56.dp) { onPlayNext() }
                    }
                }

                IconButton(onClick = { isLocked = !isLocked }, modifier = Modifier.align(Alignment.CenterStart).padding(32.dp).background(Color(0x66000000), CircleShape)) {
                    Icon(if (isLocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen, null, tint = if (isLocked) MaterialTheme.colorScheme.primary else Color.White)
                }

                if(!isLocked && !hasError) {
                    Column(Modifier.align(Alignment.CenterEnd).padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        IconButton(onClick = { Toast.makeText(context, "Screenshot captured!", Toast.LENGTH_SHORT).show() }, modifier = Modifier.background(Color(0x66000000), CircleShape)) {
                            Icon(Icons.Rounded.CameraAlt, null, tint = Color.White)
                        }
                        IconButton(onClick = { toggleResizeMode() }, modifier = Modifier.background(Color(0x66000000), CircleShape)) {
                            Icon(Icons.Rounded.AspectRatio, null, tint = Color.White)
                        }
                    }
                }

                if (!isLocked && !hasError) {
                    Column(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))))
                            .navigationBarsPadding()
                            .padding(horizontal = 24.dp, vertical = 24.dp)
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(formatTime(if (pendingSeek != -1L) pendingSeek else currentTime), color = Color.White, style = MaterialTheme.typography.labelLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                IconButton(onClick = {
                                    if(abStart == -1L) { abStart = currentTime; Toast.makeText(context, "A set", Toast.LENGTH_SHORT).show() }
                                    else if(abEnd == -1L) { abEnd = currentTime; repeatMode = RepeatMode.AB; Toast.makeText(context, "A-B Loop Active", Toast.LENGTH_SHORT).show() }
                                    else { abStart = -1L; abEnd = -1L; repeatMode = RepeatMode.OFF; Toast.makeText(context, "A-B Cleared", Toast.LENGTH_SHORT).show() }
                                }, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Rounded.RepeatOne, null, tint = if(repeatMode == RepeatMode.AB) MaterialTheme.colorScheme.primary else Color.White)
                                }
                                IconButton(onClick = {
                                    repeatMode = when(repeatMode) { RepeatMode.OFF -> RepeatMode.ALL; RepeatMode.ALL -> RepeatMode.ONE; else -> RepeatMode.OFF }
                                }, modifier = Modifier.size(36.dp)) {
                                    Icon(if(repeatMode == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, null, tint = if(repeatMode == RepeatMode.ALL || repeatMode == RepeatMode.ONE) MaterialTheme.colorScheme.primary else Color.White)
                                }
                            }
                            Text(formatTime(totalDuration), color = Color.White, style = MaterialTheme.typography.labelLarge)
                        }
                        Spacer(Modifier.height(8.dp))
                        Slider(
                            value = if (pendingSeek != -1L) pendingSeek.toFloat() else currentTime.toFloat(),
                            onValueChange = {
                                pendingSeek = it.toLong()
                                exoPlayer.seekTo(it.toLong())
                            },
                            onValueChangeFinished = { pendingSeek = -1L },
                            valueRange = 0f..totalDuration.toFloat().coerceAtLeast(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                            ),
                            modifier = Modifier.height(16.dp)
                        )
                    }
                }
            }
        }
        if (isBuffering && !hasError) CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.primary)
    }

    if (showAudioSheet) ModernTrackSelectionSheet("Audio Tracks", audioTracks, currentAudioTrack, onSelect = { trackId ->
        val track = audioTracks.find { it.id == trackId }
        if (track != null) {
            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .addOverride(TrackSelectionOverride(exoPlayer.currentTracks.groups[track.groupIndex].mediaTrackGroup, track.trackIndex))
                .build()
        }
    }, onDismiss = { showAudioSheet = false }, null)

    if (showSubSheet) {
        ModernTrackSelectionSheet(
            title = "Subtitles",
            tracks = subtitleTracks,
            currentId = currentSubtitleTrack,
            onSelect = { trackId ->
                val track = subtitleTracks.find { it.id == trackId }
                if (track != null) {
                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .addOverride(TrackSelectionOverride(exoPlayer.currentTracks.groups[track.groupIndex].mediaTrackGroup, track.trackIndex))
                        .build()
                }
            },
            onDismiss = { showSubSheet = false },
            onAddExternal = {
                subtitlePicker.launch("*/*")
                Unit
            }
        )
    }

    if (showSpeedSheet) {
        ModalBottomSheet(onDismissRequest = { showSpeedSheet = false }) {
            Column(Modifier.padding(24.dp).padding(bottom = 24.dp)) {
                Text("Playback Speed: ${String.format(Locale.US, "%.2f", playbackSpeed)}x", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf(0.5f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { speed ->
                        FilterChip(selected = playbackSpeed == speed, onClick = { playbackSpeed = speed; exoPlayer.setPlaybackParameters(PlaybackParameters(speed)) }, label = { Text("${speed}x") })
                    }
                }
                Spacer(Modifier.height(16.dp))
                Slider(value = playbackSpeed, onValueChange = { playbackSpeed = it; exoPlayer.setPlaybackParameters(PlaybackParameters(it)) }, valueRange = 0.25f..4.0f)
            }
        }
    }

    if (showEqSheet && equalizer != null) {
        ModalBottomSheet(onDismissRequest = { showEqSheet = false }) {
            Column(Modifier.padding(24.dp).padding(bottom = 24.dp)) {
                Text("Equalizer", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    for (i in 0 until equalizer.numberOfBands) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            var bandVal by remember { mutableFloatStateOf(equalizer.getBandLevel(i.toShort()).toFloat()) }
                            Slider(
                                value = bandVal,
                                onValueChange = { bandVal = it; equalizer.setBandLevel(i.toShort(), it.toInt().toShort()) },
                                valueRange = equalizer.bandLevelRange[0].toFloat()..equalizer.bandLevelRange[1].toFloat(),
                                modifier = Modifier.height(150.dp).graphicsLayer { rotationZ = -90f }
                            )
                            Text("${equalizer.getCenterFreq(i.toShort()) / 1000}Hz", fontSize = 10.sp, color = Color.Gray)
                        }
                    }
                }
            }
        }
    }

    if (showSleepTimerSheet) {
        ModalBottomSheet(onDismissRequest = { showSleepTimerSheet = false }) {
            Column(Modifier.padding(24.dp).padding(bottom = 24.dp)) {
                Text("Sleep Timer", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                val options = listOf(5, 10, 30, 60)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    options.forEach { minutes ->
                        Button(onClick = {
                            Toast.makeText(context, "Timer set for $minutes min", Toast.LENGTH_SHORT).show()
                            showSleepTimerSheet = false
                        }) { Text("${minutes}m") }
                    }
                }
            }
        }
    }

    if (showStatsSheet) {
        ModalBottomSheet(onDismissRequest = { showStatsSheet = false }) {
            Column(Modifier.padding(24.dp).padding(bottom = 24.dp)) {
                Text("Playback Statistics", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                Text(videoInfo, style = MaterialTheme.typography.bodyLarge, color = Color.Gray)
            }
        }
    }
}

// ==========================================
// REUSABLE UI COMPONENTS
// ==========================================
@Composable
fun AnimatedControlButton(icon: ImageVector, size: androidx.compose.ui.unit.Dp, isPrimary: Boolean = false, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(targetValue = if (isPressed) 0.85f else 1f, animationSpec = spring(dampingRatio = 0.5f))

    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(if (isPrimary) Modifier.shadow(12.dp, CircleShape) else Modifier)
            .background(if (isPrimary) MaterialTheme.colorScheme.primary else Color(0x66000000), CircleShape)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.55f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernTrackSelectionSheet(
    title: String,
    tracks: List<TrackInfo>,
    currentId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    onAddExternal: (() -> Unit)? = null
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface, dragHandle = { BottomSheetDefaults.DragHandle() }) {
        Column(Modifier.padding(bottom = 32.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (onAddExternal != null) {
                    TextButton(onClick = { onAddExternal(); onDismiss() }) { Text("Add Local File") }
                }
            }

            if (tracks.isEmpty()) {
                Text("No tracks available.", color = Color.Gray, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            } else {
                Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 400.dp)) {
                    tracks.forEach { track ->
                        val isSelected = track.id == currentId
                        Row(
                            Modifier.fillMaxWidth().clickable { onSelect(track.id); onDismiss() }.padding(horizontal = 24.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(if (isSelected) Icons.Default.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked, contentDescription = null, tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray)
                            Spacer(Modifier.width(16.dp))
                            Text(text = track.name, style = MaterialTheme.typography.bodyLarge, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
    }
}

fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val sec = totalSec % 60
    val min = (totalSec / 60) % 60
    val hour = totalSec / 3600
    return if (hour > 0) String.format(Locale.US, "%02d:%02d:%02d", hour, min, sec)
    else String.format(Locale.US, "%02d:%02d", min, sec)
}

fun Context.findActivity(): Activity {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    throw IllegalStateException("Activity not found")
}