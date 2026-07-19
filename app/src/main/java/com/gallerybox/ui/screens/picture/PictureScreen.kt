@file:Suppress("UnsafeOptInUsageError", "UnstableApiUsage", "OPT_IN_USAGE", "unused", "DEPRECATION")
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.gallerybox.ui.screens.picture

import android.app.Activity
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
import androidx.media3.common.C
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.filter
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import com.gallerybox.data.MediaItem
import com.gallerybox.ui.screens.album.formatDuration
import com.gallerybox.viewmodel.GalleryEvent
import com.gallerybox.viewmodel.GalleryViewModel
import com.gallerybox.viewmodel.MediaTypeFilter
import androidx.media3.common.util.UnstableApi
import com.gallerybox.viewmodel.PhotoSort
import com.gallerybox.viewmodel.TrashViewModel
import com.gallerybox.viewmodel.GalleryViewerState
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

fun isValidUri(context: Context, uri: Uri?): Boolean = uri != null && uri != Uri.EMPTY
fun getSmartName(item: MediaItem): String = item.name.lowercase().let { name -> when { "fdownloader" in name -> "Downloaded Video"; "instagram" in name -> "Instagram Video"; "whatsapp" in name -> "WhatsApp Media"; "screenshot" in name -> "Screenshot"; item.isDocument -> "Document"; item.isVideo -> "Video"; else -> "Photo" } }
fun getFolderName(path: String): String = try { java.io.File(path).parentFile?.name ?: "Unknown Folder" } catch (e: Exception) { "Unknown Folder" }
fun Context.findActivity(): Activity? { var context = this; while (context is ContextWrapper) { if (context is Activity) return context; context = context.baseContext }; return null }

private val topBarFormatter = SimpleDateFormat("MMMM dd, yyyy  •  hh:mm a", Locale.getDefault())
private val metadataFormatter = SimpleDateFormat("EEEE, MMMM dd, yyyy 'at' hh:mm a", Locale.getDefault())
private val headerFormatter = SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault())

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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    onNavigateToDocs: () -> Unit,
    onNavigateToDocViewer: (Long) -> Unit,
    onNavigateToVideoPlayer: (String, List<String>) -> Unit,
    onNavigateToEditor: (String, Long) -> Unit,
    onNavigateToMoveCopy: (String, String, String?) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val haptic = LocalHapticFeedback.current
    val gridState = rememberLazyGridState()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

    val isBusy by viewModel.isBusy.collectAsState()
    val activeFilter by viewModel.activeFilter.collectAsState()
    val activeSort by viewModel.activeSort.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val viewerState by viewModel.viewerState.collectAsState()
    val mediaMap by viewModel.mediaMap.collectAsState()
    val favorites by viewModel.favoriteIds.collectAsState()

    val openViewerState = viewerState as? GalleryViewerState.Open
    val currentItem: MediaItem? = openViewerState?.mediaId?.let { id -> mediaMap[id] }

    // Filter PagingData dynamically to exclude photos/videos when the DOCUMENT tab is active
    val pagedMedia = remember(viewModel, activeFilter) {
        viewModel.pagedMedia.map { pagingData ->
            pagingData.filter { item ->
                if (item is GalleryGridItem.Media) {
                    val filterName = activeFilter.name.uppercase()
                    if (filterName == "DOCUMENT" || filterName == "DOCUMENTS") {
                        item.item.isDocument && !item.item.isVideo
                    } else {
                        true
                    }
                } else {
                    true
                }
            }
        }
    }.collectAsLazyPagingItems()

    val prefs = remember { context.getSharedPreferences("gallery_prefs", Context.MODE_PRIVATE) }
    var columnCount by rememberSaveable { mutableIntStateOf(prefs.getInt("picture_grid_columns", 4)) }
    var isSelectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<Long>()) }
    var isSearchActive by rememberSaveable { mutableStateOf(false) }
    var activeDialog by remember { mutableStateOf<PictureUiDialog>(PictureUiDialog.None) }

    val showScrollToTop by remember { derivedStateOf { gridState.firstVisibleItemIndex > 10 } }
    val isScrolling = gridState.isScrollInProgress

    // Explicitly filter to remove GIF and RAW, keeping only relevant categories
    val filters = remember {
        MediaTypeFilter.entries.filter {
            val n = it.name.uppercase()
            n == "ALL" || n == "PHOTOS" || n == "VIDEOS" || n == "DOCUMENT" || n == "DOCUMENTS"
        }
    }

    val pagerState = rememberPagerState(initialPage = filters.indexOf(activeFilter).coerceAtLeast(0), pageCount = { filters.size })

    val intentSenderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val g = result.resultCode == Activity.RESULT_OK
        trashViewModel.onPermissionResultGlobal(g)
        if (!g) Toast.makeText(context, "Permission Denied", Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(trashViewModel) {
        trashViewModel.onRefreshGallery = { scope.launch { viewModel.forceSync() } }
        trashViewModel.events.collect { event ->
            when (event) {
                is GalleryEvent.RequestPermission -> intentSenderLauncher.launch(IntentSenderRequest.Builder(event.intentSender).build())
                is GalleryEvent.OperationSuccess -> {
                    activeDialog = PictureUiDialog.None
                    isSelectionMode = false
                    selectedIds = emptySet()
                    viewModel.closeViewer()
                    scope.launch {
                        if (snackbarHostState.showSnackbar("Moved to Trash", "View Trash", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) onNavigateToTrash()
                    }
                }
                is GalleryEvent.ShowToast -> Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                else -> {}
            }
        }
    }

    LaunchedEffect(viewerState) { onViewerStateChanged(viewerState is GalleryViewerState.Open) }
    LaunchedEffect(pagerState.currentPage) {
        val selectedFilter = filters[pagerState.currentPage]
        if (activeFilter != selectedFilter) {
            viewModel.updateFilter(selectedFilter)
            gridState.scrollToItem(0)
        }
    }
    LaunchedEffect(activeFilter) {
        val targetPage = filters.indexOf(activeFilter)
        if (targetPage != -1 && pagerState.currentPage != targetPage) pagerState.animateScrollToPage(targetPage)
    }
    LaunchedEffect(activeFilter) {
        if (isSelectionMode) {
            isSelectionMode = false
            selectedIds = emptySet()
        }
    }

    val selectedSizeStr by remember(selectedIds.size) { derivedStateOf { Formatter.formatShortFileSize(context, selectedIds.sumOf { mediaMap[it]?.size ?: 0L }) } }

    val currentScrollDate by remember {
        derivedStateOf {
            if (pagedMedia.itemCount == 0) "" else {
                val index = gridState.firstVisibleItemIndex
                if (index < 0 || index >= pagedMedia.itemCount) "" else {
                    when (val item = pagedMedia.peek(index)) {
                        is GalleryGridItem.Header -> item.title
                        is GalleryGridItem.Media -> item.item.dateHeader
                        null -> ""
                    }
                }
            }
        }
    }

    val dynamicThumbSize = remember(columnCount) {
        when (columnCount) {
            1, 2 -> 512
            3 -> 256
            else -> maxOf(160, 512 / columnCount)
        }
    }

    val pulse = rememberInfiniteTransition(label = "pulse")
    val alphaPulse by pulse.animateFloat(initialValue = 0.3f, targetValue = 0.7f, animationSpec = infiniteRepeatable(animation = tween(1000), repeatMode = RepeatMode.Reverse), label = "alpha")

    fun shareMedia(items: List<MediaItem>) {
        if (items.isEmpty()) return
        val uris = items.map { it.uri }
        val intent = Intent().apply {
            action = if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND
            val hasImage = items.any { !it.isVideo && !it.isDocument }
            val hasVideo = items.any { it.isVideo }
            val hasDocument = items.any { it.isDocument }
            type = when {
                hasDocument && !hasImage && !hasVideo -> if (items.size == 1) items.first().mimeType.takeIf { it.isNotEmpty() } ?: "*/*" else "*/*"
                hasVideo && !hasImage && !hasDocument -> "video/*"
                hasImage && !hasVideo && !hasDocument -> "image/*"
                else -> "*/*"
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (uris.size > 1) putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)) else putExtra(Intent.EXTRA_STREAM, uris.first())
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
        selectedIds = emptySet()
    }
    BackHandler(enabled = activeDialog != PictureUiDialog.None) { activeDialog = PictureUiDialog.None }
    BackHandler(enabled = viewerState is GalleryViewerState.Open) { viewModel.closeViewer() }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Scaffold(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                Column {
                    AnimatedContent(targetState = isSelectionMode to isSearchActive, label = "TopBar") { (selection, search) ->
                        when {
                            selection -> MediaSelectionTopBar(selectedCount = selectedIds.size, selectedSizeStr = selectedSizeStr, totalCount = pagedMedia.itemCount, onClose = { isSelectionMode = false; selectedIds = emptySet() }, onSelectAll = { val mediaItems = pagedMedia.itemSnapshotList.items.filterIsInstance<GalleryGridItem.Media>(); selectedIds = if (selectedIds.size == mediaItems.size && mediaItems.isNotEmpty()) emptySet() else mediaItems.map { it.item.id }.toSet().takeIf { set -> set.size < 5000 } ?: selectedIds })
                            search -> SearchTopBar(query = searchQuery, onQueryChange = { viewModel.setSearchQuery(it) }, onClose = { isSearchActive = false; viewModel.setSearchQuery("") })
                            else -> ModernTopBar(title = "Pictures", scrollBehavior = scrollBehavior, onSearchClick = { isSearchActive = true }, onSelectionClick = { isSelectionMode = true }, onMenuAction = { action -> when (action) { "grid" -> activeDialog = PictureUiDialog.GridSize; "sort" -> activeDialog = PictureUiDialog.Sort; "slideshow" -> onNavigateToSlideshow(); "duplicates" -> onNavigateToDuplicates(); "scan" -> onNavigateToScan(); "trash" -> onNavigateToTrash(); "hidden" -> onNavigateToHidden(); "lock_app" -> onLockApp(); "docs" -> onNavigateToDocs(); "settings" -> onNavigateToSettings() } })
                        }
                    }
                    if (!isSelectionMode && !isSearchActive) ModernFilterRow(filters = filters, selectedIndex = pagerState.currentPage, onFilterSelected = { index -> scope.launch { pagerState.animateScrollToPage(index) } })
                }
            },
            floatingActionButton = {
                Box {
                    androidx.compose.animation.AnimatedVisibility(visible = !isSelectionMode && (!isScrolling || showScrollToTop), enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                        if (showScrollToTop) {
                            FloatingActionButton(onClick = { scope.launch { gridState.animateScrollToItem(0) } }, containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer, shape = RoundedCornerShape(16.dp), elevation = FloatingActionButtonDefaults.elevation(8.dp)) {
                                Icon(Icons.Rounded.ArrowUpward, "Scroll to Top")
                            }
                        }
                    }
                }
            }
        ) { padding ->
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize().padding(padding)) { page ->
                Box(Modifier.fillMaxSize()) {
                    val isEmptyState = pagedMedia.itemSnapshotList.items.none { it is GalleryGridItem.Media }
                    if (isBusy && pagedMedia.itemCount == 0) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    } else if (pagedMedia.itemCount == 0 || isEmptyState) {
                        EmptyMediaOverlay()
                    } else {
                        LazyVerticalGrid(
                            state = gridState,
                            columns = GridCells.Fixed(columnCount),
                            modifier = Modifier.fillMaxSize()
                                .pointerInput(isSelectionMode) {
                                    if (!isSelectionMode) return@pointerInput
                                    var initialKey: Long? = null
                                    detectDragGestures(
                                        onDragStart = { offset ->
                                            val itemInfo = gridState.layoutInfo.visibleItemsInfo.find { offset.y >= it.offset.y && offset.y <= (it.offset.y + it.size.height) && offset.x >= it.offset.x && offset.x <= (it.offset.x + it.size.width) }
                                            itemInfo?.let {
                                                val item = pagedMedia.peek(it.index) as? GalleryGridItem.Media
                                                if (item != null) {
                                                    initialKey = item.item.id
                                                    selectedIds = (selectedIds + item.item.id).takeIf { set -> set.size < 5000 } ?: selectedIds
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                }
                                            }
                                        },
                                        onDrag = { change, _ ->
                                            val offset = change.position
                                            val itemInfo = gridState.layoutInfo.visibleItemsInfo.find { offset.y >= it.offset.y && offset.y <= (it.offset.y + it.size.height) && offset.x >= it.offset.x && offset.x <= (it.offset.x + it.size.width) }
                                            itemInfo?.let {
                                                val item = pagedMedia.peek(it.index) as? GalleryGridItem.Media
                                                if (item != null && item.item.id != initialKey && !selectedIds.contains(item.item.id)) {
                                                    selectedIds = (selectedIds + item.item.id).takeIf { set -> set.size < 5000 } ?: selectedIds
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                }
                                            }
                                        },
                                        onDragEnd = { initialKey = null },
                                        onDragCancel = { initialKey = null }
                                    )
                                },
                            contentPadding = PaddingValues(top = 3.dp, bottom = 120.dp, start = 3.dp, end = 3.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            items(
                                count = pagedMedia.itemCount,
                                span = { index -> if (pagedMedia.peek(index) is GalleryGridItem.Header) GridItemSpan(columnCount) else GridItemSpan(1) },
                                key = { index -> when (val item = pagedMedia.peek(index)) { is GalleryGridItem.Media -> item.item.id; is GalleryGridItem.Header -> "header_${item.id}"; else -> index } },
                                contentType = { index -> if (pagedMedia.peek(index) is GalleryGridItem.Header) "header" else "media" }
                            ) { index ->
                                when (val gridItem = pagedMedia[index]) {
                                    is GalleryGridItem.Header -> ModernDateHeader(
                                        title = gridItem.title,
                                        count = gridItem.count,
                                        onSelectAllForDate = {
                                            val snapshot = pagedMedia.itemSnapshotList.items.filterIsInstance<GalleryGridItem.Media>().filter { it.item.dateHeader == gridItem.title }
                                            selectedIds = (selectedIds + snapshot.map { it.item.id }.toSet()).takeIf { set -> set.size < 5000 } ?: selectedIds
                                            isSelectionMode = true
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                    )
                                    is GalleryGridItem.Media -> {
                                        val mediaItem = mediaMap[gridItem.item.id] ?: gridItem.item
                                        if (isValidUri(context, mediaItem.uri)) {
                                            ModernMediaGridTile(
                                                item = mediaItem,
                                                thumbSize = dynamicThumbSize,
                                                isSelected = selectedIds.contains(mediaItem.id),
                                                isSelectionMode = isSelectionMode,
                                                isScrolling = isScrolling,
                                                onClick = {
                                                    try {
                                                        if (isSelectionMode) {
                                                            selectedIds = if (selectedIds.contains(mediaItem.id)) selectedIds - mediaItem.id else (selectedIds + mediaItem.id).takeIf { set -> set.size < 5000 } ?: selectedIds
                                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                        } else if (mediaItem.isDocument) {
                                                            onNavigateToDocViewer(mediaItem.id)
                                                        } else {
                                                            viewModel.openViewer(mediaItem.id)
                                                        }
                                                    } catch (e: Exception) { Log.e("GalleryBox", "Open Error", e) }
                                                },
                                                onLongClick = {
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    if (isSelectionMode) {
                                                        selectedIds = if (selectedIds.contains(mediaItem.id)) selectedIds - mediaItem.id else (selectedIds + mediaItem.id).takeIf { set -> set.size < 5000 } ?: selectedIds
                                                    } else {
                                                        activeDialog = PictureUiDialog.QuickAction(mediaItem)
                                                    }
                                                }
                                            )
                                        }
                                    }
                                    null -> Box(modifier = Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = alphaPulse)))
                                }
                            }
                        }
                    }

                    androidx.compose.animation.AnimatedVisibility(
                        visible = isScrolling && currentScrollDate.isNotEmpty(),
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp)
                    ) {
                        Box(Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(currentScrollDate, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                }
            }
        }

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
                                val itemsToTrash = dialog.mediaIds.mapNotNull { mediaMap[it] }
                                if (itemsToTrash.isNotEmpty()) trashViewModel.confirmPendingGalleryTrash(itemsToTrash)
                            }
                        ) { Text("Move to Trash") }
                    },
                    dismissButton = {
                        TextButton(onClick = { activeDialog = PictureUiDialog.None }) { Text("Cancel") }
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
                    Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp).padding(bottom = 24.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text(getSmartName(item), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(getFolderName(item.path), color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                        LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            item { ActionItem(Icons.Outlined.Edit, "Edit") { activeDialog = PictureUiDialog.None; onNavigateToEditor(item.uri.toString(), item.id) } }
                            item { ActionItem(Icons.Outlined.Share, "Share") { shareMedia(listOf(item)); activeDialog = PictureUiDialog.None } }
                            item { ActionItem(Icons.Outlined.Delete, "Delete", isDestructive = true) { activeDialog = PictureUiDialog.TrashConfirm(listOf(item.id)) } }
                            item { ActionItem(Icons.Default.MoreVert, "More") { showMoreExpanded = true } }
                        }
                        AnimatedVisibility(visible = showMoreExpanded) {
                            Column(Modifier.padding(top = 16.dp)) {
                                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                                ListItem(headlineContent = { Text("Details", fontWeight = FontWeight.SemiBold) }, leadingContent = { Icon(Icons.Outlined.Info, null) }, modifier = Modifier.clickable { activeDialog = PictureUiDialog.None; activeDialog = PictureUiDialog.MetadataInfo(item) })
                                ListItem(headlineContent = { Text("Move to Album", fontWeight = FontWeight.SemiBold) }, leadingContent = { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, null) }, modifier = Modifier.clickable { activeDialog = PictureUiDialog.None; onNavigateToMoveCopy("MOVE", item.id.toString(), null) })
                                ListItem(headlineContent = { Text("Copy to Album", fontWeight = FontWeight.SemiBold) }, leadingContent = { Icon(Icons.Outlined.FileCopy, null) }, modifier = Modifier.clickable { activeDialog = PictureUiDialog.None; onNavigateToMoveCopy("COPY", item.id.toString(), null) })
                                if (!item.isDocument && !item.isVideo) {
                                    ListItem(headlineContent = { Text("Set as Wallpaper", fontWeight = FontWeight.SemiBold) }, leadingContent = { Icon(Icons.Outlined.Wallpaper, null) }, modifier = Modifier.clickable { activeDialog = PictureUiDialog.None; onNavigateToWallpaper(item.uri.toString(), item.id) })
                                }
                            }
                        }
                    }
                }
            }
            is PictureUiDialog.GridSize -> ModernGridSheet(currentColumns = columnCount, onDismiss = { activeDialog = PictureUiDialog.None }, onUpdate = { columnCount = it; prefs.edit().putInt("picture_grid_columns", it).apply(); activeDialog = PictureUiDialog.None })
            is PictureUiDialog.Sort -> ModernSortSheet(activeSort = activeSort, onDismiss = { activeDialog = PictureUiDialog.None }, onSortSelected = { viewModel.updateSort(it); activeDialog = PictureUiDialog.None })
            is PictureUiDialog.MetadataInfo -> MediaMetadataSheet(item = dialog.item) { activeDialog = PictureUiDialog.None }
            PictureUiDialog.None -> {}
        }

        AnimatedVisibility(visible = viewerState is GalleryViewerState.Open, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            if (currentItem?.isDocument == true) {
                LaunchedEffect(currentItem.id) {
                    onNavigateToDocViewer(currentItem.id)
                    viewModel.closeViewer()
                }
            } else if (currentItem != null) {
                val stableMediaList = pagedMedia.itemSnapshotList.items
                    .filterIsInstance<GalleryGridItem.Media>()
                    .map { mediaMap[it.item.id] ?: it.item }
                    .filter { !it.isDocument }

                val stableStartIndex = stableMediaList.indexOfFirst {
                    it.id == currentItem.id
                }.coerceAtLeast(0)

                key(currentItem.id) {
                    FullscreenMediaPager(
                        initialIndex = stableStartIndex,
                        mediaList = stableMediaList,
                        favorites = favorites,
                        sharedPlayer = viewModel.getPlayer(),
                        onClose = { viewModel.closeViewer() },
                        onEdit = { item -> viewModel.closeViewer(); onNavigateToEditor(item.uri.toString(), item.id) },
                        onDelete = { item -> activeDialog = PictureUiDialog.TrashConfirm(listOf(item.id)) },
                        onNavigateToDocViewer = { id -> viewModel.closeViewer(); onNavigateToDocViewer(id) },
                        onNavigateToVideoPlayer = { uri ->
                            viewModel.closeViewer()
                            val videoList = stableMediaList.filter { it.isVideo }.map { it.uri.toString() }
                            onNavigateToVideoPlayer(uri, videoList)
                        },
                        onMove = { item -> viewModel.closeViewer(); onNavigateToMoveCopy("MOVE", item.id.toString(), null) },
                        onCopy = { item -> viewModel.closeViewer(); onNavigateToMoveCopy("COPY", item.id.toString(), null) },
                        onWallpaper = { item -> viewModel.closeViewer(); onNavigateToWallpaper(item.uri.toString(), item.id) },
                        onToggleFavorite = { id -> viewModel.toggleFavorite(id) },
                        onShowDetails = { item -> activeDialog = PictureUiDialog.MetadataInfo(item) }
                    )
                }
            }
        }
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
    isScrolling: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val cornerRadius = if (isScrolling) { if (isSelected) 16.dp else 12.dp } else { animateDpAsState(targetValue = if (isSelected) 16.dp else 12.dp, label = "cornerRadius").value }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val targetScale = when { isPressed -> 0.96f; isSelected -> 0.92f; else -> 1f }
    val scale = if (isScrolling) targetScale else animateFloatAsState(targetValue = targetScale, animationSpec = spring(stiffness = 400f), label = "tileScale").value

    Box(
        modifier = modifier.fillMaxWidth().aspectRatio(1f).graphicsLayer {
            scaleX = scale
            scaleY = scale
            clip = true
            shape = RoundedCornerShape(cornerRadius)
        }.combinedClickable(
            interactionSource = interactionSource,
            indication = androidx.compose.material3.ripple(),
            onClick = onClick,
            onLongClick = onLongClick
        )
    ) {
        if (item.isDocument) {
            val ext = item.name.substringAfterLast('.', "").uppercase(Locale.ROOT)
            val tintColor = when (ext) {
                "PDF" -> Color(0xFFE53935)
                "DOC", "DOCX" -> Color(0xFF1E88E5)
                "XLS", "XLSX", "CSV" -> Color(0xFF43A047)
                "PPT", "PPTX" -> Color(0xFFFFB300)
                "TXT", "JSON", "XML", "HTML", "MD" -> Color(0xFF757575)
                "KT", "JAVA", "CPP", "C", "H", "PY", "JS", "TS", "CSS", "PHP", "SQL", "SH" -> Color(0xFF00897B)
                else -> MaterialTheme.colorScheme.primary
            }
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(74.dp).clip(CircleShape).background(tintColor.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.InsertDriveFile, null, tint = tintColor, modifier = Modifier.size(36.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Document", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (ext.isNotEmpty()) {
                Box(Modifier.align(Alignment.TopStart).padding(8.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 3.dp)) {
                    Text(text = ext, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        } else {
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
                .apply { if (item.isVideo) decoderFactory(coil.decode.VideoFrameDecoder.Factory()) }

            AsyncImage(
                model = requestBuilder.build(),
                placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.Low,
                modifier = Modifier.fillMaxSize(),
                onError = { state -> Log.e("GalleryBox", "GridTile error: ${item.uri}", state.result.throwable) }
            )
        }
        if (!item.isDocument && item.isVideo) {
            Box(Modifier.fillMaxSize().drawWithCache { val brush = Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f)); onDrawBehind { drawRect(brush) } })
            Surface(modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp), shape = RoundedCornerShape(8.dp), color = Color.Black.copy(alpha = 0.6f)) {
                Text("▶ ${formatDuration(item.duration)}", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
            }
        }
        SelectionOverlay(isSelected, isSelectionMode, cornerRadius)
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FullscreenMediaPager(
    initialIndex: Int,
    mediaList: List<MediaItem>,
    favorites: List<Long>,
    sharedPlayer: Player,
    onClose: () -> Unit,
    onEdit: (MediaItem) -> Unit,
    onDelete: (MediaItem) -> Unit,
    onNavigateToDocViewer: (Long) -> Unit,
    onNavigateToVideoPlayer: (String) -> Unit,
    onMove: (MediaItem) -> Unit,
    onCopy: (MediaItem) -> Unit,
    onWallpaper: (MediaItem) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onShowDetails: (MediaItem) -> Unit
) {
    if (mediaList.isEmpty()) return
    val context = LocalContext.current
    val view = LocalView.current
    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { mediaList.size })
    var showControls by remember { mutableStateOf(true) }
    var showMoreMenu by remember { mutableStateOf(false) }

    val activity = remember { context.findActivity() }
    val zoomedPages = remember { mutableStateMapOf<Int, Boolean>() }
    val isCurrentPageZoomed = zoomedPages[pagerState.currentPage] ?: false

    LaunchedEffect(initialIndex, mediaList.size) {
        if (pagerState.currentPage != initialIndex && initialIndex in mediaList.indices) {
            pagerState.scrollToPage(initialIndex)
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        if (zoomedPages.size > 20) zoomedPages.clear()
        showControls = true
    }

    DisposableEffect(activity) {
        val window = activity?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            window?.let { WindowCompat.getInsetsController(it, view).show(WindowInsetsCompat.Type.systemBars()) }
        }
    }

    BackHandler(enabled = !showControls) { showControls = true }
    BackHandler(enabled = showControls) { onClose() }

    val currentItem = mediaList.getOrNull(pagerState.currentPage)
    LaunchedEffect(currentItem) { if (currentItem == null && mediaList.isNotEmpty()) onClose() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            pageSpacing = 16.dp,
            userScrollEnabled = !isCurrentPageZoomed,
            key = { index -> mediaList[index].id },
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val item = mediaList[page]
            when {
                item.isDocument -> DocumentPage(item = item, onNavigateToDocViewer = onNavigateToDocViewer)
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
                else -> SamsungZoomableImage(
                    item = item,
                    onTap = { showControls = !showControls },
                    onDismiss = onClose,
                    onZoomChanged = { isZoomed -> zoomedPages[page] = isZoomed }
                )
            }
        }

        AnimatedVisibility(visible = showControls, modifier = Modifier.align(Alignment.TopCenter), enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
            Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent))).statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                        currentItem?.let { item ->
                            Text(
                                text = item.name,
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = topBarFormatter.format(Date(item.dateAdded * 1000)),
                                color = Color.White.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }

                    IconButton(onClick = { showMoreMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More", tint = Color.White)
                    }
                }
            }
        }

        AnimatedVisibility(visible = showControls, modifier = Modifier.align(Alignment.BottomCenter), enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
            Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)))).navigationBarsPadding()) {

                Text(
                    text = "${pagerState.currentPage + 1} / ${mediaList.size}",
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp)
                )

                val listState = rememberLazyListState(initialFirstVisibleItemIndex = maxOf(0, pagerState.currentPage - 3))
                val coroutineScope = rememberCoroutineScope()

                LaunchedEffect(pagerState.currentPage) {
                    coroutineScope.launch {
                        listState.animateScrollToItem(maxOf(0, pagerState.currentPage - 3))
                    }
                }

                LazyRow(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp).padding(bottom = 8.dp)
                ) {
                    itemsIndexed(mediaList) { index, item ->
                        val isSelected = index == pagerState.currentPage
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .border(if (isSelected) 2.dp else 0.dp, if (isSelected) Color.White else Color.Transparent, RoundedCornerShape(4.dp))
                                .clickable {
                                    coroutineScope.launch { pagerState.animateScrollToPage(index) }
                                }
                        ) {
                            AsyncImage(
                                model = ImageRequest.Builder(context).data(item.uri).size(150).allowHardware(true).build(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (isSelected) 1f else 0.5f }
                            )
                        }
                    }
                }

                currentItem?.let { item ->
                    val isFavorite = favorites.contains(item.id)
                    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onToggleFavorite(item.id) }) {
                            Icon(if (isFavorite) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder, contentDescription = "Favorite", tint = if (isFavorite) Color.Red else Color.White)
                        }
                        IconButton(onClick = {
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = if (item.isVideo) "video/*" else "image/*"
                                putExtra(Intent.EXTRA_STREAM, item.uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }, "Share Media"))
                        }) {
                            Icon(Icons.Outlined.Share, contentDescription = "Share", tint = Color.White)
                        }
                        IconButton(onClick = { onEdit(item) }) {
                            Icon(Icons.Outlined.Edit, contentDescription = "Edit", tint = Color.White)
                        }
                        IconButton(onClick = { onDelete(item) }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = Color.White)
                        }
                    }
                }
            }
        }
    }

    if (showMoreMenu) {
        currentItem?.let { item ->
            ModalBottomSheet(onDismissRequest = { showMoreMenu = false }, containerColor = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(bottom = 32.dp)) {
                    ListItem(headlineContent = { Text("Details", fontWeight = FontWeight.SemiBold) }, leadingContent = { Icon(Icons.Outlined.Info, null) }, modifier = Modifier.clickable { showMoreMenu = false; onShowDetails(item) })
                    ListItem(headlineContent = { Text("Move to Album", fontWeight = FontWeight.SemiBold) }, leadingContent = { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, null) }, modifier = Modifier.clickable { showMoreMenu = false; onMove(item) })
                    ListItem(headlineContent = { Text("Copy to Album", fontWeight = FontWeight.SemiBold) }, leadingContent = { Icon(Icons.Outlined.FileCopy, null) }, modifier = Modifier.clickable { showMoreMenu = false; onCopy(item) })
                    if (!item.isDocument && !item.isVideo) {
                        ListItem(headlineContent = { Text("Set as Wallpaper", fontWeight = FontWeight.SemiBold) }, leadingContent = { Icon(Icons.Outlined.Wallpaper, null) }, modifier = Modifier.clickable { showMoreMenu = false; onWallpaper(item) })
                    }
                }
            }
        }
    }
}

@Composable
fun SamsungZoomableImage(
    item: MediaItem,
    onTap: () -> Unit,
    onDismiss: () -> Unit,
    onZoomChanged: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val wPx = with(density) { screenWidth.toPx() }
    val hPx = with(density) { screenHeight.toPx() }

    val dismissThreshold = hPx * 0.25f

    val scale = remember { Animatable(1f) }
    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(scale.value) { onZoomChanged(scale.value > 1.05f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = (1f - (abs(dragOffsetY) / 850f)).coerceIn(0.2f, 1f)))
            .offset { IntOffset(0, dragOffsetY.roundToInt()) }
            .graphicsLayer {
                val dismissScale = 1f - (abs(dragOffsetY) / 2500f)
                scaleX = scale.value * dismissScale
                scaleY = scaleX
                translationX = offsetX.value
                translationY = offsetY.value
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tapOffset ->
                        scope.launch {
                            val currentScale = scale.value
                            if (currentScale > 1.5f) {
                                launch { offsetX.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
                                launch { offsetY.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
                                launch { scale.animateTo(1f, spring(stiffness = Spring.StiffnessMediumLow)) }
                            } else {
                                val targetScale = 3f
                                val targetX = -(tapOffset.x - wPx / 2) * (targetScale - 1)
                                val targetY = -(tapOffset.y - hPx / 2) * (targetScale - 1)

                                val limitX = ((wPx * targetScale - wPx) / 2f).coerceAtLeast(0f)
                                val limitY = ((hPx * targetScale - hPx) / 2f).coerceAtLeast(0f)

                                launch { offsetX.animateTo(targetX.coerceIn(-limitX, limitX), spring(stiffness = Spring.StiffnessMediumLow)) }
                                launch { offsetY.animateTo(targetY.coerceIn(-limitY, limitY), spring(stiffness = Spring.StiffnessMediumLow)) }
                                launch { scale.animateTo(targetScale, spring(stiffness = Spring.StiffnessMediumLow)) }
                            }
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var lastDragAmount = Offset.Zero

                    do {
                        val event = awaitPointerEvent()
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()

                        scope.launch {
                            scale.snapTo((scale.value * zoom).coerceIn(1f, 5f))
                        }

                        if (scale.value > 1.05f) {
                            event.changes.forEach { if (it.positionChanged()) it.consume() }

                            val limitX = ((wPx * scale.value - wPx) / 2f).coerceAtLeast(0f)
                            val limitY = ((hPx * scale.value - hPx) / 2f).coerceAtLeast(0f)

                            var nextX = offsetX.value + pan.x
                            var nextY = offsetY.value + pan.y

                            if (nextX > limitX) nextX = limitX + (nextX - limitX) * 0.3f
                            else if (nextX < -limitX) nextX = -limitX + (nextX + limitX) * 0.3f

                            if (nextY > limitY) nextY = limitY + (nextY - limitY) * 0.3f
                            else if (nextY < -limitY) nextY = -limitY + (nextY + limitY) * 0.3f

                            scope.launch {
                                offsetX.snapTo(nextX)
                                offsetY.snapTo(nextY)
                            }
                            dragOffsetY = 0f
                            lastDragAmount = pan
                        } else {
                            val isVerticalDrag = abs(pan.y) > abs(pan.x)
                            if (isVerticalDrag && event.changes.size == 1) {
                                dragOffsetY += pan.y
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
                        }
                    } else {
                        scope.launch {
                            val limitX = ((wPx * scale.value - wPx) / 2f).coerceAtLeast(0f)
                            val limitY = ((hPx * scale.value - hPx) / 2f).coerceAtLeast(0f)

                            val targetX = (offsetX.value + lastDragAmount.x * 10).coerceIn(-limitX, limitX)
                            val targetY = (offsetX.value + lastDragAmount.y * 10).coerceIn(-limitY, limitY)

                            launch { offsetX.animateTo(targetX, spring(dampingRatio = 0.8f, stiffness = 400f)) }
                            launch { offsetY.animateTo(targetY, spring(dampingRatio = 0.8f, stiffness = 400f)) }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(item.uri)
                .allowHardware(true)
                .precision(Precision.INEXACT)
                .networkCachePolicy(CachePolicy.ENABLED)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .crossfade(true)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )
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
            modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }
        )

        AnimatedVisibility(visible = showControls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.Center)) {
            Surface(
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.5f),
                onClick = onPlay
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(40.dp))
                }
            }
        }
    }
}

@Composable
fun DocumentPage(item: MediaItem, onNavigateToDocViewer: (Long) -> Unit) { Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF101114), Color(0xFF181A1F), Color.Black))), contentAlignment = Alignment.Center) { Box(Modifier.size(320.dp).blur(40.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f), CircleShape)); ElevatedCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp), shape = RoundedCornerShape(36.dp), elevation = CardDefaults.elevatedCardElevation(12.dp), colors = CardDefaults.elevatedCardColors(containerColor = Color.White.copy(alpha = 0.06f))) { Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 34.dp), horizontalAlignment = Alignment.CenterHorizontally) { Box(Modifier.size(120.dp).clip(RoundedCornerShape(34.dp)).background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f), MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)))), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.InsertDriveFile, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp)) }; Spacer(Modifier.height(28.dp)); Text(text = item.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis); Spacer(Modifier.height(10.dp)); Text(text = item.path, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.65f), textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis); Spacer(Modifier.height(26.dp)); Surface(shape = RoundedCornerShape(24.dp), color = Color.White.copy(alpha = 0.06f)) { Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.Description, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)); Text("Tap below to open document viewer", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f)) } }; Spacer(Modifier.height(34.dp)); Button(onClick = { onNavigateToDocViewer(item.id) }, modifier = Modifier.fillMaxWidth().height(62.dp), shape = RoundedCornerShape(24.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary), elevation = ButtonDefaults.buttonElevation(6.dp)) { Icon(Icons.Rounded.OpenInNew, null, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(12.dp)); Text("Open Document", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) } } } } }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaMetadataSheet(item: MediaItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val dateStr = remember(item) { metadataFormatter.format(Date(item.dateAdded * 1000)) }
    val formattedSize = remember(item) { Formatter.formatFileSize(context, item.size) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface, dragHandle = { Box(Modifier.padding(top = 10.dp).width(54.dp).height(5.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f))) }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 34.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(58.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("Media Details", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text("Information & metadata", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(28.dp))
            Surface(shape = RoundedCornerShape(30.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.padding(18.dp)) {
                    MetadataRow(Icons.Outlined.Title, "Name", item.name)
                    MetadataRow(Icons.Outlined.Folder, "Path", item.path)
                    MetadataRow(Icons.Outlined.CalendarToday, "Date", dateStr)
                    MetadataRow(Icons.Outlined.Storage, "Size", formattedSize)
                    if (item.width > 0 && item.height > 0) MetadataRow(Icons.Outlined.AspectRatio, "Resolution", "${item.width} × ${item.height}")
                    if (item.isVideo && item.duration > 0L) MetadataRow(Icons.Outlined.Timer, "Duration", formatDuration(item.duration))
                }
            }
        }
    }
}

@Composable
fun MetadataRow(icon: ImageVector, label: String, value: String) { Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.Top) { Box(Modifier.size(42.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) }; Spacer(Modifier.width(16.dp)); Column(Modifier.weight(1f)) { Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(4.dp)); Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium) } } }

@Composable
fun SelectionOverlay(isSelected: Boolean, isSelectionMode: Boolean, cornerRadius: Dp) { AnimatedVisibility(visible = isSelectionMode, enter = fadeIn(), exit = fadeOut()) { Box(Modifier.fillMaxSize().clip(RoundedCornerShape(cornerRadius))) { Box(Modifier.fillMaxSize().background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.14f))); AnimatedVisibility(visible = isSelected) { Box(Modifier.fillMaxSize().border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(cornerRadius))) }; Box(Modifier.padding(8.dp).align(Alignment.TopStart)) { if (isSelected) Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp).background(Color.White, CircleShape)) else Icon(Icons.Outlined.RadioButtonUnchecked, null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(24.dp)) } } } }

@Composable
fun ActionItem(icon: ImageVector, label: String, isDestructive: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) { val contentColor = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface; ElevatedCard(modifier = Modifier.width(84.dp).clip(RoundedCornerShape(24.dp)).clickable(enabled = enabled, onClick = onClick), shape = RoundedCornerShape(24.dp), elevation = CardDefaults.elevatedCardElevation(3.dp), colors = CardDefaults.elevatedCardColors(containerColor = if (enabled) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLowest)) { Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) { Box(Modifier.size(46.dp).clip(CircleShape).background(contentColor.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) { Icon(icon, label, tint = if (enabled) contentColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f), modifier = Modifier.size(24.dp)) }; Spacer(Modifier.height(10.dp)); Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = if (enabled) contentColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f), maxLines = 1) } } }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernSortSheet(activeSort: PhotoSort, onDismiss: () -> Unit, onSortSelected: (PhotoSort) -> Unit) { ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface, dragHandle = { Box(Modifier.padding(top = 10.dp).width(54.dp).height(5.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f))) }) { Column(Modifier.fillMaxWidth().padding(bottom = 34.dp)) { Row(Modifier.padding(horizontal = 22.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Filled.Sort, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp)) }; Spacer(Modifier.width(16.dp)); Column { Text("Sort Media", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Spacer(Modifier.height(4.dp)); Text("Choose how media is organized", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }; Spacer(Modifier.height(8.dp)); PhotoSort.entries.forEach { option -> val isSelected = activeSort == option; Surface(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp).clip(RoundedCornerShape(24.dp)).clickable { onSortSelected(option) }, shape = RoundedCornerShape(24.dp), color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = if (isSelected) 4.dp else 0.dp) { Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(28.dp).clip(CircleShape).background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) { Icon(if (isSelected) Icons.Rounded.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked, null, tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) }; Spacer(Modifier.width(16.dp)); Text(option.name, style = MaterialTheme.typography.bodyLarge, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium, color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface); Spacer(Modifier.weight(1f)); AnimatedVisibility(visible = isSelected) { Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) } } } } } } }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernGridSheet(currentColumns: Int, max: Int = 6, onDismiss: () -> Unit, onUpdate: (Int) -> Unit) { var sliderValue by remember { mutableFloatStateOf(currentColumns.toFloat()) }; ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface, dragHandle = { Box(Modifier.padding(top = 10.dp).width(54.dp).height(5.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f))) }) { Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 34.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(58.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.GridView, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp)) }; Spacer(Modifier.width(16.dp)); Column { Text("Grid Layout", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Spacer(Modifier.height(4.dp)); Text("${sliderValue.toInt()} Columns", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }; Spacer(Modifier.height(28.dp)); Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(30.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) { Column(Modifier.padding(22.dp)) { repeat(2) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { repeat(sliderValue.toInt()) { Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))) } }; Spacer(Modifier.height(8.dp)) } } }; Spacer(Modifier.height(28.dp)); Slider(value = sliderValue, onValueChange = { sliderValue = it }, valueRange = 1f..max.toFloat(), steps = (max - 2).coerceAtLeast(0), onValueChangeFinished = { onUpdate(sliderValue.toInt()) }, colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary, inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))); Spacer(Modifier.height(10.dp)); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Compact", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Text("Comfortable", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } } } }

@Composable
fun ModernDateHeader(title: String, count: Int, onSelectAllForDate: () -> Unit = {}) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 24.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        TextButton(onClick = onSelectAllForDate) {
            Text("Select All", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernTopBar(title: String, scrollBehavior: TopAppBarScrollBehavior, onSearchClick: () -> Unit, onSelectionClick: () -> Unit, onMenuAction: (String) -> Unit) {
    var showMenu by remember { mutableStateOf(false) }
    CenterAlignedTopAppBar(
        title = { Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) },
        actions = {
            IconButton(onClick = onSearchClick) { Icon(Icons.Outlined.Search, "Search", tint = MaterialTheme.colorScheme.onSurface) }
            IconButton(onClick = onSelectionClick) { Icon(Icons.Outlined.Checklist, "Select", tint = MaterialTheme.colorScheme.onSurface) }
            Box {
                IconButton(onClick = { showMenu = true }) { Icon(Icons.Rounded.MoreVert, "More", tint = MaterialTheme.colorScheme.onSurface) }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface)) {
                    PremiumMenuItem("Grid Size", Icons.Rounded.GridView) { onMenuAction("grid"); showMenu = false }
                    PremiumMenuItem("Sort Media", Icons.AutoMirrored.Filled.Sort) { onMenuAction("sort"); showMenu = false }
                    PremiumMenuItem("Slideshow", Icons.Rounded.Slideshow) { onMenuAction("slideshow"); showMenu = false }
                    HorizontalDivider()
                    PremiumMenuItem("Documents", Icons.Outlined.InsertDriveFile) { onMenuAction("docs"); showMenu = false }
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
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = MaterialTheme.colorScheme.surface)
    )
}
@Composable
private fun PremiumMenuItem(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = {
            Text(
                text = text,
                fontWeight = FontWeight.SemiBold
            )
        },
        onClick = onClick,
        leadingIcon = {
            Box(
                modifier = Modifier
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
fun SearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 6.dp,
        shadowElevation = 10.dp,
        color = MaterialTheme.colorScheme.surface
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
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(Modifier.width(14.dp))

            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Row(
                    modifier = Modifier
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

                    AnimatedVisibility(visible = query.isNotEmpty()) {
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

@OptIn(ExperimentalMaterial3Api::class)
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
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(vertical = 12.dp)
        ) {
            Box(Modifier.fillMaxWidth()) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.align(Alignment.CenterStart)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close"
                    )
                }

                Text(
                    text = "$selectedCount selected",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.align(Alignment.Center)
                )

                IconButton(
                    onClick = onSelectAll,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 8.dp)
                ) {
                    Icon(
                        imageVector = if (isAllSelected) Icons.Rounded.SelectAll else Icons.Outlined.SelectAll,
                        contentDescription = "Select All",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
fun ModernFilterRow(
    filters: List<MediaTypeFilter>,
    selectedIndex: Int,
    onFilterSelected: (Int) -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(filters, key = { _, filter -> filter.name }) { index, filter ->
            val isSelected = selectedIndex == index

            val backgroundColor by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                label = "filterBg"
            )

            val textColor by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                label = "filterText"
            )

            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onFilterSelected(index) },
                shape = RoundedCornerShape(16.dp),
                color = backgroundColor,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = filter.label,
                        color = textColor,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

@Composable
fun EmptyMediaOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
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