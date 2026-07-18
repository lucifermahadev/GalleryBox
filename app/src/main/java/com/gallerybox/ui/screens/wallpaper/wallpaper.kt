@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@file:Suppress("UnsafeOptInUsageError", "DEPRECATION", "BlockingMethodInNonBlockingContext", "unused")

package com.gallerybox.ui.screens.wallpaper

import android.app.Activity
import android.app.WallpaperManager
import android.content.*
import android.graphics.*
import android.net.Uri
import android.os.PowerManager
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import com.gallerybox.data.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.*

object CacheEngine {
    fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height, width) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2; val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) inSampleSize *= 2
        }
        return inSampleSize
    }
}

object ImageEngine {
    fun createFinalBitmap(
        context: Context,
        uri: Uri,
        screenWidth: Int,
        screenHeight: Int,
        userScale: Float,
        userOffsetX: Float,
        userOffsetY: Float,
        userRotation: Float,
        dimLevel: Float
    ): Bitmap? {

        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }

        options.inSampleSize = CacheEngine.calculateInSampleSize(
            options,
            (screenWidth * userScale).toInt(),
            (screenHeight * userScale).toInt()
        )

        options.inJustDecodeBounds = false
        options.inPreferredConfig = Bitmap.Config.RGB_565

        val original = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null

        val finalBitmap = Bitmap.createBitmap(
            screenWidth,
            screenHeight,
            Bitmap.Config.RGB_565
        )

        val canvas = Canvas(finalBitmap).apply {
            drawColor(android.graphics.Color.BLACK)
        }

        val matrix = Matrix()
        val rotationRad = Math.toRadians(userRotation.toDouble())
        val overScale = abs(cos(rotationRad)) + abs(sin(rotationRad))

        val baseScale = max(
            screenWidth.toFloat() / original.width,
            screenHeight.toFloat() / original.height
        ) * overScale.toFloat()

        val scaledW = original.width * baseScale
        val scaledH = original.height * baseScale

        val dx = (screenWidth - scaledW) / 2f
        val dy = (screenHeight - scaledH) / 2f

        matrix.postScale(baseScale, baseScale)
        matrix.postTranslate(dx, dy)

        val cx = screenWidth / 2f
        val cy = screenHeight / 2f

        matrix.postTranslate(-cx, -cy)
        matrix.postScale(userScale, userScale)
        matrix.postRotate(userRotation)
        matrix.postTranslate(
            cx + userOffsetX,
            cy + userOffsetY
        )

        canvas.drawBitmap(original, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        original.recycle()

        if (dimLevel > 0f) {
            val dimCanvas = Canvas(finalBitmap)
            dimCanvas.drawColor(android.graphics.Color.argb((dimLevel * 255).toInt(), 0, 0, 0))
        }

        return finalBitmap
    }
}

object StaticEngine {
    suspend fun apply(
        context: Context, item: MediaItem, flags: Int,
        scale: Float, offsetX: Float, offsetY: Float, rotation: Float, dimLevel: Float
    ) = withContext(Dispatchers.IO) {
        try {
            val wm = WallpaperManager.getInstance(context)
            val metrics = context.resources.displayMetrics

            val targetWidth = if (wm.desiredMinimumWidth > 0) wm.desiredMinimumWidth else metrics.widthPixels
            val targetHeight = if (wm.desiredMinimumHeight > 0) wm.desiredMinimumHeight else metrics.heightPixels

            val finalBitmap = ImageEngine.createFinalBitmap(
                context, item.uri, targetWidth, targetHeight,
                scale, offsetX, offsetY, rotation, dimLevel
            ) ?: throw Exception("Failed to create bitmap")

            wm.setBitmap(finalBitmap, null, true, flags)
            finalBitmap.recycle()

            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Wallpaper Applied Successfully", Toast.LENGTH_SHORT).show()
                (context as? Activity)?.finish()
            }
        } catch (e: Exception) {
            Log.e("StaticEngine", "Failed to apply wallpaper", e)
            withContext(Dispatchers.Main) { Toast.makeText(context, "Failed to apply wallpaper", Toast.LENGTH_SHORT).show() }
        }
    }
}

object VideoEngine {
    fun setVideoWallpaper(context: Context, item: MediaItem, playAudio: Boolean, speed: Float, loop: Boolean, scaleMode: String) {
        try { context.contentResolver.takePersistableUriPermission(item.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) { Log.w("VideoEngine", "Persistable permission failed") }
        val prefs = context.getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("wallpaper_video_uri", item.uri.toString())
            .putBoolean("wallpaper_video_audio", playAudio)
            .putFloat("wallpaper_video_speed", speed)
            .putBoolean("wallpaper_video_loop", loop)
            .putString("wallpaper_video_scale_mode", scaleMode)
            .apply()

        try {
            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(context, VideoWallpaperService::class.java))
            }
            context.startActivity(intent)
        } catch (e: Exception) { Toast.makeText(context, "Live Wallpapers not supported", Toast.LENGTH_SHORT).show() }
    }
}

class VideoWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = VideoEngineInstance()

    private inner class VideoEngineInstance : Engine() {
        private var exoPlayer: ExoPlayer? = null
        private var isVisible = false
        private var systemReceiver: BroadcastReceiver? = null

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            initializePlayer()
            registerSystemReceivers()
        }

        private fun initializePlayer() {
            val prefs = getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
            val videoUriString = prefs.getString("wallpaper_video_uri", null) ?: return

            exoPlayer = ExoPlayer.Builder(applicationContext).build().apply {
                volume = if (prefs.getBoolean("wallpaper_video_audio", false)) 1f else 0f
                setPlaybackSpeed(prefs.getFloat("wallpaper_video_speed", 1f))
                repeatMode = if (prefs.getBoolean("wallpaper_video_loop", true)) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF

                val scaleModeStr = prefs.getString("wallpaper_video_scale_mode", "Crop")
                videoScalingMode = if (scaleModeStr == "Fit") C.VIDEO_SCALING_MODE_SCALE_TO_FIT else C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING

                setMediaItem(ExoMediaItem.Builder().setUri(Uri.parse(videoUriString)).setMediaId(videoUriString).build())
                prepare()
            }
        }

        private fun registerSystemReceivers() {
            systemReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (intent?.action) {
                        Intent.ACTION_SCREEN_OFF -> exoPlayer?.pause()
                        Intent.ACTION_SCREEN_ON -> if (isVisible) checkPowerAndPlay()
                        PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> checkPowerAndPlay()
                    }
                }
            }
            val filter = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) }
            applicationContext.registerReceiver(systemReceiver, filter)
        }

        private fun checkPowerAndPlay() {
            val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isPowerSaveMode) exoPlayer?.pause() else if (isVisible) exoPlayer?.play()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.isVisible = visible
            if (visible) {
                val prefs = getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
                exoPlayer?.apply {
                    volume = if (prefs.getBoolean("wallpaper_video_audio", false)) 1f else 0f
                    setPlaybackSpeed(prefs.getFloat("wallpaper_video_speed", 1f))
                    repeatMode = if (prefs.getBoolean("wallpaper_video_loop", true)) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF

                    val scaleModeStr = prefs.getString("wallpaper_video_scale_mode", "Crop")
                    videoScalingMode = if (scaleModeStr == "Fit") C.VIDEO_SCALING_MODE_SCALE_TO_FIT else C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                    playWhenReady = true
                }
                checkPowerAndPlay()
            } else {
                exoPlayer?.pause()
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) { super.onSurfaceCreated(holder); exoPlayer?.setVideoSurfaceHolder(holder) }
        override fun onSurfaceDestroyed(holder: SurfaceHolder) { super.onSurfaceDestroyed(holder); exoPlayer?.clearVideoSurfaceHolder(holder); exoPlayer?.pause() }
        override fun onDestroy() {
            super.onDestroy()
            systemReceiver?.let { applicationContext.unregisterReceiver(it) }
            exoPlayer?.release()
            exoPlayer = null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WallpaperScreen(item: MediaItem, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isAudioEnabled by remember { mutableStateOf(false) }
    var playbackSpeed by remember { mutableFloatStateOf(1f) }
    var loopVideo by remember { mutableStateOf(true) }
    var scaleMode by remember { mutableStateOf("Crop") }

    var showTargetDialog by remember { mutableStateOf(false) }
    var showAdjustments by remember { mutableStateOf(false) }
    var isTransforming by remember { mutableStateOf(false) }
    var isApplying by remember { mutableStateOf(false) }

    val scale = remember { Animatable(1f) }
    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }
    val rotation = remember { Animatable(0f) }

    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val density = LocalDensity.current
    val wPx = with(density) { screenWidth.toPx() }
    val hPx = with(density) { screenHeight.toPx() }

    var dimLevel by remember { mutableFloatStateOf(0f) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Setup Wallpaper", color = androidx.compose.ui.graphics.Color.White) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = androidx.compose.ui.graphics.Color.White) } },
                actions = {
                    IconButton(onClick = { showAdjustments = !showAdjustments }) {
                        Icon(Icons.Filled.Tune, "Adjust", tint = androidx.compose.ui.graphics.Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showTargetDialog = true },
                icon = { Icon(Icons.Rounded.Check, "Apply") },
                text = { Text("Apply") }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Black)
                .pointerInput(Unit) {
                    if (!item.isVideo) {
                        detectTapGestures(
                            onDoubleTap = {
                                scope.launch {
                                    launch { scale.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 400f)) }
                                    launch { offsetX.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 400f)) }
                                    launch { offsetY.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 400f)) }
                                    launch { rotation.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 400f)) }
                                }
                            }
                        )
                    }
                }
                .pointerInput(Unit) {
                    if (!item.isVideo) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            isTransforming = true
                            do {
                                val event = awaitPointerEvent()
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                val rotChange = event.calculateRotation()

                                if (abs(zoomChange - 1f) < 0.005f && panChange.getDistance() < 1f && abs(rotChange) < 0.5f) continue

                                scope.launch {
                                    var targetScale = scale.value * zoomChange
                                    if (targetScale < 1f) targetScale = 1f + (targetScale - 1f) * 0.3f
                                    else if (targetScale > 5f) targetScale = 5f + (targetScale - 5f) * 0.3f
                                    scale.snapTo(targetScale)

                                    val rotationRad = Math.toRadians(rotation.value.toDouble())
                                    val overScale = abs(cos(rotationRad)) + abs(sin(rotationRad))
                                    val effectiveScale = scale.value * overScale.toFloat()

                                    val limitX = max(0f, (wPx * effectiveScale - wPx) / 2f)
                                    val limitY = max(0f, (hPx * effectiveScale - hPx) / 2f)

                                    var targetX = offsetX.value + panChange.x
                                    var targetY = offsetY.value + panChange.y

                                    if (targetX > limitX) targetX = limitX + (targetX - limitX) * 0.3f
                                    else if (targetX < -limitX) targetX = -limitX + (targetX + limitX) * 0.3f

                                    if (targetY > limitY) targetY = limitY + (targetY - limitY) * 0.3f
                                    else if (targetY < -limitY) targetY = -limitY + (targetY + limitY) * 0.3f

                                    offsetX.snapTo(targetX)
                                    offsetY.snapTo(targetY)

                                    var targetRot = rotation.value + rotChange * 0.4f
                                    if (targetRot > 45f) targetRot = 45f + (targetRot - 45f) * 0.3f
                                    else if (targetRot < -45f) targetRot = -45f + (targetRot + 45f) * 0.3f

                                    rotation.snapTo(targetRot)
                                }

                                event.changes.forEach { if (it.positionChange() != androidx.compose.ui.geometry.Offset.Zero) it.consume() }
                            } while (event.changes.any { it.pressed })
                            isTransforming = false

                            scope.launch {
                                val finalScale = scale.value.coerceIn(1f, 5f)
                                val rotationRad = Math.toRadians(rotation.value.toDouble())
                                val overScale = abs(cos(rotationRad)) + abs(sin(rotationRad))
                                val effectiveScale = finalScale * overScale.toFloat()

                                val limitX = max(0f, (wPx * effectiveScale - wPx) / 2f)
                                val limitY = max(0f, (hPx * effectiveScale - hPx) / 2f)

                                val finalX = offsetX.value.coerceIn(-limitX, limitX)
                                val finalY = offsetY.value.coerceIn(-limitY, limitY)
                                val finalRot = rotation.value.coerceIn(-45f, 45f)

                                launch { scale.animateTo(finalScale, spring(dampingRatio = 0.8f, stiffness = 400f)) }
                                launch { offsetX.animateTo(finalX, spring(dampingRatio = 0.8f, stiffness = 400f)) }
                                launch { offsetY.animateTo(finalY, spring(dampingRatio = 0.8f, stiffness = 400f)) }
                                launch { rotation.animateTo(finalRot, spring(dampingRatio = 0.8f, stiffness = 400f)) }
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {

            if (item.isVideo) {
                val player = remember {
                    ExoPlayer.Builder(context).build().apply {
                        setMediaItem(ExoMediaItem.fromUri(item.uri))
                        playWhenReady = true
                        prepare()
                    }
                }

                LaunchedEffect(isAudioEnabled) { player.volume = if (isAudioEnabled) 1f else 0f }
                LaunchedEffect(playbackSpeed) { player.setPlaybackSpeed(playbackSpeed) }
                LaunchedEffect(loopVideo) { player.repeatMode = if (loopVideo) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF }

                DisposableEffect(Unit) { onDispose { player.release() } }

                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = false
                            this.player = player
                            this.resizeMode = when (scaleMode) {
                                "Fit" -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                                "Fill" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                else -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            }
                        }
                    },
                    update = { view ->
                        view.resizeMode = when (scaleMode) {
                            "Fit" -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                            "Fill" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                            else -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                val optimizedImageRequest = remember(item.uri, wPx, hPx) {
                    ImageRequest.Builder(context)
                        .data(item.uri)
                        .size(wPx.toInt(), hPx.toInt())
                        .crossfade(false)
                        .precision(Precision.INEXACT)
                        .build()
                }

                AsyncImage(
                    model = optimizedImageRequest,
                    contentDescription = "Wallpaper Preview",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale.value
                            scaleY = scale.value
                            translationX = offsetX.value
                            translationY = offsetY.value
                            rotationZ = rotation.value
                        }
                )
            }

            if (dimLevel > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = dimLevel))
                )
            }

            AnimatedVisibility(visible = showAdjustments, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp)) {
                Surface(color = androidx.compose.ui.graphics.Color.Black.copy(0.7f), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(0.9f)) {
                    Column(Modifier.padding(16.dp)) {
                        if (item.isVideo) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Audio", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold)
                                Switch(checked = isAudioEnabled, onCheckedChange = { isAudioEnabled = it })
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Loop Video", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold)
                                Switch(checked = loopVideo, onCheckedChange = { loopVideo = it })
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Playback Speed", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold)
                                Row {
                                    listOf(0.5f, 1f, 1.5f, 2f).forEach { speed ->
                                        TextButton(onClick = { playbackSpeed = speed }) {
                                            Text("${speed}x", color = if (playbackSpeed == speed) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.White)
                                        }
                                    }
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Scale Mode", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold)
                                Row {
                                    listOf("Crop", "Fit", "Fill").forEach { mode ->
                                        TextButton(onClick = { scaleMode = mode }) {
                                            Text(mode, color = if (scaleMode == mode) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.White)
                                        }
                                    }
                                }
                            }
                        } else {
                            Text("Dimming", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold)
                            Slider(value = dimLevel, onValueChange = { dimLevel = it }, valueRange = 0f..0.8f)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                TextButton(onClick = {
                                    scope.launch {
                                        launch { scale.animateTo(1f) }
                                        launch { offsetX.animateTo(0f) }
                                        launch { offsetY.animateTo(0f) }
                                        launch { rotation.animateTo(0f) }
                                        dimLevel = 0f
                                    }
                                }) { Text("Reset All") }
                            }
                        }
                    }
                }
            }

            if (isApplying) {
                Box(
                    modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = androidx.compose.ui.graphics.Color.White)
                }
            }
        }
    }

    if (showTargetDialog) {
        var applyTo by remember { mutableIntStateOf(WallpaperManager.FLAG_SYSTEM) }
        var videoWithSound by remember { mutableStateOf(isAudioEnabled) }

        AlertDialog(
            onDismissRequest = { showTargetDialog = false },
            title = { Text("Set Wallpaper") },
            text = {
                Column {
                    if (item.isVideo) {
                        Text("Live Wallpapers are handled by the system. The OS will choose to apply it to the Home Screen or Both.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(16.dp))
                        Text("Playback", fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { videoWithSound = true }) { RadioButton(selected = videoWithSound, onClick = { videoWithSound = true }); Text("With Sound") }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { videoWithSound = false }) { RadioButton(selected = !videoWithSound, onClick = { videoWithSound = false }); Text("Without Sound") }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { applyTo = WallpaperManager.FLAG_SYSTEM }) { RadioButton(selected = applyTo == WallpaperManager.FLAG_SYSTEM, onClick = { applyTo = WallpaperManager.FLAG_SYSTEM }); Text("Home Screen") }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { applyTo = WallpaperManager.FLAG_LOCK }) { RadioButton(selected = applyTo == WallpaperManager.FLAG_LOCK, onClick = { applyTo = WallpaperManager.FLAG_LOCK }); Text("Lock Screen") }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { applyTo = (WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK) }) { RadioButton(selected = applyTo == (WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK), onClick = { applyTo = (WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK) }); Text("Both Screens") }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    showTargetDialog = false
                    if (item.isVideo) {
                        VideoEngine.setVideoWallpaper(context, item, videoWithSound, playbackSpeed, loopVideo, scaleMode)
                    } else {
                        isApplying = true
                        scope.launch {
                            try {
                                StaticEngine.apply(context, item, applyTo, scale.value, offsetX.value, offsetY.value, rotation.value, dimLevel)
                            } finally {
                                isApplying = false
                            }
                        }
                    }
                }) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { showTargetDialog = false }) { Text("Cancel") } }
        )
    }
}