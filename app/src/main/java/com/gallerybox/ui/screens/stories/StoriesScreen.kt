@file:Suppress("unused", "UnsafeOptInUsageError", "UnstableApiUsage", "OPT_IN_USAGE")
@file:OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3Api::class)

package com.gallerybox.ui.screens.stories

import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.database.sqlite.SQLiteException
import android.graphics.Bitmap
import android.graphics.RenderEffect
import android.graphics.Shader
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.hilt.work.HiltWorker
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import coil.size.Precision
import coil.size.Size
import com.gallerybox.data.GalleryDao
import com.gallerybox.data.StoryEntity
import com.gallerybox.data.UiStory
import com.gallerybox.ui.screens.picture.GalleryGridItem
import com.gallerybox.viewmodel.GalleryEvent
import com.gallerybox.viewmodel.GalleryViewModel
import com.gallerybox.viewmodel.StoryViewModel
import com.gallerybox.viewmodel.TrashViewModel
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

@Composable
fun StoriesScreen(
    viewModel: GalleryViewModel = hiltViewModel(),
    storyViewModel: StoryViewModel = hiltViewModel(),
    trashViewModel: TrashViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val mediaMap by viewModel.mediaMap.collectAsState()

    LaunchedEffect(mediaMap) {
        storyViewModel.updateMediaMap(mediaMap)
    }

    LaunchedEffect(Unit) {
        storyViewModel.events.collect { event ->
            when (event) {
                is GalleryEvent.ShowToast -> Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                else -> {}
            }
        }
    }

    val stories by storyViewModel.stories.collectAsState()
    val viewModelBusy by viewModel.isBusy.collectAsState()
    val workManager = remember { WorkManager.getInstance(context) }
    val workInfos by workManager.getWorkInfosForUniqueWorkLiveData("story_generation").observeAsState(emptyList())
    val isWorkerRunning by remember { derivedStateOf { workInfos.any { !it.state.isFinished } } }
    val isBusy by remember { derivedStateOf { viewModelBusy || isWorkerRunning } }
    val pagedMedia = viewModel.pagedMedia.collectAsLazyPagingItems()

    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<Long>()) }
    var activeStoryIndex by remember { mutableStateOf<Int?>(null) }
    var showNameDialog by remember { mutableStateOf(false) }
    var newStoryTitle by remember { mutableStateOf("") }
    val gridState = rememberLazyGridState()

    val sharedExoPlayer = remember(context) {
        ExoPlayer.Builder(context.applicationContext).build().apply { repeatMode = Player.REPEAT_MODE_OFF }
    }

    val selectedVideos = remember(selectedIds, mediaMap) {
        selectedIds.count { mediaMap[it]?.isVideo == true }
    }

    LaunchedEffect(Unit) {
        val request = androidx.work.OneTimeWorkRequestBuilder<StoryCreationWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "story_generation",
            androidx.work.ExistingWorkPolicy.REPLACE,
            request
        )
    }

    DisposableEffect(sharedExoPlayer) {
        onDispose { sharedExoPlayer.release() }
    }

    LaunchedEffect(stories) {
        stories.take(8).forEach {
            context.imageLoader.enqueue(
                ImageRequest.Builder(context)
                    .data(it.coverUri)
                    .size(320)
                    .bitmapConfig(Bitmap.Config.RGB_565)
                    .build()
            )
        }
    }

    BackHandler(enabled = activeStoryIndex != null) {
        activeStoryIndex = null
    }

    BackHandler(enabled = isSelectionMode) {
        isSelectionMode = false; selectedIds = emptySet()
    }

    SharedTransitionLayout {
        AnimatedContent(
            targetState = activeStoryIndex,
            label = "StoryMorph",
            transitionSpec = { fadeIn(tween(300)).togetherWith(fadeOut(tween(300))) }
        ) { currentIndex ->
            if (currentIndex == null) {
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    floatingActionButton = {
                        if (!isSelectionMode) {
                            SmallFloatingActionButton(
                                onClick = { isSelectionMode = true },
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                elevation = FloatingActionButtonDefaults.elevation(2.dp),
                                modifier = Modifier.padding(bottom = 16.dp, end = 8.dp)
                            ) {
                                Icon(Icons.Rounded.Add, "Create Manual Story")
                            }
                        }
                    }
                ) { paddingValues ->
                    Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                        if (isSelectionMode) {
                            MediaSelectorGrid(
                                pagedMedia = pagedMedia,
                                selectedIds = selectedIds,
                                onToggle = { id -> selectedIds = if (selectedIds.contains(id)) selectedIds - id else selectedIds + id },
                                contentPadding = WindowInsets.systemBars.asPaddingValues()
                            )
                        } else if (stories.isEmpty() && !isBusy) {
                            EmptyStoriesView()
                        } else if (stories.isEmpty() && isBusy) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            }
                        } else {
                            LazyVerticalGrid(
                                state = gridState,
                                columns = GridCells.Fixed(2),
                                contentPadding = WindowInsets.systemBars.asPaddingValues(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)
                            ) {
                                item(span = { GridItemSpan(2) }) { Spacer(Modifier.height(88.dp)) }

                                items(stories.size, key = { index -> stories[index].id }, contentType = { "story" }) { index ->
                                    val story = stories[index]
                                    StoryCard(
                                        story = story,
                                        sharedTransitionScope = this@SharedTransitionLayout,
                                        animatedVisibilityScope = this@AnimatedContent,
                                        onClick = { activeStoryIndex = index },
                                        onDelete = { storyViewModel.deleteStory(story.id) },
                                        onSave = { Toast.makeText(context, "Exporting '${story.title}' to Gallery...", Toast.LENGTH_SHORT).show() }
                                    )
                                }

                                item(span = { GridItemSpan(2) }) { Spacer(Modifier.height(100.dp)) }
                            }
                        }

                        FloatingHeader(
                            isSelectionMode = isSelectionMode,
                            selectedCount = selectedIds.size,
                            videoCount = selectedVideos,
                            onCancelSelection = { isSelectionMode = false; selectedIds = emptySet() },
                            onCreateClick = { if (selectedIds.isNotEmpty()) showNameDialog = true },
                            onStartManualStory = { isSelectionMode = true }
                        )
                    }
                }
            } else {
                stories.getOrNull(currentIndex)?.let { activeStory ->
                    StoryPlayer(
                        story = activeStory,
                        sharedExoPlayer = sharedExoPlayer,
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = this@AnimatedContent,
                        onClose = { activeStoryIndex = null },
                        onNextStoryGroup = {
                            if (currentIndex < stories.lastIndex) activeStoryIndex = currentIndex + 1 else activeStoryIndex = null
                        },
                        onPrevStoryGroup = {
                            if (currentIndex > 0) activeStoryIndex = currentIndex - 1 else activeStoryIndex = null
                        }
                    )
                }
            }
        }
    }

    if (showNameDialog) {
        AlertDialog(
            onDismissRequest = { showNameDialog = false; newStoryTitle = "" },
            title = { Text("Name your story", color = MaterialTheme.colorScheme.onSurface) },
            text = {
                OutlinedTextField(
                    value = newStoryTitle,
                    onValueChange = { newStoryTitle = it },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary)
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (selectedIds.isEmpty()) {
                        Toast.makeText(context, "Select at least one item", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    if (newStoryTitle.isBlank()) {
                        Toast.makeText(context, "Enter story name", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    storyViewModel.createManualStory(selectedIds.toList(), newStoryTitle.trim())
                    isSelectionMode = false
                    selectedIds = emptySet()
                    newStoryTitle = ""
                    showNameDialog = false
                }) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNameDialog = false; newStoryTitle = "" }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun FloatingHeader(
    isSelectionMode: Boolean,
    selectedCount: Int,
    videoCount: Int,
    onCancelSelection: () -> Unit,
    onCreateClick: () -> Unit,
    onStartManualStory: () -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)) {
        Box(
            modifier = Modifier.matchParentSize()
                .graphicsLayer { renderEffect = RenderEffect.createBlurEffect(8f, 8f, Shader.TileMode.MIRROR).asComposeRenderEffect() }
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.4f))
        )
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (isSelectionMode) {
                IconButton(onClick = onCancelSelection) {
                    Icon(Icons.Default.Close, "Cancel", tint = MaterialTheme.colorScheme.onSurface)
                }
                Text(
                    text = if (videoCount > 0) "$selectedCount Selected • $videoCount Videos" else "$selectedCount Selected",
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f).padding(start = 8.dp)
                )
                TextButton(onClick = onCreateClick, enabled = selectedCount > 0) {
                    Text("Create", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                }
            } else {
                Text(
                    text = "Stories",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f).padding(start = 16.dp)
                )
                IconButton(onClick = onStartManualStory) {
                    Icon(Icons.Rounded.Add, "Create Manual Story", tint = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@Composable
fun StoryCard(
    story: UiStory,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onSave: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f),
        label = "StoryCardScale"
    )
    val videoCount = story.items.count { it.isVideo }

    with(sharedTransitionScope) {
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            modifier = Modifier.fillMaxWidth().aspectRatio(0.56f).scale(scale)
                .sharedBounds(
                    sharedContentState = rememberSharedContentState("story_bounds_${story.id}"),
                    animatedVisibilityScope = animatedVisibilityScope,
                    boundsTransform = { _, _ -> spring(dampingRatio = 0.8f, stiffness = 300f) }
                )
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = { showMenu = true },
                        onTap = { onClick() }
                    )
                }
        ) {
            Box(Modifier.fillMaxSize()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(story.coverUri)
                        .size(320)
                        .bitmapConfig(Bitmap.Config.RGB_565)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.3f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f))))

                if (story.items.any { it.isVideo }) {
                    Box(modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).background(Color.Black.copy(alpha = 0.6f), CircleShape).padding(8.dp)) {
                        Icon(Icons.Rounded.Movie, contentDescription = null, tint = Color.White)
                    }
                }

                Column(modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                    Text(story.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (videoCount > 0) Text("$videoCount Videos", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.labelSmall)
                    if (story.subtitle.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        Text(story.subtitle, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.8f))
                    }
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
                ) {
                    DropdownMenuItem(text = { Text("Save to Device") }, leadingIcon = { Icon(Icons.Rounded.SaveAlt, null) }, onClick = { showMenu = false; onSave() })
                    DropdownMenuItem(text = { Text("Move to Trash", color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) }, onClick = { showMenu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
fun StoryPlayer(
    story: UiStory,
    sharedExoPlayer: ExoPlayer,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onClose: () -> Unit,
    onNextStoryGroup: () -> Unit,
    onPrevStoryGroup: () -> Unit
) {
    if (story.items.isEmpty()) {
        onClose()
        return
    }

    val context = LocalContext.current
    var currentIndex by remember { mutableIntStateOf(0) }
    var isPaused by remember { mutableStateOf(false) }
    val currentItemProgress = remember { mutableFloatStateOf(0f) }
    val currentItem = remember(currentIndex, story.items) { story.items.getOrNull(currentIndex) }
    val coroutineScope = rememberCoroutineScope()
    val dragOffsetY = remember { Animatable(0f) }

    val animatedScale = 1f - (dragOffsetY.value / 2500f).coerceIn(0f, 0.4f)
    val dynamicCornerRadius = (dragOffsetY.value / 10f).coerceIn(0f, 48f).dp

    LaunchedEffect(story) {
        story.items.take(3).forEach {
            context.imageLoader.enqueue(
                ImageRequest.Builder(context)
                    .data(it.uri)
                    .size(Size.ORIGINAL)
                    .allowHardware(true)
                    .build()
            )
        }
    }

    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            sharedExoPlayer.stop()
            sharedExoPlayer.clearMediaItems()
        }
    }

    val onNextState by rememberUpdatedState {
        sharedExoPlayer.seekTo(0)
        currentItemProgress.floatValue = 0f
        if (currentIndex < story.items.lastIndex) currentIndex++ else onNextStoryGroup()
    }

    val onPrevState by rememberUpdatedState {
        sharedExoPlayer.seekTo(0)
        currentItemProgress.floatValue = 0f
        if (currentIndex > 0) currentIndex-- else onPrevStoryGroup()
    }

    with(sharedTransitionScope) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = animatedScale.coerceIn(0f, 1f)))) {
            if (currentItem != null) {
                Box(
                    modifier = Modifier.fillMaxSize().scale(animatedScale).clip(RoundedCornerShape(dynamicCornerRadius))
                        .sharedBounds(
                            sharedContentState = rememberSharedContentState(key = "story_bounds_${story.id}"),
                            animatedVisibilityScope = animatedVisibilityScope,
                            boundsTransform = { _, _ -> spring(dampingRatio = 0.8f, stiffness = 300f) }
                        )
                ) {
                    if (currentItem.isVideo) {
                        LifecycleAwareVideoPlayer(
                            exoPlayer = sharedExoPlayer,
                            uri = currentItem.uri,
                            isStoryPaused = isPaused || dragOffsetY.value > 0,
                            progressState = currentItemProgress,
                            onComplete = { onNextState() }
                        )
                    } else {
                        SimpleImageItem(
                            uri = currentItem.uri,
                            isPaused = isPaused || dragOffsetY.value > 0,
                            durationMs = 5000L,
                            progressState = currentItemProgress,
                            onComplete = { onNextState() }
                        )
                    }

                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(140.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.4f)))))

                    Box(Modifier.fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    isPaused = true
                                    tryAwaitRelease()
                                    isPaused = false
                                },
                                onTap = { offset ->
                                    if (offset.x < size.width * 0.3f) onPrevState() else if (offset.x > size.width * 0.7f) onNextState()
                                }
                            )
                        }
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onDragEnd = {
                                    if (dragOffsetY.value > 300f) onClose() else coroutineScope.launch { dragOffsetY.animateTo(0f, spring(0.7f, 400f)) }
                                },
                                onDragCancel = {
                                    coroutineScope.launch { dragOffsetY.animateTo(0f, spring(0.7f, 400f)) }
                                }
                            ) { change, dragAmount ->
                                change.consume()
                                if (dragAmount > 0 || dragOffsetY.value > 0) {
                                    coroutineScope.launch {
                                        dragOffsetY.snapTo(dragOffsetY.value + (dragAmount * (1f - (dragOffsetY.value / 2000f).coerceIn(0f, 0.8f))))
                                    }
                                }
                            }
                        }
                    )

                    StoryHeaderOverlay(story = story, currentIndex = currentIndex, currentItemProgress = currentItemProgress, onClose = onClose)
                }
            }
        }
    }
}

@Composable
fun StoryHeaderOverlay(story: UiStory, currentIndex: Int, currentItemProgress: State<Float>, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(top = 16.dp, start = 8.dp, end = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (index in story.items.indices) {
                StoryProgressBar(index = index, currentIndex = currentIndex, currentItemProgress = currentItemProgress, modifier = Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(model = story.coverUri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(32.dp).clip(CircleShape).border(1.dp, Color.White.copy(0.2f), CircleShape))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(story.title, color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                if (story.subtitle.isNotEmpty()) {
                    Text(story.subtitle, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                }
            }
            IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }
        }
    }
}

@Composable
fun StoryProgressBar(index: Int, currentIndex: Int, currentItemProgress: State<Float>, modifier: Modifier) {
    val targetProgress = when {
        index < currentIndex -> 1f
        index > currentIndex -> 0f
        else -> currentItemProgress.value
    }

    val animatedProgress by animateFloatAsState(targetValue = targetProgress, animationSpec = tween(durationMillis = 100, easing = LinearOutSlowInEasing), label = "StoryProgress")

    LinearProgressIndicator(
        progress = { animatedProgress },
        modifier = modifier.height(1.5.dp).clip(RoundedCornerShape(50)),
        color = Color.White,
        trackColor = Color.White.copy(alpha = 0.3f)
    )
}

@Composable
fun MediaSelectorGrid(pagedMedia: LazyPagingItems<GalleryGridItem>, selectedIds: Set<Long>, onToggle: (Long) -> Unit, contentPadding: PaddingValues) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(top = contentPadding.calculateTopPadding() + 80.dp, bottom = 80.dp, start = 8.dp, end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(
            count = pagedMedia.itemCount,
            span = { index -> if (pagedMedia.peek(index) is GalleryGridItem.Header) GridItemSpan(3) else GridItemSpan(1) },
            key = { index ->
                when (val item = pagedMedia.peek(index)) {
                    is GalleryGridItem.Header -> item.id
                    is GalleryGridItem.Media -> item.item.id
                    else -> "placeholder_$index"
                }
            },
            contentType = { index ->
                when (pagedMedia.peek(index)) {
                    is GalleryGridItem.Header -> "header"
                    is GalleryGridItem.Media -> "media"
                    else -> "placeholder"
                }
            }
        ) { index ->
            when (val item = pagedMedia[index]) {
                is GalleryGridItem.Header -> {
                    Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp))
                }
                is GalleryGridItem.Media -> {
                    val mediaItem = item.item
                    val isSelected = selectedIds.contains(mediaItem.id)
                    val scale by animateFloatAsState(if (isSelected) 0.85f else 1f, label = "GridScale")

                    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(if(isSelected) 16.dp else 4.dp)).clickable { onToggle(mediaItem.id) }.background(MaterialTheme.colorScheme.surfaceVariant)) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(mediaItem.uri)
                                .size(256)
                                .apply { if (mediaItem.isVideo) videoFrameMillis(1000) }
                                .bitmapConfig(Bitmap.Config.RGB_565)
                                .build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().scale(scale).clip(RoundedCornerShape(if(isSelected) 16.dp else 0.dp))
                        )

                        if (mediaItem.isVideo) {
                            Box(modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(12.dp)).padding(horizontal = 6.dp, vertical = 3.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.Movie, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(text = "VIDEO", color = Color.White, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }

                        if (isSelected) {
                            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)).border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)))
                            Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).background(MaterialTheme.colorScheme.surface, CircleShape))
                        }
                    }
                }
                null -> Box(Modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant))
            }
        }
    }
}

@Composable
fun SimpleImageItem(uri: Uri, isPaused: Boolean, durationMs: Long, progressState: MutableState<Float>, onComplete: () -> Unit) {
    val progressAnim = remember { Animatable(0f) }

    LaunchedEffect(isPaused, uri) {
        if (!isPaused) {
            val remainingTime = ((1f - progressAnim.value) * durationMs).toInt()
            val result = progressAnim.animateTo(targetValue = 1f, animationSpec = tween(durationMillis = remainingTime, easing = LinearEasing))
            if (result.endReason == AnimationEndReason.Finished) onComplete()
        }
    }

    LaunchedEffect(progressAnim) {
        snapshotFlow { progressAnim.value }.collect { progressState.value = it }
    }

    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(uri)
            .size(Size.ORIGINAL)
            .bitmapConfig(Bitmap.Config.RGB_565)
            .precision(Precision.INEXACT)
            .allowHardware(true)
            .crossfade(false)
            .build(),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize()
    )
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun LifecycleAwareVideoPlayer(exoPlayer: ExoPlayer, uri: Uri, isStoryPaused: Boolean, progressState: MutableState<Float>, onComplete: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasError by remember { mutableStateOf(false) }

    LaunchedEffect(uri) {
        val mediaId = uri.toString()
        if (exoPlayer.currentMediaItem?.mediaId != mediaId) {
            exoPlayer.clearMediaItems()
            exoPlayer.setMediaItem(ExoMediaItem.Builder().setUri(uri).setMediaId(mediaId).build())
            exoPlayer.prepare()
        }
        hasError = false
    }

    LaunchedEffect(isStoryPaused, uri) {
        if (!isStoryPaused) {
            while (isActive) {
                if (exoPlayer.playbackState == Player.STATE_READY && exoPlayer.duration > 0) {
                    progressState.value = exoPlayer.currentPosition.toFloat() / exoPlayer.duration.toFloat()
                }
                delay(100)
            }
        }
    }

    DisposableEffect(uri) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) onComplete()
            }
            override fun onPlayerError(error: PlaybackException) {
                hasError = true
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    LaunchedEffect(isStoryPaused) {
        if (isStoryPaused) exoPlayer.pause() else exoPlayer.play()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> exoPlayer.pause()
                Lifecycle.Event.ON_RESUME -> if (!isStoryPaused) exoPlayer.play()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.pause()
        }
    }

    if (hasError) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Video failed to load", color = Color.White)
        }
    } else {
        AndroidView(
            factory = {
                PlayerView(context).apply {
                    player = exoPlayer
                    useController = false
                    setKeepContentOnPlayerReset(true)
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
fun EmptyStoriesView() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Box(modifier = Modifier.size(100.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Movie, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(24.dp))
            Text("No stories yet", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(8.dp))
            Text("Your highlights and memories will appear here automatically.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@HiltWorker
class StoryCreationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val dao: GalleryDao
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val MIN_CLUSTER_SIZE = 3
        private const val PREFS_NAME = "gallery_story_prefs"
        private const val KEY_LAST_SCANNED = "last_scanned_date"

        // Physics & Clustering Constants
        private const val EVENT_THRESHOLD_MS = 6 * 60 * 60 * 1000L // 6 hours
        private const val MERGE_THRESHOLD_MS = 24 * 60 * 60 * 1000L // 24 hours
        private const val BURST_THRESHOLD_MS = 2000L // 2 seconds
        private const val TRIP_DURATION_MS = 2 * 24 * 60 * 60 * 1000L // 2 days
        private const val VACATION_DURATION_MS = 5 * 24 * 60 * 60 * 1000L // 5 days
    }

    data class SimpleMediaData(
        val id: Long,
        val uri: String,
        val path: String,
        val displayName: String,
        val dateTaken: Long,
        val isVideo: Boolean,
        val size: Long,
        val lat: Double? = null,
        val lon: Double? = null,
        val width: Int = 0,
        val height: Int = 0
    )

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val lastScanned = prefs.getLong(KEY_LAST_SCANNED, 0L)

            // Incremental Scan Logic
            val media = queryCandidates(lastScanned)
            if (media.isEmpty()) return@withContext Result.success()

            val filteredMedia = filterNoise(media).distinctBy { "${it.size}_${it.dateTaken}" }.sortedBy { it.dateTaken }
            val burstCollapsedMedia = removeBurstsByTime(filteredMedia)
            val rawClusters = advancedClusterByTimeAndFolder(burstCollapsedMedia)

            val scoredClusters = rawClusters.filter { it.size >= MIN_CLUSTER_SIZE }.map { it to scoreCluster(it) }.sortedByDescending { it.second }

            scoredClusters.take(50).forEach { (cluster, _) ->
                val hash = generateStableHash(cluster)
                if (!dao.storyExists(hash)) {
                    createStoryCluster(cluster, hash)?.let { dao.insertStory(it) }
                }
            }

            prefs.edit().putLong(KEY_LAST_SCANNED, media.maxOfOrNull { it.dateTaken } ?: lastScanned).apply()
            Result.success()
        } catch (e: SQLiteException) { Result.retry() } catch (e: IOException) { Result.retry() } catch (e: Exception) { Result.failure() }
    }

    private fun createStoryCluster(cluster: List<SimpleMediaData>, id: String): StoryEntity? {
        val title = buildStoryTitle(cluster)
        val subtitle = "${cluster.size} items"
        val bestCover = selectBestCover(cluster)

        return StoryEntity(
            id = id,
            title = title,
            subtitle = subtitle,
            coverUri = bestCover.uri,
            mediaIdsJson = cluster.joinToString(",", prefix = "[", postfix = "]") { it.id.toString() },
            createdAt = System.currentTimeMillis()
        )
    }

    private fun selectBestCover(cluster: List<SimpleMediaData>): SimpleMediaData {
        return cluster.maxByOrNull { item ->
            var score = 0.0
            if (!item.isVideo) score += 20.0

            if (item.width > 0 && item.height > 0) {
                val ratio = item.width.toDouble() / item.height.toDouble()
                if (ratio in 0.5..0.8 || ratio in 1.3..1.8) score += 15.0
            }
            val sizeScore = (item.size.toDouble() / 1024.0 / 1024.0) * 0.5
            score += min(sizeScore, 40.0)
            val pathLower = item.path.lowercase(Locale.ROOT)
            if (pathLower.contains("screenshot")) score -= 50.0

            score
        } ?: cluster.first()
    }

    private fun scoreCluster(cluster: List<SimpleMediaData>): Double {
        val n = cluster.size.toDouble()
        val v = cluster.count { it.isVideo }.toDouble()

        val uniqueDays = cluster.map {
            Calendar.getInstance().apply { timeInMillis = it.dateTaken }.get(Calendar.DAY_OF_YEAR)
        }.toSet().size.toDouble()

        val ageMs = System.currentTimeMillis() - cluster.first().dateTaken
        val recency = max(0.0, 1.0 - (ageMs.toDouble() / (365.0 * 24 * 60 * 60 * 1000)))

        return (2.0 * n) + (5.0 * v) + (2.0 * uniqueDays) + (1.0 * recency)
    }

    private fun removeBurstsByTime(items: List<SimpleMediaData>): List<SimpleMediaData> {
        if (items.isEmpty()) return emptyList()
        val collapsed = mutableListOf<SimpleMediaData>()
        var burstGroup = mutableListOf(items.first())

        for (i in 1 until items.size) {
            val curr = items[i]
            val prev = items[i - 1]
            if (abs(curr.dateTaken - prev.dateTaken) < BURST_THRESHOLD_MS) {
                burstGroup.add(curr)
            } else {
                collapsed.add(burstGroup.maxByOrNull { it.size } ?: burstGroup.first())
                burstGroup = mutableListOf(curr)
            }
        }
        collapsed.add(burstGroup.maxByOrNull { it.size } ?: burstGroup.first())
        return collapsed
    }

    private fun advancedClusterByTimeAndFolder(items: List<SimpleMediaData>): List<List<SimpleMediaData>> {
        if (items.isEmpty()) return emptyList()
        val rawClusters = mutableListOf<List<SimpleMediaData>>()
        val grouped = items.groupBy { File(it.path).parentFile?.name ?: "Unknown" }

        for ((_, fItems) in grouped) {
            var currentCluster = mutableListOf(fItems.first())

            for (i in 1 until fItems.size) {
                val current = fItems[i]
                val previous = fItems[i - 1]
                if (abs(current.dateTaken - previous.dateTaken) < EVENT_THRESHOLD_MS) {
                    currentCluster.add(current)
                } else {
                    rawClusters.add(currentCluster)
                    currentCluster = mutableListOf(current)
                }
            }
            rawClusters.add(currentCluster)
        }

        val mergedClusters = mutableListOf<List<SimpleMediaData>>()
        var accumulator = mutableListOf<SimpleMediaData>()

        val sortedRaw = rawClusters.sortedBy { it.first().dateTaken }
        for (i in sortedRaw.indices) {
            if (accumulator.isEmpty()) {
                accumulator.addAll(sortedRaw[i])
            } else {
                val lastItem = accumulator.last()
                val firstNew = sortedRaw[i].first()
                if (abs(firstNew.dateTaken - lastItem.dateTaken) < MERGE_THRESHOLD_MS) {
                    accumulator.addAll(sortedRaw[i])
                } else {
                    mergedClusters.add(accumulator.toList())
                    accumulator = sortedRaw[i].toMutableList()
                }
            }
        }
        if (accumulator.isNotEmpty()) mergedClusters.add(accumulator)

        return mergedClusters
    }

    private fun buildStoryTitle(cluster: List<SimpleMediaData>): String {
        val firstItem = cluster.first()
        val lastItem = cluster.last()
        val durationMs = abs(lastItem.dateTaken - firstItem.dateTaken)
        val folder = File(firstItem.path).parentFile?.name ?: ""

        val cal = Calendar.getInstance().apply { timeInMillis = firstItem.dateTaken }
        val now = Calendar.getInstance()

        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val month = SimpleDateFormat("MMMM", Locale.getDefault()).format(cal.time)
        val year = SimpleDateFormat("yyyy", Locale.getDefault()).format(cal.time)

        val isWeekend = dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY
        val isNight = cal.get(Calendar.HOUR_OF_DAY) in 20..23 || cal.get(Calendar.HOUR_OF_DAY) in 0..4
        val exactFolder = folder.lowercase(Locale.ROOT)

        val isAnniversary = cal.get(Calendar.MONTH) == now.get(Calendar.MONTH) &&
                cal.get(Calendar.DAY_OF_MONTH) == now.get(Calendar.DAY_OF_MONTH) &&
                cal.get(Calendar.YEAR) < now.get(Calendar.YEAR)

        return when {
            isAnniversary -> "This Day in $year"
            durationMs > VACATION_DURATION_MS -> "$month Vacation"
            durationMs > TRIP_DURATION_MS -> "Weekend Getaway"
            exactFolder == "whatsapp" || exactFolder.contains("whatsapp") -> "WhatsApp Memories"
            exactFolder == "download" || exactFolder == "downloads" -> "Recent Downloads"
            exactFolder == "screenshot" || exactFolder == "screenshots" -> "Screenshots"
            isWeekend && (exactFolder == "camera" || exactFolder == "dcim") -> "Weekend Memories"
            isNight -> "Night Moments"
            cluster.any { it.isVideo } -> "$month Video Highlights"
            cal.get(Calendar.MONTH) in Calendar.DECEMBER..Calendar.FEBRUARY -> "Winter Memories"
            cal.get(Calendar.MONTH) in Calendar.MARCH..Calendar.MAY -> "Spring Highlights"
            cal.get(Calendar.MONTH) in Calendar.JUNE..Calendar.AUGUST -> "Summer Memories"
            else -> "$month Highlights $year"
        }
    }

    private fun filterNoise(cluster: List<SimpleMediaData>): List<SimpleMediaData> {
        val banned = setOf("cache", "thumb", "temp", "thumbnails", ".thumbnails")
        return cluster.filter { item ->
            val segments = item.path.lowercase(Locale.ROOT).split("/")
            val n = item.displayName.lowercase(Locale.ROOT)
            banned.none { b -> segments.any { it == b } || n == b }
        }
    }

    private fun queryCandidates(lastScanned: Long): List<SimpleMediaData> {
        val hiddenAlbums = applicationContext.getSharedPreferences("gallery_prefs", Context.MODE_PRIVATE).getStringSet("hidden_albums", emptySet()) ?: emptySet()
        val list = mutableListOf<SimpleMediaData>()

        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT
        )

        val selection = if (lastScanned > 0) "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?) AND (${MediaStore.MediaColumns.DATE_ADDED} * 1000 > ?)" else "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
        val args = if (lastScanned > 0) arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(), MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(), lastScanned.toString()) else arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(), MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString())

        applicationContext.contentResolver.query(MediaStore.Files.getContentUri("external"), projection, selection, args, "${MediaStore.MediaColumns.DATE_TAKEN} DESC")?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val pathCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val typeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val widthCol = c.getColumnIndex(MediaStore.MediaColumns.WIDTH)
            val heightCol = c.getColumnIndex(MediaStore.MediaColumns.HEIGHT)

            while (c.moveToNext()) {
                val path = c.getString(pathCol) ?: ""
                if (hiddenAlbums.contains(path)) continue

                val id = c.getLong(idCol)
                val isVideo = c.getInt(typeCol) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val uri = ContentUris.withAppendedId(if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id).toString()
                val dateTaken = c.getLong(dateCol)
                val finalDate = if (dateTaken > 0L) dateTaken else c.getLong(addedCol) * 1000L
                val width = if (widthCol != -1) c.getInt(widthCol) else 0
                val height = if (heightCol != -1) c.getInt(heightCol) else 0

                list.add(SimpleMediaData(id = id, uri = uri, path = path, displayName = c.getString(nameCol) ?: "", dateTaken = finalDate, isVideo = isVideo, size = c.getLong(sizeCol), width = width, height = height))
            }
        }
        return list
    }

    private fun generateStableHash(cluster: List<SimpleMediaData>): String = MessageDigest.getInstance("MD5").digest(cluster.joinToString("|") { "${it.path}_${it.dateTaken}" }.toByteArray()).joinToString("") { "%02x".format(it) }

    @Suppress("unused")
    private fun calculateHaversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
}