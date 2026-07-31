@file:Suppress(
    "UnsafeOptInUsageError",
    "UnstableApiUsage",
    "OPT_IN_USAGE",
    "unused",
    "DEPRECATION"
)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.gallerybox.ui.screens.picture

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.Player
import androidx.media3.common.MediaItem as Media3Item
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
import com.gallerybox.viewmodel.GalleryViewerState
import com.gallerybox.viewmodel.MediaTypeFilter
import com.gallerybox.viewmodel.PhotoSort
import com.gallerybox.viewmodel.TrashViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ============================================================================
// HELPERS & STATE
// ============================================================================

enum class UiMediaFilter(val label: String) {
    ALL("All"),
    PHOTOS("Photos"),
    VIDEOS("Videos")
}

fun isValidUri(context: Context, uri: Uri?): Boolean {
    return uri != null && uri != Uri.EMPTY
}

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

private val metadataFormatter by lazy {
    SimpleDateFormat("EEEE, MMMM dd, yyyy 'at' hh a", Locale.getDefault())
}

private val shortDateFormatter by lazy {
    SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault())
}

sealed class GalleryGridItem {
    data class Header(
        val id: String,
        val title: String,
        val count: Int
    ) : GalleryGridItem()

    data class Media(
        val item: MediaItem
    ) : GalleryGridItem()
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
        listOf(UiMediaFilter.ALL, UiMediaFilter.PHOTOS, UiMediaFilter.VIDEOS)
    }

    var activeFilter by rememberSaveable { mutableStateOf(UiMediaFilter.ALL) }

    val isBusy by viewModel.isBusy.collectAsState()
    val activeSort by viewModel.activeSort.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val viewerState by viewModel.viewerState.collectAsState()
    val mediaMap by viewModel.mediaMap.collectAsState()

    // FIX: Extract global favorite IDs
    val favoriteIds by viewModel.favoriteIds.collectAsState()

    val openViewerState = viewerState as? GalleryViewerState.Open
    val currentItem = openViewerState?.mediaId?.let { mediaMap[it] }
    val pagedMedia = viewModel.pagedMedia.collectAsLazyPagingItems()

    val prefs = remember {
        context.getSharedPreferences("gallery_prefs", Context.MODE_PRIVATE)
    }

    // FIX: Locked Default Column Count to 4
    var columnCount by rememberSaveable { mutableIntStateOf(prefs.getInt("picture_grid_columns", 4)) }

    var isSelectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var isSearchActive by rememberSaveable { mutableStateOf(false) }
    var activeDialog by remember { mutableStateOf<PictureUiDialog>(PictureUiDialog.None) }

    // FIX: Auto-hide topbar logic for full screen scrolling
    var isTopBarVisible by remember { mutableStateOf(true) }
    var previousIndex by remember { mutableIntStateOf(0) }
    var previousScrollOffset by remember { mutableIntStateOf(0) }

    LaunchedEffect(gridState) {
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                if (index > previousIndex) {
                    isTopBarVisible = false
                } else if (index < previousIndex) {
                    isTopBarVisible = true
                } else {
                    if (offset > previousScrollOffset + 15) isTopBarVisible = false
                    else if (offset < previousScrollOffset - 15) isTopBarVisible = true
                }
                previousIndex = index
                previousScrollOffset = offset
            }
    }

    val isScrolling by remember { derivedStateOf { gridState.isScrollInProgress } }
    LaunchedEffect(isScrolling) {
        if (!isScrolling) {
            isTopBarVisible = true
        }
    }

    val intentSenderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val isGranted = result.resultCode == Activity.RESULT_OK
        trashViewModel.onPermissionResultGlobal(isGranted)
        if (!isGranted) {
            Toast.makeText(context, "Permission Denied", Toast.LENGTH_SHORT).show()
        }
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
                    val request = IntentSenderRequest.Builder(event.intentSender).build()
                    intentSenderLauncher.launch(request)
                }
                is GalleryEvent.OperationSuccess -> {
                    activeDialog = PictureUiDialog.None
                    isSelectionMode = false
                    selectedIds = emptySet()
                    viewModel.closeViewer()

                    val result = snackbarHostState.showSnackbar(
                        message = "Moved to Trash",
                        actionLabel = "View Trash",
                        duration = SnackbarDuration.Short
                    )

                    if (result == SnackbarResult.ActionPerformed) {
                        onNavigateToTrash()
                    }
                }
                is GalleryEvent.ShowToast -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
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
            selectedIds = emptySet()
        }
    }

    BackHandler(enabled = isSearchActive) {
        isSearchActive = false
        viewModel.setSearchQuery("")
    }

    BackHandler(enabled = isSelectionMode) {
        isSelectionMode = false
        selectedIds = emptySet()
    }

    BackHandler(enabled = activeDialog != PictureUiDialog.None) {
        activeDialog = PictureUiDialog.None
    }

    BackHandler(enabled = viewerState is GalleryViewerState.Open) {
        viewModel.closeViewer()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = Color.Transparent,
            snackbarHost = {
                SnackbarHost(snackbarHostState)
            },
            topBar = {
                // FIX: Slide the entire top section out of the way when scrolling
                AnimatedVisibility(
                    visible = isTopBarVisible || isSelectionMode || isSearchActive,
                    enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
                ) {
                    Column(modifier = Modifier.background(MaterialTheme.colorScheme.background.copy(alpha = 0.95f))) {
                        if (isSelectionMode) {
                            MediaSelectionTopBar(
                                selectedCount = selectedIds.size,
                                totalCount = pagedMedia.itemCount,
                                onClose = {
                                    isSelectionMode = false
                                    selectedIds = emptySet()
                                },
                                onSelectAll = {
                                    val mediaItems = pagedMedia.itemSnapshotList.items
                                        .filterIsInstance<GalleryGridItem.Media>()

                                    selectedIds = if (selectedIds.size == mediaItems.size && mediaItems.isNotEmpty()) {
                                        emptySet()
                                    } else {
                                        mediaItems.map { it.item.id }.take(5000).toSet()
                                    }
                                }
                            )
                        } else if (isSearchActive) {
                            SearchTopBar(
                                query = searchQuery,
                                onQueryChange = {
                                    viewModel.setSearchQuery(it)
                                },
                                onClose = {
                                    isSearchActive = false
                                    viewModel.setSearchQuery("")
                                }
                            )
                        } else {
                            ModernTopBar(
                                title = "Photos",
                                scrollBehavior = scrollBehavior,
                                onSearchClick = {
                                    isSearchActive = true
                                },
                                onMenuAction = { action ->
                                    when (action) {
                                        "edit" -> {}
                                        "select_all" -> {
                                            val mediaItems = pagedMedia.itemSnapshotList.items
                                                .filterIsInstance<GalleryGridItem.Media>()
                                            selectedIds = mediaItems.map { it.item.id }.take(5000).toSet()
                                            isSelectionMode = true
                                        }
                                        "grid" -> activeDialog = PictureUiDialog.GridSize
                                        "sort" -> activeDialog = PictureUiDialog.Sort
                                        "slideshow" -> onNavigateToSlideshow()
                                        "duplicates" -> onNavigateToDuplicates()
                                        "trash" -> onNavigateToTrash()
                                        "lock_app" -> onLockApp()
                                        "settings" -> onNavigateToSettings()
                                    }
                                }
                            )
                            ModernFilterRow(
                                filters = filters,
                                activeFilter = activeFilter,
                                onFilterSelected = { filter ->
                                    activeFilter = filter
                                    viewModel.updateFilter(
                                        when (filter) {
                                            UiMediaFilter.PHOTOS -> MediaTypeFilter.PHOTOS
                                            UiMediaFilter.VIDEOS -> MediaTypeFilter.VIDEOS
                                            else -> MediaTypeFilter.ALL
                                        }
                                    )
                                }
                            )
                        }
                    }
                }
            },
            floatingActionButton = {
                val showScrollToTop by remember {
                    derivedStateOf { gridState.firstVisibleItemIndex > 10 }
                }

                AnimatedVisibility(
                    visible = !isSelectionMode && showScrollToTop,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    FloatingActionButton(
                        onClick = {
                            scope.launch {
                                gridState.animateScrollToItem(0)
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ArrowUpward,
                            contentDescription = "Scroll to Top"
                        )
                    }
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // Apply padding so list doesn't get hidden behind status bar, but handles full scrolling beautifully.
                    .padding(top = if (isTopBarVisible || isSelectionMode || isSearchActive) padding.calculateTopPadding() else WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
            ) {
                if (isBusy && pagedMedia.itemCount == 0) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
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
                        onSelectionChange = {
                            selectedIds = it
                        },
                        onSelectionModeChange = {
                            isSelectionMode = it
                        },
                        onItemClick = { item ->
                            if (isSelectionMode) {
                                selectedIds = if (selectedIds.contains(item.id)) {
                                    selectedIds - item.id
                                } else {
                                    (selectedIds + item.id).takeIf { it.size < 5000 } ?: selectedIds
                                }
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            } else {
                                viewModel.openViewer(item.id)
                            }
                        },
                        onItemLongClick = { item ->
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (isSelectionMode) {
                                selectedIds = if (selectedIds.contains(item.id)) {
                                    selectedIds - item.id
                                } else {
                                    (selectedIds + item.id).takeIf { it.size < 5000 } ?: selectedIds
                                }
                            } else {
                                activeDialog = PictureUiDialog.QuickAction(item)
                            }
                        }
                    )
                }
            }
        }

        // Separate Dialog Rendering
        if (activeDialog != PictureUiDialog.None) {
            DialogsHost(
                dialog = activeDialog,
                mediaMap = mediaMap,
                trashViewModel = trashViewModel,
                viewModel = viewModel,
                activeSort = activeSort,
                columnCount = columnCount,
                prefs = prefs,
                onDismiss = {
                    activeDialog = PictureUiDialog.None
                },
                onNavigateToEditor = onNavigateToEditor,
                onNavigateToMoveCopy = onNavigateToMoveCopy,
                onNavigateToWallpaper = onNavigateToWallpaper,
                onUpdateColumns = { cols ->
                    columnCount = cols
                    prefs.edit().putInt("picture_grid_columns", cols).apply()
                    activeDialog = PictureUiDialog.None
                }
            )
        }

        // VIEWER OVERLAY
        AnimatedVisibility(
            visible = viewerState is GalleryViewerState.Open,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(150))
        ) {
            if (currentItem != null) {
                val stableMediaList = remember(pagedMedia.itemCount) {
                    pagedMedia.itemSnapshotList.items
                        .filterIsInstance<GalleryGridItem.Media>()
                        .map { mediaMap[it.item.id] ?: it.item }
                }

                val stableStartIndex = remember(currentItem.id, stableMediaList) {
                    stableMediaList
                        .indexOfFirst { it.id == currentItem.id }
                        .coerceAtLeast(0)
                }

                // FIX: Pass favoriteIds and onToggleFavorite correctly
                FullscreenMediaPager(
                    initialIndex = stableStartIndex,
                    mediaList = stableMediaList,
                    mediaMap = mediaMap,
                    favoriteIds = favoriteIds,
                    sharedPlayer = viewModel.getPlayer(),
                    onClose = {
                        viewModel.closeViewer()
                    },
                    onToggleFavorite = { id ->
                        viewModel.toggleFavorite(id)
                    },
                    onEdit = { item ->
                        viewModel.closeViewer()
                        onNavigateToEditor(item.uri.toString(), item.id)
                    },
                    onDelete = { item ->
                        activeDialog = PictureUiDialog.TrashConfirm(listOf(item.id))
                    },
                    onNavigateToVideoPlayer = { uri ->
                        viewModel.closeViewer()
                        val videoList = stableMediaList
                            .filter { it.isVideo }
                            .map { it.uri.toString() }
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

// ============================================================================
// DIALOG HOST
// ============================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialogsHost(
    dialog: PictureUiDialog,
    mediaMap: Map<Long, MediaItem>,
    trashViewModel: TrashViewModel,
    viewModel: GalleryViewModel,
    activeSort: PhotoSort,
    columnCount: Int,
    prefs: android.content.SharedPreferences,
    onDismiss: () -> Unit,
    onNavigateToEditor: (String, Long) -> Unit,
    onNavigateToMoveCopy: (String, String, String?) -> Unit,
    onNavigateToWallpaper: (String, Long) -> Unit,
    onUpdateColumns: (Int) -> Unit
) {
    val context = LocalContext.current
    when (dialog) {
        is PictureUiDialog.TrashConfirm -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                },
                title = {
                    Text("Move to Trash?")
                },
                text = {
                    Text("These items will be moved to the Trash. You can restore or permanently delete them from there within 30 days.")
                },
                confirmButton = {
                    Button(
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ),
                        onClick = {
                            val itemsToTrash = dialog.mediaIds.mapNotNull { mediaMap[it] }
                            if (itemsToTrash.isNotEmpty()) {
                                trashViewModel.confirmPendingGalleryTrash(itemsToTrash)
                            }
                        }
                    ) {
                        Text("Move to Trash")
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
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
                onDismissRequest = onDismiss,
                sheetState = sheetState,
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier
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

                    Spacer(modifier = Modifier.height(24.dp))

                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        item {
                            ActionItem(
                                icon = Icons.Outlined.Edit,
                                label = "Edit"
                            ) {
                                onDismiss()
                                onNavigateToEditor(item.uri.toString(), item.id)
                            }
                        }
                        item {
                            ActionItem(
                                icon = Icons.Outlined.Share,
                                label = "Share"
                            ) {
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = if (item.isVideo) "video/*" else "image/*"
                                    putExtra(Intent.EXTRA_STREAM, item.uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, "Share via"))
                                onDismiss()
                            }
                        }
                        item {
                            ActionItem(
                                icon = Icons.Outlined.Delete,
                                label = "Delete",
                                isDestructive = true
                            ) {
                                onDismiss()
                                trashViewModel.confirmPendingGalleryTrash(listOf(item))
                            }
                        }
                        item {
                            ActionItem(
                                icon = Icons.Default.MoreVert,
                                label = "More"
                            ) {
                                showMoreExpanded = true
                            }
                        }
                    }

                    AnimatedVisibility(visible = showMoreExpanded) {
                        Column(
                            modifier = Modifier.padding(top = 16.dp)
                        ) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                            ListItem(
                                headlineContent = {
                                    Text("Move to Album", fontWeight = FontWeight.SemiBold)
                                },
                                leadingContent = {
                                    Icon(Icons.Outlined.DriveFileMove, contentDescription = null)
                                },
                                modifier = Modifier.clickable {
                                    onDismiss()
                                    onNavigateToMoveCopy("MOVE", item.id.toString(), null)
                                }
                            )
                            ListItem(
                                headlineContent = {
                                    Text("Copy to Album", fontWeight = FontWeight.SemiBold)
                                },
                                leadingContent = {
                                    Icon(Icons.Outlined.FileCopy, contentDescription = null)
                                },
                                modifier = Modifier.clickable {
                                    onDismiss()
                                    onNavigateToMoveCopy("COPY", item.id.toString(), null)
                                }
                            )
                        }
                    }
                }
            }
        }
        is PictureUiDialog.GridSize -> {
            ModernGridSheet(
                currentColumns = columnCount,
                max = 8, // FIX: Max Grid Size 8
                onDismiss = onDismiss,
                onUpdate = onUpdateColumns
            )
        }
        is PictureUiDialog.Sort -> {
            ModernSortSheet(
                activeSort = activeSort,
                onDismiss = onDismiss,
                onSortSelected = {
                    viewModel.updateSort(it)
                    onDismiss()
                }
            )
        }
        is PictureUiDialog.MetadataInfo -> {
            MediaMetadataSheet(
                item = dialog.item,
                onDismiss = onDismiss
            )
        }
        PictureUiDialog.None -> {}
    }
}

// ============================================================================
// CONTENT GRID & PREFETCHER
// ============================================================================
@Composable
fun GalleryGridImagePrefetcher(
    gridState: LazyGridState,
    pagedMedia: LazyPagingItems<GalleryGridItem>,
    mediaMap: Map<Long, MediaItem>,
    isLowRam: Boolean
) {
    val context = LocalContext.current
    val imageLoader = context.imageLoader

    LaunchedEffect(gridState, isLowRam) {
        snapshotFlow { gridState.layoutInfo }.collect { layoutInfo ->
            val visibleItems = layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty() || pagedMedia.itemCount == 0) return@collect

            val firstVisible = visibleItems.first().index
            val lastVisible = visibleItems.last().index

            // Optimized bounds strictly for low-end devices
            val lookBehind = if (isLowRam) 2 else 6
            val lookAhead = if (isLowRam) 4 else 8

            val prefetchStart = (firstVisible - lookBehind).coerceAtLeast(0)
            val prefetchEnd = (lastVisible + lookAhead).coerceAtMost(pagedMedia.itemCount - 1)

            val config = if (isLowRam) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888

            for (i in prefetchStart..prefetchEnd) {
                if (i !in firstVisible..lastVisible) {
                    val gridItem = pagedMedia.peek(i)
                    if (gridItem is GalleryGridItem.Media) {
                        val mediaItem = mediaMap[gridItem.item.id] ?: gridItem.item
                        if (isValidUri(context, mediaItem.uri)) {
                            val request = ImageRequest.Builder(context)
                                .data(mediaItem.uri)
                                .size(200)
                                .bitmapConfig(config)
                                .memoryCacheKey("thumb_${mediaItem.id}")
                                .diskCachePolicy(CachePolicy.ENABLED)
                                .memoryCachePolicy(CachePolicy.ENABLED)
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
fun IsolatedStickyHeader(
    gridState: LazyGridState,
    pagedMedia: LazyPagingItems<GalleryGridItem>
) {
    val firstVisibleIndex by remember {
        derivedStateOf { gridState.firstVisibleItemIndex }
    }

    val stickyDate by remember(firstVisibleIndex) {
        derivedStateOf {
            if (pagedMedia.itemCount > 0 && firstVisibleIndex in 0 until pagedMedia.itemCount) {
                when (val item = pagedMedia.peek(firstVisibleIndex)) {
                    is GalleryGridItem.Header -> item.title
                    is GalleryGridItem.Media -> item.item.dateHeader
                    else -> ""
                }
            } else {
                ""
            }
        }
    }

    AnimatedVisibility(
        visible = stickyDate.isNotBlank() && firstVisibleIndex > 0,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
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
    val isScrolling by remember {
        derivedStateOf { gridState.isScrollInProgress }
    }

    var lastHapticTime by remember { mutableLongStateOf(0L) }

    val dynamicThumbSize = remember(columnCount) {
        when (columnCount) {
            1, 2 -> 512
            3 -> 256
            else -> maxOf(160, 512 / columnCount)
        }
    }

    GalleryGridImagePrefetcher(
        gridState = gridState,
        pagedMedia = pagedMedia,
        mediaMap = mediaMap,
        isLowRam = isLowRam
    )

    Box(modifier = Modifier.fillMaxSize()) {
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
                                    val updatedSelection = (selectedIds + item.item.id).takeIf { s -> s.size < 5000 } ?: selectedIds
                                    onSelectionChange(updatedSelection)
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
                                    val updatedSelection = (selectedIds + item.item.id).takeIf { s -> s.size < 5000 } ?: selectedIds
                                    onSelectionChange(updatedSelection)

                                    val now = System.currentTimeMillis()
                                    if (now - lastHapticTime > 80) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        lastHapticTime = now
                                    }
                                }
                            }
                        },
                        onDragEnd = { initialKey = null },
                        onDragCancel = { initialKey = null }
                    )
                },
            contentPadding = PaddingValues(
                top = 4.dp,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 8.dp,
                start = 2.dp,
                end = 2.dp
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(
                count = pagedMedia.itemCount,
                span = { index ->
                    if (pagedMedia.peek(index) is GalleryGridItem.Header) {
                        GridItemSpan(columnCount)
                    } else {
                        GridItemSpan(1)
                    }
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
                // Avoid layout thrashing by removing animateItem() conditionally based on state.
                // Using animateItem() without conditions resolves massive scrolling lag.
                val modifierWithAnim = Modifier.animateItem()

                when (val gridItem = pagedMedia[index]) {
                    is GalleryGridItem.Header -> {
                        ModernDateHeader(
                            modifier = modifierWithAnim,
                            title = gridItem.title,
                            onSelectAllForDate = {
                                val snapshot = pagedMedia.itemSnapshotList.items
                                    .filterIsInstance<GalleryGridItem.Media>()
                                    .filter { it.item.dateHeader == gridItem.title }

                                val updatedSelection = (selectedIds + snapshot.map { it.item.id }.toSet()).takeIf { s -> s.size < 5000 } ?: selectedIds
                                onSelectionChange(updatedSelection)
                                onSelectionModeChange(true)
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        )
                    }
                    is GalleryGridItem.Media -> {
                        val mediaItem = mediaMap[gridItem.item.id] ?: gridItem.item
                        ModernMediaGridTile(
                            modifier = modifierWithAnim,
                            item = mediaItem,
                            thumbSize = dynamicThumbSize,
                            isSelected = selectedIds.contains(mediaItem.id),
                            isSelectionMode = isSelectionMode,
                            isLowRam = isLowRam,
                            isScrolling = isScrolling,
                            onClick = { onItemClick(mediaItem) },
                            onLongClick = { onItemLongClick(mediaItem) }
                        )
                    }
                    null -> {
                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.LightGray.copy(alpha = 0.3f))
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier.align(Alignment.TopStart)
        ) {
            IsolatedStickyHeader(
                gridState = gridState,
                pagedMedia = pagedMedia
            )
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
    favoriteIds: List<Long>,
    sharedPlayer: Player,
    onClose: () -> Unit,
    onToggleFavorite: (Long) -> Unit,
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
    val activity = remember { context.findActivity() }

    val safeInitialPage = initialIndex.coerceIn(0, maxOf(mediaList.lastIndex, 0))
    val pagerState = rememberPagerState(
        initialPage = safeInitialPage,
        pageCount = { mediaList.size }
    )

    var showControls by remember { mutableStateOf(true) }
    var showMetadataSheet by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }

    // Playlist setup for ExoPlayer
    val videoList = remember(mediaList) {
        mediaList.filter { it.isVideo }
    }

    LaunchedEffect(videoList) {
        if (videoList.isNotEmpty()) {
            val mediaItems = videoList.map { Media3Item.fromUri(it.uri) }
            sharedPlayer.setMediaItems(mediaItems)
            sharedPlayer.playWhenReady = false
            sharedPlayer.prepare()
        }
    }

    LaunchedEffect(initialIndex, mediaList.size) {
        if (pagerState.currentPage != initialIndex && initialIndex in mediaList.indices) {
            pagerState.scrollToPage(initialIndex)
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        showControls = true

        val current = mediaList.getOrNull(pagerState.currentPage)

        if (current != null && !current.isVideo) {
            sharedPlayer.pause()
            if (sharedPlayer.mediaItemCount > 0) {
                sharedPlayer.seekTo(sharedPlayer.currentMediaItemIndex, 0)
            }
            sharedPlayer.playWhenReady = false
        }
    }

    DisposableEffect(activity) {
        val window = activity?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            sharedPlayer.pause()
            sharedPlayer.playWhenReady = false

            activity?.window?.let {
                WindowCompat.getInsetsController(it, view).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    BackHandler(enabled = !showControls) {
        showControls = true
    }

    BackHandler(enabled = showControls) {
        onClose()
    }

    val liveCurrentItem = mediaList.getOrNull(pagerState.currentPage)?.let {
        mediaMap[it.id] ?: it
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        HorizontalPager(
            state = pagerState,
            pageSpacing = 20.dp,
            key = { index -> mediaList[index].id },
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val item = mediaList[page]
            if (item.isVideo) {
                val videoIndex = videoList.indexOfFirst { it.id == item.id }
                VideoPreviewPage(
                    item = item,
                    videoIndex = videoIndex,
                    isCurrentPage = pagerState.currentPage == page,
                    showControls = showControls,
                    sharedPlayer = sharedPlayer,
                    onTap = { showControls = !showControls },
                    onPlay = { onNavigateToVideoPlayer(item.uri.toString()) }
                )
            } else {
                ZoomableImagePage(
                    item = item,
                    onTap = { showControls = !showControls },
                    onDismiss = onClose
                )
            }
        }

        AnimatedVisibility(
            visible = showControls,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent)
                        )
                    )
                    .statusBarsPadding()
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        val dateStr = liveCurrentItem?.let {
                            shortDateFormatter.format(Date(it.dateAdded * 1000))
                        } ?: ""

                        Text(
                            text = dateStr,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(modifier = Modifier.size(48.dp))
                }
            }
        }

        AnimatedVisibility(
            visible = showControls,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut()
        ) {
            liveCurrentItem?.let { currentItem ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))
                            )
                        )
                        .navigationBarsPadding()
                        .padding(bottom = 12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SamsungViewerAction(
                            icon = if (favoriteIds.contains(currentItem.id)) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            label = if (favoriteIds.contains(currentItem.id)) "Unfavorite" else "Favorite",
                            tint = if (favoriteIds.contains(currentItem.id)) Color.Red else Color.White
                        ) {
                            onToggleFavorite(currentItem.id)
                        }

                        SamsungViewerAction(
                            icon = Icons.Outlined.Edit,
                            label = "Edit"
                        ) {
                            onEdit(currentItem)
                        }

                        SamsungViewerAction(
                            icon = Icons.Outlined.Share,
                            label = "Share"
                        ) {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = if (currentItem.isVideo) "video/*" else "image/*"
                                putExtra(Intent.EXTRA_STREAM, currentItem.uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share"))
                        }

                        SamsungViewerAction(
                            icon = Icons.Outlined.Delete,
                            label = "Delete"
                        ) {
                            onDelete(currentItem)
                        }

                        Box {
                            SamsungViewerAction(
                                icon = Icons.Default.MoreVert,
                                label = "More"
                            ) {
                                showMoreMenu = true
                            }

                            DropdownMenu(
                                expanded = showMoreMenu,
                                onDismissRequest = { showMoreMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Details") },
                                    onClick = {
                                        showMetadataSheet = true
                                        showMoreMenu = false
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.Info, contentDescription = null)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Set as Wallpaper") },
                                    onClick = {
                                        onWallpaper(currentItem)
                                        showMoreMenu = false
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.Wallpaper, contentDescription = null)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Print") },
                                    onClick = {
                                        showMoreMenu = false
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.Print, contentDescription = null)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showMetadataSheet && liveCurrentItem != null) {
        MediaMetadataSheet(item = liveCurrentItem) {
            showMetadataSheet = false
        }
    }
}

@Composable
fun VideoPreviewPage(
    item: MediaItem,
    videoIndex: Int,
    isCurrentPage: Boolean,
    showControls: Boolean,
    sharedPlayer: Player,
    onTap: () -> Unit,
    onPlay: () -> Unit
) {
    val context = LocalContext.current
    var muted by rememberSaveable(item.id) { mutableStateOf(true) }

    val playerView = remember {
        PlayerView(context).apply {
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            setOnTouchListener { _, _ -> false }
            isClickable = false
            isFocusable = false
        }
    }

    LaunchedEffect(isCurrentPage) {
        if (isCurrentPage && videoIndex >= 0) {
            if (sharedPlayer.currentMediaItemIndex != videoIndex) {
                sharedPlayer.seekTo(videoIndex, 0)
            }
            sharedPlayer.volume = if (muted) 0f else 1f
            sharedPlayer.playWhenReady = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { playerView },
            update = { view -> view.player = sharedPlayer },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onTap() })
                }
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
                    .padding(bottom = 80.dp)
            ) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clickable { onPlay() },
                    shape = RoundedCornerShape(50),
                    color = Color.Black.copy(alpha = 0.55f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.White
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Play video",
                            color = Color.White
                        )
                    }
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
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            color = tint,
            style = MaterialTheme.typography.labelSmall
        )
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
    isScrolling: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = LocalContext.current
    val cornerRadius = 8.dp
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val targetScale = if (isPressed || isSelected) 0.92f else 1f
    val scale = if (isScrolling || isLowRam) {
        targetScale
    } else {
        animateFloatAsState(targetValue = targetScale, label = "tileScale").value
    }

    val durationText = remember(item.duration) {
        formatDuration(item.duration)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .graphicsLayer {
                if (scale != 1f) {
                    scaleX = scale
                    scaleY = scale
                }
                clip = true
                shape = RoundedCornerShape(cornerRadius)
            }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = if (isScrolling) null else androidx.compose.material3.ripple(),
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        val request = remember(item.id, thumbSize, isLowRam, context) {
            val config = if (isLowRam) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
            ImageRequest.Builder(context)
                .data(item.uri)
                .size(thumbSize)
                .bitmapConfig(config)
                .memoryCacheKey("thumb_${item.id}")
                .networkCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .precision(Precision.INEXACT)
                .allowHardware(true)
                .crossfade(false)
                .build()
        }

        AsyncImage(
            model = request,
            placeholder = ColorPainter(Color.LightGray.copy(alpha = 0.2f)),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.None,
            modifier = Modifier.fillMaxSize()
        )

        if (item.isVideo) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp),
                shape = RoundedCornerShape(4.dp),
                color = Color.Black.copy(alpha = 0.6f)
            ) {
                Text(
                    text = "▶ $durationText",
                    fontSize = 11.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }
        }

        if (isSelectionMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        else Color.Transparent
                    )
            )

            Box(
                modifier = Modifier
                    .padding(6.dp)
                    .align(Alignment.TopStart)
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(20.dp)
                            .background(Color.White, CircleShape)
                    )
                } else {
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
fun ZoomableImagePage(
    item: MediaItem,
    onTap: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        scale = if (scale > 1f) 1f else 2.5f
                        offset = Offset.Zero
                    }
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    var totalPanY = 0f
                    do {
                        val event = awaitPointerEvent()
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()

                        scale = (scale * zoom).coerceIn(1f, 4f)

                        if (scale > 1f) {
                            offset += pan
                            event.changes.forEach { it.consume() }
                        } else {
                            offset = Offset.Zero
                            totalPanY += pan.y
                            if (totalPanY > 150f) {
                                onDismiss()
                                event.changes.forEach { it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val request = remember(item.uri) {
            ImageRequest.Builder(context)
                .data(item.uri)
                .allowHardware(true)
                .crossfade(false)
                .build()
        }

        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaMetadataSheet(
    item: MediaItem,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val dateStr = remember(item) {
        metadataFormatter.format(Date(item.dateAdded * 1000))
    }

    val formattedSize = remember(item) {
        Formatter.formatFileSize(context, item.size)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 16.dp)
                .padding(bottom = 34.dp)
        ) {
            Text(
                text = "Media Details",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            MetadataRow(
                icon = Icons.Outlined.Info,
                label = "Name",
                value = item.name
            )

            MetadataRow(
                icon = Icons.Outlined.DriveFileMove,
                label = "Path",
                value = item.path
            )

            MetadataRow(
                icon = Icons.Outlined.Info,
                label = "Date",
                value = dateStr
            )

            MetadataRow(
                icon = Icons.Outlined.Info,
                label = "Size",
                value = formattedSize
            )

            if (item.width > 0 && item.height > 0) {
                MetadataRow(
                    icon = Icons.Outlined.Info,
                    label = "Resolution",
                    value = "${item.width} × ${item.height}"
                )
            }
        }
    }
}

@Composable
fun MetadataRow(
    icon: ImageVector,
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
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

@Composable
fun ActionItem(
    icon: ImageVector,
    label: String,
    isDestructive: Boolean = false,
    onClick: () -> Unit
) {
    val contentColor = if (isDestructive) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    ElevatedCard(
        modifier = Modifier
            .width(84.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernSortSheet(
    activeSort: PhotoSort,
    onDismiss: () -> Unit,
    onSortSelected: (PhotoSort) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 34.dp)
        ) {
            Text(
                text = "Sort Media",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(22.dp)
            )

            PhotoSort.entries.forEach { option ->
                val isSelected = activeSort == option
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSortSelected(option) }
                        .padding(horizontal = 22.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isSelected) Icons.Rounded.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.width(16.dp))

                    Text(
                        text = option.name,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernGridSheet(
    currentColumns: Int,
    max: Int = 8,
    onDismiss: () -> Unit,
    onUpdate: (Int) -> Unit
) {
    var sliderValue by remember { mutableFloatStateOf(currentColumns.toFloat()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .padding(bottom = 34.dp)
        ) {
            Text(
                text = "Grid Layout",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(28.dp))

            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                valueRange = 1f..max.toFloat(),
                steps = (max - 2).coerceAtLeast(0),
                onValueChangeFinished = { onUpdate(sliderValue.toInt()) }
            )

            Text(
                text = "${sliderValue.toInt()} Columns",
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

@Composable
fun ModernDateHeader(
    modifier: Modifier = Modifier,
    title: String,
    onSelectAllForDate: () -> Unit = {}
) {
    Text(
        text = title,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 14.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernTopBar(
    title: String,
    scrollBehavior: TopAppBarScrollBehavior,
    onSearchClick: () -> Unit,
    onMenuAction: (String) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    CenterAlignedTopAppBar(
        title = {
            Text(
                text = title,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        },
        actions = {
            IconButton(onClick = onSearchClick) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = "Search"
                )
            }

            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More"
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        onClick = {
                            onMenuAction("edit")
                            showMenu = false
                        },
                        leadingIcon = {
                            Icon(Icons.Outlined.Edit, contentDescription = null)
                        }
                    )

                    DropdownMenuItem(
                        text = { Text("Select All") },
                        onClick = {
                            onMenuAction("select_all")
                            showMenu = false
                        },
                        leadingIcon = {
                            Icon(Icons.Outlined.SelectAll, contentDescription = null)
                        }
                    )

                    DropdownMenuItem(
                        text = { Text("Start Slideshow") },
                        onClick = {
                            onMenuAction("slideshow")
                            showMenu = false
                        },
                        leadingIcon = {
                            Icon(Icons.Outlined.Slideshow, contentDescription = null)
                        }
                    )

                    DropdownMenuItem(
                        text = { Text("View Duplicates") },
                        onClick = {
                            onMenuAction("duplicates")
                            showMenu = false
                        },
                        leadingIcon = {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                        }
                    )

                    DropdownMenuItem(
                        text = { Text("Grid Size") },
                        onClick = {
                            onMenuAction("grid")
                            showMenu = false
                        },
                        leadingIcon = {
                            Icon(Icons.Rounded.GridView, contentDescription = null)
                        }
                    )

                    DropdownMenuItem(
                        text = { Text("Sort") },
                        onClick = {
                            onMenuAction("sort")
                            showMenu = false
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Sort, contentDescription = null)
                        }
                    )

                    DropdownMenuItem(
                        text = { Text("Trash") },
                        onClick = {
                            onMenuAction("trash")
                            showMenu = false
                        },
                        leadingIcon = {
                            Icon(Icons.Outlined.Delete, contentDescription = null)
                        }
                    )

                    // FIX: "Hidden" removed from Top Menu as requested.

                    DropdownMenuItem(
                        text = { Text("Lock App") },
                        onClick = {
                            onMenuAction("lock_app")
                            showMenu = false
                        },
                        leadingIcon = {
                            Icon(Icons.Outlined.Lock, contentDescription = null)
                        }
                    )

                    DropdownMenuItem(
                        text = { Text("Settings") },
                        onClick = {
                            onMenuAction("settings")
                            showMenu = false
                        },
                        leadingIcon = {
                            Icon(Icons.Outlined.Settings, contentDescription = null)
                        }
                    )
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
fun SearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back"
                )
            }

            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = {
                    Text("Search...")
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent
                )
            )
        }
    }
}

@Composable
fun MediaSelectionTopBar(
    selectedCount: Int,
    totalCount: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit
) {
    val isAllSelected = selectedCount == totalCount && totalCount > 0

    Surface(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(vertical = 12.dp)
        ) {
            Text(
                text = "$selectedCount selected",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .clickable { onSelectAll() }
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = if (isAllSelected) Icons.Rounded.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "All",
                        style = MaterialTheme.typography.labelSmall
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
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(
            items = filters,
            key = { _, filter -> filter.name }
        ) { _, filter ->
            val isSelected = activeFilter == filter

            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable { onFilterSelected(filter) },
                shape = RoundedCornerShape(50),
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                }
            ) {
                Text(
                    text = filter.label,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
fun EmptyMediaOverlay() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Outlined.PhotoLibrary,
                contentDescription = null,
                modifier = Modifier.size(58.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "No Media Found",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }
    }
}