@file:Suppress("UnsafeOptInUsageError", "UnstableApiUsage", "OPT_IN_USAGE", "unused", "DEPRECATION")
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.gallerybox.ui.screens.album

import android.app.Activity
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
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
import androidx.media3.common.Player
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Size
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
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.media3.common.MediaItem as Media3Item

const val ID_CAMERA = "virtual_camera"; const val ID_RECENT = "virtual_recent"; const val ID_FAVORITES = "virtual_favorites"
const val ID_VIDEOS = "virtual_videos"; const val ID_SCREENSHOTS = "virtual_screenshots"; const val ID_DOWNLOADS = "virtual_downloads"
const val ID_WHATSAPP = "virtual_whatsapp"; const val ID_INSTAGRAM = "virtual_instagram"; const val ID_HIDDEN = "virtual_hidden"

enum class AlbumMediaFilter { ALL, PHOTOS, VIDEOS }
@Stable data class AlbumActions(val onAlbumClick: (Album) -> Unit, val onNavigateToFavorites: () -> Unit, val onNavigateToTrash: () -> Unit, val onNavigateToHidden: () -> Unit, val onLockApp: () -> Unit, val onNavigateToSettings: () -> Unit, val onNavigateToDuplicates: () -> Unit, val onNavigateToScan: () -> Unit)
@Stable data class DetailActions(val onBack: () -> Unit, val onNavigateToPhotoEditor: (String, Long) -> Unit, val onNavigateToVideoEditor: (String, Long) -> Unit, val onNavigateToVideoPlayer: (String, List<String>) -> Unit, val onNavigateToMoveCopy: (String, String, String?) -> Unit, val onNavigateToTrash: () -> Unit, val onNavigateToHidden: () -> Unit, val onLockApp: () -> Unit, val onNavigateToWallpaper: (String, Long) -> Unit, val onAddMediaToAlbum: ((String) -> Unit)? = null, val onDeleteAlbum: ((String) -> Unit)? = null)

sealed class AlbumUiDialog { data object None : AlbumUiDialog(); data object GridSize : AlbumUiDialog(); data object Sort : AlbumUiDialog(); data object CreateAlbum : AlbumUiDialog(); data object HiddenAlbums : AlbumUiDialog(); data class Rename(val album: Album) : AlbumUiDialog(); data class Delete(val albums: List<Album>) : AlbumUiDialog(); data class Info(val album: Album) : AlbumUiDialog(); data class QuickAction(val album: Album) : AlbumUiDialog(); data class MoveCopy(val album: Album, val isMove: Boolean) : AlbumUiDialog(); data class CreateAndMoveCopy(val album: Album, val isMove: Boolean) : AlbumUiDialog() }
sealed class DetailUiDialog { data object None : DetailUiDialog(); data object GridSize : DetailUiDialog(); data object Sort : DetailUiDialog(); data object DeleteAlbum : DetailUiDialog(); data class Delete(val mediaIds: List<Long>) : DetailUiDialog(); data class QuickAction(val item: MediaItem) : DetailUiDialog() }

fun isValidUri(context: Context, uri: Uri?): Boolean = uri != null && uri != Uri.EMPTY
fun clearImageCache(context: Context) { context.imageLoader.memoryCache?.clear(); context.imageLoader.diskCache?.clear(); Toast.makeText(context, "Cache Cleared", Toast.LENGTH_SHORT).show() }
fun getSmartName(item: MediaItem): String = item.name.lowercase().let { when { "fdownloader" in it -> "Downloaded Video"; "instagram" in it -> "Instagram Video"; "whatsapp" in it -> "WhatsApp Media"; "screenshot" in it -> "Screenshot"; item.isVideo -> "Video"; else -> "Photo" } }
fun getFolderName(path: String): String = try { File(path).parentFile?.name ?: "Unknown Folder" } catch (e: Exception) { "Unknown Folder" }
fun formatDuration(durationMs: Long): String = "%d:%02d".format(Locale.US, (durationMs / 60000) % 60, (durationMs / 1000) % 60).let { if (it.startsWith("0:")) it else "%d:%02d:%02d".format(Locale.US, durationMs / 3600000, (durationMs / 60000) % 60, (durationMs / 1000) % 60) }
fun Context.findActivity(): Activity? { var ctx = this; while (ctx is ContextWrapper) { if (ctx is Activity) return ctx; ctx = ctx.baseContext }; return null }
fun albumMatchesQuery(album: Album, query: String): Boolean { if (query.isBlank()) return true; val q = query.lowercase().trim(); if (album.name.lowercase().contains(q)) return true; return when (q) { "video", "videos" -> album.id in listOf(ID_CAMERA, ID_WHATSAPP); "photo", "photos", "image" -> album.id in listOf(ID_CAMERA, ID_RECENT); "fav", "favorite", "heart" -> album.id == ID_FAVORITES; "download" -> album.id == ID_DOWNLOADS; "social", "chat" -> album.id in listOf(ID_WHATSAPP, ID_INSTAGRAM); else -> false } }

fun shareMediaItems(context: Context, items: List<MediaItem>) {
    if (items.isEmpty()) return
    val intent = Intent(if (items.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply { val hasImg = items.any { !it.isVideo }; val hasVid = items.any { it.isVideo }; type = if (hasVid && !hasImg) "video/*" else if (hasImg && !hasVid) "image/*" else "*/*"; addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); if (items.size > 1) putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(items.map { it.uri })) else putExtra(Intent.EXTRA_STREAM, items.first().uri) }
    try { context.startActivity(Intent.createChooser(intent, "Share via")) } catch (e: Exception) { Toast.makeText(context, "No app found to share", Toast.LENGTH_SHORT).show() }
}

@Composable
fun rememberGridImageRequest(uri: Uri?, size: Int, isVideo: Boolean): ImageRequest { val context = LocalContext.current; return remember(uri, size, isVideo) { ImageRequest.Builder(context).data(uri).size(size).bitmapConfig(Bitmap.Config.RGB_565).memoryCachePolicy(CachePolicy.ENABLED).diskCachePolicy(CachePolicy.ENABLED).precision(Precision.INEXACT).allowHardware(true).crossfade(false).error(android.R.drawable.ic_menu_report_image).fallback(android.R.drawable.ic_menu_report_image).apply { if (isVideo) decoderFactory(coil.decode.VideoFrameDecoder.Factory()) }.build() } }

@Composable
private fun OptimizedAlbumTile(album: Album, albumPreviews: Map<String, List<Uri>>, isSelected: Boolean, isSelectionMode: Boolean, isVirtualNode: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val rawPreviews = albumPreviews[album.id] ?: emptyList()
    ModernAlbumTile(album = album, previews = rawPreviews, isSelected = isSelected, isSelectionMode = isSelectionMode, isVirtual = isVirtualNode, onClick = onClick, onLongClick = onLongClick)
}

// ============================================================================
// 1. ALBUM SCREEN
// ============================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumScreen(viewModel: GalleryViewModel = hiltViewModel(), trashViewModel: TrashViewModel = hiltViewModel(), onViewerStateChanged: (Boolean) -> Unit = {}, actions: AlbumActions) {
    val context = LocalContext.current; val scope = rememberCoroutineScope(); val haptic = LocalHapticFeedback.current; val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState()); val vmAlbums by viewModel.albumsState.collectAsState(initial = emptyList()); val albumPreviews by viewModel.albumPreviewMap.collectAsState()
    val allAlbums by viewModel.allAlbumsState.collectAsState(initial = emptyList()); val sortOption by viewModel.albumSort.collectAsState(); var searchQuery by remember { mutableStateOf("") }; var isSearchActive by remember { mutableStateOf(false) }

    val viewerState by viewModel.viewerState.collectAsState()
    val rawMedia by viewModel.rawMedia.collectAsState()

    val openViewerState = viewerState as? GalleryViewerState.Open
    val viewerItemId = openViewerState?.mediaId

    var draggedIndex by remember { mutableIntStateOf(-1) }; var targetIndex by remember { mutableIntStateOf(-1) }

    LaunchedEffect(viewerState) { onViewerStateChanged(viewerState is GalleryViewerState.Open) }

    val virtualAlbums = remember(vmAlbums, searchQuery) { vmAlbums.filter { it.id.startsWith("virtual_") && albumMatchesQuery(it, searchQuery) }.sortedBy { when (it.id) { ID_RECENT -> 0; ID_FAVORITES -> 1; ID_DOWNLOADS -> 2; else -> 99 } } }
    val userAlbums = remember(vmAlbums, searchQuery, sortOption) { val filtered = vmAlbums.filter { !it.id.startsWith("virtual_") }.filter { albumMatchesQuery(it, searchQuery) }; if (sortOption == AlbumSort.Custom) { filtered } else { val baseCmp = compareByDescending<Album> { it.isPinned }; val finalCmp = when (sortOption.name) { "NameAsc" -> baseCmp.thenBy { it.name.lowercase() }; "NameDesc" -> baseCmp.thenByDescending { it.name.lowercase() }; "SizeDesc" -> baseCmp.thenByDescending { it.sizeBytes }; "CountDesc" -> baseCmp.thenByDescending { it.mediaCount }; else -> baseCmp }; filtered.sortedWith(finalCmp) } }
    val displayAlbums = remember(virtualAlbums, userAlbums) { virtualAlbums + userAlbums }

    val configuration = LocalConfiguration.current; val density = LocalDensity.current; val screenWidthDp = configuration.screenWidthDp.toFloat()
    val adaptiveCols = remember(screenWidthDp) { when { screenWidthDp >= 800f -> 8; screenWidthDp >= 600f -> 6; else -> 4 } }
    val prefs = remember { context.getSharedPreferences("gallery_prefs", Context.MODE_PRIVATE) }
    var columnCount by remember { mutableIntStateOf(prefs.getInt("gallery_grid_columns", adaptiveCols)) }
    val dragThreshold = remember(screenWidthDp, columnCount, density) { with(density) { ((screenWidthDp / columnCount).dp.toPx()) * 0.45f } }

    var isSelectionMode by remember { mutableStateOf(false) }; var selectedIds by remember { mutableStateOf(emptySet<String>()) }; var activeDialog by remember { mutableStateOf<AlbumUiDialog>(AlbumUiDialog.None) }; var showSelectionMenu by remember { mutableStateOf(false) }
    val intentSenderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result -> val g = result.resultCode == Activity.RESULT_OK; trashViewModel.onPermissionResultGlobal(g); if (!g) Toast.makeText(context, "Permission Denied", Toast.LENGTH_SHORT).show() }

    LaunchedEffect(trashViewModel) { trashViewModel.events.collect { event -> when (event) { is GalleryEvent.RequestPermission -> intentSenderLauncher.launch(IntentSenderRequest.Builder(event.intentSender).build()); is GalleryEvent.OperationSuccess -> { isSelectionMode = false; selectedIds = emptySet(); Toast.makeText(context, "Album moved to Trash", Toast.LENGTH_SHORT).show() }; is GalleryEvent.ShowToast -> Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show(); else -> {} } } }
    BackHandler(enabled = isSearchActive) { isSearchActive = false; searchQuery = "" }; BackHandler(enabled = isSelectionMode) { isSelectionMode = false; selectedIds = emptySet() }; BackHandler(enabled = activeDialog != AlbumUiDialog.None) { activeDialog = AlbumUiDialog.None }; BackHandler(enabled = viewerState is GalleryViewerState.Open) { viewModel.closeViewer() }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Scaffold(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection), containerColor = Color.Transparent, snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                if (isSelectionMode) { Column(Modifier.fillMaxWidth().statusBarsPadding().padding(vertical = 12.dp)) { Text("${selectedIds.size} selected", style = MaterialTheme.typography.titleLarge, modifier = Modifier.align(Alignment.CenterHorizontally)) } }
                else if (isSearchActive) SearchTopBar(query = searchQuery, onQueryChange = { searchQuery = it }, onClose = { isSearchActive = false; searchQuery = "" })
                else ModernAlbumTopBar(scrollBehavior = scrollBehavior, onSearchClick = { isSearchActive = true }, onMenuAction = { action -> when (action) { "grid" -> activeDialog = AlbumUiDialog.GridSize; "sort" -> activeDialog = AlbumUiDialog.Sort; "create" -> activeDialog = AlbumUiDialog.CreateAlbum; "trash" -> actions.onNavigateToTrash(); "hidden" -> activeDialog = AlbumUiDialog.HiddenAlbums; "lock_app" -> actions.onLockApp(); "settings" -> actions.onNavigateToSettings(); "duplicates" -> actions.onNavigateToDuplicates(); "scan" -> actions.onNavigateToScan(); "clearcache" -> clearImageCache(context) } })
            }
        ) { padding ->
            if (displayAlbums.isEmpty()) EmptyAlbumsOverlay(onCreateClick = { activeDialog = AlbumUiDialog.CreateAlbum })
            else {
                LazyVerticalGrid(columns = GridCells.Fixed(columnCount), modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (isSelectionMode) { item(span = { GridItemSpan(maxLineSpan) }) { val isAllSelected = selectedIds.size == displayAlbums.size && displayAlbums.isNotEmpty(); Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) { Column(Modifier.clip(CircleShape).clickable { if (isAllSelected) selectedIds = emptySet() else selectedIds = displayAlbums.map { it.id }.toSet() }.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(if (isAllSelected) Icons.Rounded.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null, tint = if (isAllSelected) MaterialTheme.colorScheme.primary else Color.Gray); Spacer(Modifier.height(4.dp)); Text("All", fontSize = 12.sp) } } } }
                    val onAlbumClick: (Album) -> Unit = { album -> if (isSelectionMode) selectedIds = if (selectedIds.contains(album.id)) selectedIds - album.id else selectedIds + album.id else actions.onAlbumClick(album) }
                    val onAlbumLongClick: (Album) -> Unit = { album -> haptic.performHapticFeedback(HapticFeedbackType.LongPress); isSelectionMode = true; selectedIds = if (selectedIds.contains(album.id)) selectedIds - album.id else selectedIds + album.id }

                    itemsIndexed(items = displayAlbums, key = { _, album -> album.id }) { index, album ->
                        var dragOffset by remember { mutableStateOf(Offset.Zero) }; val isBeingDragged = draggedIndex == index
                        Box(
                            modifier = Modifier
                                .animateItem()
                                .graphicsLayer { if (isBeingDragged) { scaleX = 1.05f; scaleY = 1.05f; alpha = 0.9f; translationX = dragOffset.x; translationY = dragOffset.y } }
                                .pointerInput(displayAlbums, searchQuery, sortOption) {
                                    if (!album.id.startsWith("virtual_") && sortOption == AlbumSort.Custom && searchQuery.isBlank()) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = { draggedIndex = index; targetIndex = index; dragOffset = Offset.Zero },
                                            onDrag = { change, dragAmount -> change.consume(); dragOffset += dragAmount; if (dragOffset.x > dragThreshold) { targetIndex = (targetIndex + 1).coerceAtMost(displayAlbums.lastIndex); dragOffset = Offset(0f, dragOffset.y) }; if (dragOffset.x < -dragThreshold) { targetIndex = (targetIndex - 1).coerceAtLeast(0); dragOffset = Offset(0f, dragOffset.y) }; if (dragOffset.y > dragThreshold) { targetIndex = (targetIndex + columnCount).coerceAtMost(displayAlbums.lastIndex); dragOffset = Offset(dragOffset.x, 0f) }; if (dragOffset.y < -dragThreshold) { targetIndex = (targetIndex - columnCount).coerceAtLeast(0); dragOffset = Offset(dragOffset.x, 0f) } },
                                            onDragEnd = { if (draggedIndex >= 0 && targetIndex >= 0 && draggedIndex != targetIndex) { val fromId = displayAlbums.getOrNull(draggedIndex)?.id; val toId = displayAlbums.getOrNull(targetIndex)?.id; if (fromId != null && toId != null && !toId.startsWith("virtual_")) viewModel.reorderAlbums(fromId, toId) }; draggedIndex = -1; targetIndex = -1 },
                                            onDragCancel = { draggedIndex = -1; targetIndex = -1 }
                                        )
                                    }
                                }
                        ) { OptimizedAlbumTile(album = album, albumPreviews = albumPreviews, isSelected = selectedIds.contains(album.id), isSelectionMode = isSelectionMode, isVirtualNode = album.id.startsWith("virtual_"), onClick = { onAlbumClick(album) }, onLongClick = { onAlbumLongClick(album) }) }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) { Spacer(modifier = Modifier.height(80.dp)) }
                }
            }
        }
        if (isSelectionMode) {
            Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f), tonalElevation = 12.dp, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 24.dp, start = 16.dp, end = 16.dp).navigationBarsPadding()) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    ActionItem(Icons.Outlined.Share, "Share") { shareMediaItems(context, rawMedia.filter { selectedIds.contains(it.bucketId) }); isSelectionMode = false; selectedIds = emptySet() }
                    val allPinned = displayAlbums.filter { selectedIds.contains(it.id) }.all { it.isPinned }
                    ActionItem(if (allPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin, if (allPinned) "Unpin" else "Pin") { displayAlbums.filter { selectedIds.contains(it.id) }.forEach { viewModel.toggleAlbumPin(it) }; isSelectionMode = false; selectedIds = emptySet() }
                    ActionItem(Icons.Outlined.Delete, "Delete", isDestructive = true) { activeDialog = AlbumUiDialog.Delete(displayAlbums.filter { selectedIds.contains(it.id) }) }
                    Box {
                        ActionItem(Icons.Default.MoreVert, "More") { showSelectionMenu = true }
                        DropdownMenu(expanded = showSelectionMenu, onDismissRequest = { showSelectionMenu = false }) {
                            DropdownMenuItem(text = { Text("Rename") }, onClick = { showSelectionMenu = false; if (selectedIds.size == 1) displayAlbums.find { it.id == selectedIds.first() }?.let { activeDialog = AlbumUiDialog.Rename(it) } else Toast.makeText(context, "Select only 1 album to rename", Toast.LENGTH_SHORT).show() })
                            DropdownMenuItem(text = { Text("Info") }, onClick = { showSelectionMenu = false; if (selectedIds.size == 1) displayAlbums.find { it.id == selectedIds.first() }?.let { activeDialog = AlbumUiDialog.Info(it) } else Toast.makeText(context, "Select only 1 album for info", Toast.LENGTH_SHORT).show() })
                            DropdownMenuItem(text = { Text("Move") }, onClick = { showSelectionMenu = false; if (selectedIds.size == 1) displayAlbums.find { it.id == selectedIds.first() }?.let { activeDialog = AlbumUiDialog.MoveCopy(it, true) } else Toast.makeText(context, "Select only 1 album to move", Toast.LENGTH_SHORT).show() })
                            DropdownMenuItem(text = { Text("Copy") }, onClick = { showSelectionMenu = false; if (selectedIds.size == 1) displayAlbums.find { it.id == selectedIds.first() }?.let { activeDialog = AlbumUiDialog.MoveCopy(it, false) } else Toast.makeText(context, "Select only 1 album to copy", Toast.LENGTH_SHORT).show() })
                        }
                    }
                }
            }
        }
    }
    when (val dialog = activeDialog) {
        is AlbumUiDialog.QuickAction -> ModalBottomSheet(onDismissRequest = { activeDialog = AlbumUiDialog.None }, containerColor = MaterialTheme.colorScheme.surface) { Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp).padding(bottom = 24.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Column { Text(dialog.album.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${dialog.album.mediaCount} items", color = Color.Gray, style = MaterialTheme.typography.bodySmall) } }; Spacer(Modifier.height(24.dp)); LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) { item { ActionItem(Icons.Outlined.Share, "Share") { shareMediaItems(context, rawMedia.filter { it.bucketId == dialog.album.id }); activeDialog = AlbumUiDialog.None } }; item { ActionItem(Icons.Outlined.Delete, "Delete", isDestructive = true) { activeDialog = AlbumUiDialog.Delete(listOf(dialog.album)) } }; item { ActionItem(Icons.Default.MoreVert, "More") { activeDialog = AlbumUiDialog.None; } } } } }
        is AlbumUiDialog.Info -> ModalBottomSheet(onDismissRequest = { activeDialog = AlbumUiDialog.None }, containerColor = MaterialTheme.colorScheme.surface) { Column(Modifier.padding(24.dp).padding(bottom = 24.dp)) { val albumItems = rawMedia.filter { it.bucketId == dialog.album.id }; val oldestItem = albumItems.minByOrNull { it.dateAdded }; val dateStr = oldestItem?.let { SimpleDateFormat("MMMM dd, yyyy 'at' hh:mm a", Locale.getDefault()).format(Date(it.dateAdded * 1000)) } ?: "Unknown"; val albumPath = oldestItem?.path?.let { File(it).parent } ?: "Unknown"; Text("Album Details", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Spacer(Modifier.height(24.dp)); MetadataRow(Icons.Outlined.Title, "Name", dialog.album.name); MetadataRow(Icons.Outlined.Storage, "Size", Formatter.formatFileSize(context, dialog.album.sizeBytes)); MetadataRow(Icons.Outlined.PhotoLibrary, "Items", dialog.album.mediaCount.toString()); MetadataRow(Icons.Outlined.Folder, "Path", albumPath); MetadataRow(Icons.Outlined.CalendarToday, "Created On", dateStr) } }
        is AlbumUiDialog.MoveCopy -> ModalBottomSheet(onDismissRequest = { activeDialog = AlbumUiDialog.None }, containerColor = MaterialTheme.colorScheme.surface) { Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) { Text(if (dialog.isMove) "Move To..." else "Copy To...", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)); LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f, fill = false), contentPadding = PaddingValues(bottom = 12.dp)) { item { ListItem(headlineContent = { Text("Create New Album", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) }, leadingContent = { Icon(Icons.Rounded.CreateNewFolder, null, tint = MaterialTheme.colorScheme.primary) }, modifier = Modifier.clickable { activeDialog = AlbumUiDialog.CreateAndMoveCopy(dialog.album, dialog.isMove) }) }; items(allAlbums.filter { !it.id.startsWith("virtual_") && it.id != dialog.album.id }.sortedBy { it.name.lowercase() }) { targetAlbum -> ListItem(headlineContent = { Text(targetAlbum.name, fontWeight = FontWeight.Medium) }, leadingContent = { Icon(Icons.Outlined.Folder, null) }, modifier = Modifier.clickable { viewModel.mergeAlbums(sourceAlbumIds = listOf(dialog.album.id), targetAlbumId = targetAlbum.id, mergeMode = if (dialog.isMove) MergeMode.MOVE_AND_DELETE else MergeMode.COPY); activeDialog = AlbumUiDialog.None }) } } } }
        is AlbumUiDialog.CreateAndMoveCopy -> ModernInputSheet(title = if (dialog.isMove) "New Album & Move" else "New Album & Copy", initial = "${dialog.album.name} Copy", onDismiss = { activeDialog = AlbumUiDialog.None }, onConfirm = { newName -> val mediaIds = rawMedia.filter { it.bucketId == dialog.album.id }.map { it.id }; if (dialog.isMove) { viewModel.createAndMove(mediaIds, newName) } else { viewModel.createAndCopy(mediaIds, newName) }; activeDialog = AlbumUiDialog.None; scope.launch { delay(800); viewModel.forceSync() } })
        is AlbumUiDialog.Rename -> ModernInputSheet("Rename Album", dialog.album.name, onDismiss = { activeDialog = AlbumUiDialog.None }, onConfirm = { viewModel.renameAlbum(dialog.album, it); activeDialog = AlbumUiDialog.None })
        is AlbumUiDialog.Delete -> ModernSmartDeleteSheet(dialog.albums.size, onDismiss = { activeDialog = AlbumUiDialog.None }, onDeleteAll = { trashViewModel.confirmPendingAlbumTrash(dialog.albums, rawMedia); activeDialog = AlbumUiDialog.None; scope.launch { delay(500); viewModel.forceSync() } })
        is AlbumUiDialog.CreateAlbum -> ModernCreateAlbumSheet(onDismiss = { activeDialog = AlbumUiDialog.None }, onCreate = { name, sd -> viewModel.createAlbum(name, sd); activeDialog = AlbumUiDialog.None; scope.launch { delay(500); viewModel.forceSync() } })
        is AlbumUiDialog.Sort -> ModernAlbumSortSheet(sortOption, onDismiss = { activeDialog = AlbumUiDialog.None }, onSortSelected = { viewModel.updateAlbumSort(it); activeDialog = AlbumUiDialog.None })
        is AlbumUiDialog.GridSize -> ModernGridSheet(columnCount, 8, onDismiss = { activeDialog = AlbumUiDialog.None }, onUpdate = { columnCount = it; prefs.edit().putInt("gallery_grid_columns", it).apply(); activeDialog = AlbumUiDialog.None })
        is AlbumUiDialog.HiddenAlbums -> ModalBottomSheet(onDismissRequest = { activeDialog = AlbumUiDialog.None }, containerColor = MaterialTheme.colorScheme.surface) { val hiddenAlbums by viewModel.hiddenAlbums.collectAsState(); val filterAlbums = allAlbums.filter { !it.id.startsWith("virtual_") }; Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) { Text("Hide or Unhide", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)); LazyColumn(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 12.dp)) { items(filterAlbums, key = { it.id }) { album -> Row(modifier = Modifier.fillMaxWidth().clickable { viewModel.toggleHiddenAlbum(album.id) }.padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Column(modifier = Modifier.weight(1f)) { Text(album.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium); Text("${album.mediaCount} items", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Switch(checked = hiddenAlbums.contains(album.id), onCheckedChange = { viewModel.toggleHiddenAlbum(album.id) }) } } } } }
        AlbumUiDialog.None -> {}
    }
}

// ============================================================================
// 2. ALBUM DETAIL SCREEN
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

    var activeDialog by remember { mutableStateOf<DetailUiDialog>(DetailUiDialog.None) }; var metadataItemToShow by remember { mutableStateOf<MediaItem?>(null) }
    val intentSenderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result -> val g = result.resultCode == Activity.RESULT_OK; trashViewModel.onPermissionResultGlobal(g); if (!g) Toast.makeText(context, "Permission Denied", Toast.LENGTH_SHORT).show() }

    var localSearchQuery by rememberSaveable { mutableStateOf("") }; var currentPhotoSort by rememberSaveable { mutableStateOf(PhotoSort.DateDesc) }

    LaunchedEffect(trashViewModel) { trashViewModel.events.collect { event -> when (event) { is GalleryEvent.RequestPermission -> intentSenderLauncher.launch(IntentSenderRequest.Builder(event.intentSender).build()); is GalleryEvent.OperationSuccess -> { Toast.makeText(context, "Moved to Trash", Toast.LENGTH_SHORT).show(); viewModel.forceSync() }; is GalleryEvent.ShowToast -> Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show(); else -> {} } } }
    LaunchedEffect(viewerState) { onViewerStateChanged(viewerState is GalleryViewerState.Open) }

    val album = remember(vmAlbums, albumId) { when (albumId) { ID_RECENT -> Album(ID_RECENT, "Recent", Uri.EMPTY, 0, 0L, isPinned = true); ID_FAVORITES -> Album(ID_FAVORITES, "Favorites", Uri.EMPTY, 0, 0L, isPinned = true); ID_VIDEOS -> Album(ID_VIDEOS, "Videos", Uri.EMPTY, 0, 0L, isPinned = true); ID_SCREENSHOTS -> Album(ID_SCREENSHOTS, "Screenshots", Uri.EMPTY, 0, 0L, isPinned = true); ID_WHATSAPP -> Album(ID_WHATSAPP, "WhatsApp", Uri.EMPTY, 0, 0L, isPinned = true); ID_INSTAGRAM -> Album(ID_INSTAGRAM, "Instagram", Uri.EMPTY, 0, 0L, isPinned = true); ID_DOWNLOADS -> Album(ID_DOWNLOADS, "Downloads", Uri.EMPTY, 0, 0L, isPinned = true); ID_HIDDEN -> Album(ID_HIDDEN, "Hidden", Uri.EMPTY, 0, 0L, isPinned = true); else -> vmAlbums.find { it.id == albumId } } }
    val isVirtual = albumId.startsWith("virtual_"); var isSelectionMode by remember { mutableStateOf(false) }; var selectedIds by remember { mutableStateOf(emptySet<Long>()) }; var selectedSize by remember { mutableLongStateOf(0L) }; var showMediaSelectionMenu by remember { mutableStateOf(false) }

    fun toggleSelection(item: MediaItem) { if (selectedIds.contains(item.id)) { selectedIds = selectedIds - item.id; selectedSize = maxOf(0L, selectedSize - item.size) } else { selectedIds = (selectedIds + item.id).takeIf { it.size < 5000 } ?: selectedIds; selectedSize += item.size } }

    var showMenu by remember { mutableStateOf(false) }; var mediaFilter by remember { mutableStateOf(AlbumMediaFilter.ALL) }; var showRenameSheet by remember { mutableStateOf(false) }

    val configuration = LocalConfiguration.current; val density = LocalDensity.current; val screenWidthDp = configuration.screenWidthDp.toFloat(); val screenWidthPx = with(density) { configuration.screenWidthDp.dp.roundToPx() }
    val adaptiveCols = remember(screenWidthDp) { when { screenWidthDp >= 800f -> 8; screenWidthDp >= 600f -> 6; else -> 4 } }
    val prefs = remember { context.getSharedPreferences("gallery_prefs", Context.MODE_PRIVATE) }
    var detailColumns by remember { mutableIntStateOf(prefs.getInt("gallery_grid_columns", adaptiveCols)) }
    val isSpecialAlbum = albumId == ID_FAVORITES || albumId == ID_RECENT; val actualColumns = if (isSpecialAlbum) detailColumns else detailColumns
    val dynamicThumbSize = remember(actualColumns, screenWidthPx) { maxOf(160, screenWidthPx / actualColumns) }; val isScrolling = gridState.isScrollInProgress

    BackHandler(enabled = localSearchQuery.isNotEmpty()) { localSearchQuery = "" }; BackHandler(enabled = isSelectionMode) { isSelectionMode = false; selectedIds = emptySet(); selectedSize = 0L }; BackHandler(enabled = activeDialog != DetailUiDialog.None) { activeDialog = DetailUiDialog.None }; BackHandler(enabled = metadataItemToShow != null) { metadataItemToShow = null }; BackHandler(enabled = viewerState is GalleryViewerState.Open) { viewModel.closeViewer() }

    val baseMedia = remember(rawMedia, albumId, favoriteIds) { rawMedia.filter { item -> when (albumId) { ID_FAVORITES -> favoriteIds.contains(item.id); ID_VIDEOS -> item.isVideo; ID_SCREENSHOTS -> item.path.contains("Screenshot", true) || item.path.contains("Screenshots", true); ID_DOWNLOADS -> item.path.contains("Download", true); ID_WHATSAPP -> item.path.contains("WhatsApp", true); ID_INSTAGRAM -> item.path.contains("Instagram", true); ID_RECENT -> true; else -> item.bucketId == albumId } } }
    val filteredMedia = remember(baseMedia, mediaFilter, localSearchQuery, currentPhotoSort) { val base = when (mediaFilter) { AlbumMediaFilter.ALL -> baseMedia; AlbumMediaFilter.PHOTOS -> baseMedia.filter { !it.isVideo }; AlbumMediaFilter.VIDEOS -> baseMedia.filter { it.isVideo } }; val searched = if (localSearchQuery.isBlank()) base else { val q = localSearchQuery.trim().lowercase(); base.filter { it.name.lowercase().contains(q) || getSmartName(it).lowercase().contains(q) } }; when (currentPhotoSort) { PhotoSort.DateDesc -> searched.sortedByDescending { it.dateAdded }; PhotoSort.DateAsc -> searched.sortedBy { it.dateAdded }; PhotoSort.NameAsc -> searched.sortedBy { it.name.lowercase() }; PhotoSort.NameDesc -> searched.sortedByDescending { it.name.lowercase() }; PhotoSort.SizeDesc -> searched.sortedByDescending { it.size } } }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Scaffold(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection), containerColor = Color.Transparent, snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                if (isSelectionMode) { Column(Modifier.fillMaxWidth().statusBarsPadding().padding(vertical = 12.dp)) { Text("${selectedIds.size} selected", style = MaterialTheme.typography.titleLarge, modifier = Modifier.align(Alignment.CenterHorizontally)) } } else {
                    TopAppBar(
                        title = { Text(album?.name ?: "Album", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold) },
                        navigationIcon = { IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                        actions = {
                            Box {
                                IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, "More") }
                                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, modifier = Modifier.clip(RoundedCornerShape(12.dp))) {
                                    DropdownMenuItem(text = { Text("Select items") }, onClick = { isSelectionMode = true; showMenu = false }, leadingIcon = { Icon(Icons.Outlined.Checklist, null) })
                                    if (!isVirtual) { DropdownMenuItem(text = { Text("Add Photos") }, onClick = { actions.onAddMediaToAlbum?.invoke(albumId); showMenu = false }, leadingIcon = { Icon(Icons.Rounded.AddPhotoAlternate, null) }) }
                                    DropdownMenuItem(text = { Text("Sort Media") }, onClick = { activeDialog = DetailUiDialog.Sort; showMenu = false }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, null) })
                                    DropdownMenuItem(text = { Text("Grid Size") }, onClick = { activeDialog = DetailUiDialog.GridSize; showMenu = false }, leadingIcon = { Icon(Icons.Default.Grid4x4, null) })
                                    if (!isVirtual && album != null) { DropdownMenuItem(text = { Text(if (album.isPinned) "Unpin Album" else "Pin Album") }, onClick = { viewModel.toggleAlbumPin(album); showMenu = false }, leadingIcon = { Icon(if (album.isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin, null) }); DropdownMenuItem(text = { Text("Rename") }, onClick = { showRenameSheet = true; showMenu = false }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }); DropdownMenuItem(text = { Text("Delete Album", color = MaterialTheme.colorScheme.error) }, onClick = { activeDialog = DetailUiDialog.DeleteAlbum; showMenu = false }, leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) }) }
                                    DropdownMenuItem(text = { Text("Lock App") }, onClick = { actions.onLockApp(); showMenu = false }, leadingIcon = { Icon(Icons.Outlined.Lock, null) })
                                }
                            }
                        }, scrollBehavior = scrollBehavior, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = MaterialTheme.colorScheme.surface)
                    )
                }
            }
        ) { padding ->
            if (filteredMedia.isEmpty() && localSearchQuery.isBlank()) Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Outlined.ImageNotSupported, null, Modifier.size(72.dp), Color.LightGray); Spacer(Modifier.height(16.dp)); Text("No photos here", color = Color.Gray, style = MaterialTheme.typography.titleMedium); if (!isVirtual) { Spacer(Modifier.height(24.dp)); FilledTonalButton(onClick = { actions.onAddMediaToAlbum?.invoke(albumId) }, shape = RoundedCornerShape(16.dp)) { Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("Add Photos") } } } }
            else Column(Modifier.padding(padding)) {
                OutlinedTextField(value = localSearchQuery, onValueChange = { localSearchQuery = it }, placeholder = { Text("Search photos inside album...", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), fontSize = 14.sp) }, leadingIcon = { Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) }, trailingIcon = { if (localSearchQuery.isNotEmpty()) { IconButton(onClick = { localSearchQuery = "" }, modifier = Modifier.size(28.dp)) { Icon(Icons.Rounded.Close, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp)) } } }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).height(50.dp), shape = RoundedCornerShape(25.dp), singleLine = true, textStyle = LocalTextStyle.current.copy(fontSize = 14.sp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent, focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer))
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(selected = mediaFilter == AlbumMediaFilter.ALL, onClick = { mediaFilter = AlbumMediaFilter.ALL }, label = { Text("All") }); FilterChip(selected = mediaFilter == AlbumMediaFilter.PHOTOS, onClick = { mediaFilter = AlbumMediaFilter.PHOTOS }, label = { Text("Photos") }); FilterChip(selected = mediaFilter == AlbumMediaFilter.VIDEOS, onClick = { mediaFilter = AlbumMediaFilter.VIDEOS }, label = { Text("Videos") }) }; Surface(onClick = { activeDialog = DetailUiDialog.Sort }, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))) { Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.AutoMirrored.Filled.Sort, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(when(currentPhotoSort) { PhotoSort.DateDesc -> "Newest"; PhotoSort.DateAsc -> "Oldest"; PhotoSort.NameAsc -> "A-Z"; PhotoSort.NameDesc -> "Z-A"; PhotoSort.SizeDesc -> "Size" }, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface) } } }
                if (filteredMedia.isEmpty()) Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) { Text("No matching items found", color = Color.Gray, fontSize = 15.sp) }
                else LazyVerticalGrid(state = gridState, columns = GridCells.Fixed(actualColumns), modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 3.dp, end = 3.dp, top = 8.dp, bottom = 90.dp), verticalArrangement = Arrangement.spacedBy(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (isSelectionMode) { item(span = { GridItemSpan(actualColumns) }) { val isAllSelected = selectedIds.size == filteredMedia.size && filteredMedia.isNotEmpty(); Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) { Column(Modifier.clip(CircleShape).clickable { if (isAllSelected) { selectedIds = emptySet(); selectedSize = 0L } else { selectedIds = filteredMedia.map { it.id }.toSet(); selectedSize = filteredMedia.sumOf { it.size } } }.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(if (isAllSelected) Icons.Rounded.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null, tint = if (isAllSelected) MaterialTheme.colorScheme.primary else Color.Gray); Spacer(Modifier.height(4.dp)); Text("All", fontSize = 12.sp) } } } }
                    val onMediaClick: (MediaItem) -> Unit = { currentItem ->
                        if (isSelectionMode) { toggleSelection(currentItem); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                        else { viewModel.openViewer(currentItem.id) }
                    }
                    val onMediaLongClick: (MediaItem) -> Unit = { currentItem -> if (!isSelectionMode) { isSelectionMode = true; haptic.performHapticFeedback(HapticFeedbackType.LongPress) }; toggleSelection(currentItem) }
                    items(count = filteredMedia.size, key = { i -> filteredMedia[i].id }, contentType = { "media" }) { index -> val currentItem = filteredMedia[index]; val modifierWithAnim = if (isScrolling) Modifier else Modifier.animateItem(); ModernMediaGridTile(modifier = modifierWithAnim, item = currentItem, thumbSize = dynamicThumbSize, isSelected = selectedIds.contains(currentItem.id), isSelectionMode = isSelectionMode, isScrolling = isScrolling, onClick = { onMediaClick(currentItem) }, onLongClick = { onMediaLongClick(currentItem) }, onToggleFavorite = { viewModel.toggleFavorite(currentItem.id) }) }
                }
            }
        }
        if (isSelectionMode) {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f), tonalElevation = 8.dp, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 24.dp, start = 16.dp, end = 16.dp).navigationBarsPadding()) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    ActionItem(Icons.Outlined.Share, "Share") { shareMediaItems(context, selectedIds.mapNotNull { mediaMap[it] }) }; ActionItem(Icons.Outlined.Delete, "Delete", true) { activeDialog = DetailUiDialog.Delete(selectedIds.toList()) };
                    Box {
                        ActionItem(Icons.Default.MoreVert, "More") { showMediaSelectionMenu = true };
                        DropdownMenu(expanded = showMediaSelectionMenu, onDismissRequest = { showMediaSelectionMenu = false }) {
                            DropdownMenuItem(text = { Text("Copy to album") }, onClick = { showMediaSelectionMenu = false; actions.onNavigateToMoveCopy("COPY", selectedIds.joinToString(","), albumId); isSelectionMode = false; selectedIds = emptySet(); selectedSize = 0L });
                            DropdownMenuItem(text = { Text("Move to album") }, onClick = { showMediaSelectionMenu = false; actions.onNavigateToMoveCopy("MOVE", selectedIds.joinToString(","), albumId); isSelectionMode = false; selectedIds = emptySet(); selectedSize = 0L });
                            DropdownMenuItem(text = { Text("Add to shared album") }, onClick = { showMediaSelectionMenu = false; Toast.makeText(context, "Shared albums available in cloud sync", Toast.LENGTH_SHORT).show() });
                            DropdownMenuItem(text = { Text("Set as wallpaper") }, onClick = { showMediaSelectionMenu = false; if (selectedIds.size == 1) mediaMap[selectedIds.first()]?.let { actions.onNavigateToWallpaper(it.uri.toString(), it.id); isSelectionMode = false; selectedIds = emptySet(); selectedSize = 0L } else Toast.makeText(context, "Select only 1 item for wallpaper", Toast.LENGTH_SHORT).show() });
                            DropdownMenuItem(text = { Text("Details") }, onClick = { showMediaSelectionMenu = false; if (selectedIds.size == 1) metadataItemToShow = mediaMap[selectedIds.first()] else Toast.makeText(context, "Select only 1 item for details", Toast.LENGTH_SHORT).show() });
                            if (albumId == ID_HIDDEN) { DropdownMenuItem(text = { Text("Unhide") }, onClick = { showMediaSelectionMenu = false; viewModel.unhideMedia(selectedIds.toList()); Toast.makeText(context, "Items restored", Toast.LENGTH_SHORT).show(); isSelectionMode = false; selectedIds = emptySet(); selectedSize = 0L }) } else { DropdownMenuItem(text = { Text("Hide") }, onClick = { showMediaSelectionMenu = false; viewModel.hideItems(selectedIds.toList()); Toast.makeText(context, "${selectedIds.size} items hidden", Toast.LENGTH_SHORT).show(); isSelectionMode = false; selectedIds = emptySet(); selectedSize = 0L }) }
                        }
                    }
                }
            }
        }
    }
    when (val dialog = activeDialog) {
        is DetailUiDialog.QuickAction -> { val item = dialog.item; val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true); var showMoreExpanded by remember { mutableStateOf(false) }; ModalBottomSheet(onDismissRequest = { activeDialog = DetailUiDialog.None }, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surface) { Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp).padding(bottom = 24.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Column { Text(getSmartName(item), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(getFolderName(item.path), color = Color.Gray, style = MaterialTheme.typography.bodySmall) } }; Spacer(Modifier.height(24.dp)); LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) { item { ActionItem(Icons.Outlined.Edit, "Edit") { activeDialog = DetailUiDialog.None; if (item.isVideo) actions.onNavigateToVideoEditor(item.uri.toString(), item.id) else actions.onNavigateToPhotoEditor(item.uri.toString(), item.id) } }; item { ActionItem(Icons.Outlined.Share, "Share") { shareMediaItems(context, listOf(item)); activeDialog = DetailUiDialog.None } }; item { ActionItem(Icons.Outlined.Delete, "Delete", isDestructive = true) { activeDialog = DetailUiDialog.Delete(listOf(item.id)) } }; item { ActionItem(Icons.Default.MoreVert, "More") { showMoreExpanded = true } } }; AnimatedVisibility(visible = showMoreExpanded) { Column(Modifier.padding(top = 16.dp)) { HorizontalDivider(Modifier.padding(vertical = 8.dp)); ListItem(headlineContent = { Text("Details", fontWeight = FontWeight.SemiBold) }, leadingContent = { Icon(Icons.Outlined.Info, null) }, modifier = Modifier.clickable { activeDialog = DetailUiDialog.None; metadataItemToShow = item }); ListItem(headlineContent = { Text("Move to Album", fontWeight = FontWeight.SemiBold) }, leadingContent = { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, null) }, modifier = Modifier.clickable { activeDialog = DetailUiDialog.None; actions.onNavigateToMoveCopy("MOVE", item.id.toString(), albumId) }); ListItem(headlineContent = { Text("Copy to Album", fontWeight = FontWeight.SemiBold) }, leadingContent = { Icon(Icons.Outlined.FileCopy, null) }, modifier = Modifier.clickable { activeDialog = DetailUiDialog.None; actions.onNavigateToMoveCopy("COPY", item.id.toString(), albumId) }); if (!item.isVideo) { ListItem(headlineContent = { Text("Set as Wallpaper", fontWeight = FontWeight.SemiBold) }, leadingContent = { Icon(Icons.Outlined.Wallpaper, null) }, modifier = Modifier.clickable { activeDialog = DetailUiDialog.None; actions.onNavigateToWallpaper(item.uri.toString(), item.id) }) } } } } } }
        is DetailUiDialog.DeleteAlbum -> AlertDialog(onDismissRequest = { activeDialog = DetailUiDialog.None }, icon = { Icon(Icons.Outlined.DeleteForever, null, tint = MaterialTheme.colorScheme.error) }, title = { Text("Delete Album?") }, text = { Text("This will delete the manual album placeholder. Any physical media stored within this folder on your device will remain intact.") }, confirmButton = { Button(colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), onClick = { actions.onDeleteAlbum?.invoke(albumId); activeDialog = DetailUiDialog.None; actions.onBack() }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { activeDialog = DetailUiDialog.None }) { Text("Cancel") } })
        is DetailUiDialog.Delete -> AlertDialog(onDismissRequest = { activeDialog = DetailUiDialog.None }, icon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) }, title = { Text("Move to Trash?") }, text = { Text("Items will be moved to Trash. They can be recovered within 30 days.") }, confirmButton = { Button(colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), onClick = { val itemsToTrash = rawMedia.filter { dialog.mediaIds.contains(it.id) }; trashViewModel.confirmPendingGalleryTrash(itemsToTrash); activeDialog = DetailUiDialog.None; isSelectionMode = false; selectedIds = emptySet(); selectedSize = 0L; viewModel.closeViewer() }) { Text("Move to Trash") } }, dismissButton = { TextButton(onClick = { activeDialog = DetailUiDialog.None }) { Text("Cancel") } })
        is DetailUiDialog.GridSize -> ModernGridSheet(detailColumns, 8, onDismiss = { activeDialog = DetailUiDialog.None }, onUpdate = { detailColumns = it; prefs.edit().putInt("gallery_grid_columns", it).apply(); activeDialog = DetailUiDialog.None })
        is DetailUiDialog.Sort -> ModernMediaSortSheet(activeSort = currentPhotoSort, onDismiss = { activeDialog = DetailUiDialog.None }, onSortSelected = { currentPhotoSort = it; activeDialog = DetailUiDialog.None })
        DetailUiDialog.None -> {}
    }

    if (showRenameSheet && album != null) ModernInputSheet("Rename Album", album.name, onDismiss = { showRenameSheet = false }, onConfirm = { viewModel.renameAlbum(album, it); showRenameSheet = false })
    if (metadataItemToShow != null) MediaMetadataSheet(item = metadataItemToShow!!) { metadataItemToShow = null }

    val stableMediaList = if (viewerState is GalleryViewerState.Open) filteredMedia else emptyList()

    if (viewerState is GalleryViewerState.Open && stableMediaList.isNotEmpty()) {
        val stableStartIndex = stableMediaList.indexOfFirst { it.id == viewerItemId }.coerceAtLeast(0)

        key(viewerItemId, stableMediaList.size) {
            AnimatedVisibility(visible = true, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
                FullscreenMediaPager(
                    initialIndex = stableStartIndex,
                    mediaList = stableMediaList,
                    mediaMap = mediaMap,
                    favoriteIds = favoriteIds,
                    sharedPlayer = viewModel.getPlayer(),
                    onPageChanged = {}, onClose = { viewModel.closeViewer() },
                    onToggleFavorite = { id: Long -> viewModel.toggleFavorite(id); if (albumId == ID_FAVORITES) { scope.launch { delay(300); viewModel.closeViewer() } } },
                    onEdit = { item: MediaItem -> viewModel.closeViewer(); if (item.isVideo) actions.onNavigateToVideoEditor(item.uri.toString(), item.id) else actions.onNavigateToPhotoEditor(item.uri.toString(), item.id) },
                    onPlayVideo = { uri, playlist -> actions.onNavigateToVideoPlayer(uri, playlist) },
                    onDelete = { item: MediaItem -> activeDialog = DetailUiDialog.Delete(listOf(item.id)) },
                    onMove = { item -> viewModel.closeViewer(); actions.onNavigateToMoveCopy("MOVE", item.id.toString(), albumId) },
                    onCopy = { item -> viewModel.closeViewer(); actions.onNavigateToMoveCopy("COPY", item.id.toString(), albumId) },
                    onWallpaper = { item -> viewModel.closeViewer(); actions.onNavigateToWallpaper(item.uri.toString(), item.id) }
                )
            }
        }
    }
}

// ============================================================================
// SHARED COMPONENTS (Viewer, Tiles, Dialogs, etc.)
// ============================================================================

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FullscreenMediaPager(
    initialIndex: Int,
    mediaList: List<MediaItem>,
    mediaMap: Map<Long, MediaItem>,
    favoriteIds: List<Long>,
    sharedPlayer: Player,
    onPageChanged: (MediaItem) -> Unit, onClose: () -> Unit, onToggleFavorite: (Long) -> Unit,
    onEdit: (MediaItem) -> Unit, onPlayVideo: (String, List<String>) -> Unit,
    onDelete: (MediaItem) -> Unit, onMove: (MediaItem) -> Unit, onCopy: (MediaItem) -> Unit, onWallpaper: (MediaItem) -> Unit
) {
    if (mediaList.isEmpty()) return
    val context = LocalContext.current; val view = LocalView.current
    val safeInitialPage = initialIndex.coerceIn(0, maxOf(mediaList.lastIndex, 0))
    val pagerState = rememberPagerState(initialPage = safeInitialPage, pageCount = { mediaList.size })
    var showControls by remember { mutableStateOf(true) }
    var showMetadataSheet by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    val activity = remember { context.findActivity() }
    val zoomedPages = remember { mutableStateMapOf<Int, Boolean>() }
    val isCurrentPageZoomed = zoomedPages.getOrDefault(pagerState.currentPage, false)

    LaunchedEffect(initialIndex, mediaList.size) {
        if (pagerState.currentPage != initialIndex && initialIndex in mediaList.indices) {
            pagerState.scrollToPage(initialIndex)
        }
    }

    LaunchedEffect(pagerState.currentPage) { if (zoomedPages.size > 20) zoomedPages.clear(); showControls = true; mediaList.getOrNull(pagerState.currentPage)?.let(onPageChanged) }
    DisposableEffect(activity) { val window = activity?.window; if (window != null) { val controller = WindowCompat.getInsetsController(window, view); controller.hide(WindowInsetsCompat.Type.systemBars()); controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }; onDispose { window?.let { WindowCompat.getInsetsController(it, view).show(WindowInsetsCompat.Type.systemBars()) } } }
    BackHandler(enabled = !showControls) { showControls = true }
    BackHandler(enabled = showControls) { onClose() }

    val safePage = pagerState.currentPage.coerceIn(0, maxOf(mediaList.lastIndex, 0))
    val currentPageId = mediaList.getOrNull(safePage)?.id
    LaunchedEffect(currentPageId) { if (currentPageId == null && mediaList.isNotEmpty()) { onClose() } }
    val liveCurrentItem = remember(currentPageId, mediaMap, mediaList) { currentPageId?.let { id -> mediaMap[id] ?: mediaList.find { it.id == id } } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pagerState, pageSpacing = 18.dp, userScrollEnabled = !isCurrentPageZoomed, key = { mediaList[it].id }, modifier = Modifier.fillMaxSize()) { page ->
            val item = mediaList[page]
            if (item.isVideo) {
                VideoPreviewPage(
                    item = item,
                    isCurrentPage = pagerState.currentPage == page,
                    showControls = showControls,
                    sharedPlayer = sharedPlayer,
                    onTap = { showControls = !showControls },
                    onPlay = {
                        val playlist = mediaList.filter { it.isVideo }.map { it.uri.toString() }
                        onPlayVideo(item.uri.toString(), playlist)
                    }
                )
            } else {
                ZoomableImagePage(item = item, onTap = { showControls = !showControls }, onDismiss = onClose, onZoomChanged = { isZoomed -> zoomedPages[page] = isZoomed }, onControlsVisibilityChange = { visible -> showControls = visible })
            }
        }

        val topGradientBrush = remember { Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)) }
        val bottomGradientBrush = remember { Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f))) }

        AnimatedVisibility(visible = showControls, modifier = Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) { Box(Modifier.fillMaxWidth().background(topGradientBrush).statusBarsPadding().padding(horizontal = 18.dp, vertical = 16.dp)) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { FilledIconButton(onClick = onClose, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White.copy(alpha = 0.16f))) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }; Spacer(Modifier.size(48.dp)) } } }
        AnimatedVisibility(visible = showControls, modifier = Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
            val currentItem = liveCurrentItem ?: return@AnimatedVisibility
            Column(Modifier.fillMaxWidth().background(bottomGradientBrush).navigationBarsPadding().padding(bottom = 18.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    PremiumViewerAction(if (favoriteIds.contains(currentItem.id)) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, if (favoriteIds.contains(currentItem.id)) "Unfavorite" else "Favorite", if (favoriteIds.contains(currentItem.id)) Color.Red else Color.White) { onToggleFavorite(currentItem.id) }
                    if (!currentItem.isDocument) {
                        PremiumViewerAction(Icons.Outlined.Edit, "Edit") { onEdit(currentItem) }
                    }
                    PremiumViewerAction(Icons.Outlined.Share, "Share") { val mimeType = if (currentItem.isVideo) "video/*" else "image/*"; context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = mimeType; putExtra(Intent.EXTRA_STREAM, currentItem.uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "Share Media")) }
                    PremiumViewerAction(Icons.Outlined.Delete, "Delete", Color.Red) { onDelete(currentItem) }
                    PremiumViewerAction(Icons.Default.MoreVert, "More") { showMoreMenu = true }
                }
            }
        }
    }

    if (showMetadataSheet) { liveCurrentItem?.let { MediaMetadataSheet(it) { showMetadataSheet = false } } }
    if (showMoreMenu) {
        val currentItem = liveCurrentItem ?: return
        ModalBottomSheet(onDismissRequest = { showMoreMenu = false }, containerColor = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(bottom = 32.dp)) {
                ListItem(headlineContent = { Text("Details", fontWeight = FontWeight.SemiBold) }, leadingContent = { Icon(Icons.Outlined.Info, null) }, modifier = Modifier.clickable { showMoreMenu = false; showMetadataSheet = true })
                ListItem(headlineContent = { Text("Move to Album", fontWeight = FontWeight.SemiBold) }, leadingContent = { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, null) }, modifier = Modifier.clickable { showMoreMenu = false; onMove(currentItem) })
                ListItem(headlineContent = { Text("Copy to Album", fontWeight = FontWeight.SemiBold) }, leadingContent = { Icon(Icons.Outlined.FileCopy, null) }, modifier = Modifier.clickable { showMoreMenu = false; onCopy(currentItem) })
                if (currentItem.isVideo) {
                    ListItem(headlineContent = { Text("Open In", fontWeight = FontWeight.SemiBold) }, leadingContent = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, null) }, modifier = Modifier.clickable { showMoreMenu = false; context.startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(currentItem.uri, "video/*"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }) })
                } else {
                    ListItem(headlineContent = { Text("Set as Wallpaper", fontWeight = FontWeight.SemiBold) }, leadingContent = { Icon(Icons.Outlined.Wallpaper, null) }, modifier = Modifier.clickable { showMoreMenu = false; onWallpaper(currentItem) })
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
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Box(Modifier.fillMaxWidth().padding(bottom = 90.dp)) {
                Surface(
                    modifier = Modifier.align(Alignment.Center).clickable { sharedPlayer.pause(); onPlay() },
                    shape = RoundedCornerShape(50.dp),
                    color = Color.Black.copy(alpha = 0.55f)
                ) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Play video", color = Color.White, fontSize = 14.sp)
                    }
                }
                FilledIconButton(
                    onClick = { muted = !muted },
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 20.dp).size(36.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.Black.copy(alpha = 0.55f))
                ) {
                    Icon(if (muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp, null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
fun PremiumViewerAction(icon: ImageVector, label: String, tint: Color = Color.White, onClick: () -> Unit) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Surface(modifier = Modifier.size(58.dp).clip(CircleShape).clickable(onClick = onClick), shape = CircleShape, color = Color.White.copy(alpha = 0.12f), border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))) { Box(contentAlignment = Alignment.Center) { Icon(icon, label, tint = tint, modifier = Modifier.size(24.dp)) } }; Spacer(modifier = Modifier.height(8.dp)); Text(label, color = tint.copy(alpha = 0.95f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold) } }

@Composable
fun ZoomableImagePage(item: MediaItem, onTap: () -> Unit, onDismiss: () -> Unit, onZoomChanged: (Boolean) -> Unit, onControlsVisibilityChange: (Boolean) -> Unit) {
    val context = LocalContext.current; val density = LocalDensity.current; val haptic = LocalHapticFeedback.current; val scope = rememberCoroutineScope(); val configuration = LocalConfiguration.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.roundToPx() }; val screenHeightPx = with(density) { configuration.screenHeightDp.dp.roundToPx() }; val dismissThreshold = remember(configuration.screenHeightDp, density) { with(density) { configuration.screenHeightDp.dp.toPx() * 0.25f } }
    val scale = remember { Animatable(1f) }; var offset by remember { mutableStateOf(Offset.Zero) }; var dragOffsetY by remember { mutableFloatStateOf(0f) }; var backgroundAlpha by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(scale.value) { onZoomChanged(scale.value > 1.05f) }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = backgroundAlpha)).offset { IntOffset(0, dragOffsetY.roundToInt()) }.graphicsLayer { val dismissScale = 1f - (abs(dragOffsetY) / 2200f); scaleX = scale.value * dismissScale; scaleY = scaleX; alpha = (1f - (abs(dragOffsetY) / 850f)).coerceIn(0f, 1f); translationX = offset.x; translationY = offset.y }
            .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }, onDoubleTap = { scope.launch { scale.animateTo(if (scale.value > 1f) 1f else 2.5f, spring(stiffness = Spring.StiffnessLow)) }; offset = Offset.Zero }, onLongPress = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }) }
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false); do { val event = awaitPointerEvent(); val zoom = event.calculateZoom(); val pan = event.calculatePan(); if (abs(zoom - 1f) > 0.005f) { scope.launch { scale.snapTo((scale.value * zoom).coerceIn(1f, 4f)) } }; if (scale.value > 1.05f) { event.changes.forEach { if (it.positionChanged()) it.consume() }; val maxX = (size.width * (scale.value - 1)) / 2f; val maxY = (size.height * (scale.value - 1)) / 2f; offset = Offset(x = (offset.x + pan.x).coerceIn(-maxX, maxX), y = (offset.y + pan.y).coerceIn(-maxY, maxY)); dragOffsetY = 0f } else { offset = Offset.Zero; val isVerticalDrag = abs(pan.y) > abs(pan.x); if (isVerticalDrag && event.changes.size == 1) { dragOffsetY += pan.y; backgroundAlpha = (1f - abs(dragOffsetY) / 900f).coerceIn(0.35f, 1f); if (abs(dragOffsetY) > 50f) onControlsVisibilityChange(false); event.changes.forEach { if (it.positionChanged()) it.consume() } } } } while (event.changes.any { it.pressed }); if (scale.value <= 1.05f) { if (abs(dragOffsetY) > dismissThreshold) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onDismiss() } else { dragOffsetY = 0f; backgroundAlpha = 1f } } } },
        contentAlignment = Alignment.Center
    ) {
        val requestBuilder = remember(item.id, screenWidthPx, screenHeightPx) { ImageRequest.Builder(context).data(item.uri).size(Size(screenWidthPx, screenHeightPx)).allowHardware(true).precision(Precision.INEXACT).networkCachePolicy(CachePolicy.ENABLED).memoryCacheKey("full_${item.id}").memoryCachePolicy(CachePolicy.ENABLED).crossfade(true).error(android.R.drawable.ic_menu_report_image).build() }
        AsyncImage(model = requestBuilder, placeholder = ColorPainter(Color.Black), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaMetadataSheet(item: MediaItem, onDismiss: () -> Unit) {
    val context = LocalContext.current; val dateStr = remember(item) { SimpleDateFormat("EEEE, MMMM dd, yyyy 'at' hh a", Locale.getDefault()).format(Date(item.dateAdded * 1000)) }; val formattedSize = remember(item) { Formatter.formatFileSize(context, item.size) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface, dragHandle = { Box(Modifier.padding(top = 10.dp).width(54.dp).height(5.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f))) }) { Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 34.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(58.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp)) }; Spacer(Modifier.width(16.dp)); Column { Text("Media Details", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Spacer(Modifier.height(4.dp)); Text("Information & metadata", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }; Spacer(Modifier.height(28.dp)); Surface(shape = RoundedCornerShape(30.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) { Column(Modifier.padding(18.dp)) { MetadataRow(Icons.Outlined.Title, "Name", item.name); MetadataRow(Icons.Outlined.Folder, "Path", item.path); MetadataRow(Icons.Outlined.CalendarToday, "Date", dateStr); MetadataRow(Icons.Outlined.Storage, "Size", formattedSize); if (item.width > 0 && item.height > 0) MetadataRow(Icons.Outlined.AspectRatio, "Resolution", "${item.width} × ${item.height}"); if (item.isVideo && item.duration > 0L) MetadataRow(Icons.Outlined.Timer, "Duration", formatDuration(item.duration)) } } } }
}

@Composable
fun MetadataRow(icon: ImageVector, label: String, value: String) { Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.Top) { Box(Modifier.size(42.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) }; Spacer(Modifier.width(16.dp)); Column(Modifier.weight(1f)) { Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(4.dp)); Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium) } } }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ModernAlbumTile(modifier: Modifier = Modifier, album: Album, previews: List<Uri> = emptyList(), isSelected: Boolean, isSelectionMode: Boolean, isVirtual: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }; val isPressed by interactionSource.collectIsPressedAsState()
    val scale = if (isPressed) 0.96f else if (isSelected) 0.93f else 1f; val cornerRadius = if (isSelected) 16.dp else 14.dp
    Column(modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale }.combinedClickable(interactionSource = interactionSource, indication = androidx.compose.material3.ripple(), onClick = onClick, onLongClick = onLongClick)) {
        Surface(modifier = Modifier.aspectRatio(1f), shape = RoundedCornerShape(cornerRadius), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            val actualCoverUri = remember(album.coverUri, previews) { if (album.coverUri != Uri.EMPTY) album.coverUri else previews.firstOrNull() }
            if (actualCoverUri == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Box(Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.PhotoAlbum, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) }; Spacer(Modifier.height(8.dp)); Text("Empty Album", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } } } else { Box(Modifier.fillMaxSize().border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f), RoundedCornerShape(cornerRadius))) { AsyncImage(model = rememberGridImageRequest(actualCoverUri, 512, false), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()); Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.1f))); if (isSelectionMode) { Box(Modifier.padding(6.dp).align(Alignment.TopStart)) { if (isSelected) Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp).background(Color.White, CircleShape)) else Icon(Icons.Outlined.RadioButtonUnchecked, null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(20.dp)) } } } }
        }
        Spacer(Modifier.height(6.dp)); Text(album.name, style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 4.dp)); Text("${album.mediaCount} items", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ModernMediaGridTile(modifier: Modifier = Modifier, item: MediaItem, thumbSize: Int, isSelected: Boolean, isSelectionMode: Boolean, isScrolling: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, onToggleFavorite: () -> Unit) {
    val cornerRadius by animateDpAsState(targetValue = if (isSelected) 16.dp else 12.dp, label = "cornerRadius"); val scale by animateFloatAsState(targetValue = if (isSelected) 0.94f else 1f, animationSpec = spring(stiffness = 500f), label = "tileScale"); val context = LocalContext.current
    Box(modifier = modifier.aspectRatio(1f).graphicsLayer { scaleX = scale; scaleY = scale; clip = true; shape = RoundedCornerShape(cornerRadius) }.clip(RoundedCornerShape(cornerRadius)).combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick, onLongClick = onLongClick)) {
        val baseRequest = remember(context) { ImageRequest.Builder(context).allowRgb565(true).bitmapConfig(Bitmap.Config.RGB_565).networkCachePolicy(CachePolicy.ENABLED).memoryCachePolicy(CachePolicy.ENABLED).diskCachePolicy(CachePolicy.ENABLED).precision(Precision.INEXACT) }
        val request = remember(item.id, item.uri, thumbSize) { baseRequest.data(item.uri).size(thumbSize).memoryCacheKey("thumb_${item.id}").diskCacheKey("thumb_${item.id}").allowHardware(!item.isVideo).crossfade(false).error(android.R.drawable.ic_menu_report_image).fallback(android.R.drawable.ic_menu_report_image).apply { if (item.isVideo) { decoderFactory(coil.decode.VideoFrameDecoder.Factory()) } }.build() }
        AsyncImage(model = request, placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceContainerHigh), contentDescription = null, contentScale = ContentScale.Crop, filterQuality = FilterQuality.Low, modifier = Modifier.fillMaxSize(), onError = { Log.e("GalleryBox", "GridTile error: ${item.uri}", it.result.throwable) })
        if (item.isVideo) { Box(Modifier.fillMaxSize().drawWithCache { val brush = Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f)); onDrawBehind { drawRect(brush) } }); Surface(modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp), shape = RoundedCornerShape(12.dp), color = Color.Black.copy(alpha = 0.6f)) { Text(formatDuration(item.duration), fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)) } }
        SelectionOverlay(isSelected = isSelected, isSelectionMode = isSelectionMode, cornerRadius = cornerRadius)
    }
}

@Composable
fun SelectionOverlay(isSelected: Boolean, isSelectionMode: Boolean, cornerRadius: Dp) { AnimatedVisibility(visible = isSelectionMode, enter = fadeIn(), exit = fadeOut()) { Box(Modifier.fillMaxSize().clip(RoundedCornerShape(cornerRadius))) { Box(Modifier.fillMaxSize().background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.14f))); AnimatedVisibility(visible = isSelected) { Box(Modifier.fillMaxSize().border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(cornerRadius))) }; Box(Modifier.padding(6.dp).align(Alignment.TopStart)) { if (isSelected) Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp).background(Color.White, CircleShape)) else Icon(Icons.Outlined.RadioButtonUnchecked, null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(20.dp)) } } } }

@Composable
fun ActionItem(icon: ImageVector, label: String, isDestructive: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) { val contentColor = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface; ElevatedCard(modifier = Modifier.width(86.dp).clip(RoundedCornerShape(24.dp)).clickable(enabled = enabled, onClick = onClick), shape = RoundedCornerShape(24.dp), elevation = CardDefaults.elevatedCardElevation(3.dp), colors = CardDefaults.elevatedCardColors(containerColor = if (enabled) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLowest)) { Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) { Box(Modifier.size(46.dp).clip(CircleShape).background(contentColor.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) { Icon(icon, label, tint = if (enabled) contentColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f), modifier = Modifier.size(24.dp)) }; Spacer(Modifier.height(10.dp)); Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = if (enabled) contentColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f), maxLines = 1) } } }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernAlbumSortSheet(activeSort: AlbumSort, onDismiss: () -> Unit, onSortSelected: (AlbumSort) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface, dragHandle = { Box(Modifier.padding(top = 10.dp).width(54.dp).height(5.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f))) }) { Column(Modifier.fillMaxWidth().padding(bottom = 34.dp)) { Row(Modifier.padding(horizontal = 22.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Filled.Sort, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp)) }; Spacer(Modifier.width(16.dp)); Column { Text("Sort Albums", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Spacer(Modifier.height(4.dp)); Text("Choose album arrangement", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }; Spacer(Modifier.height(8.dp)); AlbumSort.entries.forEach { option -> val isSelected = activeSort == option; val sortLabel = when (option.name) { "DateDesc" -> "Newest First"; "DateAsc" -> "Oldest First"; "NameAsc" -> "A → Z"; "NameDesc" -> "Z → A"; "SizeDesc" -> "Largest First"; "CountDesc" -> "Most Items"; "Custom" -> "Manual Order"; else -> option.name }; Surface(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp).clip(RoundedCornerShape(24.dp)).clickable { onSortSelected(option) }, shape = RoundedCornerShape(24.dp), color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = if (isSelected) 4.dp else 0.dp) { Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(28.dp).clip(CircleShape).background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) { Icon(if (isSelected) Icons.Rounded.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked, null, tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) }; Spacer(Modifier.width(16.dp)); Text(sortLabel, style = MaterialTheme.typography.bodyLarge, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium, color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface); Spacer(Modifier.weight(1f)); AnimatedVisibility(visible = isSelected) { Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) } } } } } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernMediaSortSheet(activeSort: PhotoSort, onDismiss: () -> Unit, onSortSelected: (PhotoSort) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface, dragHandle = { Box(Modifier.padding(top = 10.dp).width(54.dp).height(5.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f))) }) { Column(Modifier.fillMaxWidth().padding(bottom = 34.dp)) { Row(Modifier.padding(horizontal = 22.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Filled.Sort, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp)) }; Spacer(Modifier.width(16.dp)); Column { Text("Sort Media", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Spacer(Modifier.height(4.dp)); Text("Arrange photos and videos", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }; Spacer(Modifier.height(8.dp)); PhotoSort.entries.forEach { option -> val isSelected = activeSort == option; val sortLabel = when (option) { PhotoSort.DateDesc -> "Newest First"; PhotoSort.DateAsc -> "Oldest First"; PhotoSort.NameAsc -> "Name (A → Z)"; PhotoSort.NameDesc -> "Name (Z → A)"; PhotoSort.SizeDesc -> "Largest First" }; Surface(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp).clip(RoundedCornerShape(24.dp)).clickable { onSortSelected(option) }, shape = RoundedCornerShape(24.dp), color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = if (isSelected) 4.dp else 0.dp) { Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(28.dp).clip(CircleShape).background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) { Icon(if (isSelected) Icons.Rounded.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked, null, tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) }; Spacer(Modifier.width(16.dp)); Text(sortLabel, style = MaterialTheme.typography.bodyLarge, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium, color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface); Spacer(Modifier.weight(1f)); AnimatedVisibility(visible = isSelected) { Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) } } } } } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernGridSheet(currentColumns: Int, max: Int = 8, onDismiss: () -> Unit, onUpdate: (Int) -> Unit) { var sliderValue by remember { mutableFloatStateOf(currentColumns.toFloat()) }; ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface, dragHandle = { Box(Modifier.padding(top = 10.dp).width(54.dp).height(5.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f))) }) { Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 34.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(58.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.GridView, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp)) }; Spacer(Modifier.width(16.dp)); Column { Text("Grid Layout", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Spacer(Modifier.height(4.dp)); Text("${sliderValue.toInt()} Columns", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }; Spacer(Modifier.height(28.dp)); Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(30.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) { Column(Modifier.padding(22.dp)) { repeat(2) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { repeat(sliderValue.toInt()) { Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))) } }; Spacer(Modifier.height(8.dp)) } } }; Spacer(Modifier.height(28.dp)); Slider(value = sliderValue, onValueChange = { sliderValue = it }, valueRange = 1f..max.toFloat(), steps = (max - 2).coerceAtLeast(0), onValueChangeFinished = { onUpdate(sliderValue.toInt()) }, colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary, inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))) } } }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernCreateAlbumSheet(onDismiss: () -> Unit, onCreate: (String, Boolean) -> Unit) { var text by remember { mutableStateOf("") }; var useSdCard by remember { mutableStateOf(false) }; ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface, dragHandle = { Box(Modifier.padding(top = 10.dp).width(54.dp).height(5.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f))) }) { Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 34.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(58.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.CreateNewFolder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp)) }; Spacer(Modifier.width(16.dp)); Column { Text("Create Album", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Spacer(Modifier.height(4.dp)); Text("Organize your memories", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }; Spacer(Modifier.height(28.dp)); OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(22.dp), label = { Text("Album Name") }, leadingIcon = { Icon(Icons.Rounded.Folder, null) }); Spacer(Modifier.height(18.dp)); Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) { Row(Modifier.fillMaxWidth().clickable { useSdCard = !useSdCard }.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = useSdCard, onCheckedChange = { useSdCard = it }); Spacer(Modifier.width(10.dp)); Column { Text("Create on SD Card", fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(2.dp)); Text("Store album externally", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }; Spacer(Modifier.height(26.dp)); Button(onClick = { if (text.isNotBlank()) onCreate(text.trim(), useSdCard) }, modifier = Modifier.fillMaxWidth().height(58.dp), shape = RoundedCornerShape(22.dp)) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(10.dp)); Text("Create Album", fontWeight = FontWeight.Bold) } } } }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernInputSheet(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) { var text by remember { mutableStateOf(initial) }; ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) { Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 34.dp)) { Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Spacer(Modifier.height(24.dp)); OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(22.dp)); Spacer(Modifier.height(28.dp)); Button(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }, modifier = Modifier.fillMaxWidth().height(58.dp), shape = RoundedCornerShape(22.dp)) { Text("Save", fontWeight = FontWeight.Bold) } } } }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernSmartDeleteSheet(count: Int, onDismiss: () -> Unit, onDeleteAll: () -> Unit) { ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) { Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 34.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(58.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.DeleteForever, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(30.dp)) }; Spacer(Modifier.width(16.dp)); Column { Text("Delete Albums", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Spacer(Modifier.height(4.dp)); Text("$count album(s) selected", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }; Spacer(Modifier.height(24.dp)); Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)) { Row(Modifier.padding(18.dp), verticalAlignment = Alignment.Top) { Icon(Icons.Rounded.WarningAmber, null, tint = MaterialTheme.colorScheme.error); Spacer(Modifier.width(12.dp)); Text("This action permanently deletes the selected albums and may remove their media from your device.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, lineHeight = 22.sp) } }; Spacer(Modifier.height(30.dp)); Button(onClick = onDeleteAll, modifier = Modifier.fillMaxWidth().height(58.dp), shape = RoundedCornerShape(22.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Icon(Icons.Rounded.Delete, null); Spacer(Modifier.width(10.dp)); Text("Delete Permanently", fontWeight = FontWeight.Bold) } } } }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernAlbumTopBar(scrollBehavior: TopAppBarScrollBehavior, onSearchClick: () -> Unit, onMenuAction: (String) -> Unit) { var showMenu by remember { mutableStateOf(false) }; TopAppBar(title = { Text("Albums", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) }, actions = { IconButton(onClick = onSearchClick) { Icon(Icons.Outlined.Search, "Search", tint = MaterialTheme.colorScheme.onSurface) }; Box { IconButton(onClick = { showMenu = true }) { Icon(Icons.Rounded.MoreVert, "More", tint = MaterialTheme.colorScheme.onSurface) }; DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, modifier = Modifier.clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surface)) { PremiumAlbumMenuItem("Grid Size", Icons.Rounded.GridView) { onMenuAction("grid"); showMenu = false }; PremiumAlbumMenuItem("Sort Albums", Icons.AutoMirrored.Filled.Sort) { onMenuAction("sort"); showMenu = false }; PremiumAlbumMenuItem("Create Album", Icons.Rounded.CreateNewFolder) { onMenuAction("create"); showMenu = false }; HorizontalDivider(); PremiumAlbumMenuItem("Duplicates", Icons.Outlined.FileCopy) { onMenuAction("duplicates"); showMenu = false }; PremiumAlbumMenuItem("Scan Library", Icons.Outlined.ImageSearch) { onMenuAction("scan"); showMenu = false }; HorizontalDivider(); PremiumAlbumMenuItem("Trash", Icons.Outlined.Delete) { onMenuAction("trash"); showMenu = false }; PremiumAlbumMenuItem("Hide Albums", Icons.Outlined.VisibilityOff) { onMenuAction("hidden"); showMenu = false }; PremiumAlbumMenuItem("Lock App", Icons.Outlined.Lock) { onMenuAction("lock_app"); showMenu = false }; HorizontalDivider(); PremiumAlbumMenuItem("Settings", Icons.Outlined.Settings) { onMenuAction("settings"); showMenu = false } } } }, scrollBehavior = scrollBehavior, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = MaterialTheme.colorScheme.surface)) }

@Composable
private fun PremiumAlbumMenuItem(text: String, icon: ImageVector, onClick: () -> Unit) { DropdownMenuItem(text = { Text(text, fontWeight = FontWeight.SemiBold) }, onClick = onClick, leadingIcon = { Box(Modifier.size(34.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) } }) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchTopBar(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) { Surface(Modifier.fillMaxWidth(), tonalElevation = 6.dp, shadowElevation = 10.dp, color = MaterialTheme.colorScheme.surface) { Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) { FilledIconButton(onClick = onClose, colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onSurface) }; Spacer(Modifier.width(14.dp)); Surface(Modifier.weight(1f), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) { Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(10.dp)); TextField(value = query, onValueChange = onQueryChange, modifier = Modifier.weight(1f), placeholder = { Text("Search albums, photos...", color = MaterialTheme.colorScheme.onSurfaceVariant) }, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge, colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, focusedTextColor = MaterialTheme.colorScheme.onSurface, unfocusedTextColor = MaterialTheme.colorScheme.onSurface, cursorColor = MaterialTheme.colorScheme.primary)); AnimatedVisibility(visible = query.isNotEmpty()) { FilledIconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(34.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))) { Icon(Icons.Rounded.Close, "Clear", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) } } } } } } }

@Composable
fun EmptyAlbumsOverlay(onCreateClick: () -> Unit) { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Box(Modifier.size(112.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.PhotoAlbum, null, modifier = Modifier.size(58.dp), tint = MaterialTheme.colorScheme.primary) }; Spacer(Modifier.height(28.dp)); Text("No Albums", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface); Spacer(Modifier.height(10.dp)); Text("Create albums to organize your memories.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center); Spacer(Modifier.height(34.dp)); Button(onClick = onCreateClick, modifier = Modifier.height(58.dp).padding(horizontal = 24.dp), shape = RoundedCornerShape(22.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) { Icon(Icons.Rounded.Add, null, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Create Album", fontWeight = FontWeight.Bold) } } } }