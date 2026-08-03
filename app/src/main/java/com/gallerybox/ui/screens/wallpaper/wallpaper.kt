@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@file:Suppress("UnsafeOptInUsageError", "DEPRECATION", "BlockingMethodInNonBlockingContext", "unused")

package com.gallerybox.ui.screens.wallpaper

import android.app.Activity
import android.app.WallpaperManager
import android.content.*
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.PowerManager
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.gallerybox.data.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
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
        imageScaleMode: String,
        userScale: Float,
        userOffsetX: Float,
        userOffsetY: Float,
        userRotation: Float,
        dimLevel: Float
    ): Bitmap? {
        try {
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
            options.inPreferredConfig = Bitmap.Config.ARGB_8888

            val original = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return null

            val finalBitmap = Bitmap.createBitmap(
                screenWidth,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )

            val canvas = Canvas(finalBitmap).apply {
                drawColor(Color.BLACK)
            }

            val matrix = Matrix()

            val scaleX = screenWidth.toFloat() / original.width
            val scaleY = screenHeight.toFloat() / original.height

            val baseScaleX: Float
            val baseScaleY: Float

            when (imageScaleMode) {
                "Fit" -> { val s = min(scaleX, scaleY); baseScaleX = s; baseScaleY = s }
                "Fill" -> { baseScaleX = scaleX; baseScaleY = scaleY }
                "Original" -> { baseScaleX = 1f; baseScaleY = 1f }
                else -> { val s = max(scaleX, scaleY); baseScaleX = s; baseScaleY = s } // Crop
            }

            val cx = screenWidth / 2f
            val cy = screenHeight / 2f

            matrix.postTranslate(-original.width / 2f, -original.height / 2f)
            matrix.postScale(baseScaleX * userScale, baseScaleY * userScale)
            matrix.postRotate(userRotation)
            matrix.postTranslate(cx + userOffsetX, cy + userOffsetY)

            canvas.drawBitmap(original, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            original.recycle()

            if (dimLevel > 0f) {
                val dimCanvas = Canvas(finalBitmap)
                dimCanvas.drawColor(Color.argb((dimLevel * 255).toInt(), 0, 0, 0))
            }

            return finalBitmap
        } catch (e: OutOfMemoryError) {
            Log.e("ImageEngine", "OOM while creating wallpaper bitmap", e)
            return null
        } catch (e: Exception) {
            Log.e("ImageEngine", "Failed to create final bitmap", e)
            return null
        }
    }
}

object StaticEngine {
    suspend fun apply(
        context: Context, item: MediaItem, flags: Int,
        imageScaleMode: String, scale: Float, offsetX: Float, offsetY: Float, rotation: Float, dimLevel: Float
    ) = withContext(Dispatchers.IO) {
        try {
            val wm = WallpaperManager.getInstance(context)
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val bounds = windowManager.currentWindowMetrics.bounds
            val targetWidth = bounds.width()
            val targetHeight = bounds.height()

            wm.suggestDesiredDimensions(targetWidth, targetHeight)

            val finalBitmap = ImageEngine.createFinalBitmap(
                context, item.uri, targetWidth, targetHeight, imageScaleMode,
                scale, offsetX, offsetY, rotation, dimLevel
            ) ?: throw Exception("Failed to create bitmap, possibly OutOfMemory")

            wm.setBitmap(finalBitmap, null, true, flags)
            finalBitmap.recycle()

            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Wallpaper Applied Successfully", Toast.LENGTH_SHORT).show()
                (context as? Activity)?.finish()
            }
        } catch (e: SecurityException) {
            Log.e("StaticEngine", "Permission denied", e)
            withContext(Dispatchers.Main) { Toast.makeText(context, "Missing wallpaper permissions", Toast.LENGTH_SHORT).show() }
        } catch (e: Exception) {
            Log.e("StaticEngine", "Failed to apply wallpaper", e)
            withContext(Dispatchers.Main) { Toast.makeText(context, "Failed to apply wallpaper", Toast.LENGTH_SHORT).show() }
        }
    }
}

object VideoEngine {
    suspend fun setVideoWallpaper(context: Context, item: MediaItem, playAudio: Boolean, speed: Float, loop: Boolean, scaleMode: String) = withContext(Dispatchers.IO) {
        try {
            val internalFile = File(context.filesDir, "live_wallpaper_video.mp4")
            if (internalFile.exists()) {
                internalFile.delete()
            }

            context.contentResolver.openInputStream(item.uri)?.use { input ->
                FileOutputStream(internalFile).use { output ->
                    input.copyTo(output)
                }
            } ?: throw Exception("Cannot open video stream")

            val safeUri = Uri.fromFile(internalFile).toString()

            val prefs = context.getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
            prefs.edit {
                putString("wallpaper_video_uri", safeUri)
                putBoolean("wallpaper_video_audio", playAudio)
                putFloat("wallpaper_video_speed", speed)
                putBoolean("wallpaper_video_loop", loop)
                putString("wallpaper_video_scale_mode", scaleMode)
            }

            withContext(Dispatchers.Main) {
                try {
                    val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                        putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(context, VideoWallpaperService::class.java))
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(context, "Live Wallpapers not supported on this device", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e("VideoEngine", "Failed to prepare video", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Failed to prepare video wallpaper", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

class VideoWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = VideoEngineInstance()

    private inner class VideoEngineInstance : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {
        private var exoPlayer: ExoPlayer? = null
        private var isVisible = false
        private var systemReceiver: BroadcastReceiver? = null
        private var currentSurfaceHolder: SurfaceHolder? = null
        private var retryCount = 0

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            val prefs = getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
            prefs.registerOnSharedPreferenceChangeListener(this)
            initializePlayer(prefs)
            registerSystemReceivers()
        }

        private fun initializePlayer(prefs: SharedPreferences) {
            val videoUriString = prefs.getString("wallpaper_video_uri", null) ?: return

            if (exoPlayer == null) {
                exoPlayer = ExoPlayer.Builder(applicationContext).build()
            }

            exoPlayer?.apply {
                volume = if (prefs.getBoolean("wallpaper_video_audio", false)) 1f else 0f
                setPlaybackSpeed(prefs.getFloat("wallpaper_video_speed", 1f))
                repeatMode = if (prefs.getBoolean("wallpaper_video_loop", true)) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF

                val scaleModeStr = prefs.getString("wallpaper_video_scale_mode", "Crop")
                videoScalingMode = if (scaleModeStr == "Fit" || scaleModeStr == "Original") C.VIDEO_SCALING_MODE_SCALE_TO_FIT else C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING

                setMediaItem(ExoMediaItem.Builder().setUri(videoUriString.toUri()).setMediaId(videoUriString).build())

                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        Log.d("VideoWallpaper", "State changed: $state")
                        when(state) {
                            Player.STATE_READY -> {
                                retryCount = 0
                                if (isVisible) play()
                            }
                            Player.STATE_ENDED -> seekTo(0)
                            Player.STATE_BUFFERING, Player.STATE_IDLE -> { /* No-op */ }
                        }
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        Log.e("VideoWallpaper", "Error: ${error.errorCodeName}", error)
                        if (retryCount < 1) {
                            retryCount++
                            prepare()
                        }
                    }
                })

                currentSurfaceHolder?.let { setVideoSurfaceHolder(it) }
                prepare()
                playWhenReady = true
            }
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            if (sharedPreferences == null || exoPlayer == null) return

            when (key) {
                "wallpaper_video_audio" -> exoPlayer?.volume = if (sharedPreferences.getBoolean(key, false)) 1f else 0f
                "wallpaper_video_speed" -> exoPlayer?.setPlaybackSpeed(sharedPreferences.getFloat(key, 1f))
                "wallpaper_video_loop" -> exoPlayer?.repeatMode = if (sharedPreferences.getBoolean(key, true)) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                "wallpaper_video_scale_mode" -> {
                    val scaleModeStr = sharedPreferences.getString(key, "Crop")
                    exoPlayer?.videoScalingMode = if (scaleModeStr == "Fit" || scaleModeStr == "Original") C.VIDEO_SCALING_MODE_SCALE_TO_FIT else C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                }
                "wallpaper_video_uri" -> {
                    val videoUriString = sharedPreferences.getString(key, null)
                    if (videoUriString != null) {
                        exoPlayer?.setMediaItem(ExoMediaItem.Builder().setUri(videoUriString.toUri()).setMediaId(videoUriString).build())
                        exoPlayer?.prepare()
                        if (isVisible) checkPowerAndPlay()
                    }
                }
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
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            }

            ContextCompat.registerReceiver(
                applicationContext,
                systemReceiver!!,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }

        private fun checkPowerAndPlay() {
            val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isPowerSaveMode) exoPlayer?.pause() else if (isVisible) exoPlayer?.play()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.isVisible = visible
            if (visible) {
                checkPowerAndPlay()
            } else {
                exoPlayer?.pause()
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            currentSurfaceHolder = holder
            exoPlayer?.setVideoSurfaceHolder(holder)
            if (exoPlayer?.playbackState == Player.STATE_IDLE) {
                exoPlayer?.prepare()
            }
            if (isVisible) checkPowerAndPlay()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            currentSurfaceHolder = holder
            exoPlayer?.setVideoSurfaceHolder(holder)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            currentSurfaceHolder = null
            exoPlayer?.clearVideoSurfaceHolder(holder)
            exoPlayer?.pause()
        }

        override fun onDestroy() {
            super.onDestroy()
            val prefs = getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            systemReceiver?.let {
                try { applicationContext.unregisterReceiver(it) } catch (e: Exception) { /* Ignored */ }
            }
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

    // Scale Mode state unified for both Image and Video
    var scaleMode by remember { mutableStateOf("Crop") }

    var showTargetDialog by remember { mutableStateOf(false) }
    var showAdjustments by remember { mutableStateOf(false) }
    var isApplying by remember { mutableStateOf(false) }

    val scaleAnim = remember { Animatable(1f) }
    val offsetXAnim = remember { Animatable(0f) }
    val offsetYAnim = remember { Animatable(0f) }
    val rotationAnim = remember { Animatable(0f) }

    var gestureScale by remember { mutableFloatStateOf(1f) }
    var gestureOffsetX by remember { mutableFloatStateOf(0f) }
    var gestureOffsetY by remember { mutableFloatStateOf(0f) }
    var gestureRotation by remember { mutableFloatStateOf(0f) }
    var isTransforming by remember { mutableStateOf(false) }

    val currentScale = if (isTransforming) gestureScale else scaleAnim.value
    val currentOffsetX = if (isTransforming) gestureOffsetX else offsetXAnim.value
    val currentOffsetY = if (isTransforming) gestureOffsetY else offsetYAnim.value
    val currentRotation = if (isTransforming) gestureRotation else rotationAnim.value

    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val density = LocalDensity.current
    val wPx = with(density) { screenWidth.toPx() }
    val hPx = with(density) { screenHeight.toPx() }

    var dimLevel by remember { mutableFloatStateOf(0f) }

    // Loaded bitmap state for preview matching final output
    var previewBitmap by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var imageLoadFailed by remember { mutableStateOf(false) }

    // Tap tracking for Double Tap (Fit) and Triple Tap (Original)
    var tapCount by remember { mutableIntStateOf(0) }
    var lastTapTime by remember { mutableLongStateOf(0L) }
    var tapJob by remember { mutableStateOf<Job?>(null) }

    if (!item.isVideo) {
        LaunchedEffect(item.uri) {
            try {
                val request = ImageRequest.Builder(context)
                    .data(item.uri)
                    .allowHardware(false) // required for direct canvas manipulation matching exactly
                    .build()
                val result = context.imageLoader.execute(request)
                if (result is SuccessResult) {
                    val drawable = result.drawable
                    if (drawable is BitmapDrawable) {
                        previewBitmap = drawable.bitmap.asImageBitmap()
                    }
                } else {
                    imageLoadFailed = true
                }
            } catch (e: Exception) {
                imageLoadFailed = true
            }
        }
    }

    val resetTransforms = {
        scope.launch { scaleAnim.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 400f)) }
        scope.launch { offsetXAnim.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 400f)) }
        scope.launch { offsetYAnim.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 400f)) }
        scope.launch { rotationAnim.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 400f)) }
    }

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
                onClick = {
                    if (!isApplying && (item.isVideo || previewBitmap != null)) {
                        showTargetDialog = true
                    } else if (imageLoadFailed) {
                        Toast.makeText(context, "Cannot apply, media corrupted or deleted", Toast.LENGTH_SHORT).show()
                    }
                },
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
                    if (!item.isVideo && previewBitmap != null) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            isTransforming = true

                            gestureScale = scaleAnim.value
                            gestureOffsetX = offsetXAnim.value
                            gestureOffsetY = offsetYAnim.value
                            gestureRotation = rotationAnim.value

                            var moved = false

                            do {
                                val event = awaitPointerEvent()
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                val rotChange = event.calculateRotation()

                                if (abs(zoomChange - 1f) > 0.005f || panChange.getDistance() > 1f || abs(rotChange) > 0.5f) {
                                    moved = true
                                }

                                var targetScale = gestureScale * zoomChange
                                if (targetScale < 0.5f) targetScale = 0.5f + (targetScale - 0.5f) * 0.3f
                                else if (targetScale > 10f) targetScale = 10f + (targetScale - 10f) * 0.3f
                                gestureScale = targetScale

                                val imgW = previewBitmap!!.width.toFloat()
                                val imgH = previewBitmap!!.height.toFloat()

                                val screenRatioX = wPx / imgW
                                val screenRatioY = hPx / imgH

                                val baseScaleX = when (scaleMode) {
                                    "Fit" -> min(screenRatioX, screenRatioY)
                                    "Fill" -> screenRatioX
                                    "Original" -> 1f
                                    else -> max(screenRatioX, screenRatioY) // Crop
                                }
                                val baseScaleY = when (scaleMode) {
                                    "Fit" -> min(screenRatioX, screenRatioY)
                                    "Fill" -> screenRatioY
                                    "Original" -> 1f
                                    else -> max(screenRatioX, screenRatioY)
                                }

                                val totalScaleX = baseScaleX * gestureScale
                                val totalScaleY = baseScaleY * gestureScale

                                val rotationRad = Math.toRadians(gestureRotation.toDouble())
                                val cosA = abs(cos(rotationRad)).toFloat()
                                val sinA = abs(sin(rotationRad)).toFloat()

                                val boundW = imgW * totalScaleX
                                val boundH = imgH * totalScaleY

                                val rotW = boundW * cosA + boundH * sinA
                                val rotH = boundW * sinA + boundH * cosA

                                val limitX = max(0f, (rotW - wPx) / 2f)
                                val limitY = max(0f, (rotH - hPx) / 2f)

                                var targetX = gestureOffsetX + panChange.x
                                var targetY = gestureOffsetY + panChange.y

                                if (targetX > limitX) targetX = limitX + (targetX - limitX) * 0.3f
                                else if (targetX < -limitX) targetX = -limitX + (targetX + limitX) * 0.3f

                                if (targetY > limitY) targetY = limitY + (targetY - limitY) * 0.3f
                                else if (targetY < -limitY) targetY = -limitY + (targetY + limitY) * 0.3f

                                gestureOffsetX = targetX
                                gestureOffsetY = targetY

                                var targetRot = gestureRotation + rotChange * 0.8f
                                if (targetRot > 180f) targetRot -= 360f
                                else if (targetRot < -180f) targetRot += 360f

                                gestureRotation = targetRot

                                event.changes.forEach { if (it.positionChange() != androidx.compose.ui.geometry.Offset.Zero) it.consume() }
                            } while (event.changes.any { it.pressed })

                            isTransforming = false

                            if (!moved) {
                                val now = System.currentTimeMillis()
                                if (now - lastTapTime < 300) {
                                    tapCount++
                                } else {
                                    tapCount = 1
                                }
                                lastTapTime = now

                                tapJob?.cancel()
                                tapJob = scope.launch {
                                    delay(300)
                                    if (tapCount == 2) {
                                        scaleMode = "Fit"
                                        resetTransforms()
                                    } else if (tapCount >= 3) {
                                        scaleMode = "Original"
                                        resetTransforms()
                                    }
                                    tapCount = 0
                                }
                            }

                            scope.launch {
                                val imgW = previewBitmap!!.width.toFloat()
                                val imgH = previewBitmap!!.height.toFloat()

                                val screenRatioX = wPx / imgW
                                val screenRatioY = hPx / imgH

                                val baseScaleX = when (scaleMode) {
                                    "Fit" -> min(screenRatioX, screenRatioY)
                                    "Fill" -> screenRatioX
                                    "Original" -> 1f
                                    else -> max(screenRatioX, screenRatioY)
                                }
                                val baseScaleY = when (scaleMode) {
                                    "Fit" -> min(screenRatioX, screenRatioY)
                                    "Fill" -> screenRatioY
                                    "Original" -> 1f
                                    else -> max(screenRatioX, screenRatioY)
                                }

                                val finalScale = gestureScale.coerceIn(0.5f, 10f)

                                val totalScaleX = baseScaleX * finalScale
                                val totalScaleY = baseScaleY * finalScale

                                val rotationRad = Math.toRadians(gestureRotation.toDouble())
                                val cosA = abs(cos(rotationRad)).toFloat()
                                val sinA = abs(sin(rotationRad)).toFloat()

                                val boundW = imgW * totalScaleX
                                val boundH = imgH * totalScaleY

                                val rotW = boundW * cosA + boundH * sinA
                                val rotH = boundW * sinA + boundH * cosA

                                val limitX = max(0f, (rotW - wPx) / 2f)
                                val limitY = max(0f, (rotH - hPx) / 2f)

                                val finalX = gestureOffsetX.coerceIn(-limitX, limitX)
                                val finalY = gestureOffsetY.coerceIn(-limitY, limitY)

                                scaleAnim.snapTo(gestureScale)
                                offsetXAnim.snapTo(gestureOffsetX)
                                offsetYAnim.snapTo(gestureOffsetY)
                                rotationAnim.snapTo(gestureRotation)

                                launch { scaleAnim.animateTo(finalScale, spring(dampingRatio = 0.8f, stiffness = 400f)) }
                                launch { offsetXAnim.animateTo(finalX, spring(dampingRatio = 0.8f, stiffness = 400f)) }
                                launch { offsetYAnim.animateTo(finalY, spring(dampingRatio = 0.8f, stiffness = 400f)) }
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {

            if (item.isVideo) {
                var playerError by remember { mutableStateOf(false) }
                val player = remember {
                    ExoPlayer.Builder(context).build().apply {
                        setMediaItem(ExoMediaItem.fromUri(item.uri))
                        playWhenReady = true
                        addListener(object : Player.Listener {
                            override fun onPlayerError(error: PlaybackException) {
                                playerError = true
                            }
                        })
                        prepare()
                    }
                }

                LaunchedEffect(isAudioEnabled) { player.volume = if (isAudioEnabled) 1f else 0f }
                LaunchedEffect(playbackSpeed) { player.setPlaybackSpeed(playbackSpeed) }
                LaunchedEffect(loopVideo) { player.repeatMode = if (loopVideo) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF }

                DisposableEffect(Unit) { onDispose { player.release() } }

                if (playerError) {
                    Text("Failed to load video", color = androidx.compose.ui.graphics.Color.White)
                } else {
                    key(scaleMode) {
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    useController = false
                                    this.player = player
                                    this.resizeMode = when (scaleMode) {
                                        "Fit" -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                                        "Fill" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                        "Original" -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                                        else -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                    }
                                }
                            },
                            update = { view ->
                                view.resizeMode = when (scaleMode) {
                                    "Fit" -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                                    "Fill" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                    "Original" -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                                    else -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            } else {
                if (imageLoadFailed) {
                    Text("Failed to load image", color = androidx.compose.ui.graphics.Color.White)
                } else if (previewBitmap != null) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val imgW = previewBitmap!!.width.toFloat()
                        val imgH = previewBitmap!!.height.toFloat()

                        val screenRatioX = size.width / imgW
                        val screenRatioY = size.height / imgH

                        val baseScaleX = when (scaleMode) {
                            "Fit" -> min(screenRatioX, screenRatioY)
                            "Fill" -> screenRatioX
                            "Original" -> 1f
                            else -> max(screenRatioX, screenRatioY)
                        }
                        val baseScaleY = when (scaleMode) {
                            "Fit" -> min(screenRatioX, screenRatioY)
                            "Fill" -> screenRatioY
                            "Original" -> 1f
                            else -> max(screenRatioX, screenRatioY)
                        }

                        val cx = size.width / 2f
                        val cy = size.height / 2f

                        withTransform({
                            translate(cx + currentOffsetX, cy + currentOffsetY)
                            rotate(currentRotation)
                            scale(baseScaleX * currentScale, baseScaleY * currentScale)
                            translate(-imgW / 2f, -imgH / 2f)
                        }) {
                            drawImage(image = previewBitmap!!)
                        }
                    }
                } else {
                    CircularProgressIndicator(color = androidx.compose.ui.graphics.Color.White)
                }
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
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Scale Mode", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold)
                            Row {
                                listOf("Crop", "Fit", "Fill", "Original").forEach { mode ->
                                    TextButton(onClick = { scaleMode = mode; resetTransforms() }) {
                                        Text(mode, color = if (scaleMode == mode) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }

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
                        } else {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Rotation", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold)
                                Row {
                                    IconButton(onClick = { scope.launch { rotationAnim.animateTo(rotationAnim.value - 90f) } }) {
                                        Icon(Icons.Filled.RotateLeft, "Rotate Left", tint = androidx.compose.ui.graphics.Color.White)
                                    }
                                    IconButton(onClick = { scope.launch { rotationAnim.animateTo(rotationAnim.value + 90f) } }) {
                                        Icon(Icons.Filled.RotateRight, "Rotate Right", tint = androidx.compose.ui.graphics.Color.White)
                                    }
                                }
                            }
                            Text("Dimming", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold)
                            Slider(value = dimLevel, onValueChange = { dimLevel = it }, valueRange = 0f..0.8f)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                TextButton(onClick = {
                                    resetTransforms()
                                    dimLevel = 0f
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
            onDismissRequest = { if (!isApplying) showTargetDialog = false },
            title = { Text("Set Wallpaper") },
            text = {
                Column {
                    if (item.isVideo) {
                        Text("Live Wallpapers are handled by the system. The OS will choose to apply it to the Home Screen or Both.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(16.dp))
                        Text("Playback", fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(enabled = !isApplying) { videoWithSound = true }) { RadioButton(selected = videoWithSound, onClick = { videoWithSound = true }, enabled = !isApplying); Text("With Sound") }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(enabled = !isApplying) { videoWithSound = false }) { RadioButton(selected = !videoWithSound, onClick = { videoWithSound = false }, enabled = !isApplying); Text("Without Sound") }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(enabled = !isApplying) { applyTo = WallpaperManager.FLAG_SYSTEM }) { RadioButton(selected = applyTo == WallpaperManager.FLAG_SYSTEM, onClick = { applyTo = WallpaperManager.FLAG_SYSTEM }, enabled = !isApplying); Text("Home Screen") }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(enabled = !isApplying) { applyTo = WallpaperManager.FLAG_LOCK }) { RadioButton(selected = applyTo == WallpaperManager.FLAG_LOCK, onClick = { applyTo = WallpaperManager.FLAG_LOCK }, enabled = !isApplying); Text("Lock Screen") }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(enabled = !isApplying) { applyTo = (WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK) }) { RadioButton(selected = applyTo == (WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK), onClick = { applyTo = (WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK) }, enabled = !isApplying); Text("Both Screens") }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !isApplying,
                    onClick = {
                        isApplying = true
                        if (item.isVideo) {
                            scope.launch {
                                try {
                                    VideoEngine.setVideoWallpaper(context, item, videoWithSound, playbackSpeed, loopVideo, scaleMode)
                                } finally {
                                    isApplying = false
                                    showTargetDialog = false
                                }
                            }
                        } else {
                            scope.launch {
                                try {
                                    StaticEngine.apply(context, item, applyTo, scaleMode, currentScale, currentOffsetX, currentOffsetY, currentRotation, dimLevel)
                                } finally {
                                    isApplying = false
                                    showTargetDialog = false
                                }
                            }
                        }
                    }
                ) { Text("Apply") }
            },
            dismissButton = {
                TextButton(
                    enabled = !isApplying,
                    onClick = { showTargetDialog = false }
                ) { Text("Cancel") }
            }
        )
    }
}