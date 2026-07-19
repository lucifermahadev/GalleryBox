@file:Suppress("UnsafeOptInUsageError", "UnstableApiUsage", "OPT_IN_USAGE", "unused", "DEPRECATION")
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.gallerybox.ui.screens.vault

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.hardware.Sensor
import java.util.Formatter
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import com.gallerybox.findActivity
import android.hardware.SensorManager
import android.net.Uri
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricPrompt
import android.util.Log
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.common.MediaItem as ExoMediaItem
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.gallerybox.data.MediaItem
import com.gallerybox.viewmodel.GalleryViewModel
import com.gallerybox.viewmodel.SecurityViewModel
import kotlinx.coroutines.*
import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun VaultSecureScreen(
    viewModel: GalleryViewModel = hiltViewModel(),
    securityViewModel: SecurityViewModel = hiltViewModel(),
    isGlobalAppGuard: Boolean = false,
    onBack: () -> Unit,
    onNavigateToPicker: () -> Unit = {},
    onUnlockGlobalSuccess: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = remember(context) { context.findActivity() }

    val unlocked by securityViewModel.isUnlocked.collectAsState()
    var isUnlocking by remember { mutableStateOf(false) }
    val autoLockTimeout by securityViewModel.autoLockTimeout.collectAsState(initial = 5)

    VaultShakeDetector {
        securityViewModel.lock()
        viewModel.clearTempVaultCache()
        if (!isGlobalAppGuard) onBack()
    }

    LaunchedEffect(Unit) {
        viewModel.clearTempVaultCache()
    }

    val hiddenItems by viewModel.hiddenMedia.collectAsState(emptyList())

    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            securityViewModel.lock()
            viewModel.clearTempVaultCache()
        }
    }

    DisposableEffect(lifecycleOwner, autoLockTimeout) {
        val obs = LifecycleEventObserver { _, ev ->
            if (ev == Lifecycle.Event.ON_PAUSE || ev == Lifecycle.Event.ON_STOP) {
                if (autoLockTimeout == 0) {
                    securityViewModel.lock()
                    viewModel.clearTempVaultCache()
                }
            }
            if (ev == Lifecycle.Event.ON_RESUME) {
                if (securityViewModel.shouldRelock(autoLockTimeout)) {
                    securityViewModel.lock()
                }
            }
            if (ev == Lifecycle.Event.ON_DESTROY) {
                securityViewModel.lock()
                viewModel.clearTempVaultCache()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
        }
    }

    AnimatedContent(
        targetState = when {
            isUnlocking -> "PROCESSING"
            !unlocked -> "AUTH_GUARD"
            else -> "GRANTED"
        },
        label = "VaultStateTransition"
    ) { state ->
        when (state) {
            "PROCESSING" -> {
                Box(
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Preparing Vault...", color = MaterialTheme.colorScheme.onBackground)
                    }
                }
            }
            "AUTH_GUARD" -> {
                StandardAppLockScreen(
                    viewModel = securityViewModel,
                    isGlobalAppGuard = isGlobalAppGuard,
                    onBack = onBack
                )
            }
            "GRANTED" -> {
                LaunchedEffect(Unit) {
                    isUnlocking = false
                    if (isGlobalAppGuard) onUnlockGlobalSuccess()
                }
                if (!isGlobalAppGuard) {
                    VaultGridScreen(
                        items = hiddenItems,
                        viewModel = viewModel,
                        onBack = onBack,
                        onAdd = onNavigateToPicker
                    )
                }
            }
        }
    }
}

@Composable
fun StandardAppLockScreen(
    viewModel: SecurityViewModel,
    isGlobalAppGuard: Boolean,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var bioShown by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = isGlobalAppGuard) {
        activity?.moveTaskToBack(true)
    }

    val triggerBiometrics = {
        if (activity is FragmentActivity && viewModel.canUseSystemAuthentication()) {
            BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(context),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(res: BiometricPrompt.AuthenticationResult) {
                        super.onAuthenticationSucceeded(res)
                        viewModel.onAuthenticationSuccess()
                    }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        super.onAuthenticationError(errorCode, errString)
                    }
                    override fun onAuthenticationFailed() {
                        super.onAuthenticationFailed()
                    }
                }
            ).authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle("GalleryBox")
                    .setSubtitle("Confirm your identity")
                    .setAllowedAuthenticators(
                        androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                                androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    )
                    .build()
            )
        }
    }

    LaunchedEffect(Unit) {
        if (!bioShown) {
            bioShown = true
            triggerBiometrics()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.Lock,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = if (isGlobalAppGuard) "App Locked" else "Vault Locked",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(16.dp))

        FilledTonalButton(
            onClick = triggerBiometrics,
            modifier = Modifier.fillMaxWidth(0.8f).height(50.dp)
        ) {
            Icon(imageVector = Icons.Default.LockOpen, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "Unlock", fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (!isGlobalAppGuard) {
            TextButton(onClick = onBack) {
                Text("Cancel")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun VaultGridScreen(
    items: List<MediaItem>,
    viewModel: GalleryViewModel,
    onBack: () -> Unit,
    onAdd: () -> Unit
) {
    val context = LocalContext.current
    var selectionMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateMapOf<Long, Long>() }
    var viewerItemId by remember { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val haptic = LocalHapticFeedback.current

    BackHandler(enabled = viewerItemId != null || selectionMode) {
        if (viewerItemId != null) {
            viewerItemId = null
        } else if (selectionMode) {
            selectionMode = false
            selectedIds.clear()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = if (selectionMode) "${selectedIds.size} selected" else "Secure Vault",
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (selectionMode) {
                                selectionMode = false
                                selectedIds.clear()
                            } else {
                                onBack()
                            }
                        }) {
                            Icon(
                                imageVector = if (selectionMode) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = null
                            )
                        }
                    },
                    actions = {
                        if (!selectionMode) {
                            IconButton(onClick = onAdd) {
                                Icon(imageVector = Icons.Default.Add, contentDescription = "Add")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
            },
            bottomBar = {
                AnimatedVisibility(
                    visible = selectionMode,
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
                ) {
                    Surface(
                        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                        shadowElevation = 8.dp,
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            ActionItem(
                                icon = Icons.Outlined.LockOpen,
                                label = "Unhide"
                            ) {
                                viewModel.unhideMedia(selectedIds.keys.toList())
                                selectedIds.clear()
                                selectionMode = false
                            }

                            ActionItem(
                                icon = Icons.Outlined.Share,
                                label = "Export"
                            ) {
                                val exportItems = items.filter { selectedIds.containsKey(it.id) }
                                scope.launch {
                                    stripExifAndShare(context, exportItems, viewModel)
                                    selectedIds.clear()
                                    selectionMode = false
                                }
                            }

                            ActionItem(
                                icon = Icons.Outlined.Delete,
                                label = "Delete",
                                isDestructive = true
                            ) {
                                viewModel.deleteSecureMedia(selectedIds.keys.toList())
                                selectedIds.clear()
                                selectionMode = false
                            }
                        }
                    }
                }
            }
        ) { padding ->
            if (items.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Outlined.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(80.dp),
                            tint = MaterialTheme.colorScheme.surfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(text = "Vault is empty", fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.padding(padding).fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 120.dp, start = 2.dp, end = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(items, key = { it.id }) { item ->
                        val isSelected = selectedIds.containsKey(item.id)
                        val tileScale by animateFloatAsState(
                            targetValue = if (isSelected) 0.90f else 1f,
                            label = "tileScale"
                        )
                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .graphicsLayer {
                                    scaleX = tileScale
                                    scaleY = tileScale
                                    clip = true
                                    shape = RoundedCornerShape(if (isSelected) 12.dp else 0.dp)
                                }
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .combinedClickable(
                                    onClick = {
                                        if (selectionMode) {
                                            if (selectedIds.containsKey(item.id)) {
                                                selectedIds.remove(item.id)
                                            } else {
                                                selectedIds[item.id] = item.size
                                            }
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        } else {
                                            viewerItemId = item.id
                                        }
                                    },
                                    onLongClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        if (!selectionMode) {
                                            selectionMode = true
                                            selectedIds[item.id] = item.size
                                        }
                                    }
                                )
                        ) {
                            SecureAsyncImage(
                                item = item,
                                viewModel = viewModel,
                                isThumbnail = true,
                                modifier = Modifier.fillMaxSize()
                            )

                            if (item.isVideo) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayCircle,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.align(Alignment.Center).size(24.dp)
                                )
                            }

                            if (selectionMode) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else Color.Transparent)
                                        .border(if (isSelected) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(if (isSelected) 12.dp else 0.dp))
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .padding(8.dp)
                                            .align(Alignment.TopEnd)
                                            .size(22.dp)
                                            .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(0.3f), CircleShape)
                                            .border(1.5.dp, Color.White, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Filled.Check,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = viewerItemId != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            if (viewerItemId != null) {
                SecureFullscreenViewer(
                    initialIndex = items.indexOfFirst { it.id == viewerItemId },
                    items = items,
                    viewModel = viewModel,
                    onBack = { viewerItemId = null }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SecureFullscreenViewer(
    initialIndex: Int,
    items: List<MediaItem>,
    viewModel: GalleryViewModel,
    onBack: () -> Unit
) {
    if (items.isEmpty()) return

    val context = LocalContext.current
    val view = LocalView.current
    val haptic = LocalHapticFeedback.current
    val containerHeightPx = LocalWindowInfo.current.containerSize.height

    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { items.size })
    var showControls by remember { mutableStateOf(true) }
    var showMeta by remember { mutableStateOf(false) }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = false
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer.release()
        }
    }

    DisposableEffect(context.findActivity()) {
        val w = context.findActivity()?.window
        if (w != null) {
            val c = WindowCompat.getInsetsController(w, view)
            c.hide(WindowInsetsCompat.Type.systemBars())
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            w?.let {
                WindowCompat.getInsetsController(it, view).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    BackHandler(enabled = !showControls) {
        showControls = true
    }
    BackHandler(enabled = showControls) {
        onBack()
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            pageSpacing = 16.dp,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val item = items[page]
            var tempFile by remember(item.id) { mutableStateOf<File?>(null) }
            var videoLoading by remember(item.id) { mutableStateOf(item.isVideo) }
            var trigger by remember { mutableIntStateOf(0) }
            var dragOffsetY by remember { mutableFloatStateOf(0f) }
            var scale by remember { mutableFloatStateOf(1f) }
            var offset by remember { mutableStateOf(Offset.Zero) }
            var isVideoPlaying by remember(item.id) { mutableStateOf(false) }

            DisposableEffect(item.id) {
                onDispose {
                    if (exoPlayer.currentMediaItem?.mediaId == item.id.toString()) {
                        exoPlayer.stop()
                        exoPlayer.clearMediaItems()
                    }
                    tempFile?.let {
                        it.delete()
                        viewModel.deleteTempFile(it)
                    }
                    isVideoPlaying = false
                }
            }

            LaunchedEffect(item.id, trigger) {
                if (item.isVideo) {
                    videoLoading = true
                    tempFile = withContext(Dispatchers.IO) {
                        viewModel.decryptToTempFile(item.path)
                    }
                    tempFile?.deleteOnExit()
                    videoLoading = false
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset { IntOffset(0, dragOffsetY.roundToInt()) }
                    .graphicsLayer {
                        scaleX = scale * (1f - (abs(dragOffsetY) / 2000f))
                        scaleY = scaleX
                        alpha = 1f - (abs((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction) * 0.3f)
                        translationX = offset.x
                        translationY = offset.y
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { showControls = !showControls },
                            onDoubleTap = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                scale = if (scale > 1f) 1f else 2.5f
                                offset = Offset.Zero
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 3f)
                            if (scale > 1.05f) {
                                offset += pan
                                dragOffsetY = 0f
                            } else {
                                offset = Offset.Zero
                                dragOffsetY += pan.y
                                if (abs(dragOffsetY) > 50f) {
                                    showControls = false
                                }
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown()
                            do {
                                val ev = awaitPointerEvent()
                            } while (ev.changes.any { it.pressed })
                            if (scale <= 1.05f && abs(dragOffsetY) > containerHeightPx * 0.25f) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onBack()
                            } else {
                                dragOffsetY = 0f
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                when {
                    videoLoading -> CircularProgressIndicator()
                    !item.isVideo -> SecureAsyncImage(
                        item = item,
                        viewModel = viewModel,
                        isThumbnail = false,
                        modifier = Modifier.fillMaxSize()
                    )
                    item.isVideo && tempFile != null -> {
                        if (isVideoPlaying) {
                            AndroidView(
                                factory = {
                                    PlayerView(context).apply {
                                        player = exoPlayer
                                        useController = true
                                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                                    }
                                },
                                update = {
                                    if (exoPlayer.currentMediaItem?.mediaId != item.id.toString()) {
                                        exoPlayer.clearMediaItems()
                                        exoPlayer.setMediaItem(
                                            ExoMediaItem.Builder()
                                                .setUri(Uri.fromFile(tempFile))
                                                .setMediaId(item.id.toString())
                                                .build()
                                        )
                                        exoPlayer.prepare()
                                        exoPlayer.play()
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                SecureAsyncImage(
                                    item = item,
                                    viewModel = viewModel,
                                    isThumbnail = true,
                                    modifier = Modifier.fillMaxSize()
                                )
                                IconButton(
                                    onClick = {
                                        isVideoPlaying = true
                                        exoPlayer.playWhenReady = true
                                        showControls = false
                                    },
                                    modifier = Modifier.size(80.dp).background(Color.Black.copy(0.4f), CircleShape)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.PlayCircle,
                                        contentDescription = "Play",
                                        tint = Color.White,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }
                    }
                    else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(64.dp)
                        )
                        Text(text = "Decryption Failure", color = Color.White)
                        TextButton(onClick = { trigger++ }) {
                            Text(text = "Retry")
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = showControls,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(0.7f), Color.Transparent)))
                    .statusBarsPadding()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        tint = Color.White
                    )
                }
                if (items.isNotEmpty()) {
                    Text(
                        text = items[pagerState.currentPage].dateHeader,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
                IconButton(onClick = { showMeta = true }) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        tint = Color.White
                    )
                }
            }
        }
    }

    if (showMeta) {
        val currentItem = items.getOrNull(pagerState.currentPage)
        val context = LocalContext.current
        val locale = androidx.compose.ui.text.intl.Locale.current.platformLocale

        if (currentItem != null) {
            val dateString = remember(currentItem.dateAdded, locale) {
                val pattern = if (android.text.format.DateFormat.is24HourFormat(context)) {
                    "EEEE, MMMM dd, yyyy 'at' HH:mm"
                } else {
                    "EEEE, MMMM dd, yyyy 'at' hh:mm a"
                }
                SimpleDateFormat(pattern, locale).format(Date(currentItem.dateAdded * 1000L))
            }

            ModalBottomSheet(onDismissRequest = { showMeta = false }) {
                Column(Modifier.padding(24.dp).padding(bottom = 24.dp)) {
                    Text(
                        text = "Secure File Details",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(24.dp))

                    MetadataRow(
                        icon = Icons.Outlined.Title,
                        label = "Name",
                        value = currentItem.name
                    )
                    MetadataRow(
                        icon = Icons.Outlined.Folder,
                        label = "Encrypted Container Path",
                        value = currentItem.path
                    )
                    MetadataRow(
                        icon = Icons.Outlined.CalendarToday,
                        label = "Injected Timestamp",
                        value = dateString
                    )
                    MetadataRow(
                        icon = Icons.Outlined.Storage,
                        label = "Size",
                        value = android.text.format.Formatter.formatFileSize(context, currentItem.size)
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.errorContainer.copy(0.3f), RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Directly pulling this asset bypasses privacy buffers. Use the metadata scrub export tool for secure transmission.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        } else {
            showMeta = false
        }
    }
}

// =======================================================
// 4. UI COMPONENTS
// =======================================================
@Composable
fun SecureAsyncImage(
    item: MediaItem,
    viewModel: GalleryViewModel,
    isThumbnail: Boolean,
    modifier: Modifier = Modifier
) {
    var bytes by remember(item.id) { mutableStateOf<ByteArray?>(null) }
    var loading by remember(item.id) { mutableStateOf(true) }
    var trigger by remember { mutableIntStateOf(0) }
    val context = LocalContext.current

    DisposableEffect(item.id) {
        onDispose {
            bytes = null
        }
    }

    LaunchedEffect(item.id, trigger) {
        loading = true
        if (!item.isVideo) {
            bytes = withContext(Dispatchers.IO) {
                if (isThumbnail) viewModel.decryptThumbnailToMemory(item.path) else viewModel.decryptToMemory(item.path)
            }
        }
        loading = false
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when {
            loading -> CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp
            )
            item.isVideo && isThumbnail -> AsyncImage(
                model = ImageRequest.Builder(context).data(item.uri).videoFrameMillis(1000).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            bytes != null -> AsyncImage(
                model = ImageRequest.Builder(context).data(bytes).memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED).allowHardware(false).build(),
                contentScale = if (isThumbnail) ContentScale.Crop else ContentScale.Fit,
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
            )
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(imageVector = Icons.Default.BrokenImage, contentDescription = null)
                if (!isThumbnail) {
                    TextButton(onClick = { trigger++ }) {
                        Text(text = "Retry")
                    }
                }
            }
        }
    }
}

@Composable
fun ActionItem(
    icon: ImageVector,
    label: String,
    isDestructive: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick).padding(8.dp)
    ) {
        val color = if (!enabled) {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
        } else if (isDestructive) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurface
        }

        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = color
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            color = color
        )
    }
}

@Composable
fun MetadataRow(
    icon: ImageVector,
    label: String,
    value: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

// =======================================================
// 5. UTILITIES (PANIC, LOGS, OVERWRITE)
// =======================================================
@Composable
fun VaultShakeDetector(onShakeDetected: () -> Unit) {
    val context = LocalContext.current
    val sensorManager = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val accelerometer = remember { sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }

    DisposableEffect(sensorManager, accelerometer) {
        if (accelerometer == null) {
            return@DisposableEffect onDispose { }
        }

        val listener = object : SensorEventListener {
            private var lastUpdate = 0L
            private var lastX = 0f
            private var lastY = 0f
            private var lastZ = 0f
            private val SHAKE_THRESHOLD = 800f

            override fun onSensorChanged(event: SensorEvent?) {
                if (event == null) return
                val currentTime = System.currentTimeMillis()
                if ((currentTime - lastUpdate) > 100) {
                    val diffTime = currentTime - lastUpdate
                    lastUpdate = currentTime
                    val x = event.values[0]
                    val y = event.values[1]
                    val z = event.values[2]
                    val speed: Float = kotlin.math.abs(x + y + z - lastX - lastY - lastZ) / diffTime * 10000f
                    if (speed > SHAKE_THRESHOLD) {
                        onShakeDetected()
                    }
                    lastX = x
                    lastY = y
                    lastZ = z
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        sensorManager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
        onDispose {
            sensorManager.unregisterListener(listener)
        }
    }
}

suspend fun secureWipeFile(file: File) = withContext(Dispatchers.IO) {
    if (!file.exists()) return@withContext
    try {
        val random = SecureRandom()
        RandomAccessFile(file, "rws").use { raf ->
            val b = ByteArray(4096)
            var w = 0L
            while (w < file.length()) {
                random.nextBytes(b)
                val t = minOf(b.size.toLong(), file.length() - w).toInt()
                raf.write(b, 0, t)
                w += t
            }
        }
    } finally {
        file.delete()
    }
}

private suspend fun stripExifAndShare(
    context: Context,
    items: List<MediaItem>,
    viewModel: GalleryViewModel
) {
    withContext(Dispatchers.IO) {
        val uris = mutableListOf<Uri>()
        for (item in items) {
            val f = viewModel.decryptToTempFile(item.path) ?: continue
            f.deleteOnExit()
            if (!item.isVideo && (item.mimeType.contains("jpeg") || item.mimeType.contains("jpg"))) {
                try {
                    ExifInterface(f.absolutePath).apply {
                        setAttribute(ExifInterface.TAG_GPS_LATITUDE, null)
                        setAttribute(ExifInterface.TAG_GPS_LONGITUDE, null)
                        setAttribute(ExifInterface.TAG_DATETIME, null)
                        setAttribute(ExifInterface.TAG_MAKE, "SecureVault")
                        setAttribute(ExifInterface.TAG_MODEL, "SecureVault")
                        saveAttributes()
                    }
                } catch (e: Exception) {
                    Log.e("VaultShare", "EXIF clean skipped", e)
                }
            }
            uris.add(FileProvider.getUriForFile(context, "${context.packageName}.provider", f))
        }
        withContext(Dispatchers.Main) {
            if (uris.isNotEmpty()) {
                val intent = Intent().apply {
                    action = if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND
                    type = "*/*"
                    if (uris.size > 1) {
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                    } else {
                        putExtra(Intent.EXTRA_STREAM, uris.first())
                    }
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "Share Securely"))
            }
        }
    }
}