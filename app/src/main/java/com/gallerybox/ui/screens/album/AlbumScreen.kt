@file:Suppress("UnsafeOptInUsageError", "UnstableApiUsage", "OPT_IN_USAGE", "unused", "DEPRECATION")
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.gallerybox.ui.screens.album

import android.app.Activity
import com.gallerybox.ui.screens.picture.ActionItem
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
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.Player
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import com.gallerybox.data.Album
import com.gallerybox.data.MediaItem
import com.gallerybox.viewmodel.AlbumSort
import com.gallerybox.viewmodel.GalleryEvent
import com.gallerybox.viewmodel.GalleryViewModel
import com.gallerybox.viewmodel.GalleryViewerState
import com.gallerybox.viewmodel.MergeMode
import com.gallerybox.viewmodel.PhotoSort
import com.gallerybox.viewmodel.TrashViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.media3.common.MediaItem as Media3Item

const val ID_CAMERA = "virtual_camera"
const val ID_RECENT = "virtual_recent"
const val ID_FAVORITES = "virtual_favorites"
const val ID_VIDEOS = "virtual_videos"
const val ID_SCREENSHOTS = "virtual_screenshots"
const val ID_DOWNLOADS = "virtual_downloads"
const val ID_WHATSAPP = "virtual_whatsapp"
const val ID_INSTAGRAM = "virtual_instagram"
const val ID_HIDDEN = "virtual_hidden"

enum class AlbumMediaFilter { ALL, PHOTOS, VIDEOS }

@Stable data class AlbumActions(
    val onAlbumClick: (Album) -> Unit,
    val onNavigateToFavorites: () -> Unit,
    val onNavigateToTrash: () -> Unit,
    val onNavigateToHidden: () -> Unit,
    val onLockApp: () -> Unit,
    val onNavigateToSettings: () -> Unit,
    val onNavigateToDuplicates: () -> Unit,
    val onNavigateToScan: () -> Unit
)

@Stable data class DetailActions(
    val onBack: () -> Unit,
    val onNavigateToPhotoEditor: (String, Long) -> Unit,
    val onNavigateToVideoEditor: (String, Long) -> Unit,
    val onNavigateToVideoPlayer: (String, List<String>) -> Unit,
    val onNavigateToMoveCopy: (String, String, String?) -> Unit,
    val onNavigateToTrash: () -> Unit,
    val onNavigateToHidden: () -> Unit,
    val onLockApp: () -> Unit,
    val onNavigateToWallpaper: (String, Long) -> Unit,
    val onAddMediaToAlbum: ((String) -> Unit)? = null,
    val onDeleteAlbum: ((String) -> Unit)? = null
)

sealed class AlbumUiDialog {
    data object None : AlbumUiDialog()
    data object GridSize : AlbumUiDialog()
    data object Sort : AlbumUiDialog()
    data object CreateAlbum : AlbumUiDialog()
    data object HiddenAlbums : AlbumUiDialog()
    data class Rename(val album: Album) : AlbumUiDialog()
    data class Delete(val albums: List<Album>) : AlbumUiDialog()
    data class Info(val album: Album) : AlbumUiDialog()
    data class QuickAction(val album: Album) : AlbumUiDialog()
    data class MoveCopy(val album: Album, val isMove: Boolean) : AlbumUiDialog()
    data class CreateAndMoveCopy(val album: Album, val isMove: Boolean) : AlbumUiDialog()
}

sealed class DetailUiDialog {
    data object None : DetailUiDialog()
    data object GridSize : DetailUiDialog()
    data object Sort : DetailUiDialog()
    data object DeleteAlbum : DetailUiDialog()
    data class Delete(val mediaIds: List<Long>) : DetailUiDialog()
    data class QuickAction(val item: MediaItem) : DetailUiDialog()
    data class MetadataInfo(val item: MediaItem) : DetailUiDialog()
}

sealed class GalleryGridItem {
    data class Header(val id: String, val title: String, val count: Int) : GalleryGridItem()
    data class Media(val item: MediaItem) : GalleryGridItem()
}

fun isValidUri(context: Context, uri: Uri?): Boolean = uri != null && uri != Uri.EMPTY
fun clearImageCache(context: Context) { context.imageLoader.memoryCache?.clear(); context.imageLoader.diskCache?.clear(); Toast.makeText(context, "Cache Cleared", Toast.LENGTH_SHORT).show() }
fun getSmartName(item: MediaItem): String = item.name.lowercase().let { when { "fdownloader" in it -> "Downloaded Video"; "instagram" in it -> "Instagram Video"; "whatsapp" in it -> "WhatsApp Media"; "screenshot" in it -> "Screenshot"; item.isVideo -> "Video"; else -> "Photo" } }
fun getFolderName(path: String): String = try { File(path).parentFile?.name ?: "Unknown Folder" } catch (e: Exception) { "Unknown Folder" }
fun formatDuration(durationMs: Long): String = "%d:%02d".format(Locale.US, (durationMs / 60000) % 60, (durationMs / 1000) % 60).let { if (it.startsWith("0:")) it else "%d:%02d:%02d".format(Locale.US, durationMs / 3600000, (durationMs / 60000) % 60, (durationMs / 1000) % 60) }
fun Context.findActivity(): Activity? { var ctx = this; while (ctx is ContextWrapper) { if (ctx is Activity) return ctx; ctx = ctx.baseContext }; return null }
fun albumMatchesQuery(album: Album, query: String): Boolean { if (query.isBlank()) return true; val q = query.lowercase().trim(); if (album.name.lowercase().contains(q)) return true; return when (q) { "video", "videos" -> album.id in listOf(ID_CAMERA, ID_WHATSAPP); "photo", "photos", "image" -> album.id in listOf(ID_CAMERA, ID_RECENT); "fav", "favorite", "heart" -> album.id == ID_FAVORITES; "download" -> album.id == ID_DOWNLOADS; "social", "chat" -> album.id in listOf(ID_WHATSAPP, ID_INSTAGRAM); else -> false } }

private val topBarFormatter = SimpleDateFormat("MMMM dd, yyyy  •  hh:mm a", Locale.getDefault())
private val metadataFormatter = SimpleDateFormat("EEEE, MMMM dd, yyyy 'at' hh:mm a", Locale.getDefault())

fun getSamsungDateHeader(timestampSec: Long): String {
    val date = Date(timestampSec * 1000)
    val cal = Calendar.getInstance()
    val todayDay = cal.get(Calendar.DAY_OF_YEAR)
    val todayYear = cal.get(Calendar.YEAR)
    cal.time = date
    val targetDay = cal.get(Calendar.DAY_OF_YEAR)
    val targetYear = cal.get(Calendar.YEAR)
    return when {
        todayYear == targetYear && todayDay == targetDay -> "TODAY"
        todayYear == targetYear && todayDay - 1 == targetDay -> "YESTERDAY"
        else -> SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault()).format(date)
    }
}

fun shareMediaItems(context: Context, items: List<MediaItem>) {
    if (items.isEmpty()) return
    val intent = Intent(if (items.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply { val hasImg = items.any { !it.isVideo }; val hasVid = items.any { it.isVideo }; type = if (hasVid && !hasImg) "video/*" else if (hasImg && !hasVid) "image/*" else "*/*"; addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); if (items.size > 1) putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(items.map { it.uri })) else putExtra(Intent.EXTRA_STREAM, items.first().uri) }
    try { context.startActivity(Intent.createChooser(intent, "Share via")) } catch (e: Exception) { Toast.makeText(context, "No app found to share", Toast.LENGTH_SHORT).show() }
}

@Composable
fun rememberGridImageRequest(uri: Uri?, size: Int, isVideo: Boolean): ImageRequest { val context = LocalContext.current; return remember(uri, size, isVideo) { ImageRequest.Builder(context).data(uri).size(size).bitmapConfig(Bitmap.Config.RGB_565).memoryCachePolicy(CachePolicy.ENABLED).diskCachePolicy(CachePolicy.ENABLED).precision(Precision.INEXACT).allowHardware(true).crossfade(false).error(android.R.drawable.ic_menu_report_image).fallback(android.R.drawable.ic_menu_report_image).apply { if (isVideo) decoderFactory(coil.decode.VideoFrameDecoder.Factory()) }.build() } }

// ============================================================================
// 1. ALBUM SCREEN
// ============================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumScreen(viewModel: GalleryViewModel = hiltViewModel(), trashViewModel: TrashViewModel = hiltViewModel(), onViewerStateChanged: (Boolean) -> Unit = {}, actions: AlbumActions) {
    val context = LocalContext.current; val scope = rememberCoroutineScope(); val haptic = LocalHapticFeedback.current; val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState()); val vmAlbums by viewModel.albumsState.collectAsState(initial = emptyList()); val albumPreviews by viewModel.albumPreviewMap.collectAsState()
    val allAlbums by viewModel.allAlbumsState.collectAsState(initial = emptyList()); val sortOption by viewModel.albumSort.collectAsState(); var searchQuery by remember { mutableStateOf("") }; var isSearchActive by remember { mutableStateOf(false) }
    val rawMedia by viewModel.rawMedia.collectAsState()

    var activeDialog by remember { mutableStateOf<AlbumUiDialog>(AlbumUiDialog.None) }
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var showSelectionMenu by remember { mutableStateOf(false) }

    val virtualAlbums = remember(vmAlbums, searchQuery) { vmAlbums.filter { it.id.startsWith("virtual_") && albumMatchesQuery(it, searchQuery) }.sortedBy { when (it.id) { ID_RECENT -> 0; ID_FAVORITES -> 1; ID_DOWNLOADS -> 2; else -> 99 } } }
    val userAlbums = remember(vmAlbums, searchQuery, sortOption) { val filtered = vmAlbums.filter { !it.id.startsWith("virtual_") }.filter { albumMatchesQuery(it, searchQuery) }; if (sortOption == AlbumSort.Custom) { filtered } else { val baseCmp = compareByDescending<Album> { it.isPinned }; val finalCmp = when (sortOption.name) { "NameAsc" -> baseCmp.thenBy { it.name.lowercase() }; "NameDesc" -> baseCmp.thenByDescending { it.name.lowercase() }; "SizeDesc" -> baseCmp.thenByDescending { it.sizeBytes }; "CountDesc" -> baseCmp.thenByDescending { it.mediaCount }; else -> baseCmp }; filtered.sortedWith(finalCmp) } }

    var dynamicUserAlbums by remember(userAlbums) { mutableStateOf(userAlbums) }
    val displayAlbums = remember(virtualAlbums, dynamicUserAlbums) { virtualAlbums + dynamicUserAlbums }

    var draggedIndex by remember { mutableIntStateOf(-1) }
    var targetIndex by remember { mutableIntStateOf(-1) }
    var originalOrder by remember { mutableStateOf<List<Album>>(emptyList()) }

    val configuration = LocalConfiguration.current; val density = LocalDensity.current; val screenWidthDp = configuration.screenWidthDp.toFloat()
    val adaptiveCols = remember(screenWidthDp) { when { screenWidthDp >= 800f -> 6; screenWidthDp >= 600f -> 4; else -> 3 } }
    val prefs = remember { context.getSharedPreferences("gallery_prefs", Context.MODE_PRIVATE) }
    var columnCount by remember { mutableIntStateOf(prefs.getInt("gallery_grid_columns", adaptiveCols)) }

    val intentSenderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result -> val g = result.resultCode == Activity.RESULT_OK; trashViewModel.onPermissionResultGlobal(g); if (!g) Toast.makeText(context, "Permission Denied", Toast.LENGTH_SHORT).show() }

    LaunchedEffect(trashViewModel) { trashViewModel.events.collect { event -> when (event) { is GalleryEvent.RequestPermission -> intentSenderLauncher.launch(IntentSenderRequest.Builder(event.intentSender).build()); is GalleryEvent.OperationSuccess -> { isSelectionMode = false; selectedIds = emptySet(); Toast.makeText(context, "Album moved to Trash", Toast.LENGTH_SHORT).show() }; is GalleryEvent.ShowToast -> Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show(); else -> {} } } }
    BackHandler(enabled = isSearchActive) { isSearchActive = false; searchQuery = "" }; BackHandler(enabled = isSelectionMode) { isSelectionMode = false; selectedIds = emptySet() }; BackHandler(enabled = activeDialog != AlbumUiDialog.None) { activeDialog = AlbumUiDialog.None }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Scaffold(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection), containerColor = Color.Transparent, snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                if (isSelectionMode) { Column(Modifier.fillMaxWidth().statusBarsPadding().padding(vertical = 12.dp)) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = { isSelectionMode = false; selectedIds = emptySet() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close") }; Text("${selectedIds.size} selected", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); IconButton(onClick = { selectedIds = if (selectedIds.size == displayAlbums.size) emptySet() else displayAlbums.map { it.id }.toSet() }) { Icon(Icons.Outlined.Checklist, "Select All") } } } }
                else if (isSearchActive) SamsungSearchBar(query = searchQuery, onQueryChange = { searchQuery = it }, onClose = { isSearchActive = false; searchQuery = "" })
                else SamsungAlbumTopBar(scrollBehavior = scrollBehavior, onSearchClick = { isSearchActive = true }, onMenuAction = { action -> when (action) { "grid" -> activeDialog = AlbumUiDialog.GridSize; "sort" -> activeDialog = AlbumUiDialog.Sort; "create" -> activeDialog = AlbumUiDialog.CreateAlbum; "trash" -> actions.onNavigateToTrash(); "hidden" -> activeDialog = AlbumUiDialog.HiddenAlbums; "lock_app" -> actions.onLockApp(); "settings" -> actions.onNavigateToSettings(); "duplicates" -> actions.onNavigateToDuplicates(); "scan" -> actions.onNavigateToScan(); "clearcache" -> clearImageCache(context) } })
            }
        ) { padding ->
            if (displayAlbums.isEmpty()) EmptyAlbumsOverlay(onCreateClick = { activeDialog = AlbumUiDialog.CreateAlbum })
            else {
                LazyVerticalGrid(columns = GridCells.Fixed(columnCount), modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    val onAlbumClick: (Album) -> Unit = { album -> if (isSelectionMode) selectedIds = if (selectedIds.contains(album.id)) selectedIds - album.id else selectedIds + album.id else actions.onAlbumClick(album) }
                    val onAlbumLongClick: (Album) -> Unit = { album -> haptic.performHapticFeedback(HapticFeedbackType.LongPress); isSelectionMode = true; selectedIds = if (selectedIds.contains(album.id)) selectedIds - album.id else selectedIds + album.id }

                    itemsIndexed(items = displayAlbums, key = { _, album -> album.id }) { index, album ->
                        var dragOffset by remember { mutableStateOf(Offset.Zero) }
                        val isDragged = draggedIndex == index

                        Box(
                            modifier = Modifier
                                .animateItem()
                                .zIndex(if (isDragged) 1f else 0f)
                                .graphicsLayer {
                                    scaleX = if (isDragged) 1.08f else 1f
                                    scaleY = if (isDragged) 1.08f else 1f
                                    alpha = if (isDragged) 0.9f else 1f
                                    this.translationX = if (isDragged) dragOffset.x else 0f
                                    this.translationY = if (isDragged) dragOffset.y else 0f
                                    shadowElevation = if (isDragged) 16f else 0f
                                    shape = RoundedCornerShape(10.dp)
                                    clip = true
                                }
                                .pointerInput(album.id, searchQuery, sortOption) {
                                    if (!album.id.startsWith("virtual_") && sortOption == AlbumSort.Custom && searchQuery.isBlank()) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                draggedIndex = index
                                                targetIndex = index
                                                dragOffset = Offset.Zero
                                                originalOrder = dynamicUserAlbums
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                dragOffset += dragAmount

                                                val cellWidth = size.width + 16f
                                                val cellHeight = size.height + 16f

                                                val xDir = (dragOffset.x / cellWidth).roundToInt()
                                                val yDir = (dragOffset.y / cellHeight).roundToInt()

                                                val virtualCount = virtualAlbums.size
                                                val newTarget = (draggedIndex + xDir + (yDir * columnCount)).coerceIn(virtualCount, displayAlbums.lastIndex)

                                                if (newTarget != draggedIndex) {
                                                    val fromUserIndex = draggedIndex - virtualCount
                                                    val toUserIndex = newTarget - virtualCount

                                                    val list = dynamicUserAlbums.toMutableList()
                                                    val item = list.removeAt(fromUserIndex)
                                                    list.add(toUserIndex, item)
                                                    dynamicUserAlbums = list

                                                    draggedIndex = newTarget
                                                    targetIndex = newTarget

                                                    dragOffset = Offset(
                                                        x = dragOffset.x - (xDir * cellWidth),
                                                        y = dragOffset.y - (yDir * cellHeight)
                                                    )

                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                }
                                            },
                                            onDragEnd = {
                                                if (draggedIndex >= 0) {
                                                    viewModel.saveCustomAlbumOrder(virtualAlbums + dynamicUserAlbums)
                                                }
                                                draggedIndex = -1
                                                targetIndex = -1
                                                dragOffset = Offset.Zero
                                            },
                                            onDragCancel = {
                                                dynamicUserAlbums = originalOrder
                                                draggedIndex = -1
                                                targetIndex = -1
                                                dragOffset = Offset.Zero
                                            }
                                        )
                                    }
                                }
                        ) {
                            SamsungAlbumCard(album = album, albumPreviews = albumPreviews, isSelected = selectedIds.contains(album.id), isSelectionMode = isSelectionMode, onClick = { onAlbumClick(album) }, onLongClick = { onAlbumLongClick(album) })
                        }
                    }
                }
            }
        }
        AnimatedVisibility(visible = isSelectionMode, enter = slideInVertically(initialOffsetY = { it }), exit = slideOutVertically(targetOffsetY = { it }), modifier = Modifier.align(Alignment.BottomCenter)) {
            SamsungBottomActionBar(
                onShare = { shareMediaItems(context, rawMedia.filter { selectedIds.contains(it.bucketId) }); isSelectionMode = false; selectedIds = emptySet() },
                onDelete = { activeDialog = AlbumUiDialog.Delete(displayAlbums.filter { selectedIds.contains(it.id) }) },
                onMore = { showSelectionMenu = true }
            )
        }
        if (showSelectionMenu) {
            DropdownMenu(expanded = showSelectionMenu, onDismissRequest = { showSelectionMenu = false }) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = { showSelectionMenu = false; if (selectedIds.size == 1) displayAlbums.find { it.id == selectedIds.first() }?.let { activeDialog = AlbumUiDialog.Rename(it) } else Toast.makeText(context, "Select only 1 album to rename", Toast.LENGTH_SHORT).show() })
                DropdownMenuItem(text = { Text("Info") }, onClick = { showSelectionMenu = false; if (selectedIds.size == 1) displayAlbums.find { it.id == selectedIds.first() }?.let { activeDialog = AlbumUiDialog.Info(it) } else Toast.makeText(context, "Select only 1 album for info", Toast.LENGTH_SHORT).show() })
                DropdownMenuItem(text = { Text("Move") }, onClick = { showSelectionMenu = false; if (selectedIds.size == 1) displayAlbums.find { it.id == selectedIds.first() }?.let { activeDialog = AlbumUiDialog.MoveCopy(it, true) } else Toast.makeText(context, "Select only 1 album to move", Toast.LENGTH_SHORT).show() })
                DropdownMenuItem(text = { Text("Copy") }, onClick = { showSelectionMenu = false; if (selectedIds.size == 1) displayAlbums.find { it.id == selectedIds.first() }?.let { activeDialog = AlbumUiDialog.MoveCopy(it, false) } else Toast.makeText(context, "Select only 1 album to copy", Toast.LENGTH_SHORT).show() })
            }
        }
    }
    when (val dialog = activeDialog) {
        is AlbumUiDialog.Info -> ModalBottomSheet(onDismissRequest = { activeDialog = AlbumUiDialog.None }, containerColor = MaterialTheme.colorScheme.surface) { Column(Modifier.padding(24.dp).padding(bottom = 24.dp)) { val albumItems = rawMedia.filter { it.bucketId == dialog.album.id }; val oldestItem = albumItems.minByOrNull { it.dateAdded }; val dateStr = oldestItem?.let { SimpleDateFormat("MMMM dd, yyyy 'at' hh:mm a", Locale.getDefault()).format(Date(it.dateAdded * 1000)) } ?: "Unknown"; val albumPath = oldestItem?.path?.let { File(it).parent } ?: "Unknown"; Text("Album Details", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Spacer(Modifier.height(24.dp)); MetadataRow(Icons.Outlined.Title, "Name", dialog.album.name); MetadataRow(Icons.Outlined.Storage, "Size", Formatter.formatFileSize(context, dialog.album.sizeBytes)); MetadataRow(Icons.Outlined.PhotoLibrary, "Items", dialog.album.mediaCount.toString()); MetadataRow(Icons.Outlined.Folder, "Path", albumPath); MetadataRow(Icons.Outlined.CalendarToday, "Created On", dateStr) } }
        is AlbumUiDialog.MoveCopy -> ModalBottomSheet(onDismissRequest = { activeDialog = AlbumUiDialog.None }, containerColor = MaterialTheme.colorScheme.surface) { Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) { Text(if (dialog.isMove) "Move To..." else "Copy To...", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)); LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f, fill = false), contentPadding = PaddingValues(bottom = 12.dp)) { item { ListItem(headlineContent = { Text("Create New Album", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) }, leadingContent = { Icon(Icons.Rounded.CreateNewFolder, null, tint = MaterialTheme.colorScheme.primary) }, modifier = Modifier.clickable { activeDialog = AlbumUiDialog.CreateAndMoveCopy(dialog.album, dialog.isMove) }) }; items(allAlbums.filter { !it.id.startsWith("virtual_") && it.id != dialog.album.id }.sortedBy { it.name.lowercase() }) { targetAlbum -> ListItem(headlineContent = { Text(targetAlbum.name, fontWeight = FontWeight.Medium) }, leadingContent = { Icon(Icons.Outlined.Folder, null) }, modifier = Modifier.clickable { viewModel.mergeAlbums(sourceAlbumIds = listOf(dialog.album.id), targetAlbumId = targetAlbum.id, mergeMode = if (dialog.isMove) MergeMode.MOVE_AND_DELETE else MergeMode.COPY); activeDialog = AlbumUiDialog.None }) } } } }
        is AlbumUiDialog.CreateAndMoveCopy -> SamsungInputSheet(title = if (dialog.isMove) "New Album & Move" else "New Album & Copy", initial = "${dialog.album.name} Copy", onDismiss = { activeDialog = AlbumUiDialog.None }, onConfirm = { newName -> val mediaIds = rawMedia.filter { it.bucketId == dialog.album.id }.map { it.id }; if (dialog.isMove) { viewModel.createAndMove(mediaIds, newName) } else { viewModel.createAndCopy(mediaIds, newName) }; activeDialog = AlbumUiDialog.None; scope.launch { delay(800); viewModel.forceSync() } })
        is AlbumUiDialog.Rename -> SamsungInputSheet("Rename Album", dialog.album.name, onDismiss = { activeDialog = AlbumUiDialog.None }, onConfirm = { viewModel.renameAlbum(dialog.album, it); activeDialog = AlbumUiDialog.None })
        is AlbumUiDialog.Delete -> SamsungDeleteSheet(dialog.albums.size, onDismiss = { activeDialog = AlbumUiDialog.None }, onDeleteAll = { trashViewModel.confirmPendingAlbumTrash(dialog.albums, rawMedia); activeDialog = AlbumUiDialog.None; scope.launch { delay(500); viewModel.forceSync() } })
        is AlbumUiDialog.CreateAlbum -> SamsungCreateAlbumSheet(onDismiss = { activeDialog = AlbumUiDialog.None }, onCreate = { name, sd -> viewModel.createAlbum(name, sd); activeDialog = AlbumUiDialog.None; scope.launch { delay(500); viewModel.forceSync() } })
        is AlbumUiDialog.Sort -> SamsungAlbumSortSheet(sortOption, onDismiss = { activeDialog = AlbumUiDialog.None }, onSortSelected = { viewModel.updateAlbumSort(it); activeDialog = AlbumUiDialog.None })
        is AlbumUiDialog.GridSize -> SamsungGridSheet(columnCount, 8, onDismiss = { activeDialog = AlbumUiDialog.None }, onUpdate = { columnCount = it; prefs.edit().putInt("gallery_grid_columns", it).apply(); activeDialog = AlbumUiDialog.None })
        is AlbumUiDialog.HiddenAlbums -> ModalBottomSheet(onDismissRequest = { activeDialog = AlbumUiDialog.None }, containerColor = MaterialTheme.colorScheme.surface) { val hiddenAlbums by viewModel.hiddenAlbums.collectAsState(); val filterAlbums = allAlbums.filter { !it.id.startsWith("virtual_") }; Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) { Text("Hide or Unhide", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)); LazyColumn(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 12.dp)) { items(filterAlbums, key = { it.id }) { album -> Row(modifier = Modifier.fillMaxWidth().clickable { viewModel.toggleHiddenAlbum(album.id) }.padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Column(modifier = Modifier.weight(1f)) { Text(album.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium); Text("${album.mediaCount} items", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Switch(checked = hiddenAlbums.contains(album.id), onCheckedChange = { viewModel.toggleHiddenAlbum(album.id) }) } } } } }
        AlbumUiDialog.None -> {}
        else -> {}
    }
}

@Composable
fun EmptyAlbumsOverlay(onCreateClick: () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(112.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.PhotoAlbum, null, modifier = Modifier.size(58.dp), tint = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.height(28.dp))
            Text("No Albums", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(10.dp))
            Text("Create albums to organize your memories.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(34.dp))
            Button(onClick = onCreateClick, modifier = Modifier.height(58.dp).padding(horizontal = 24.dp), shape = RoundedCornerShape(22.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) { Icon(Icons.Rounded.Add, null, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Create Album", fontWeight = FontWeight.Bold) }
        }
    }
}

// ============================================================================
// 2. ALBUM DETAIL SCREEN (Samsung Style)
// ============================================================================
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AlbumDetailScreen(albumId: String, viewModel: GalleryViewModel = hiltViewModel(), trashViewModel: TrashViewModel = hiltViewModel(), onViewerStateChanged: (Boolean) -> Unit = {}, actions: DetailActions) {
    val context = LocalContext.current; val haptic = LocalHapticFeedback.current; val scope = rememberCoroutineScope(); val snackbarHostState = remember { SnackbarHostState() }; val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState()); val gridState = rememberLazyGridState()
    val mediaMap by viewModel.mediaMap.collectAsState(); val favoriteIds by viewModel.favoriteIds.collectAsState(); val rawMedia by viewModel.rawMedia.collectAsState()
    val vmAlbums by viewModel.albumsState.collectAsState(initial = emptyList())

    val viewerState by viewModel.viewerState.collectAsState()
    val openViewerState = viewerState as? GalleryViewerState.Open
    val viewerItemId = openViewerState?.mediaId

    var activeDialog by remember { mutableStateOf<DetailUiDialog>(DetailUiDialog.None) }

    val intentSenderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result -> val g = result.resultCode == Activity.RESULT_OK; trashViewModel.onPermissionResultGlobal(g); if (!g) Toast.makeText(context, "Permission Denied", Toast.LENGTH_SHORT).show() }

    var localSearchQuery by rememberSaveable { mutableStateOf("") }; var currentPhotoSort by rememberSaveable { mutableStateOf(PhotoSort.DateDesc) }

    LaunchedEffect(trashViewModel) { trashViewModel.events.collect { event -> when (event) { is GalleryEvent.RequestPermission -> intentSenderLauncher.launch(IntentSenderRequest.Builder(event.intentSender).build()); is GalleryEvent.OperationSuccess -> { Toast.makeText(context, "Moved to Trash", Toast.LENGTH_SHORT).show(); viewModel.forceSync() }; is GalleryEvent.ShowToast -> Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show(); else -> {} } } }
    LaunchedEffect(viewerState) { onViewerStateChanged(viewerState is GalleryViewerState.Open) }

    val album = remember(vmAlbums, albumId) { when (albumId) { ID_RECENT -> Album(ID_RECENT, "Recent", Uri.EMPTY, 0, 0L, isPinned = true); ID_FAVORITES -> Album(ID_FAVORITES, "Favorites", Uri.EMPTY, 0, 0L, isPinned = true); ID_VIDEOS -> Album(ID_VIDEOS, "Videos", Uri.EMPTY, 0, 0L, isPinned = true); ID_SCREENSHOTS -> Album(ID_SCREENSHOTS, "Screenshots", Uri.EMPTY, 0, 0L, isPinned = true); ID_WHATSAPP -> Album(ID_WHATSAPP, "WhatsApp", Uri.EMPTY, 0, 0L, isPinned = true); ID_INSTAGRAM -> Album(ID_INSTAGRAM, "Instagram", Uri.EMPTY, 0, 0L, isPinned = true); ID_DOWNLOADS -> Album(ID_DOWNLOADS, "Downloads", Uri.EMPTY, 0, 0L, isPinned = true); ID_HIDDEN -> Album(ID_HIDDEN, "Hidden", Uri.EMPTY, 0, 0L, isPinned = true); else -> vmAlbums.find { it.id == albumId } } }
    val isVirtual = albumId.startsWith("virtual_"); var isSelectionMode by remember { mutableStateOf(false) }; var selectedIds by remember { mutableStateOf(emptySet<Long>()) }; var selectedSize by remember { mutableLongStateOf(0L) }; var showMediaSelectionMenu by remember { mutableStateOf(false) }

    fun toggleSelection(item: MediaItem) { if (selectedIds.contains(item.id)) { selectedIds = selectedIds - item.id; selectedSize = max(0L, selectedSize - item.size) } else { selectedIds = (selectedIds + item.id).takeIf { it.size < 5000 } ?: selectedIds; selectedSize += item.size } }

    var showMenu by remember { mutableStateOf(false) }; var mediaFilter by remember { mutableStateOf(AlbumMediaFilter.ALL) }

    val configuration = LocalConfiguration.current; val density = LocalDensity.current; val screenWidthDp = configuration.screenWidthDp.toFloat(); val screenWidthPx = with(density) { configuration.screenWidthDp.dp.roundToPx() }
    val adaptiveCols = remember(screenWidthDp) { when { screenWidthDp >= 800f -> 8; screenWidthDp >= 600f -> 6; else -> 4 } }
    val prefs = remember { context.getSharedPreferences("gallery_prefs", Context.MODE_PRIVATE) }
    var detailColumns by remember { mutableIntStateOf(prefs.getInt("gallery_grid_columns", adaptiveCols)) }
    val actualColumns = detailColumns
    val dynamicThumbSize = remember(actualColumns, screenWidthPx) { max(160, screenWidthPx / actualColumns) }; val isScrolling = gridState.isScrollInProgress

    BackHandler(enabled = localSearchQuery.isNotEmpty()) { localSearchQuery = "" }; BackHandler(enabled = isSelectionMode) { isSelectionMode = false; selectedIds = emptySet(); selectedSize = 0L }; BackHandler(enabled = activeDialog != DetailUiDialog.None) { activeDialog = DetailUiDialog.None }; BackHandler(enabled = viewerState is GalleryViewerState.Open) { viewModel.closeViewer() }

    val baseMedia = remember(rawMedia, albumId, favoriteIds) { rawMedia.filter { item -> when (albumId) { ID_FAVORITES -> favoriteIds.contains(item.id); ID_VIDEOS -> item.isVideo; ID_SCREENSHOTS -> item.path.contains("Screenshot", true) || item.path.contains("Screenshots", true); ID_DOWNLOADS -> item.path.contains("Download", true); ID_WHATSAPP -> item.path.contains("WhatsApp", true); ID_INSTAGRAM -> item.path.contains("Instagram", true); ID_RECENT -> true; else -> item.bucketId == albumId } } }
    val filteredMedia = remember(baseMedia, mediaFilter, localSearchQuery, currentPhotoSort) { val base = when (mediaFilter) { AlbumMediaFilter.ALL -> baseMedia; AlbumMediaFilter.PHOTOS -> baseMedia.filter { !it.isVideo }; AlbumMediaFilter.VIDEOS -> baseMedia.filter { it.isVideo } }; val searched = if (localSearchQuery.isBlank()) base else { val q = localSearchQuery.trim().lowercase(); base.filter { it.name.lowercase().contains(q) || getSmartName(it).lowercase().contains(q) } }; when (currentPhotoSort) { PhotoSort.DateDesc -> searched.sortedByDescending { it.dateAdded }; PhotoSort.DateAsc -> searched.sortedBy { it.dateAdded }; PhotoSort.NameAsc -> searched.sortedBy { it.name.lowercase() }; PhotoSort.NameDesc -> searched.sortedByDescending { it.name.lowercase() }; PhotoSort.SizeDesc -> searched.sortedByDescending { it.size } } }
    val groupedMedia = remember(filteredMedia, currentPhotoSort) {
        if (currentPhotoSort in listOf(PhotoSort.DateDesc, PhotoSort.DateAsc)) {
            val list = mutableListOf<GalleryGridItem>()
            var lastDate = ""
            filteredMedia.forEach { item ->
                val date = getSamsungDateHeader(item.dateAdded)
                if (date != lastDate) {
                    list.add(GalleryGridItem.Header(date, date, 0))
                    lastDate = date
                }
                list.add(GalleryGridItem.Media(item))
            }
            list
        } else {
            filteredMedia.map { GalleryGridItem.Media(it) }
        }
    }

    AnimatedContent(targetState = viewerState is GalleryViewerState.Open, label = "ViewerTransition") { isViewerOpen ->
        if (!isViewerOpen) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                Scaffold(
                    modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection), containerColor = Color.Transparent, snackbarHost = { SnackbarHost(snackbarHostState) },
                    topBar = {
                        if (isSelectionMode) { Column(Modifier.fillMaxWidth().statusBarsPadding().padding(vertical = 12.dp)) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = { isSelectionMode = false; selectedIds = emptySet() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close") }; Text("${selectedIds.size} selected", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); IconButton(onClick = { selectedIds = if (selectedIds.size == filteredMedia.size) emptySet() else filteredMedia.map { it.id }.toSet() }) { Icon(Icons.Outlined.Checklist, "Select All") } } } } else {
                            TopAppBar(
                                title = { Text(album?.name ?: "Album", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold) },
                                navigationIcon = { IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                                actions = {
                                    Box {
                                        IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, "More") }
                                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, modifier = Modifier.clip(RoundedCornerShape(12.dp))) {
                                            DropdownMenuItem(text = { Text("Select items") }, onClick = { isSelectionMode = true; showMenu = false })
                                            DropdownMenuItem(text = { Text("Sort Media") }, onClick = { activeDialog = DetailUiDialog.Sort; showMenu = false })
                                            DropdownMenuItem(text = { Text("Grid Size") }, onClick = { activeDialog = DetailUiDialog.GridSize; showMenu = false })
                                        }
                                    }
                                }, scrollBehavior = scrollBehavior, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = MaterialTheme.colorScheme.surface)
                            )
                        }
                    }
                ) { padding ->
                    if (filteredMedia.isEmpty()) Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Outlined.ImageNotSupported, null, Modifier.size(72.dp), Color.LightGray); Spacer(Modifier.height(16.dp)); Text("No photos here", color = Color.Gray, style = MaterialTheme.typography.titleMedium) } }
                    else Column(Modifier.padding(padding)) {
                        SamsungFilterPills(mediaFilter, onFilterSelected = { mediaFilter = it })
                        LazyVerticalGrid(state = gridState, columns = GridCells.Fixed(actualColumns), modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 2.dp, end = 2.dp, top = 4.dp, bottom = 90.dp), verticalArrangement = Arrangement.spacedBy(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            items(
                                count = groupedMedia.size,
                                span = { index -> if (groupedMedia[index] is GalleryGridItem.Header) GridItemSpan(actualColumns) else GridItemSpan(1) },
                                key = { index ->
                                    when (val item = groupedMedia[index]) {
                                        is GalleryGridItem.Header -> "header_${item.id}"
                                        is GalleryGridItem.Media -> item.item.id
                                    }
                                },
                                contentType = { index -> if (groupedMedia[index] is GalleryGridItem.Header) "header" else "media" }
                            ) { index ->
                                when (val gridItem = groupedMedia[index]) {
                                    is GalleryGridItem.Header -> SamsungDateHeader(title = gridItem.title)
                                    is GalleryGridItem.Media -> {
                                        val mediaItem = mediaMap[gridItem.item.id] ?: gridItem.item
                                        if (isValidUri(context, mediaItem.uri)) {
                                            SamsungMediaGridTile(item = mediaItem, thumbSize = dynamicThumbSize, isSelected = selectedIds.contains(mediaItem.id), isSelectionMode = isSelectionMode, onClick = { if (isSelectionMode) { toggleSelection(mediaItem); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } else { viewModel.openViewer(mediaItem.id) } }, onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); if (isSelectionMode) { toggleSelection(mediaItem) } else { activeDialog = DetailUiDialog.QuickAction(mediaItem) } })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                AnimatedVisibility(visible = isSelectionMode, enter = slideInVertically(initialOffsetY = { it }), exit = slideOutVertically(targetOffsetY = { it }), modifier = Modifier.align(Alignment.BottomCenter)) {
                    SamsungBottomActionBar(onShare = { shareMediaItems(context, rawMedia.filter { selectedIds.contains(it.id) }); isSelectionMode = false; selectedIds = emptySet() }, onDelete = { activeDialog = DetailUiDialog.Delete(selectedIds.toList()) }, onMore = { showMediaSelectionMenu = true })
                }
                if (showMediaSelectionMenu) {
                    DropdownMenu(expanded = showMediaSelectionMenu, onDismissRequest = { showMediaSelectionMenu = false }) {
                        DropdownMenuItem(text = { Text("Details") }, onClick = { showMediaSelectionMenu = false; if (selectedIds.size == 1) activeDialog = DetailUiDialog.MetadataInfo(mediaMap[selectedIds.first()]!!) else Toast.makeText(context, "Select 1 item", Toast.LENGTH_SHORT).show() })
                        DropdownMenuItem(text = { Text("Move") }, onClick = { showMediaSelectionMenu = false; actions.onNavigateToMoveCopy("MOVE", selectedIds.joinToString(","), albumId); isSelectionMode = false; selectedIds = emptySet(); selectedSize = 0L })
                        DropdownMenuItem(text = { Text("Copy") }, onClick = { showMediaSelectionMenu = false; actions.onNavigateToMoveCopy("COPY", selectedIds.joinToString(","), albumId); isSelectionMode = false; selectedIds = emptySet(); selectedSize = 0L })
                    }
                }
            }
        } else {
            val currentItem = remember(viewerItemId, filteredMedia) {
                filteredMedia.find { it.id == viewerItemId }
            }

            if (currentItem != null) {
                val stableMediaList = filteredMedia
                val stableStartIndex = stableMediaList.indexOfFirst { it.id == currentItem.id }.coerceAtLeast(0)
                key(currentItem.id) {
                    SamsungFullscreenViewer(initialIndex = stableStartIndex, mediaList = stableMediaList, favoriteIds = favoriteIds, sharedPlayer = viewModel.getPlayer(), onClose = { viewModel.closeViewer() }, onEdit = { item -> viewModel.closeViewer(); if (item.isVideo) actions.onNavigateToVideoEditor(item.uri.toString(), item.id) else actions.onNavigateToPhotoEditor(item.uri.toString(), item.id) }, onPlayVideo = { uri, playlist -> viewModel.closeViewer(); actions.onNavigateToVideoPlayer(uri, playlist) }, onDelete = { item -> activeDialog = DetailUiDialog.Delete(listOf(item.id)) }, onMove = { item -> viewModel.closeViewer(); actions.onNavigateToMoveCopy("MOVE", item.id.toString(), albumId) }, onCopy = { item -> viewModel.closeViewer(); actions.onNavigateToMoveCopy("COPY", item.id.toString(), albumId) }, onWallpaper = { item -> viewModel.closeViewer(); actions.onNavigateToWallpaper(item.uri.toString(), item.id) }, onToggleFavorite = { id -> viewModel.toggleFavorite(id) }, onShowDetails = { item -> activeDialog = DetailUiDialog.MetadataInfo(item) })
                }
            }
        }
    }
    when (val dialog = activeDialog) {
        is DetailUiDialog.QuickAction -> { val item = dialog.item; val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true); var showMoreExpanded by remember { mutableStateOf(false) }; ModalBottomSheet(onDismissRequest = { activeDialog = DetailUiDialog.None }, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surface) { Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp).padding(bottom = 24.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Column { Text(getSmartName(item), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) } }; Spacer(Modifier.height(24.dp)); LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) { item { ActionItem(Icons.Outlined.Edit, "Edit") { activeDialog = DetailUiDialog.None; if (item.isVideo) actions.onNavigateToVideoEditor(item.uri.toString(), item.id) else actions.onNavigateToPhotoEditor(item.uri.toString(), item.id) } }; item { ActionItem(Icons.Outlined.Share, "Share") { shareMediaItems(context, listOf(item)); activeDialog = DetailUiDialog.None } }; item { ActionItem(Icons.Outlined.Delete, "Delete", isDestructive = true) { activeDialog = DetailUiDialog.Delete(listOf(item.id)) } }; item { ActionItem(Icons.Default.MoreVert, "More") { showMoreExpanded = true } } }; AnimatedVisibility(visible = showMoreExpanded) { Column(Modifier.padding(top = 16.dp)) { HorizontalDivider(Modifier.padding(vertical = 8.dp)); ListItem(headlineContent = { Text("Details", fontWeight = FontWeight.SemiBold) }, leadingContent = { Icon(Icons.Outlined.Info, null) }, modifier = Modifier.clickable { activeDialog = DetailUiDialog.None; activeDialog = DetailUiDialog.MetadataInfo(item) }); ListItem(headlineContent = { Text("Move to Album", fontWeight = FontWeight.SemiBold) }, leadingContent = { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, null) }, modifier = Modifier.clickable { activeDialog = DetailUiDialog.None; actions.onNavigateToMoveCopy("MOVE", item.id.toString(), albumId) }); ListItem(headlineContent = { Text("Copy to Album", fontWeight = FontWeight.SemiBold) }, leadingContent = { Icon(Icons.Outlined.FileCopy, null) }, modifier = Modifier.clickable { activeDialog = DetailUiDialog.None; actions.onNavigateToMoveCopy("COPY", item.id.toString(), albumId) }); if (!item.isVideo) { ListItem(headlineContent = { Text("Set as Wallpaper", fontWeight = FontWeight.SemiBold) }, leadingContent = { Icon(Icons.Outlined.Wallpaper, null) }, modifier = Modifier.clickable { activeDialog = DetailUiDialog.None; actions.onNavigateToWallpaper(item.uri.toString(), item.id) }) } } } } } }
        is DetailUiDialog.DeleteAlbum -> AlertDialog(onDismissRequest = { activeDialog = DetailUiDialog.None }, icon = { Icon(Icons.Outlined.DeleteForever, null, tint = MaterialTheme.colorScheme.error) }, title = { Text("Delete Album?") }, text = { Text("This will delete the manual album placeholder. Any physical media stored within this folder on your device will remain intact.") }, confirmButton = { Button(colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), onClick = { actions.onDeleteAlbum?.invoke(albumId); activeDialog = DetailUiDialog.None; actions.onBack() }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { activeDialog = DetailUiDialog.None }) { Text("Cancel") } })
        is DetailUiDialog.Delete -> SamsungDeleteSheet(dialog.mediaIds.size, onDismiss = { activeDialog = DetailUiDialog.None }, onDeleteAll = { val itemsToTrash = rawMedia.filter { dialog.mediaIds.contains(it.id) }; trashViewModel.confirmPendingGalleryTrash(itemsToTrash); activeDialog = DetailUiDialog.None; isSelectionMode = false; selectedIds = emptySet(); selectedSize = 0L; viewModel.closeViewer() })
        is DetailUiDialog.GridSize -> SamsungGridSheet(detailColumns, 8, onDismiss = { activeDialog = DetailUiDialog.None }, onUpdate = { detailColumns = it; prefs.edit().putInt("gallery_grid_columns", it).apply(); activeDialog = DetailUiDialog.None })
        is DetailUiDialog.Sort -> SamsungMediaSortSheet(activeSort = currentPhotoSort, onDismiss = { activeDialog = DetailUiDialog.None }, onSortSelected = { currentPhotoSort = it; activeDialog = DetailUiDialog.None })
        is DetailUiDialog.MetadataInfo -> MediaMetadataSheet(item = dialog.item) { activeDialog = DetailUiDialog.None }
        DetailUiDialog.None -> {}
    }
}

@Composable
fun SamsungFilterPills(selectedFilter: AlbumMediaFilter, onFilterSelected: (AlbumMediaFilter) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AlbumMediaFilter.entries.forEach { filter ->
            val isSelected = selectedFilter == filter
            Surface(modifier = Modifier.clip(RoundedCornerShape(50)).clickable { onFilterSelected(filter) }, color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceVariant, contentColor = if (isSelected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant) { Text(filter.name.lowercase().replaceFirstChar { it.uppercase() }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
fun SamsungDateHeader(title: String) { Row(modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 24.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) { Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground) } }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SamsungMediaGridTile(modifier: Modifier = Modifier, item: MediaItem, thumbSize: Int, isSelected: Boolean, isSelectionMode: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Box(modifier = modifier.aspectRatio(1f).graphicsLayer { clip = true; shape = RoundedCornerShape(2.dp) }.clip(RoundedCornerShape(2.dp)).combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick, onLongClick = onLongClick)) {
        val context = LocalContext.current

        val request = remember(context, item.uri, thumbSize) {
            ImageRequest.Builder(context)
                .data(item.uri)
                .size(thumbSize)
                .allowRgb565(true)
                .bitmapConfig(Bitmap.Config.RGB_565)
                .allowHardware(!item.isVideo)
                .crossfade(false)
                .apply {
                    if (item.isVideo) {
                        decoderFactory(coil.decode.VideoFrameDecoder.Factory())
                    }
                }
                .build()
        }
        AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, filterQuality = FilterQuality.Low, modifier = Modifier.fillMaxSize().graphicsLayer { if (isSelected) { scaleX = 0.85f; scaleY = 0.85f; shape = RoundedCornerShape(12.dp); clip = true } })
        if (item.isVideo) { Box(Modifier.fillMaxSize().drawWithCache { val brush = Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f)); onDrawBehind { drawRect(brush) } }); Surface(modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp), shape = RoundedCornerShape(4.dp), color = Color.Black.copy(alpha = 0.6f)) { Text(formatDuration(item.duration), fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) } }
        if (isSelectionMode) { Box(Modifier.fillMaxSize().background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else Color.Transparent)); Box(Modifier.padding(6.dp).align(Alignment.TopStart)) { if (isSelected) Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp).background(Color.White, CircleShape)) else Icon(Icons.Outlined.RadioButtonUnchecked, null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(20.dp)) } }
    }
}

// ============================================================================
// FULLSCREEN SAMSUNG VIEWER
// ============================================================================
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SamsungFullscreenViewer(
    initialIndex: Int, mediaList: List<MediaItem>, favoriteIds: List<Long>, sharedPlayer: Player,
    onClose: () -> Unit, onEdit: (MediaItem) -> Unit, onPlayVideo: (String, List<String>) -> Unit, onDelete: (MediaItem) -> Unit, onMove: (MediaItem) -> Unit, onCopy: (MediaItem) -> Unit, onWallpaper: (MediaItem) -> Unit, onToggleFavorite: (Long) -> Unit, onShowDetails: (MediaItem) -> Unit
) {
    if (mediaList.isEmpty()) return
    val context = LocalContext.current; val view = LocalView.current
    val safeInitialPage = initialIndex.coerceIn(0, max(mediaList.lastIndex, 0))
    val pagerState = rememberPagerState(initialPage = safeInitialPage, pageCount = { mediaList.size })
    var showControls by remember { mutableStateOf(true) }; var showMoreMenu by remember { mutableStateOf(false) }

    val activity = remember { context.findActivity() }
    var isCurrentPageZoomed by remember { mutableStateOf(false) }

    LaunchedEffect(initialIndex, mediaList.size) { if (pagerState.currentPage != initialIndex && initialIndex in mediaList.indices) pagerState.scrollToPage(initialIndex) }
    DisposableEffect(activity) { val window = activity?.window; if (window != null) { val controller = WindowCompat.getInsetsController(window, view); controller.hide(WindowInsetsCompat.Type.systemBars()); controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }; onDispose { window?.let { WindowCompat.getInsetsController(it, view).show(WindowInsetsCompat.Type.systemBars()) } } }
    BackHandler(enabled = !showControls) { showControls = true }
    BackHandler(enabled = showControls) { onClose() }

    val currentItem = mediaList.getOrNull(pagerState.currentPage)
    LaunchedEffect(currentItem) { if (currentItem == null && mediaList.isNotEmpty()) onClose() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pagerState, pageSpacing = 24.dp, userScrollEnabled = !isCurrentPageZoomed, key = { mediaList[it].id }, modifier = Modifier.fillMaxSize()) { page ->
            val item = mediaList[page]
            Box(Modifier.fillMaxSize()) {
                if (item.isVideo) {
                    VideoPreviewPage(item = item, isCurrentPage = pagerState.currentPage == page, showControls = showControls, sharedPlayer = sharedPlayer, onTap = { showControls = !showControls }, onPlay = { val playlist = mediaList.filter { it.isVideo }.map { it.uri.toString() }; onPlayVideo(item.uri.toString(), playlist) })
                } else {
                    SamsungZoomableImage(item = item, onTap = { showControls = !showControls }, onDismiss = onClose, onZoomChanged = { isZoomed -> isCurrentPageZoomed = isZoomed })
                }
            }
        }

        AnimatedVisibility(visible = showControls, modifier = Modifier.align(Alignment.TopCenter), enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
            Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent))).statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White) }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                        currentItem?.let { item ->
                            Text(text = item.name, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(text = topBarFormatter.format(Date(item.dateAdded * 1000)), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    IconButton(onClick = { showMoreMenu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More", tint = Color.White) }
                }
            }
        }

        AnimatedVisibility(visible = showControls, modifier = Modifier.align(Alignment.BottomCenter), enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
            Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)))).navigationBarsPadding()) {
                Text(text = "${pagerState.currentPage + 1} / ${mediaList.size}", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp))
                val listState = rememberLazyListState(initialFirstVisibleItemIndex = max(0, pagerState.currentPage - 3))
                val coroutineScope = rememberCoroutineScope()
                LaunchedEffect(pagerState.currentPage) { coroutineScope.launch { listState.animateScrollToItem(max(0, pagerState.currentPage - 3)) } }
                LazyRow(state = listState, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth().height(48.dp).padding(bottom = 8.dp)) {
                    itemsIndexed(mediaList) { index, item ->
                        val isSelected = index == pagerState.currentPage
                        Box(modifier = Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)).border(if (isSelected) 2.dp else 0.dp, if (isSelected) Color.White else Color.Transparent, RoundedCornerShape(4.dp)).clickable { coroutineScope.launch { pagerState.animateScrollToPage(index) } }) { AsyncImage(model = ImageRequest.Builder(context).data(item.uri).size(150).allowHardware(true).build(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (isSelected) 1f else 0.5f }) }
                    }
                }
                currentItem?.let { item ->
                    val isFavorite = favoriteIds.contains(item.id)
                    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        SamsungActionItem(if (isFavorite) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder, "Favorite", tint = if (isFavorite) Color.Red else Color.White, onClick = { onToggleFavorite(item.id) })
                        SamsungActionItem(Icons.Outlined.Share, "Share", tint = Color.White, onClick = { shareMediaItems(context, listOf(item)) })
                        SamsungActionItem(Icons.Outlined.Edit, "Edit", tint = Color.White, onClick = { onEdit(item) })
                        SamsungActionItem(Icons.Outlined.Delete, "Delete", tint = Color.White, onClick = { onDelete(item) })
                    }
                }
            }
        }
    }
    if (showMoreMenu && currentItem != null) {
        ModalBottomSheet(onDismissRequest = { showMoreMenu = false }, containerColor = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(bottom = 32.dp)) {
                ListItem(headlineContent = { Text("Details") }, modifier = Modifier.clickable { showMoreMenu = false; onShowDetails(currentItem) })
                ListItem(headlineContent = { Text("Move to Album") }, modifier = Modifier.clickable { showMoreMenu = false; onMove(currentItem) })
                ListItem(headlineContent = { Text("Copy to Album") }, modifier = Modifier.clickable { showMoreMenu = false; onCopy(currentItem) })
                if (!currentItem.isVideo) { ListItem(headlineContent = { Text("Set as Wallpaper") }, modifier = Modifier.clickable { showMoreMenu = false; onWallpaper(currentItem) }) }
            }
        }
    }
}

@Composable
fun SamsungActionItem(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick).padding(8.dp)) { Icon(icon, label, tint = tint, modifier = Modifier.size(24.dp)); Spacer(Modifier.height(4.dp)); Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = tint) }
}

@Composable
fun SamsungZoomableImage(item: MediaItem, onTap: () -> Unit, onDismiss: () -> Unit, onZoomChanged: (Boolean) -> Unit) {
    val context = LocalContext.current; val density = LocalDensity.current; val haptic = LocalHapticFeedback.current; val scope = rememberCoroutineScope()
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp; val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val wPx = with(density) { screenWidth.toPx() }; val hPx = with(density) { screenHeight.toPx() }
    val dismissThreshold = hPx * 0.25f
    val scale = remember { Animatable(1f) }; val offsetX = remember { Animatable(0f) }; val offsetY = remember { Animatable(0f) }; var dragOffsetY by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(scale.value) { onZoomChanged(scale.value > 1.05f) }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = (1f - (abs(dragOffsetY) / 850f)).coerceIn(0.2f, 1f))).offset { IntOffset(0, dragOffsetY.roundToInt()) }
            .graphicsLayer { val dismissScale = 1f - (abs(dragOffsetY) / 2500f); scaleX = scale.value * dismissScale; scaleY = scaleX; translationX = offsetX.value; translationY = offsetY.value }
            .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }, onDoubleTap = { tapOffset -> scope.launch { val currentScale = scale.value; if (currentScale > 1.5f) { launch { offsetX.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }; launch { offsetY.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }; launch { scale.animateTo(1f, spring(stiffness = Spring.StiffnessMediumLow)) } } else { val targetScale = 3f; val targetX = -(tapOffset.x - wPx / 2) * (targetScale - 1); val targetY = -(tapOffset.y - hPx / 2) * (targetScale - 1); val limitX = max(0f, (wPx * targetScale - wPx) / 2f); val limitY = max(0f, (hPx * targetScale - hPx) / 2f); launch { offsetX.animateTo(targetX.coerceIn(-limitX, limitX), spring(stiffness = Spring.StiffnessMediumLow)) }; launch { offsetY.animateTo(targetY.coerceIn(-limitY, limitY), spring(stiffness = Spring.StiffnessMediumLow)) }; launch { scale.animateTo(targetScale, spring(stiffness = Spring.StiffnessMediumLow)) } } } }) }
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false); var lastDragAmount = Offset.Zero; do { val event = awaitPointerEvent(); val zoom = event.calculateZoom(); val pan = event.calculatePan(); scope.launch { scale.snapTo((scale.value * zoom).coerceIn(1f, 5f)) }; if (scale.value > 1.05f) { event.changes.forEach { if (it.positionChanged()) it.consume() }; val limitX = max(0f, (wPx * scale.value - wPx) / 2f); val limitY = max(0f, (hPx * scale.value - hPx) / 2f); var nextX = offsetX.value + pan.x; var nextY = offsetY.value + pan.y; if (nextX > limitX) nextX = limitX + (nextX - limitX) * 0.3f else if (nextX < -limitX) nextX = -limitX + (nextX + limitX) * 0.3f; if (nextY > limitY) nextY = limitY + (nextY - limitY) * 0.3f else if (nextY < -limitY) nextY = -limitY + (nextY + limitY) * 0.3f; scope.launch { offsetX.snapTo(nextX); offsetY.snapTo(nextY) }; dragOffsetY = 0f; lastDragAmount = pan } else { val isVerticalDrag = abs(pan.y) > abs(pan.x); if (isVerticalDrag && event.changes.size == 1) { dragOffsetY += pan.y; event.changes.forEach { if (it.positionChanged()) it.consume() } } } } while (event.changes.any { it.pressed }); if (scale.value <= 1.05f) { if (abs(dragOffsetY) > dismissThreshold) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onDismiss() } else { dragOffsetY = 0f } } else { scope.launch { val limitX = max(0f, (wPx * scale.value - wPx) / 2f); val limitY = max(0f, (hPx * scale.value - hPx) / 2f); val targetX = (offsetX.value + lastDragAmount.x * 10).coerceIn(-limitX, limitX); val targetY = (offsetX.value + lastDragAmount.y * 10).coerceIn(-limitY, limitY); launch { offsetX.animateTo(targetX, spring(dampingRatio = 0.8f, stiffness = 400f)) }; launch { offsetY.animateTo(targetY, spring(dampingRatio = 0.8f, stiffness = 400f)) } } } } },
        contentAlignment = Alignment.Center
    ) { AsyncImage(model = ImageRequest.Builder(context).data(item.uri).allowHardware(true).precision(Precision.INEXACT).networkCachePolicy(CachePolicy.ENABLED).memoryCachePolicy(CachePolicy.ENABLED).crossfade(true).build(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
}

@OptIn(UnstableApi::class)
@Composable
fun VideoPreviewPage(item: MediaItem, isCurrentPage: Boolean, showControls: Boolean, sharedPlayer: Player, onTap: () -> Unit, onPlay: () -> Unit) {
    var muted by rememberSaveable(item.id) { mutableStateOf(true) }
    LaunchedEffect(item.id) { sharedPlayer.setMediaItem(Media3Item.fromUri(item.uri)); sharedPlayer.prepare() }
    LaunchedEffect(muted) { sharedPlayer.volume = if (muted) 0f else 1f }
    LaunchedEffect(isCurrentPage) { sharedPlayer.playWhenReady = false; sharedPlayer.pause() }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { ctx -> PlayerView(ctx).apply { useController = false; setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER); layoutParams = android.view.ViewGroup.LayoutParams(-1, -1) } }, update = { view -> view.player = sharedPlayer }, modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) })
        AnimatedVisibility(visible = showControls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.Center)) { Surface(modifier = Modifier.size(72.dp), shape = CircleShape, color = Color.Black.copy(alpha = 0.5f), onClick = onPlay) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(40.dp)) } } }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SamsungInputSheet(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 34.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text(text = "Cancel")
                }

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = {
                        if (text.isNotBlank()) {
                            onConfirm(text.trim())
                        }
                    }
                ) {
                    Text(text = "Save")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SamsungDeleteSheet(
    count: Int,
    onDismiss: () -> Unit,
    onDeleteAll: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 34.dp)
        ) {
            Text(
                text = "Delete $count albums?",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "This will remove the albums. Media will be safely moved to Trash.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text(text = "Cancel")
                }

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = onDeleteAll,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(text = "Delete")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SamsungCreateAlbumSheet(
    onDismiss: () -> Unit,
    onCreate: (String, Boolean) -> Unit
) {
    var text by remember { mutableStateOf("") }
    var useSdCard by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 34.dp)
        ) {
            Text(
                text = "Create Album",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                placeholder = {
                    Text(text = "Album name")
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = useSdCard,
                    onCheckedChange = { useSdCard = it }
                )
                Text(text = "Create on SD Card")
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text(text = "Cancel")
                }

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = {
                        if (text.isNotBlank()) {
                            onCreate(text.trim(), useSdCard)
                        }
                    }
                ) {
                    Text(text = "Create")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SamsungAlbumSortSheet(
    activeSort: AlbumSort,
    onDismiss: () -> Unit,
    onSortSelected: (AlbumSort) -> Unit
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
                text = "Sort by",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(24.dp)
            )

            AlbumSort.entries.forEach { option ->
                val sortLabel = when (option.name) {
                    "DateDesc" -> "Newest First"
                    "DateAsc" -> "Oldest First"
                    "NameAsc" -> "A → Z"
                    "NameDesc" -> "Z → A"
                    "SizeDesc" -> "Largest First"
                    "CountDesc" -> "Most Items"
                    "Custom" -> "Custom Order"
                    else -> option.name
                }

                ListItem(
                    headlineContent = {
                        Text(text = sortLabel)
                    },
                    trailingContent = {
                        if (activeSort == option) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    modifier = Modifier.clickable {
                        onSortSelected(option)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SamsungMediaSortSheet(
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
                text = "Sort by",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(24.dp)
            )

            PhotoSort.entries.forEach { option ->
                val sortLabel = when (option) {
                    PhotoSort.DateDesc -> "Newest First"
                    PhotoSort.DateAsc -> "Oldest First"
                    PhotoSort.NameAsc -> "Name (A → Z)"
                    PhotoSort.NameDesc -> "Name (Z → A)"
                    PhotoSort.SizeDesc -> "Largest First"
                }

                ListItem(
                    headlineContent = {
                        Text(text = sortLabel)
                    },
                    trailingContent = {
                        if (activeSort == option) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    modifier = Modifier.clickable {
                        onSortSelected(option)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SamsungGridSheet(
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
                .padding(horizontal = 24.dp)
                .padding(bottom = 34.dp)
        ) {
            Text(
                text = "Grid Size",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(24.dp))

            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                valueRange = 1f..max.toFloat(),
                steps = (max - 2).coerceAtLeast(0),
                onValueChangeFinished = {
                    onUpdate(sliderValue.toInt())
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "Compact")
                Text(text = "Comfortable")
            }
        }
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
                .padding(24.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = "Details",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(24.dp))

            MetadataRow(
                icon = Icons.Outlined.Title,
                label = "Name",
                value = item.name
            )

            MetadataRow(
                icon = Icons.Outlined.Folder,
                label = "Path",
                value = item.path
            )

            MetadataRow(
                icon = Icons.Outlined.CalendarToday,
                label = "Date",
                value = dateStr
            )

            MetadataRow(
                icon = Icons.Outlined.Storage,
                label = "Size",
                value = formattedSize
            )

            if (item.width > 0 && item.height > 0) {
                MetadataRow(
                    icon = Icons.Outlined.AspectRatio,
                    label = "Resolution",
                    value = "${item.width} × ${item.height}"
                )
            }

            if (item.isVideo && item.duration > 0L) {
                MetadataRow(
                    icon = Icons.Outlined.Timer,
                    label = "Duration",
                    value = formatDuration(item.duration)
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
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SamsungAlbumCard(
    album: Album,
    albumPreviews: Map<String, List<Uri>>,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale = if (isPressed) 0.96f else if (isSelected) 0.93f else 1f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = androidx.compose.material3.ripple(),
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        val actualCoverUri = remember(album.coverUri, albumPreviews) {
            if (album.coverUri != Uri.EMPTY) {
                album.coverUri
            } else {
                albumPreviews[album.id]?.firstOrNull()
            }
        }

        Box(
            modifier = Modifier
                .aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (actualCoverUri == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.PhotoAlbum,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(32.dp)
                    )
                }
            } else {
                AsyncImage(
                    model = rememberGridImageRequest(actualCoverUri, 512, false),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            if (isSelectionMode) {
                Box(
                    modifier = Modifier
                        .padding(6.dp)
                        .align(Alignment.TopStart)
                ) {
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
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

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = album.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface
        )

        Text(
            text = "${album.mediaCount}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun SamsungSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back"
            )
        }

        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .weight(1f)
                .height(46.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(23.dp)
                )
                .padding(horizontal = 16.dp),
            singleLine = true,
            textStyle = TextStyle(
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp
            ),
            decorationBox = { innerTextField ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Box(modifier = Modifier.weight(1f)) {
                        if (query.isEmpty()) {
                            Text(
                                text = "Search albums...",
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                fontSize = 16.sp
                            )
                        } else {
                            innerTextField()
                        }
                    }

                    if (query.isNotEmpty()) {
                        IconButton(
                            onClick = { onQueryChange("") },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Clear",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SamsungAlbumTopBar(
    scrollBehavior: TopAppBarScrollBehavior,
    onSearchClick: () -> Unit,
    onMenuAction: (String) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    TopAppBar(
        title = {
            Text(
                text = "Albums",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        actions = {
            IconButton(onClick = onSearchClick) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = "Search",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }

            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = "More",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    DropdownMenuItem(
                        text = { Text("Grid Size") },
                        onClick = { onMenuAction("grid"); showMenu = false }
                    )
                    DropdownMenuItem(
                        text = { Text("Sort Albums") },
                        onClick = { onMenuAction("sort"); showMenu = false }
                    )
                    DropdownMenuItem(
                        text = { Text("Create Album") },
                        onClick = { onMenuAction("create"); showMenu = false }
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Trash") },
                        onClick = { onMenuAction("trash"); showMenu = false }
                    )
                    DropdownMenuItem(
                        text = { Text("Settings") },
                        onClick = { onMenuAction("settings"); showMenu = false }
                    )
                }
            }
        },
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = MaterialTheme.colorScheme.surface
        )
    )
}

@Composable
fun SamsungBottomActionBar(
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onMore: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            SamsungActionItem(
                icon = Icons.Outlined.Share,
                label = "Share",
                onClick = onShare
            )

            SamsungActionItem(
                icon = Icons.Outlined.Delete,
                label = "Delete",
                isDestructive = true,
                onClick = onDelete
            )

            SamsungActionItem(
                icon = Icons.Rounded.MoreVert,
                label = "More",
                onClick = onMore
            )
        }
    }
}

@Composable
fun SamsungActionItem(
    icon: ImageVector,
    label: String,
    isDestructive: Boolean = false,
    onClick: () -> Unit
) {
    val color = if (isDestructive) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = color,
            modifier = Modifier.size(24.dp)
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
    }
}