@file:Suppress("UnsafeOptInUsageError", "UnstableApiUsage", "OPT_IN_USAGE", "unused", "DEPRECATION")

package com.gallerybox.ui.screens.picture

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.text.format.Formatter
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.Player
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import com.gallerybox.data.MediaItem
import com.gallerybox.ui.screens.album.formatDuration
import com.gallerybox.viewmodel.GalleryEvent
import com.gallerybox.viewmodel.GalleryViewModel
import com.gallerybox.viewmodel.MediaTypeFilter
import com.gallerybox.viewmodel.PhotoSort
import com.gallerybox.viewmodel.TrashViewModel
import com.gallerybox.viewmodel.GalleryViewerState
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// ============================================================================
// HELPERS
// ============================================================================

enum class UiMediaFilter(val label: String) {
    ALL("All"),
    PHOTOS("Photos"),
    VIDEOS("Videos")
}

fun isValidUri(context: Context, uri: Uri?): Boolean = uri != null && uri != Uri.EMPTY

fun getSmartName(item: MediaItem): String {
    val name = item.name.lowercase()
    return when {
        "fdownloader" in name -> "Downloaded Video"
        "instagram" in name -> "Instagram Video"
        "whatsapp" in name -> "WhatsApp Media"
        "screenshot" in name -> "Screenshot"
        item.isVideo -> "Video"
        else -> "Photo"
    }
}

fun getFolderName(path: String): String {
    return try {
        java.io.File(path).parentFile?.name ?: "Unknown Folder"
    } catch (e: Exception) {
        "Unknown Folder"
    }
}

fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

fun isLowRAMDevice(context: Context): Boolean {
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val memoryInfo = ActivityManager.MemoryInfo()
    activityManager.getMemoryInfo(memoryInfo)
    return memoryInfo.totalMem <= 4L * 1024 * 1024 * 1024
}

private val metadataFormatter = SimpleDateFormat("EEEE, MMMM dd, yyyy 'at' hh a", Locale.getDefault())
private val shortDateFormatter = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())

sealed class GalleryGridItem {
    data class Header(val id: String, val title: String, val count: Int) : GalleryGridItem()
    data class Media(val item: MediaItem) : GalleryGridItem()
}

sealed class PictureUiDialog {
    data object None : PictureUiDialog()
    data object GridSize : PictureUiDialog()
    data object Sort : PictureUiDialog()
    data class TrashConfirm(val mediaIds: List<Long>) : PictureUiDialog()
    data class MetadataInfo(val item: MediaItem) : PictureUiDialog()
    data class QuickAction(val item: MediaItem) : PictureUiDialog()
}

// ============================================================================
// MAIN SCREEN
// ============================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PictureScreen(
    viewModel: GalleryViewModel = hiltViewModel(),
    trashViewModel: TrashViewModel = hiltViewModel(),
    onViewerStateChanged: (Boolean) -> Unit = {},
    onNavigateToCamera: () -> Unit,
    onNavigateToTrash: () -> Unit,
    onNavigateToHidden: () -> Unit,
    onLockApp: () -> Unit = {},
    onNavigateToDuplicates: () -> Unit,
    onNavigateToWallpaper: (String, Long) -> Unit,
    onNavigateToSlideshow: () -> Unit,
    onNavigateToScan: () -> Unit,
    onNavigateToAlbum: (String) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToVideoPlayer: (String, List<String>) -> Unit,
    onNavigateToEditor: (String, Long) -> Unit,
    onNavigateToMoveCopy: (String, String, String?) -> Unit
) {
    val context = LocalContext.current
    val isLowRam = remember { isLowRAMDevice(context) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val haptic = LocalHapticFeedback.current

    val gridState = rememberLazyGridState()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

    val filters = remember {
        listOf(
            UiMediaFilter.ALL,
            UiMediaFilter.PHOTOS,
            UiMediaFilter.VIDEOS
        )
    }

    var activeFilter by rememberSaveable { mutableStateOf(UiMediaFilter.ALL) }

    val isBusy by viewModel.isBusy.collectAsState()
    val activeSort by viewModel.activeSort.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val viewerState by viewModel.viewerState.collectAsState()
    val mediaMap by viewModel.mediaMap.collectAsState()

    val openViewerState = viewerState as? GalleryViewerState.Open
    val currentItem = openViewerState?.mediaId?.let { mediaMap[it] }
    val pagedMedia = viewModel.pagedMedia.collectAsLazyPagingItems()

    val prefs = remember { context.getSharedPreferences("gallery_prefs", Context.MODE_PRIVATE) }
    var columnCount by rememberSaveable { mutableIntStateOf(prefs.getInt("picture_grid_columns", if (isLowRam) 3 else 4)) }
    var isSelectionMode by rememberSaveable { mutableStateOf(false) }

    var selectedIds by remember { mutableStateOf<ImmutableSet<Long>>(persistentSetOf()) }

    var isSearchActive by rememberSaveable { mutableStateOf(false) }
    var activeDialog by remember { mutableStateOf<PictureUiDialog>(PictureUiDialog.None) }

    val showScrollToTop by remember { derivedStateOf { gridState.firstVisibleItemIndex > 10 } }

    val intentSenderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val g = result.resultCode == Activity.RESULT_OK
        trashViewModel.onPermissionResultGlobal(g)
        if (!g) Toast.makeText(context, "Permission Denied", Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(trashViewModel) {
        trashViewModel.onRefreshGallery = {
            scope.launch {
                viewModel.forceSync()
            }
        }
        trashViewModel.events.collect { event ->
            when (event) {
                is GalleryEvent.RequestPermission -> {
                    intentSenderLauncher.launch(IntentSenderRequest.Builder(event.intentSender).build())
                }
                is GalleryEvent.OperationSuccess -> {
                    activeDialog = PictureUiDialog.None
                    isSelectionMode = false
                    selectedIds = persistentSetOf()
                    viewModel.closeViewer()
                    scope.launch {
                        if (snackbarHostState.showSnackbar("Moved to Trash", "View Trash", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                            onNavigateToTrash()
                        }
                    }
                }
                is GalleryEvent.ShowToast -> Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                else -> {}
            }
        }
    }

    LaunchedEffect(viewerState) {
        onViewerStateChanged(viewerState is GalleryViewerState.Open)
    }

    LaunchedEffect(activeFilter) {
        if (isSelectionMode) {
            isSelectionMode = false
            selectedIds = persistentSetOf()
        }
    }

    fun getMediaItemFromId(id: Long): MediaItem? {
        return mediaMap[id]
    }

    val selectedSizeStr by remember(selectedIds, mediaMap) {
        derivedStateOf {
            val sum = selectedIds.sumOf { getMediaItemFromId(it)?.size ?: 0L }
            Formatter.formatShortFileSize(context, sum)
        }
    }

    fun shareMedia(items: List<MediaItem>) {
        if (items.isEmpty()) return
        val uris = items.map { it.uri }
        val intent = Intent().apply {
            action = if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND
            val hasImage = items.any { !it.isVideo }
            val hasVideo = items.any { it.isVideo }

            type = when {
                hasVideo && !hasImage -> "video/*"
                hasImage && !hasVideo -> "image/*"
                else -> "*/*"
            }

            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (uris.size > 1) {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            } else {
                putExtra(Intent.EXTRA_STREAM, uris.first())
            }
        }
        try {
            context.startActivity(Intent.createChooser(intent, "Share via"))
        } catch (e: Exception) {
            Toast.makeText(context, "No app found to share", Toast.LENGTH_SHORT).show()
        }
    }

    BackHandler(enabled = isSearchActive) {
        isSearchActive = false
        viewModel.setSearchQuery("")
    }

    BackHandler(enabled = isSelectionMode) {
        isSelectionMode = false
        selectedIds = persistentSetOf()
    }

    BackHandler(enabled = activeDialog != PictureUiDialog.None) {
        activeDialog = PictureUiDialog.None
    }

    BackHandler(enabled = viewerState is GalleryViewerState.Open) {
        viewModel.closeViewer()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                Column {
                    AnimatedContent(
                        targetState = isSelectionMode to isSearchActive,
                        label = "TopBar",
                        transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) }
                    ) { (selection, search) ->
                        when {
                            selection -> MediaSelectionTopBar(
                                selectedCount = selectedIds.size,
                                selectedSizeStr = selectedSizeStr,
                                totalCount = pagedMedia.itemCount,
                                onClose = {
                                    isSelectionMode = false
                                    selectedIds = persistentSetOf()
                                },
                                onSelectAll = {
                                    val mediaItems = pagedMedia.itemSnapshotList.items.filterIsInstance<GalleryGridItem.Media>()
                                    selectedIds = if (selectedIds.size == mediaItems.size && mediaItems.isNotEmpty()) {
                                        persistentSetOf()
                                    } else {
                                        val newSelection = mediaItems.map { it.item.id }.toSet()
                                        if (newSelection.size < 5000) newSelection.toImmutableSet() else selectedIds
                                    }
                                }
                            )
                            search -> SearchTopBar(
                                query = searchQuery,
                                onQueryChange = {
                                    viewModel.setSearchQuery(it)
                                },
                                onClose = {
                                    isSearchActive = false
                                    viewModel.setSearchQuery("")
                                }
                            )
                            else -> ModernTopBar(
                                title = "Photos",
                                subtitle = "All Media",
                                scrollBehavior = scrollBehavior,
                                onSearchClick = { isSearchActive = true },
                                onSelectionClick = { isSelectionMode = true },
                                onMenuAction = { action ->
                                    when (action) {
                                        "grid" -> activeDialog = PictureUiDialog.GridSize
                                        "sort" -> activeDialog = PictureUiDialog.Sort
                                        "slideshow" -> onNavigateToSlideshow()
                                        "duplicates" -> onNavigateToDuplicates()
                                        "scan" -> onNavigateToScan()
                                        "trash" -> onNavigateToTrash()
                                        "hidden" -> onNavigateToHidden()
                                        "lock_app" -> onLockApp()
                                        "settings" -> onNavigateToSettings()
                                    }
                                }
                            )
                        }
                    }
                    if (!isSelectionMode && !isSearchActive) {
                        ModernFilterRow(
                            filters = filters,
                            activeFilter = activeFilter,
                            onFilterSelected = { filter ->
                                activeFilter = filter
                                val galleryFilter = when(filter) {
                                    UiMediaFilter.PHOTOS -> MediaTypeFilter.PHOTOS
                                    UiMediaFilter.VIDEOS -> MediaTypeFilter.VIDEOS
                                    else -> MediaTypeFilter.ALL
                                }
                                viewModel.updateFilter(galleryFilter)
                            }
                        )
                    }
                }
            },
            floatingActionButton = {
                AnimatedVisibility(
                    visible = !isSelectionMode && showScrollToTop,
                    enter = slideInVertically { it } + fadeIn() + scaleIn(),
                    exit = slideOutVertically { it } + fadeOut() + scaleOut()
                ) {
                    FloatingActionButton(
                        onClick = {
                            scope.launch { gridState.animateScrollToItem(0) }
                        },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = RoundedCornerShape(16.dp),
                        elevation = FloatingActionButtonDefaults.elevation(8.dp)
                    ) {
                        Icon(Icons.Rounded.ArrowUpward, "Scroll to Top")
                    }
                }
            }
        ) { padding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                if (isBusy && pagedMedia.itemCount == 0) {
                    Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (pagedMedia.itemCount == 0) {
                    EmptyMediaOverlay()
                } else {
                    GalleryGridContent(
                        pagedMedia = pagedMedia,
                        gridState = gridState,
                        columnCount = columnCount,
                        isSelectionMode = isSelectionMode,
                        selectedIds = selectedIds,
                        mediaMap = mediaMap,
                        isLowRam = isLowRam,
                        onSelectionChange = { selectedIds = it.toImmutableSet() },
                        onSelectionModeChange = { isSelectionMode = it },
                        onItemClick = { item ->
                            try {
                                if (isSelectionMode) {
                                    selectedIds = if (selectedIds.contains(item.id)) {
                                        (selectedIds - item.id).toImmutableSet()
                                    } else {
                                        (selectedIds + item.id).toImmutableSet().takeIf { it.size < 5000 } ?: selectedIds
                                    }
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                } else {
                                    viewModel.openViewer(item.id)
                                }
                            } catch (e: Exception) {
                                Log.e("GalleryBox", "Open Error", e)
                            }
                        },
                        onItemLongClick = { item ->
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (isSelectionMode) {
                                selectedIds = if (selectedIds.contains(item.id)) {
                                    (selectedIds - item.id).toImmutableSet()
                                } else {
                                    (selectedIds + item.id).toImmutableSet().takeIf { it.size < 5000 } ?: selectedIds
                                }
                            } else {
                                activeDialog = PictureUiDialog.QuickAction(item)
                            }
                        }
                    )
                }
            }
        }

        // Dialogs & Sheets
        when (val dialog = activeDialog) {
            is PictureUiDialog.TrashConfirm -> {
                AlertDialog(
                    onDismissRequest = { activeDialog = PictureUiDialog.None },
                    icon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
                    title = { Text("Move to Trash?") },
                    text = { Text("These items will be moved to the Trash. You can restore or permanently delete them from there within 30 days.") },
                    confirmButton = {
                        Button(
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            onClick = {
                                val itemsToTrash = dialog.mediaIds.mapNotNull { getMediaItemFromId(it) }
                                if (itemsToTrash.isNotEmpty()) {
                                    trashViewModel.confirmPendingGalleryTrash(itemsToTrash)
                                }
                            }
                        ) {
                            Text("Move to Trash")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { activeDialog = PictureUiDialog.None }) {
                            Text("Cancel")
                        }
                    }
                )
            }
            is PictureUiDialog.QuickAction -> {
                val item = dialog.item
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                var showMoreExpanded by remember { mutableStateOf(false) }

                ModalBottomSheet(
                    onDismissRequest = { activeDialog = PictureUiDialog.None },
                    sheetState = sheetState,
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        Modifier
                            .padding(horizontal = 24.dp, vertical = 16.dp)
                            .padding(bottom = 24.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text(
                                    text = getSmartName(item),
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = getFolderName(item.path),
                                    color = Color.Gray,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            item {
                                ActionItem(Icons.Outlined.Edit, "Edit", enabled = true) {
                                    activeDialog = PictureUiDialog.None
                                    onNavigateToEditor(item.uri.toString(), item.id)
                                }
                            }
                            item {
                                ActionItem(Icons.Outlined.Share, "Share") {
                                    shareMedia(listOf(item))
                                    activeDialog = PictureUiDialog.None
                                }
                            }
                            item {
                                ActionItem(Icons.Outlined.Delete, "Delete", isDestructive = true) {
                                    activeDialog = PictureUiDialog.TrashConfirm(listOf(item.id))
                                }
                            }
                            item {
                                ActionItem(Icons.Default.MoreVert, "More") {
                                    showMoreExpanded = true
                                }
                            }
                        }
                        AnimatedVisibility(visible = showMoreExpanded) {
                            Column(Modifier.padding(top = 16.dp)) {
                                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                                ListItem(
                                    headlineContent = { Text("Details", fontWeight = FontWeight.SemiBold) },
                                    leadingContent = { Icon(Icons.Outlined.Info, null) },
                                    modifier = Modifier.clickable {
                                        activeDialog = PictureUiDialog.None
                                        activeDialog = PictureUiDialog.MetadataInfo(item)
                                    }
                                )
                                ListItem(
                                    headlineContent = { Text("Move to Album", fontWeight = FontWeight.SemiBold) },
                                    leadingContent = { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, null) },
                                    modifier = Modifier.clickable {
                                        activeDialog = PictureUiDialog.None
                                        onNavigateToMoveCopy("MOVE", item.id.toString(), null)
                                    }
                                )
                                ListItem(
                                    headlineContent = { Text("Copy to Album", fontWeight = FontWeight.SemiBold) },
                                    leadingContent = { Icon(Icons.Outlined.FileCopy, null) },
                                    modifier = Modifier.clickable {
                                        activeDialog = PictureUiDialog.None
                                        onNavigateToMoveCopy("COPY", item.id.toString(), null)
                                    }
                                )
                                if (!item.isVideo) {
                                    ListItem(
                                        headlineContent = { Text("Set as Wallpaper", fontWeight = FontWeight.SemiBold) },
                                        leadingContent = { Icon(Icons.Outlined.Wallpaper, null) },
                                        modifier = Modifier.clickable {
                                            activeDialog = PictureUiDialog.None
                                            onNavigateToWallpaper(item.uri.toString(), item.id)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            is PictureUiDialog.GridSize -> {
                ModernGridSheet(
                    currentColumns = columnCount,
                    onDismiss = { activeDialog = PictureUiDialog.None },
                    onUpdate = {
                        columnCount = it
                        prefs.edit().putInt("picture_grid_columns", it).apply()
                        activeDialog = PictureUiDialog.None
                    }
                )
            }
            is PictureUiDialog.Sort -> {
                ModernSortSheet(
                    activeSort = activeSort,
                    onDismiss = { activeDialog = PictureUiDialog.None },
                    onSortSelected = {
                        viewModel.updateSort(it)
                        activeDialog = PictureUiDialog.None
                    }
                )
            }
            is PictureUiDialog.MetadataInfo -> {
                MediaMetadataSheet(item = dialog.item) {
                    activeDialog = PictureUiDialog.None
                }
            }
            PictureUiDialog.None -> {}
        }

        // VIEWER OVERLAY
        AnimatedVisibility(
            visible = viewerState is GalleryViewerState.Open,
            enter = scaleIn(initialScale = 0.9f, animationSpec = tween(250, easing = FastOutSlowInEasing)) + fadeIn(tween(200)),
            exit = scaleOut(targetScale = 0.9f, animationSpec = tween(200)) + fadeOut(tween(150))
        ) {
            if (currentItem != null) {
                val stableMediaList = pagedMedia.itemSnapshotList.items
                    .filterIsInstance<GalleryGridItem.Media>()
                    .map { mediaMap[it.item.id] ?: it.item }

                val stableStartIndex = stableMediaList.indexOfFirst {
                    it.id == currentItem.id
                }.coerceAtLeast(0)

                key(currentItem.id) {
                    FullscreenMediaPager(
                        initialIndex = stableStartIndex,
                        mediaList = stableMediaList,
                        mediaMap = mediaMap,
                        sharedPlayer = viewModel.getPlayer(),
                        onClose = { viewModel.closeViewer() },
                        onEdit = { item ->
                            viewModel.closeViewer()
                            onNavigateToEditor(item.uri.toString(), item.id)
                        },
                        onDelete = { item ->
                            activeDialog = PictureUiDialog.TrashConfirm(listOf(item.id))
                        },
                        onNavigateToVideoPlayer = { uri ->
                            viewModel.closeViewer()
                            val videoList = stableMediaList.filter { it.isVideo }.map { it.uri.toString() }
                            onNavigateToVideoPlayer(uri, videoList)
                        },
                        onMove = { item ->
                            viewModel.closeViewer()
                            onNavigateToMoveCopy("MOVE", item.id.toString(), null)
                        },
                        onCopy = { item ->
                            viewModel.closeViewer()
                            onNavigateToMoveCopy("COPY", item.id.toString(), null)
                        },
                        onWallpaper = { item ->
                            viewModel.closeViewer()
                            onNavigateToWallpaper(item.uri.toString(), item.id)
                        }
                    )
                }
            }
        }
    }
}

// ============================================================================
// CONTENT GRID & PREFETCHER
// ============================================================================
@Composable
fun GalleryGridImagePrefetcher(
    gridState: LazyGridState,
    pagedMedia: LazyPagingItems<GalleryGridItem>,
    mediaMap: Map<Long, MediaItem>
) {
    val context = LocalContext.current
    val imageLoader = context.imageLoader

    LaunchedEffect(gridState, pagedMedia.itemCount) {
        snapshotFlow { gridState.layoutInfo }.collect { layoutInfo ->
            val visibleItems = layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty() || pagedMedia.itemCount == 0) return@collect

            val firstVisible = visibleItems.first().index
            val lastVisible = visibleItems.last().index

            val prefetchStart = (firstVisible - 20).coerceAtLeast(0)
            val prefetchEnd = (lastVisible + 20).coerceAtMost(pagedMedia.itemCount - 1)

            for (i in prefetchStart..prefetchEnd) {
                if (i !in firstVisible..lastVisible) {
                    val gridItem = pagedMedia.peek(i)
                    if (gridItem is GalleryGridItem.Media) {
                        val mediaItem = mediaMap[gridItem.item.id] ?: gridItem.item
                        if (isValidUri(context, mediaItem.uri)) {
                            val request = ImageRequest.Builder(context)
                                .data(mediaItem.uri)
                                .size(160) // explicitly small size for prefetching
                                .memoryCacheKey("thumb_${mediaItem.id}")
                                .diskCachePolicy(CachePolicy.ENABLED)
                                .build()
                            imageLoader.enqueue(request)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GalleryGridContent(
    pagedMedia: LazyPagingItems<GalleryGridItem>,
    gridState: LazyGridState,
    columnCount: Int,
    isSelectionMode: Boolean,
    selectedIds: Set<Long>,
    mediaMap: Map<Long, MediaItem>,
    isLowRam: Boolean,
    onSelectionChange: (Set<Long>) -> Unit,
    onSelectionModeChange: (Boolean) -> Unit,
    onItemClick: (MediaItem) -> Unit,
    onItemLongClick: (MediaItem) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val isScrolling by remember { derivedStateOf { gridState.isScrollInProgress } }

    val dynamicThumbSize = remember(columnCount, isLowRam, isScrolling) {
        if (isScrolling) 160
        else if (isLowRam) 256
        else when (columnCount) {
            1, 2 -> 512
            3 -> 256
            else -> maxOf(160, 512 / columnCount)
        }
    }

    // Connect Prefetcher
    GalleryGridImagePrefetcher(gridState, pagedMedia, mediaMap)

    // Sticky Date Header state
    val firstVisibleIndex by remember { derivedStateOf { gridState.firstVisibleItemIndex } }
    val stickyDate by remember {
        derivedStateOf {
            if (pagedMedia.itemCount > 0 && firstVisibleIndex in 0 until pagedMedia.itemCount) {
                when (val item = pagedMedia.peek(firstVisibleIndex)) {
                    is GalleryGridItem.Header -> item.title
                    is GalleryGridItem.Media -> item.item.dateHeader
                    else -> ""
                }
            } else ""
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(columnCount),
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(isSelectionMode) {
                    if (!isSelectionMode) return@pointerInput
                    var initialKey: Long? = null
                    detectDragGestures(
                        onDragStart = { offset ->
                            val itemInfo = gridState.layoutInfo.visibleItemsInfo.find {
                                offset.y >= it.offset.y && offset.y <= (it.offset.y + it.size.height) &&
                                        offset.x >= it.offset.x && offset.x <= (it.offset.x + it.size.width)
                            }
                            itemInfo?.let {
                                val item = pagedMedia.peek(it.index) as? GalleryGridItem.Media
                                if (item != null) {
                                    initialKey = item.item.id
                                    onSelectionChange(
                                        (selectedIds + item.item.id).toSet().takeIf { set -> set.size < 5000 } ?: selectedIds
                                    )
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            }
                        },
                        onDrag = { change, _ ->
                            val offset = change.position
                            val itemInfo = gridState.layoutInfo.visibleItemsInfo.find {
                                offset.y >= it.offset.y && offset.y <= (it.offset.y + it.size.height) &&
                                        offset.x >= it.offset.x && offset.x <= (it.offset.x + it.size.width)
                            }
                            itemInfo?.let {
                                val item = pagedMedia.peek(it.index) as? GalleryGridItem.Media
                                if (item != null && item.item.id != initialKey && !selectedIds.contains(item.item.id)) {
                                    onSelectionChange(
                                        (selectedIds + item.item.id).toSet().takeIf { set -> set.size < 5000 } ?: selectedIds
                                    )
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            }
                        },
                        onDragEnd = { initialKey = null },
                        onDragCancel = { initialKey = null }
                    )
                },
            contentPadding = PaddingValues(top = 4.dp, bottom = 120.dp, start = 3.dp, end = 3.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            items(
                count = pagedMedia.itemCount,
                span = { index ->
                    if (pagedMedia.peek(index) is GalleryGridItem.Header) GridItemSpan(columnCount) else GridItemSpan(1)
                },
                key = { index ->
                    when (val item = pagedMedia.peek(index)) {
                        is GalleryGridItem.Media -> item.item.id
                        is GalleryGridItem.Header -> "header_${item.id}"
                        else -> index
                    }
                },
                contentType = { index ->
                    if (pagedMedia.peek(index) is GalleryGridItem.Header) "header" else "media"
                }
            ) { index ->
                val modifierWithAnim = if (isScrolling) Modifier else Modifier.animateItem()

                when (val gridItem = pagedMedia[index]) {
                    is GalleryGridItem.Header -> ModernDateHeader(
                        modifier = modifierWithAnim,
                        title = gridItem.title,
                        count = gridItem.count,
                        onSelectAllForDate = {
                            val snapshot = pagedMedia.itemSnapshotList.items
                                .filterIsInstance<GalleryGridItem.Media>()
                                .filter { it.item.dateHeader == gridItem.title }

                            onSelectionChange(
                                (selectedIds + snapshot.map { it.item.id }.toSet()).takeIf { set -> set.size < 5000 } ?: selectedIds
                            )
                            onSelectionModeChange(true)
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    )
                    is GalleryGridItem.Media -> {
                        val mediaItem = mediaMap[gridItem.item.id] ?: gridItem.item
                        if (isValidUri(LocalContext.current, mediaItem.uri)) {
                            ModernMediaGridTile(
                                modifier = modifierWithAnim,
                                item = mediaItem,
                                thumbSize = dynamicThumbSize,
                                isSelected = selectedIds.contains(mediaItem.id),
                                isSelectionMode = isSelectionMode,
                                isLowRam = isLowRam,
                                onClick = { onItemClick(mediaItem) },
                                onLongClick = { onItemLongClick(mediaItem) }
                            )
                        }
                    }
                    null -> Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = stickyDate.isNotBlank() && firstVisibleIndex > 0,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
        ) {
            Box(
                Modifier
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.95f))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(
                    text = stickyDate,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }
    }
}

// ============================================================================
// VIEWERS
// ============================================================================
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FullscreenMediaPager(
    initialIndex: Int,
    mediaList: List<MediaItem>,
    mediaMap: Map<Long, MediaItem>,
    sharedPlayer: Player,
    onClose: () -> Unit,
    onEdit: (MediaItem) -> Unit,
    onDelete: (MediaItem) -> Unit,
    onNavigateToVideoPlayer: (String) -> Unit,
    onMove: (MediaItem) -> Unit,
    onCopy: (MediaItem) -> Unit,
    onWallpaper: (MediaItem) -> Unit
) {
    if (mediaList.isEmpty()) return
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    val pagerState = rememberPagerState(
        initialPage = initialIndex,
        pageCount = { mediaList.size }
    )

    var showControls by remember { mutableStateOf(true) }
    var showMetadataSheet by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }

    val activity = remember { context.findActivity() }
    val zoomedPages = remember { mutableStateMapOf<Int, Boolean>() }
    val isCurrentPageZoomed = zoomedPages[pagerState.currentPage] ?: false

    var currentRotation by remember(pagerState.currentPage) { mutableFloatStateOf(0f) }

    val filmstripState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)

    LaunchedEffect(initialIndex, mediaList.size) {
        if (pagerState.currentPage != initialIndex && initialIndex in mediaList.indices) {
            pagerState.scrollToPage(initialIndex)
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        if (zoomedPages.size > 20) zoomedPages.clear()
        showControls = true
        filmstripState.animateScrollToItem(pagerState.currentPage.coerceAtMost(mediaList.size - 1))
    }

    DisposableEffect(activity) {
        val window = activity?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            window?.let {
                WindowCompat.getInsetsController(it, view).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    BackHandler(enabled = !showControls) { showControls = true }
    BackHandler(enabled = showControls) { onClose() }

    val currentPageId = mediaList.getOrNull(pagerState.currentPage)?.id
    LaunchedEffect(currentPageId) {
        if (currentPageId == null && mediaList.isNotEmpty()) {
            onClose()
        }
    }

    val liveCurrentItem = currentPageId?.let { mediaMap[it] }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        HorizontalPager(
            state = pagerState,
            pageSpacing = 20.dp,
            userScrollEnabled = !isCurrentPageZoomed,
            key = { index -> mediaList[index].id },
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val item = mediaList[page]
            when {
                item.isVideo -> {
                    VideoPreviewPage(
                        item = item,
                        isCurrentPage = pagerState.currentPage == page,
                        showControls = showControls,
                        sharedPlayer = sharedPlayer,
                        onTap = { showControls = !showControls },
                        onPlay = { onNavigateToVideoPlayer(item.uri.toString()) }
                    )
                }
                else -> {
                    ZoomableImagePage(
                        item = item,
                        mediaRotation = if (pagerState.currentPage == page) currentRotation else 0f,
                        onTap = { showControls = !showControls },
                        onDismiss = onClose,
                        onZoomChanged = { isZoomed -> zoomedPages[page] = isZoomed },
                        onControlsVisibilityChange = { visible -> showControls = visible }
                    )
                }
            }
        }

        val topGradientBrush = remember {
            Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent))
        }
        val bottomGradientBrush = remember {
            Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)))
        }

        AnimatedVisibility(
            visible = showControls,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(topGradientBrush)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        val dateStr = liveCurrentItem?.let {
                            shortDateFormatter.format(Date(it.dateAdded * 1000))
                        } ?: ""
                        Text(dateStr, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("${pagerState.currentPage + 1} / ${mediaList.size}", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                    }
                    IconButton(onClick = { showMetadataSheet = true }) {
                        Icon(Icons.Outlined.Info, contentDescription = "Info", tint = Color.White)
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = showControls,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut()
        ) {
            val currentItem = liveCurrentItem ?: return@AnimatedVisibility
            var isFavorite by remember(currentItem.id) { mutableStateOf(currentItem.isFavorite) }

            Column(
                Modifier
                    .fillMaxWidth()
                    .background(bottomGradientBrush)
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp)
            ) {
                LazyRow(
                    state = filmstripState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    itemsIndexed(mediaList, key = { _, item -> "strip_${item.id}" }) { index, item ->
                        val isSelected = index == pagerState.currentPage
                        AsyncImage(
                            model = ImageRequest.Builder(context).data(item.uri).size(150).build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(if (isSelected) 48.dp else 40.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .border(
                                    width = if (isSelected) 2.dp else 0.dp,
                                    color = if (isSelected) Color.White else Color.Transparent,
                                    shape = RoundedCornerShape(4.dp)
                                )
                                .clickable {
                                    scope.launch { pagerState.animateScrollToPage(index) }
                                }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SamsungViewerAction(
                        icon = if (isFavorite) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                        label = "Favorite",
                    ) {
                        isFavorite = !isFavorite
                    }
                    SamsungViewerAction(icon = Icons.Outlined.Edit, label = "Edit") {
                        onEdit(currentItem)
                    }
                    SamsungViewerAction(icon = Icons.Outlined.Share, label = "Share") {
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = if (currentItem.isVideo) "video/*" else "image/*"
                            putExtra(Intent.EXTRA_STREAM, currentItem.uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }, "Share Media"))
                    }
                    SamsungViewerAction(icon = Icons.Outlined.Delete, label = "Delete") {
                        onDelete(currentItem)
                    }
                    SamsungViewerAction(icon = Icons.Default.MoreVert, label = "More") {
                        showMoreMenu = true
                    }
                }
            }
        }
    }

    if (showMetadataSheet) {
        liveCurrentItem?.let {
            MediaMetadataSheet(item = it) { showMetadataSheet = false }
        }
    }

    if (showMoreMenu) {
        val currentItem = liveCurrentItem ?: return
        @OptIn(ExperimentalMaterial3Api::class)
        ModalBottomSheet(
            onDismissRequest = { showMoreMenu = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.padding(bottom = 32.dp)) {
                ListItem(
                    headlineContent = { Text("Rotate", fontWeight = FontWeight.SemiBold) },
                    leadingContent = { Icon(Icons.Rounded.ScreenRotation, null) },
                    modifier = Modifier.clickable {
                        showMoreMenu = false
                        currentRotation += 90f
                    }
                )
                ListItem(
                    headlineContent = { Text("Move to Album", fontWeight = FontWeight.SemiBold) },
                    leadingContent = { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, null) },
                    modifier = Modifier.clickable {
                        showMoreMenu = false
                        onMove(currentItem)
                    }
                )
                ListItem(
                    headlineContent = { Text("Copy to Album", fontWeight = FontWeight.SemiBold) },
                    leadingContent = { Icon(Icons.Outlined.FileCopy, null) },
                    modifier = Modifier.clickable {
                        showMoreMenu = false
                        onCopy(currentItem)
                    }
                )
                if (!currentItem.isVideo) {
                    ListItem(
                        headlineContent = { Text("Set as Wallpaper", fontWeight = FontWeight.SemiBold) },
                        leadingContent = { Icon(Icons.Outlined.Wallpaper, null) },
                        modifier = Modifier.clickable {
                            showMoreMenu = false
                            onWallpaper(currentItem)
                        }
                    )
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun VideoPreviewPage(
    item: MediaItem,
    isCurrentPage: Boolean,
    showControls: Boolean,
    sharedPlayer: Player,
    onTap: () -> Unit,
    onPlay: () -> Unit
) {
    var muted by rememberSaveable(item.id) { mutableStateOf(true) }

    LaunchedEffect(item.id) {
        sharedPlayer.setMediaItem(Media3Item.fromUri(item.uri))
        sharedPlayer.prepare()
    }

    LaunchedEffect(muted) {
        sharedPlayer.volume = if (muted) 0f else 1f
    }

    LaunchedEffect(isCurrentPage) {
        sharedPlayer.playWhenReady = false
        sharedPlayer.pause()
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    layoutParams = android.view.ViewGroup.LayoutParams(-1, -1)
                }
            },
            update = { view ->
                view.player = sharedPlayer
            },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }
        )

        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 120.dp)
            ) {
                Surface(
                    modifier = Modifier.align(Alignment.Center),
                    shape = RoundedCornerShape(50),
                    color = Color.Black.copy(alpha = 0.45f)
                ) {
                    Row(
                        modifier = Modifier
                            .clickable { onPlay() }
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.PlayArrow, null, tint = Color.White)
                        Spacer(Modifier.width(6.dp))
                        Text("Play video", color = Color.White)
                    }
                }

                FilledIconButton(
                    onClick = { muted = !muted },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 20.dp)
                        .size(32.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.Black.copy(alpha = 0.55f))
                ) {
                    Icon(
                        imageVector = if (muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                        contentDescription = "Toggle Volume",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SamsungViewerAction(
    icon: ImageVector,
    label: String,
    tint: Color = Color.White,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = label, color = tint, style = MaterialTheme.typography.labelSmall)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ModernMediaGridTile(
    modifier: Modifier = Modifier,
    item: MediaItem,
    thumbSize: Int,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    isLowRam: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
){
    val cornerRadius = 8.dp
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val targetScale = when {
        isPressed -> 0.94f
        isSelected -> 0.90f
        else -> 1f
    }

    val scale = if (isLowRam) {
        targetScale
    } else {
        animateFloatAsState(
            targetValue = targetScale,
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            label = "tileScale"
        ).value
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                clip = true
                shape = RoundedCornerShape(cornerRadius)
            }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = androidx.compose.material3.ripple(),
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        val requestBuilder = ImageRequest.Builder(LocalContext.current)
            .data(item.uri)
            .size(thumbSize)
            .allowRgb565(true)
            .bitmapConfig(Bitmap.Config.RGB_565)
            .memoryCacheKey("thumb_${item.id}")
            .diskCacheKey("thumb_${item.id}")
            .networkCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .precision(Precision.INEXACT)
            .allowHardware(!item.isVideo)
            .crossfade(false)
            .error(android.R.drawable.ic_menu_report_image)
            .fallback(android.R.drawable.ic_menu_report_image)
            .apply {
                if (item.isVideo) decoderFactory(coil.decode.VideoFrameDecoder.Factory())
            }

        AsyncImage(
            model = requestBuilder.build(),
            placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.None,
            modifier = Modifier.fillMaxSize(),
            onError = { state ->
                Log.e("GalleryBox", "GridTile error: ${item.uri}", state.result.throwable)
            }
        )

        if (item.isVideo) {
            Box(
                Modifier
                    .fillMaxSize()
                    .drawWithCache {
                        val brush = Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f))
                        onDrawBehind { drawRect(brush) }
                    }
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp),
                shape = RoundedCornerShape(4.dp),
                color = Color.Black.copy(alpha = 0.6f)
            ) {
                Text(
                    text = "▶ ${formatDuration(item.duration)}",
                    fontSize = 11.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }
        }
        SelectionOverlay(isSelected, isSelectionMode, cornerRadius)
    }
}

@Composable
fun ZoomableImagePage(
    item: MediaItem,
    mediaRotation: Float = 0f,
    onTap: () -> Unit,
    onDismiss: () -> Unit,
    onZoomChanged: (Boolean) -> Unit,
    onControlsVisibilityChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp

    val dismissThreshold = remember {
        with(density) { screenHeight.toPx() * 0.25f }
    }

    val scale = remember { Animatable(1f) }
    val offset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var backgroundAlpha by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(scale.value) {
        onZoomChanged(scale.value > 1.05f)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = backgroundAlpha))
            .offset { IntOffset(0, dragOffsetY.roundToInt()) }
            .graphicsLayer {
                val dismissScale = 1f - (abs(dragOffsetY) / 2200f)
                scaleX = scale.value * dismissScale
                scaleY = scaleX
                alpha = (1f - (abs(dragOffsetY) / 850f)).coerceIn(0f, 1f)
                translationX = offset.value.x
                translationY = offset.value.y
                rotationZ = mediaRotation
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        scope.launch {
                            val currentScale = scale.value
                            val target = when {
                                currentScale < 1.5f -> 2.5f
                                currentScale < 3.5f -> 4f
                                else -> 1f
                            }
                            scale.animateTo(target, spring(stiffness = Spring.StiffnessLow))
                        }
                        scope.launch { offset.animateTo(Offset.Zero, spring()) }
                    },
                    onLongPress = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()

                        scope.launch {
                            scale.snapTo((scale.value * zoom).coerceIn(1f, 4f))
                        }

                        if (scale.value > 1.05f) {
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                            val maxX = (size.width * (scale.value - 1)) / 2f
                            val maxY = (size.height * (scale.value - 1)) / 2f

                            var newX = offset.value.x + pan.x
                            var newY = offset.value.y + pan.y

                            // Elastic edge resistance
                            if (newX > maxX) newX = maxX + (newX - maxX) * 0.3f
                            else if (newX < -maxX) newX = -maxX + (newX + maxX) * 0.3f

                            if (newY > maxY) newY = maxY + (newY - maxY) * 0.3f
                            else if (newY < -maxY) newY = -maxY + (newY + maxY) * 0.3f

                            scope.launch { offset.snapTo(Offset(newX, newY)) }
                            dragOffsetY = 0f
                        } else {
                            scope.launch { offset.snapTo(Offset.Zero) }
                            val isVerticalDrag = abs(pan.y) > abs(pan.x)

                            if (isVerticalDrag && event.changes.size == 1) {
                                dragOffsetY += pan.y
                                backgroundAlpha = (1f - abs(dragOffsetY) / 900f).coerceIn(0.35f, 1f)

                                if (abs(dragOffsetY) > 50f) {
                                    onControlsVisibilityChange(false)
                                }

                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    if (scale.value <= 1.05f) {
                        if (abs(dragOffsetY) > dismissThreshold) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onDismiss()
                        } else {
                            dragOffsetY = 0f
                            backgroundAlpha = 1f
                        }
                    } else {
                        // Spring back inside bounds on release
                        val maxX = (size.width * (scale.value - 1)) / 2f
                        val maxY = (size.height * (scale.value - 1)) / 2f
                        val finalX = offset.value.x.coerceIn(-maxX, maxX)
                        val finalY = offset.value.y.coerceIn(-maxY, maxY)
                        scope.launch {
                            offset.animateTo(Offset(finalX, finalY), spring(stiffness = Spring.StiffnessMediumLow))
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val requestBuilder = ImageRequest.Builder(context)
            .data(item.uri)
            .allowHardware(true)
            .precision(Precision.INEXACT)
            .networkCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .crossfade(true)
            .error(android.R.drawable.ic_menu_report_image)

        AsyncImage(
            model = requestBuilder.build(),
            placeholder = ColorPainter(Color.Black),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaMetadataSheet(item: MediaItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val dateStr = remember(item) { metadataFormatter.format(Date(item.dateAdded * 1000)) }
    val formattedSize = remember(item) { Formatter.formatFileSize(context, item.size) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp)
                    .width(54.dp)
                    .height(5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f))
            )
        }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .padding(bottom = 34.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(58.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(30.dp)
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        text = "Media Details",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Information & metadata",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(28.dp))
            Surface(
                shape = RoundedCornerShape(30.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Column(Modifier.padding(18.dp)) {
                    MetadataRow(Icons.Outlined.Title, "Name", item.name)
                    MetadataRow(Icons.Outlined.Folder, "Path", item.path)
                    MetadataRow(Icons.Outlined.CalendarToday, "Date", dateStr)
                    MetadataRow(Icons.Outlined.Storage, "Size", formattedSize)

                    if (item.width > 0 && item.height > 0) {
                        MetadataRow(Icons.Outlined.AspectRatio, "Resolution", "${item.width} × ${item.height}")
                    }
                    if (item.isVideo && item.duration > 0L) {
                        MetadataRow(Icons.Outlined.Timer, "Duration", formatDuration(item.duration))
                    }

                    if (!item.isVideo) {
                        HorizontalDivider(Modifier.padding(vertical = 12.dp))
                        Text(
                            text = "Camera Information",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Row(Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) {
                                CameraInfoItem("Model", "Unknown")
                                CameraInfoItem("Aperture", "Not available")
                                CameraInfoItem("Focal Length", "Not available")
                            }
                            Column(Modifier.weight(1f)) {
                                CameraInfoItem("Shutter", "Not available")
                                CameraInfoItem("ISO", "Not available")
                                CameraInfoItem("GPS", "No location data")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CameraInfoItem(label: String, value: String) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun MetadataRow(icon: ImageVector, label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun SelectionOverlay(isSelected: Boolean, isSelectionMode: Boolean, cornerRadius: Dp) {
    AnimatedVisibility(visible = isSelectionMode, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(cornerRadius))) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)
            )
            Box(Modifier.padding(6.dp).align(Alignment.TopStart)) {
                AnimatedVisibility(
                    visible = isSelected,
                    enter = scaleIn() + fadeIn(),
                    exit = scaleOut() + fadeOut()
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(20.dp)
                            .background(Color.White, CircleShape)
                    )
                }
                AnimatedVisibility(
                    visible = !isSelected,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Icon(
                        imageVector = Icons.Outlined.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ActionItem(icon: ImageVector, label: String, isDestructive: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val contentColor = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ElevatedCard(
        modifier = Modifier
            .width(84.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.elevatedCardElevation(3.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (enabled) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLowest
        )
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(contentColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = if (enabled) contentColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) contentColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                maxLines = 1
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernSortSheet(activeSort: PhotoSort, onDismiss: () -> Unit, onSortSelected: (PhotoSort) -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp)
                    .width(54.dp)
                    .height(5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f))
            )
        }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 34.dp)
        ) {
            Row(
                Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Sort,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        text = "Sort Media",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Choose how media is organized",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            PhotoSort.entries.forEach { option ->
                val isSelected = activeSort == option
                Surface(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .clickable { onSortSelected(option) },
                    shape = RoundedCornerShape(24.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = if (isSelected) 4.dp else 0.dp
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerHighest),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isSelected) Icons.Rounded.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        Text(
                            text = option.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.weight(1f))
                        AnimatedVisibility(visible = isSelected) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernGridSheet(currentColumns: Int, max: Int = 6, onDismiss: () -> Unit, onUpdate: (Int) -> Unit) {
    var sliderValue by remember { mutableFloatStateOf(currentColumns.toFloat()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp)
                    .width(54.dp)
                    .height(5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f))
            )
        }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .padding(bottom = 34.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(58.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.GridView,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(30.dp)
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        text = "Grid Layout",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${sliderValue.toInt()} Columns",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(28.dp))
            Surface(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(30.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Column(Modifier.padding(22.dp)) {
                    repeat(2) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            repeat(sliderValue.toInt()) {
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                valueRange = 1f..max.toFloat(),
                steps = (max - 2).coerceAtLeast(0),
                onValueChangeFinished = { onUpdate(sliderValue.toInt()) },
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                )
            )
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Compact",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Comfortable",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun ModernDateHeader(modifier: Modifier = Modifier, title: String, count: Int, onSelectAllForDate: () -> Unit = {}) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 14.dp)
    ) {
        Text(
            text = title,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernTopBar(
    title: String,
    subtitle: String,
    scrollBehavior: TopAppBarScrollBehavior,
    onSearchClick: () -> Unit,
    onSelectionClick: () -> Unit,
    onMenuAction: (String) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    CenterAlignedTopAppBar(
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = title,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        actions = {
            IconButton(onClick = onSearchClick) {
                Icon(Icons.Outlined.Search, "Search", tint = MaterialTheme.colorScheme.onSurface)
            }
            IconButton(onClick = onSelectionClick) {
                Icon(Icons.Outlined.Checklist, "Select", tint = MaterialTheme.colorScheme.onSurface)
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Rounded.MoreVert, "More", tint = MaterialTheme.colorScheme.onSurface)
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    PremiumMenuItem("Grid Size", Icons.Rounded.GridView) { onMenuAction("grid"); showMenu = false }
                    PremiumMenuItem("Sort Media", Icons.AutoMirrored.Filled.Sort) { onMenuAction("sort"); showMenu = false }
                    PremiumMenuItem("Slideshow", Icons.Rounded.Slideshow) { onMenuAction("slideshow"); showMenu = false }
                    HorizontalDivider()
                    PremiumMenuItem("Duplicates", Icons.Outlined.FileCopy) { onMenuAction("duplicates"); showMenu = false }
                    PremiumMenuItem("Scan Library", Icons.Outlined.ImageSearch) { onMenuAction("scan"); showMenu = false }
                    HorizontalDivider()
                    PremiumMenuItem("Trash", Icons.Outlined.Delete) { onMenuAction("trash"); showMenu = false }
                    PremiumMenuItem("Hidden Media", Icons.Outlined.VisibilityOff) { onMenuAction("hidden"); showMenu = false }
                    PremiumMenuItem("Lock App", Icons.Outlined.Lock) { onMenuAction("lock_app"); showMenu = false }
                    HorizontalDivider()
                    PremiumMenuItem("Clear Cache", Icons.Outlined.DeleteSweep) { onMenuAction("clearcache"); showMenu = false }
                    PremiumMenuItem("Settings", Icons.Outlined.Settings) { onMenuAction("settings"); showMenu = false }
                }
            }
        },
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = MaterialTheme.colorScheme.surface
        )
    )
}

@Composable
private fun PremiumMenuItem(text: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, fontWeight = FontWeight.SemiBold) },
        onClick = onClick,
        leadingIcon = {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchTopBar(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth(),
        tonalElevation = 6.dp,
        shadowElevation = 10.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onSurface)
            }
            Spacer(Modifier.width(14.dp))
            Surface(
                Modifier.weight(1f),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    TextField(
                        value = query,
                        onValueChange = onQueryChange,
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                text = "Search photos, videos...",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            cursorColor = MaterialTheme.colorScheme.primary
                        )
                    )
                    AnimatedVisibility(visible = query.isNotBlank()) {
                        IconButton(
                            onClick = { onQueryChange("") },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Clear",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MediaSelectionTopBar(
    selectedCount: Int,
    selectedSizeStr: String,
    totalCount: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit
) {
    val isAllSelected = selectedCount == totalCount && totalCount > 0

    Surface(
        Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(vertical = 12.dp)
        ) {
            Box(Modifier.fillMaxWidth()) {
                Text(
                    text = "$selectedCount selected",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    Modifier
                        .clip(CircleShape)
                        .clickable { onSelectAll() }
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = if (isAllSelected) Icons.Rounded.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (isAllSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "All",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
fun ModernFilterRow(
    filters: List<UiMediaFilter>,
    activeFilter: UiMediaFilter,
    onFilterSelected: (UiMediaFilter) -> Unit
) {
    LazyRow(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(filters, key = { _, filter -> filter.name }) { _, filter ->
            val isSelected = activeFilter == filter
            val backgroundColor by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                label = "filterBg"
            )
            val textColor by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                label = "filterText"
            )

            Surface(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable { onFilterSelected(filter) },
                shape = RoundedCornerShape(50),
                color = backgroundColor
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AnimatedVisibility(visible = isSelected) {
                        Row {
                            Box(
                                Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                    }
                    Text(
                        text = filter.label,
                        color = textColor,
                        fontWeight = FontWeight.Medium,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}

@Composable
fun EmptyMediaOverlay() {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(112.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.PhotoLibrary,
                    contentDescription = null,
                    modifier = Modifier.size(58.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(28.dp))
            Text(
                text = "No Media Found",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "No images or videos are currently available.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}