@file:Suppress("unused", "UnsafeOptInUsageError", "UnstableApiUsage", "OPT_IN_USAGE")
@file:OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3Api::class)

package com.gallerybox.ui.screens.stories

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
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
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import coil.size.Precision
import coil.size.Size
import com.gallerybox.data.UiStory
import com.gallerybox.ui.screens.picture.GalleryGridItem
import com.gallerybox.viewmodel.GalleryEvent
import com.gallerybox.viewmodel.GalleryViewModel
import com.gallerybox.viewmodel.StoryViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoriesScreen(
    viewModel: GalleryViewModel = hiltViewModel(),
    storyViewModel: StoryViewModel = hiltViewModel()
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
    val isGenerating by storyViewModel.isGenerating.collectAsState()
    val generationProgress by storyViewModel.generationProgress.collectAsState()
    val generationTotal by storyViewModel.generationTotal.collectAsState()

    val pagedMedia = viewModel.pagedMedia.collectAsLazyPagingItems()

    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<Long>()) }
    var activeStoryIndex by remember { mutableStateOf<Int?>(null) }
    var showNameDialog by remember { mutableStateOf(false) }
    var newStoryTitle by remember { mutableStateOf("") }

    var showDisplayLimitSheet by remember { mutableStateOf(false) }
    var showSortSheet by remember { mutableStateOf(false) }

    val prefs = remember { context.getSharedPreferences("gallery_engine_prefs", Context.MODE_PRIVATE) }
    var displayLimit by remember { mutableIntStateOf(prefs.getInt("memory_display_limit", 100)) }
    var sortOrder by remember { mutableStateOf(prefs.getString("memory_sort_order", "Highest Rated") ?: "Highest Rated") }

    val gridState = rememberLazyGridState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val pullRefreshState = rememberPullToRefreshState()
    var isRefreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()


    val sharedExoPlayer = remember(context) {
        ExoPlayer.Builder(context.applicationContext).build().apply { repeatMode = Player.REPEAT_MODE_OFF }
    }

    DisposableEffect(sharedExoPlayer) {
        onDispose { sharedExoPlayer.release() }
    }

    val sortedAndLimitedStories = remember(stories, displayLimit, sortOrder) {
        val scored = stories.map { story ->
            val photos = story.items.count { !it.isVideo }
            val videos = story.items.count { it.isVideo }
            val days = story.items.map { it.dateAdded / 86400 }.distinct().size
            val favs = story.items.count { it.isFavorite }
            val score = StoryViewModel.calculateStoryScore(photos, videos, days, favs)
            Pair(story, score)
        }

        val sorted = when (sortOrder) {
            "Newest" -> scored.sortedByDescending { it.first.items.firstOrNull()?.dateAdded ?: 0L }
            "Oldest" -> scored.sortedBy { it.first.items.lastOrNull()?.dateAdded ?: Long.MAX_VALUE }
            "Random" -> scored.shuffled()
            else -> scored.sortedByDescending { it.second }
        }.map { it.first }

        if (displayLimit == -1) sorted else sorted.take(displayLimit)
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
                    modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
                    containerColor = MaterialTheme.colorScheme.background,
                    topBar = {
                        if (isSelectionMode) {
                            Surface(shadowElevation = 2.dp, color = MaterialTheme.colorScheme.surface) {
                                TopAppBar(
                                    title = { Text("${selectedIds.size} selected", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) },
                                    navigationIcon = { IconButton(onClick = { isSelectionMode = false; selectedIds = emptySet() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close") } },
                                    actions = {
                                        TextButton(onClick = { if (selectedIds.isNotEmpty()) showNameDialog = true }) {
                                            Text("Create", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                        }
                                    },
                                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                                )
                            }
                        } else {
                            Surface(shadowElevation = 2.dp, color = MaterialTheme.colorScheme.surface) {
                                CenterAlignedTopAppBar(
                                    title = {
                                        val totalCount = stories.size
                                        val titleText = if (displayLimit == -1) "$totalCount Memories" else "Memories ${sortedAndLimitedStories.size} / $totalCount"
                                        Text(titleText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                    },
                                    actions = {
                                        var showMenu by remember { mutableStateOf(false) }
                                        IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, "More") }
                                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, modifier = Modifier.clip(RoundedCornerShape(12.dp))) {
                                            DropdownMenuItem(text = { Text("Create Memory") }, leadingIcon = { Icon(Icons.Rounded.Add, null) }, onClick = { showMenu = false; isSelectionMode = true })
                                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                            DropdownMenuItem(text = { Text("Memories to Show") }, leadingIcon = { Icon(Icons.Rounded.FilterList, null) }, onClick = { showMenu = false; showDisplayLimitSheet = true })
                                            DropdownMenuItem(text = { Text("Sort") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, null) }, onClick = { showMenu = false; showSortSheet = true })
                                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                            DropdownMenuItem(text = { Text("Refresh Memories") }, leadingIcon = { Icon(Icons.Rounded.Refresh, null) }, onClick = { showMenu = false; storyViewModel.refreshMemories() })
                                            DropdownMenuItem(text = { Text("Delete Generated Memories", color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) }, onClick = { showMenu = false; storyViewModel.deleteGeneratedMemories() })
                                            DropdownMenuItem(text = { Text("About Memories") }, leadingIcon = { Icon(Icons.Rounded.Info, null) }, onClick = { showMenu = false; Toast.makeText(context, "Memories are generated automatically on your device based on time, location, and content.", Toast.LENGTH_LONG).show() })
                                        }
                                    },
                                    scrollBehavior = scrollBehavior,
                                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent)
                                )
                            }
                        }
                    }
                ) { paddingValues ->
                    PullToRefreshBox(
                        isRefreshing = isRefreshing,
                        onRefresh = {
                            isRefreshing = true
                            scope.launch {
                                storyViewModel.refreshMemories()
                                delay(1000)
                                isRefreshing = false
                            }
                        },
                        state = pullRefreshState,
                        modifier = Modifier.fillMaxSize().padding(top = paddingValues.calculateTopPadding())
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            if (isSelectionMode) {
                                MediaSelectorGrid(
                                    pagedMedia = pagedMedia,
                                    selectedIds = selectedIds,
                                    onToggle = { id -> selectedIds = if (selectedIds.contains(id)) selectedIds - id else selectedIds + id },
                                    contentPadding = PaddingValues(0.dp)
                                )
                            } else if (stories.isEmpty() && !isGenerating) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                                        Box(modifier = Modifier.size(100.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), CircleShape), contentAlignment = Alignment.Center) {
                                            Icon(Icons.Rounded.Movie, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Spacer(Modifier.height(24.dp))
                                        Text("No Memories Yet", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                        Spacer(Modifier.height(8.dp))
                                        Text("Your best moments will appear here automatically.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                                    }
                                }
                            } else if (isGenerating && stories.isEmpty()) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.height(16.dp))
                                        Text("Generating Memories...", fontWeight = FontWeight.Medium)
                                        Spacer(Modifier.height(8.dp))
                                        Text("$generationProgress / $generationTotal", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            } else {
                                LazyVerticalGrid(
                                    state = gridState,
                                    columns = GridCells.Fixed(2),
                                    contentPadding = PaddingValues(top = 16.dp, bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 80.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)
                                ) {
                                    items(sortedAndLimitedStories.size, key = { index -> sortedAndLimitedStories[index].id }, contentType = { "story" }) { index ->
                                        val story = sortedAndLimitedStories[index]
                                        StoryCard(
                                            story = story,
                                            sharedTransitionScope = this@SharedTransitionLayout,
                                            animatedVisibilityScope = this@AnimatedContent,
                                            onClick = { activeStoryIndex = index },
                                            onDelete = { storyViewModel.deleteStory(story.id) },
                                            onSave = { Toast.makeText(context, "Exporting '${story.title}' to Gallery...", Toast.LENGTH_SHORT).show() }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                sortedAndLimitedStories.getOrNull(currentIndex)?.let { activeStory ->
                    StoryPlayer(
                        story = activeStory,
                        sharedExoPlayer = sharedExoPlayer,
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = this@AnimatedContent,
                        onClose = { activeStoryIndex = null },
                        onNextStoryGroup = {
                            if (currentIndex < sortedAndLimitedStories.lastIndex) activeStoryIndex = currentIndex + 1 else activeStoryIndex = null
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
            title = { Text("Name your memory", color = MaterialTheme.colorScheme.onSurface) },
            text = {
                OutlinedTextField(
                    value = newStoryTitle,
                    onValueChange = { newStoryTitle = it },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
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
                        Toast.makeText(context, "Enter memory name", Toast.LENGTH_SHORT).show()
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

    if (showDisplayLimitSheet) {
        val limits = listOf(10, 25, 50, 100, 250, 500, 1000, -1)
        ModalBottomSheet(onDismissRequest = { showDisplayLimitSheet = false }, containerColor = MaterialTheme.colorScheme.surface, dragHandle = { BottomSheetDefaults.DragHandle(width = 48.dp, height = 4.dp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)) }) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 34.dp)) {
                Row(modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(48.dp).clip(CircleShape).background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha=0.2f), MaterialTheme.colorScheme.primary.copy(alpha=0.05f)))), contentAlignment = Alignment.Center) { Icon(imageVector = Icons.Rounded.FilterList, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) }
                    Spacer(Modifier.width(16.dp))
                    Column { Text(text = "Memories to Show", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Spacer(Modifier.height(4.dp)); Text(text = "Adjust the display limit", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Spacer(Modifier.height(16.dp))
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f, fill = false), contentPadding = PaddingValues(bottom = 12.dp)) {
                    items(limits) { limit ->
                        val isSelected = displayLimit == limit
                        val label = if (limit == -1) "Unlimited" else limit.toString()
                        Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(20.dp)).clickable { displayLimit = limit; prefs.edit().putInt("memory_display_limit", limit).apply(); showDisplayLimitSheet = false }, shape = RoundedCornerShape(20.dp), color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = if (isSelected) 2.dp else 0.dp) {
                            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.size(28.dp).clip(CircleShape).background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) { Icon(imageVector = if (isSelected) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked, contentDescription = null, tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) }
                                Spacer(Modifier.width(16.dp)); Text(text = label, style = MaterialTheme.typography.bodyLarge, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium, color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface); Spacer(Modifier.weight(1f)); if (isSelected) { Icon(imageVector = Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSortSheet) {
        val sorts = listOf("Newest", "Oldest", "Random", "Highest Rated")
        ModalBottomSheet(onDismissRequest = { showSortSheet = false }, containerColor = MaterialTheme.colorScheme.surface, dragHandle = { BottomSheetDefaults.DragHandle(width = 48.dp, height = 4.dp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)) }) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 34.dp)) {
                Row(modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(48.dp).clip(CircleShape).background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha=0.2f), MaterialTheme.colorScheme.primary.copy(alpha=0.05f)))), contentAlignment = Alignment.Center) { Icon(imageVector = Icons.AutoMirrored.Filled.Sort, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) }
                    Spacer(Modifier.width(16.dp))
                    Column { Text(text = "Sort Memories", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Spacer(Modifier.height(4.dp)); Text(text = "Arrange your highlights", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Spacer(Modifier.height(16.dp))
                sorts.forEach { option ->
                    val isSelected = sortOrder == option
                    Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(20.dp)).clickable { sortOrder = option; prefs.edit().putString("memory_sort_order", option).apply(); showSortSheet = false }, shape = RoundedCornerShape(20.dp), color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = if (isSelected) 2.dp else 0.dp) {
                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(28.dp).clip(CircleShape).background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) { Icon(imageVector = if (isSelected) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked, contentDescription = null, tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) }
                            Spacer(Modifier.width(16.dp)); Text(text = option, style = MaterialTheme.typography.bodyLarge, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium, color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface); Spacer(Modifier.weight(1f)); if (isSelected) { Icon(imageVector = Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                        }
                    }
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
    val daysAgo = ((System.currentTimeMillis() - story.items.first().dateAdded * 1000L) / 86400000L).coerceAtLeast(0)
    val timeStr = if (daysAgo == 0L) "Today" else if (daysAgo == 1L) "Yesterday" else "$daysAgo days ago"

    with(sharedTransitionScope) {
        Card(
            shape = RoundedCornerShape(20.dp),
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

                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.3f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.65f))))

                if (videoCount > 0) {
                    Box(modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).background(Color.Black.copy(alpha = 0.6f), CircleShape).padding(8.dp)) {
                        Icon(Icons.Rounded.Movie, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }

                Column(modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    Text(story.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text("${story.items.size} Items • ${if (videoCount > 0) "$videoCount Videos • " else ""}$timeStr", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.labelSmall)
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)).clip(RoundedCornerShape(12.dp))
                ) {
                    DropdownMenuItem(text = { Text("Save to Device") }, leadingIcon = { Icon(Icons.Rounded.SaveAlt, null) }, onClick = { showMenu = false; onSave() }, colors = MenuDefaults.itemColors(Color.Transparent))
                    DropdownMenuItem(text = { Text("Move to Trash", color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) }, onClick = { showMenu = false; onDelete() }, colors = MenuDefaults.itemColors(Color.Transparent))
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
        contentPadding = PaddingValues(top = contentPadding.calculateTopPadding() + 8.dp, bottom = 80.dp, start = 4.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(
            count = pagedMedia.itemCount,
            span = { index -> if (pagedMedia.peek(index) is GalleryGridItem.Header) GridItemSpan(3) else GridItemSpan(1) },
            key = { index ->
                when (val item = pagedMedia.peek(index)) {
                    is GalleryGridItem.Header -> "header_${item.id}"
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
                    Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp))
                }
                is GalleryGridItem.Media -> {
                    val mediaItem = item.item
                    val isSelected = selectedIds.contains(mediaItem.id)
                    val scale by animateFloatAsState(if (isSelected) 0.85f else 1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "GridScale")
                    val corner by animateDpAsState(if (isSelected) 16.dp else 4.dp, spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "GridCorner")

                    Box(Modifier.aspectRatio(1f).scale(scale).clip(RoundedCornerShape(corner)).clickable { onToggle(mediaItem.id) }.background(MaterialTheme.colorScheme.surfaceVariant)) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(mediaItem.uri)
                                .size(256)
                                .apply { if (mediaItem.isVideo) videoFrameMillis(1000) }
                                .bitmapConfig(Bitmap.Config.RGB_565)
                                .crossfade(false)
                                .build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )

                        if (mediaItem.isVideo) {
                            Box(modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(12.dp)).padding(horizontal = 6.dp, vertical = 3.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.Movie, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(text = "VIDEO", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        if (isSelected) {
                            Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.25f)))
                            Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.TopStart).padding(8.dp).shadow(4.dp, CircleShape).background(Color.White, CircleShape).size(24.dp))
                        } else {
                            Icon(Icons.Outlined.RadioButtonUnchecked, null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.align(Alignment.TopStart).padding(8.dp).shadow(2.dp, CircleShape).size(24.dp))
                        }
                    }
                }
                null -> Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
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