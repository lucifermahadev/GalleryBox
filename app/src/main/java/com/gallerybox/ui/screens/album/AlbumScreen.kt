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
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.RectangleShape
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
import androidx.media3.common.MediaItem as Media3Item
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
import kotlinx.collections.immutable.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// ============================================================================
// CONSTANTS & HELPERS
// ============================================================================
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

@Stable
data class AlbumActions(
    val onAlbumClick: (Album) -> Unit,
    val onNavigateToFavorites: () -> Unit,
    val onNavigateToTrash: () -> Unit,
    val onNavigateToHidden: () -> Unit,
    val onLockApp: () -> Unit,
    val onNavigateToSettings: () -> Unit,
    val onNavigateToDuplicates: () -> Unit,
    val onNavigateToScan: () -> Unit
)

@Stable
data class DetailActions(
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
    data class MoveCopy(val albums: List<Album>, val isMove: Boolean) : AlbumUiDialog()
    data class CreateAndMoveCopy(val albums: List<Album>, val isMove: Boolean) : AlbumUiDialog()
}

sealed class DetailUiDialog {
    data object None : DetailUiDialog()
    data object GridSize : DetailUiDialog()
    data object Sort : DetailUiDialog()
    data object DeleteAlbum : DetailUiDialog()
    data class Delete(val mediaIds: List<Long>) : DetailUiDialog()
    data class QuickAction(val item: MediaItem) : DetailUiDialog()
}

fun clearImageCache(context: Context) {
    context.imageLoader.memoryCache?.clear()
    context.imageLoader.diskCache?.clear()
    Toast.makeText(context, "Cache Cleared", Toast.LENGTH_SHORT).show()
}

fun getSmartName(item: MediaItem): String {
    val lower = item.name.lowercase()
    return when {
        "fdownloader" in lower -> "Downloaded Video"
        "instagram" in lower -> "Instagram Video"
        "whatsapp" in lower -> "WhatsApp Media"
        "screenshot" in lower -> "Screenshot"
        item.isVideo -> "Video"
        else -> "Photo"
    }
}

fun getFolderName(path: String): String {
    return try {
        File(path).parentFile?.name ?: "Unknown Folder"
    } catch (e: Exception) {
        "Unknown Folder"
    }
}

fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    val seconds = totalSeconds % 60

    return if (hours > 0) {
        "%d:%02d:%02d".format(Locale.US, hours, minutes, seconds)
    } else {
        "%d:%02d".format(Locale.US, minutes, seconds).let {
            if (it.startsWith("0:")) it else it
        }
    }
}

fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

fun albumMatchesQuery(album: Album, query: String): Boolean {
    if (query.isBlank()) return true
    val q = query.lowercase().trim()
    if (album.name.lowercase().contains(q)) return true

    return when (q) {
        "video", "videos" -> album.id in listOf(ID_CAMERA, ID_WHATSAPP)
        "photo", "photos", "image" -> album.id in listOf(ID_CAMERA, ID_RECENT)
        "fav", "favorite", "heart" -> album.id == ID_FAVORITES
        "download" -> album.id == ID_DOWNLOADS
        "social", "chat" -> album.id in listOf(ID_WHATSAPP, ID_INSTAGRAM)
        else -> false
    }
}

fun shareMediaItems(context: Context, items: List<MediaItem>) {
    if (items.isEmpty()) return

    val intent = Intent(if (items.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
        val hasImg = items.any { !it.isVideo }
        val hasVid = items.any { it.isVideo }

        type = if (hasVid && !hasImg) "video/*" else if (hasImg && !hasVid) "image/*" else "*/*"
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        if (items.size > 1) {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(items.map { it.uri }))
        } else {
            putExtra(Intent.EXTRA_STREAM, items.first().uri)
        }
    }

    try {
        context.startActivity(Intent.createChooser(intent, "Share via"))
    } catch (e: Exception) {
        Toast.makeText(context, "No app found to share", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun rememberGridImageRequest(uri: Uri?, size: Int, isVideo: Boolean): ImageRequest {
    val context = LocalContext.current
    return remember(uri, size, isVideo) {
        ImageRequest.Builder(context)
            .data(uri)
            .size(size)
            .bitmapConfig(Bitmap.Config.RGB_565)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .precision(Precision.INEXACT)
            .allowHardware(true)
            .crossfade(false)
            .error(android.R.drawable.ic_menu_report_image)
            .fallback(android.R.drawable.ic_menu_report_image)
            .apply {
                if (isVideo) {
                    decoderFactory(coil.decode.VideoFrameDecoder.Factory())
                }
            }
            .build()
    }
}

// ============================================================================
// 1. ALBUM SCREEN
// ============================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumScreen(
    viewModel: GalleryViewModel = hiltViewModel(),
    trashViewModel: TrashViewModel = hiltViewModel(),
    onViewerStateChanged: (Boolean) -> Unit = {},
    actions: AlbumActions
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

    // State Collection
    val vmAlbums by viewModel.albumsState.collectAsState(initial = emptyList())
    val rawAlbumPreviews by viewModel.albumPreviewMap.collectAsState()
    val allAlbums by viewModel.allAlbumsState.collectAsState(initial = emptyList())
    val sortOption by viewModel.albumSort.collectAsState()
    val viewerState by viewModel.viewerState.collectAsState()
    val rawMedia by viewModel.rawMedia.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<ImmutableSet<String>>(persistentListOf<String>().toImmutableSet()) }
    var activeDialog by remember { mutableStateOf<AlbumUiDialog>(AlbumUiDialog.None) }
    var showSelectionMenu by remember { mutableStateOf(false) }

    LaunchedEffect(viewerState, isSelectionMode) {
        onViewerStateChanged(viewerState is GalleryViewerState.Open || isSelectionMode)
    }

    val albumPreviews = remember(rawAlbumPreviews) {
        rawAlbumPreviews.mapValues { it.value.toImmutableList() }.toImmutableMap()
    }

    val displayAlbums: ImmutableList<Album> = remember(vmAlbums, searchQuery, sortOption) {
        val virtualAlbums = vmAlbums
            .filter { it.id.startsWith("virtual_") && albumMatchesQuery(it, searchQuery) }
            .sortedBy {
                when (it.id) {
                    ID_RECENT -> 0
                    ID_FAVORITES -> 1
                    ID_DOWNLOADS -> 2
                    else -> 99
                }
            }

        val userAlbums = vmAlbums.filter { !it.id.startsWith("virtual_") && albumMatchesQuery(it, searchQuery) }

        val sortedUserAlbums = if (sortOption == AlbumSort.Custom) {
            userAlbums
        } else {
            userAlbums.sortedWith(Comparator { a, b ->
                if (a.isPinned != b.isPinned) {
                    b.isPinned.compareTo(a.isPinned)
                } else {
                    when (sortOption.name) {
                        "NameAsc" -> a.name.compareTo(b.name, ignoreCase = true)
                        "NameDesc" -> b.name.compareTo(a.name, ignoreCase = true)
                        "SizeDesc" -> b.sizeBytes.compareTo(a.sizeBytes)
                        "CountDesc" -> b.mediaCount.compareTo(a.mediaCount)
                        else -> 0
                    }
                }
            })
        }
        (virtualAlbums + sortedUserAlbums).toImmutableList()
    }

    var dynamicList: ImmutableList<Album> by remember { mutableStateOf(displayAlbums) }

    LaunchedEffect(displayAlbums) {
        if (!isDragging) {
            dynamicList = displayAlbums
        }
    }

    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp.toFloat()
    val adaptiveCols = remember(screenWidthDp) {
        when {
            screenWidthDp >= 800f -> 8
            screenWidthDp >= 600f -> 6
            else -> 4
        }
    }

    val prefs = remember { context.getSharedPreferences("gallery_prefs", Context.MODE_PRIVATE) }
    var columnCount by remember { mutableIntStateOf(prefs.getInt("gallery_grid_columns", adaptiveCols)) }

    val gridState = rememberLazyGridState()
    val intentSenderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val g = result.resultCode == Activity.RESULT_OK
        trashViewModel.onPermissionResultGlobal(g)
        if (!g) Toast.makeText(context, "Permission Denied", Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(trashViewModel) {
        trashViewModel.events.collect { event ->
            when (event) {
                is GalleryEvent.RequestPermission -> intentSenderLauncher.launch(IntentSenderRequest.Builder(event.intentSender).build())
                is GalleryEvent.OperationSuccess -> {
                    isSelectionMode = false
                    selectedIds = persistentListOf<String>().toImmutableSet()
                    Toast.makeText(context, "Album moved to Trash", Toast.LENGTH_SHORT).show()
                }
                is GalleryEvent.ShowToast -> Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                else -> {}
            }
        }
    }

    BackHandler(enabled = isSearchActive) {
        isSearchActive = false
        searchQuery = ""
    }

    BackHandler(enabled = isSelectionMode) {
        isSelectionMode = false
        selectedIds = persistentListOf<String>().toImmutableSet()
    }

    BackHandler(enabled = activeDialog != AlbumUiDialog.None) {
        activeDialog = AlbumUiDialog.None
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
                if (isSelectionMode) {
                    TopAppBar(
                        title = {
                            Text(
                                text = "${selectedIds.size} selected",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = {
                                isSelectionMode = false
                                selectedIds = persistentListOf<String>().toImmutableSet()
                            }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close Selection")
                            }
                        },
                        actions = {
                            val isAllSelected = selectedIds.size == dynamicList.size && dynamicList.isNotEmpty()
                            TextButton(onClick = {
                                selectedIds = if (isAllSelected) {
                                    persistentListOf<String>().toImmutableSet()
                                } else {
                                    dynamicList.map { it.id }.toImmutableSet()
                                }
                            }) {
                                Text(
                                    text = if (isAllSelected) "Deselect All" else "Select All",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    )
                } else if (isSearchActive) {
                    SearchTopBar(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        onClose = {
                            isSearchActive = false
                            searchQuery = ""
                        }
                    )
                } else {
                    ModernAlbumTopBar(
                        scrollBehavior = scrollBehavior,
                        onSearchClick = { isSearchActive = true },
                        onMenuAction = { action ->
                            when (action) {
                                "grid" -> activeDialog = AlbumUiDialog.GridSize
                                "sort" -> activeDialog = AlbumUiDialog.Sort
                                "create" -> activeDialog = AlbumUiDialog.CreateAlbum
                                "trash" -> actions.onNavigateToTrash()
                                "hidden" -> activeDialog = AlbumUiDialog.HiddenAlbums
                                "lock_app" -> actions.onLockApp()
                                "settings" -> actions.onNavigateToSettings()
                                "duplicates" -> actions.onNavigateToDuplicates()
                                "scan" -> actions.onNavigateToScan()
                                "clearcache" -> clearImageCache(context)
                            }
                        }
                    )
                }
            }
        ) { padding ->
            if (dynamicList.isEmpty()) {
                EmptyAlbumsOverlay(onCreateClick = { activeDialog = AlbumUiDialog.CreateAlbum })
            } else {
                StatelessAlbumGrid(
                    gridState = gridState,
                    padding = padding,
                    columnCount = columnCount,
                    dynamicList = dynamicList,
                    albumPreviews = albumPreviews,
                    isSelectionMode = isSelectionMode,
                    selectedIds = selectedIds,
                    sortOption = sortOption,
                    searchQuery = searchQuery,
                    screenWidthDp = screenWidthDp,
                    onListUpdate = { dynamicList = it },
                    onOrderSaved = { viewModel.saveCustomAlbumOrder(it) },
                    onAlbumClick = { album ->
                        if (isSelectionMode) {
                            selectedIds = if (selectedIds.contains(album.id)) {
                                (selectedIds - album.id).toImmutableSet()
                            } else {
                                (selectedIds + album.id).toImmutableSet()
                            }
                        } else {
                            actions.onAlbumClick(album)
                        }
                    },
                    onAlbumLongClick = { album ->
                        if (!isSelectionMode) {
                            isSelectionMode = true
                            selectedIds = persistentListOf(album.id).toImmutableSet()
                        }
                    },
                    onSelectAll = { isAllSelected ->
                        selectedIds = if (isAllSelected) {
                            persistentListOf<String>().toImmutableSet()
                        } else {
                            dynamicList.map { it.id }.toImmutableSet()
                        }
                    },
                    onDragStateChange = { isDragging = it }
                )
            }
        }

        if (isSelectionMode) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 16.dp,
                shape = RectangleShape
            ) {
                Row(
                    Modifier
                        .navigationBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    BottomBarActionItem(
                        icon = Icons.AutoMirrored.Outlined.DriveFileMove,
                        label = "Move"
                    ) {
                        activeDialog = AlbumUiDialog.MoveCopy(dynamicList.filter { selectedIds.contains(it.id) }, true)
                    }

                    BottomBarActionItem(
                        icon = Icons.Outlined.Share,
                        label = "Share"
                    ) {
                        shareMediaItems(context, rawMedia.filter { selectedIds.contains(it.bucketId) })
                        isSelectionMode = false
                        selectedIds = persistentListOf<String>().toImmutableSet()
                    }

                    BottomBarActionItem(
                        icon = Icons.Outlined.Delete,
                        label = "Delete",
                        isDestructive = true
                    ) {
                        activeDialog = AlbumUiDialog.Delete(dynamicList.filter { selectedIds.contains(it.id) })
                    }

                    Box {
                        BottomBarActionItem(
                            icon = Icons.Default.MoreVert,
                            label = "More"
                        ) {
                            showSelectionMenu = true
                        }

                        DropdownMenu(
                            expanded = showSelectionMenu,
                            onDismissRequest = { showSelectionMenu = false }
                        ) {
                            val allPinned = dynamicList.filter { selectedIds.contains(it.id) }.all { it.isPinned }

                            if (selectedIds.size == 1) {
                                DropdownMenuItem(
                                    text = { Text("Rename") },
                                    onClick = {
                                        showSelectionMenu = false
                                        dynamicList.find { it.id == selectedIds.first() }?.let { activeDialog = AlbumUiDialog.Rename(it) }
                                    }
                                )
                            }

                            DropdownMenuItem(
                                text = { Text(if (allPinned) "Unpin" else "Pin") },
                                onClick = {
                                    showSelectionMenu = false
                                    dynamicList.filter { selectedIds.contains(it.id) }.forEach { viewModel.toggleAlbumPin(it) }
                                    isSelectionMode = false
                                    selectedIds = persistentListOf<String>().toImmutableSet()
                                }
                            )

                            if (selectedIds.size == 1) {
                                DropdownMenuItem(
                                    text = { Text("Info") },
                                    onClick = {
                                        showSelectionMenu = false
                                        dynamicList.find { it.id == selectedIds.first() }?.let { activeDialog = AlbumUiDialog.Info(it) }
                                    }
                                )
                            }

                            DropdownMenuItem(
                                text = { Text("Copy") },
                                onClick = {
                                    showSelectionMenu = false
                                    activeDialog = AlbumUiDialog.MoveCopy(dynamicList.filter { selectedIds.contains(it.id) }, false)
                                }
                            )

                            DropdownMenuItem(
                                text = { Text("Hide") },
                                onClick = {
                                    showSelectionMenu = false
                                    selectedIds.forEach { id -> viewModel.toggleHiddenAlbum(id) }
                                    isSelectionMode = false
                                    selectedIds = persistentListOf<String>().toImmutableSet()
                                    Toast.makeText(context, "Albums hidden", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    when (val dialog = activeDialog) {
        is AlbumUiDialog.QuickAction -> {} // Replaced by Bottom Nav Actions
        is AlbumUiDialog.Info -> {
            ModalBottomSheet(
                onDismissRequest = { activeDialog = AlbumUiDialog.None },
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    Modifier
                        .padding(24.dp)
                        .padding(bottom = 24.dp)
                ) {
                    val albumItems = rawMedia.filter { it.bucketId == dialog.album.id }
                    val oldestItem = albumItems.minByOrNull { it.dateAdded }
                    val dateStr = oldestItem?.let {
                        SimpleDateFormat("MMMM dd, yyyy 'at' hh:mm a", Locale.getDefault()).format(Date(it.dateAdded * 1000))
                    } ?: "Unknown"
                    val albumPath = oldestItem?.path?.let { File(it).parent } ?: "Unknown"

                    Text("Album Details", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(24.dp))
                    MetadataRow(Icons.Outlined.Title, "Name", dialog.album.name)
                    MetadataRow(Icons.Outlined.Storage, "Size", Formatter.formatFileSize(context, dialog.album.sizeBytes))
                    MetadataRow(Icons.Outlined.PhotoLibrary, "Items", dialog.album.mediaCount.toString())
                    MetadataRow(Icons.Outlined.Folder, "Path", albumPath)
                    MetadataRow(Icons.Outlined.CalendarToday, "Created On", dateStr)
                }
            }
        }
        is AlbumUiDialog.MoveCopy -> {
            ModalBottomSheet(
                onDismissRequest = { activeDialog = AlbumUiDialog.None },
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp)
                ) {
                    Text(
                        text = if (dialog.isMove) "Move To..." else "Copy To...",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                        contentPadding = PaddingValues(bottom = 12.dp)
                    ) {
                        item {
                            ListItem(
                                headlineContent = {
                                    Text("Create New Album", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                                },
                                leadingContent = {
                                    Icon(Icons.Rounded.CreateNewFolder, null, tint = MaterialTheme.colorScheme.primary)
                                },
                                modifier = Modifier.clickable {
                                    activeDialog = AlbumUiDialog.CreateAndMoveCopy(dialog.albums, dialog.isMove)
                                }
                            )
                        }
                        items(allAlbums.filter { !it.id.startsWith("virtual_") && dialog.albums.none { sel -> sel.id == it.id } }.sortedBy { it.name.lowercase() }) { targetAlbum ->
                            ListItem(
                                headlineContent = { Text(targetAlbum.name, fontWeight = FontWeight.Medium) },
                                leadingContent = { Icon(Icons.Outlined.Folder, null) },
                                modifier = Modifier.clickable {
                                    viewModel.mergeAlbums(
                                        sourceAlbumIds = dialog.albums.map { it.id },
                                        targetAlbumId = targetAlbum.id,
                                        mergeMode = if (dialog.isMove) MergeMode.MOVE_AND_DELETE else MergeMode.COPY
                                    )
                                    activeDialog = AlbumUiDialog.None
                                    isSelectionMode = false
                                    selectedIds = persistentListOf<String>().toImmutableSet()
                                }
                            )
                        }
                    }
                }
            }
        }
        is AlbumUiDialog.CreateAndMoveCopy -> {
            val initialName = if (dialog.albums.size == 1) "${dialog.albums.first().name} Copy" else "New Album"
            ModernInputSheet(
                title = if (dialog.isMove) "New Album & Move" else "New Album & Copy",
                initial = initialName,
                onDismiss = { activeDialog = AlbumUiDialog.None },
                onConfirm = { newName ->
                    val mediaIds = rawMedia.filter { item -> dialog.albums.any { it.id == item.bucketId } }.map { it.id }
                    if (dialog.isMove) {
                        viewModel.createAndMove(mediaIds, newName)
                    } else {
                        viewModel.createAndCopy(mediaIds, newName)
                    }
                    activeDialog = AlbumUiDialog.None
                    isSelectionMode = false
                    selectedIds = persistentListOf<String>().toImmutableSet()
                    scope.launch { delay(800); viewModel.forceSync() }
                }
            )
        }
        is AlbumUiDialog.Rename -> {
            ModernInputSheet(
                title = "Rename Album",
                initial = dialog.album.name,
                onDismiss = { activeDialog = AlbumUiDialog.None },
                onConfirm = {
                    viewModel.renameAlbum(dialog.album, it)
                    activeDialog = AlbumUiDialog.None
                    isSelectionMode = false
                    selectedIds = persistentListOf<String>().toImmutableSet()
                }
            )
        }
        is AlbumUiDialog.Delete -> {
            ModernSmartDeleteSheet(
                count = dialog.albums.size,
                onDismiss = { activeDialog = AlbumUiDialog.None },
                onDeleteAll = {
                    trashViewModel.confirmPendingAlbumTrash(dialog.albums, rawMedia)
                    activeDialog = AlbumUiDialog.None
                    isSelectionMode = false
                    selectedIds = persistentListOf<String>().toImmutableSet()
                    scope.launch { delay(500); viewModel.forceSync() }
                }
            )
        }
        is AlbumUiDialog.CreateAlbum -> {
            ModernCreateAlbumSheet(
                onDismiss = { activeDialog = AlbumUiDialog.None },
                onCreate = { name, sd ->
                    viewModel.createAlbum(name, sd)
                    activeDialog = AlbumUiDialog.None
                    scope.launch { delay(500); viewModel.forceSync() }
                }
            )
        }
        is AlbumUiDialog.Sort -> {
            ModernAlbumSortSheet(
                activeSort = sortOption,
                onDismiss = { activeDialog = AlbumUiDialog.None },
                onSortSelected = {
                    viewModel.updateAlbumSort(it)
                    activeDialog = AlbumUiDialog.None
                }
            )
        }
        is AlbumUiDialog.GridSize -> {
            ModernGridSheet(
                currentColumns = columnCount,
                max = 8,
                onDismiss = { activeDialog = AlbumUiDialog.None },
                onUpdate = {
                    columnCount = it
                    prefs.edit().putInt("gallery_grid_columns", it).apply()
                    activeDialog = AlbumUiDialog.None
                }
            )
        }
        is AlbumUiDialog.HiddenAlbums -> {
            ModalBottomSheet(
                onDismissRequest = { activeDialog = AlbumUiDialog.None },
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                val hiddenAlbums by viewModel.hiddenAlbums.collectAsState()

                val initialAlbums = remember { allAlbums.filter { !it.id.startsWith("virtual_") } }

                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp)
                ) {
                    Text(
                        text = "Hide or Unhide",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                    )
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(bottom = 12.dp)
                    ) {
                        items(initialAlbums, key = { it.id }) { album ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.toggleHiddenAlbum(album.id) }
                                    .padding(horizontal = 24.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = album.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "${album.mediaCount} items",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = hiddenAlbums.contains(album.id),
                                    onCheckedChange = { viewModel.toggleHiddenAlbum(album.id) }
                                )
                            }
                        }
                    }
                }
            }
        }
        AlbumUiDialog.None -> {}
    }
}

// ============================================================================
// 2. ALBUM DETAIL SCREEN
// ============================================================================
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AlbumDetailScreen(
    albumId: String,
    viewModel: GalleryViewModel = hiltViewModel(),
    trashViewModel: TrashViewModel = hiltViewModel(),
    onViewerStateChanged: (Boolean) -> Unit = {},
    actions: DetailActions
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val gridState = rememberLazyGridState()

    val mediaMap by viewModel.mediaMap.collectAsState()
    val favoriteIds by viewModel.favoriteIds.collectAsState()
    val vmAlbums by viewModel.albumsState.collectAsState(initial = emptyList())
    val viewerState by viewModel.viewerState.collectAsState()
    val rawMedia by viewModel.rawMedia.collectAsState()

    val albumMedia: List<MediaItem> = remember(rawMedia, albumId, favoriteIds) {
        rawMedia.filter { item ->
            when (albumId) {
                ID_FAVORITES -> favoriteIds.contains(item.id)
                ID_VIDEOS -> item.isVideo
                ID_SCREENSHOTS -> item.path.contains("Screenshot", true) || item.path.contains("Screenshots", true)
                ID_DOWNLOADS -> item.path.contains("Download", true)
                ID_WHATSAPP -> item.path.contains("WhatsApp", true)
                ID_INSTAGRAM -> item.path.contains("Instagram", true)
                ID_RECENT -> true
                else -> item.bucketId == albumId
            }
        }
    }

    var activeDialog by remember { mutableStateOf<DetailUiDialog>(DetailUiDialog.None) }
    var metadataItemToShow by remember { mutableStateOf<MediaItem?>(null) }

    val intentSenderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val g = result.resultCode == Activity.RESULT_OK
        trashViewModel.onPermissionResultGlobal(g)
        if (!g) Toast.makeText(context, "Permission Denied", Toast.LENGTH_SHORT).show()
    }

    var localSearchQuery by rememberSaveable { mutableStateOf("") }
    var currentPhotoSort by rememberSaveable { mutableStateOf(PhotoSort.DateDesc) }

    LaunchedEffect(trashViewModel) {
        trashViewModel.events.collect { event ->
            when (event) {
                is GalleryEvent.RequestPermission -> intentSenderLauncher.launch(IntentSenderRequest.Builder(event.intentSender).build())
                is GalleryEvent.OperationSuccess -> {
                    Toast.makeText(context, "Moved to Trash", Toast.LENGTH_SHORT).show()
                    viewModel.forceSync()
                }
                is GalleryEvent.ShowToast -> Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                else -> {}
            }
        }
    }

    var isSelectionMode by remember { mutableStateOf(false) }

    LaunchedEffect(viewerState, isSelectionMode) {
        onViewerStateChanged(viewerState is GalleryViewerState.Open || isSelectionMode)
    }

    val album = remember(vmAlbums, albumId) {
        when (albumId) {
            ID_RECENT -> Album(ID_RECENT, "Recent", Uri.EMPTY, 0, 0L, isPinned = true)
            ID_FAVORITES -> Album(ID_FAVORITES, "Favorites", Uri.EMPTY, 0, 0L, isPinned = true)
            ID_VIDEOS -> Album(ID_VIDEOS, "Videos", Uri.EMPTY, 0, 0L, isPinned = true)
            ID_SCREENSHOTS -> Album(ID_SCREENSHOTS, "Screenshots", Uri.EMPTY, 0, 0L, isPinned = true)
            ID_WHATSAPP -> Album(ID_WHATSAPP, "WhatsApp", Uri.EMPTY, 0, 0L, isPinned = true)
            ID_INSTAGRAM -> Album(ID_INSTAGRAM, "Instagram", Uri.EMPTY, 0, 0L, isPinned = true)
            ID_DOWNLOADS -> Album(ID_DOWNLOADS, "Downloads", Uri.EMPTY, 0, 0L, isPinned = true)
            ID_HIDDEN -> Album(ID_HIDDEN, "Hidden", Uri.EMPTY, 0, 0L, isPinned = true)
            else -> vmAlbums.find { it.id == albumId }
        }
    }

    val isVirtual = albumId.startsWith("virtual_")
    var selectedIds by remember { mutableStateOf<ImmutableSet<Long>>(persistentListOf<Long>().toImmutableSet()) }
    var selectedSize by remember { mutableLongStateOf(0L) }
    var showMediaSelectionMenu by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var mediaFilter by remember { mutableStateOf(AlbumMediaFilter.ALL) }
    var showRenameSheet by remember { mutableStateOf(false) }

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.roundToPx() }
    val screenWidthDp = configuration.screenWidthDp.toFloat()

    val adaptiveCols = remember(screenWidthDp) {
        when {
            screenWidthDp >= 800f -> 8
            screenWidthDp >= 600f -> 6
            else -> 4
        }
    }

    val prefs = remember { context.getSharedPreferences("gallery_prefs", Context.MODE_PRIVATE) }
    var detailColumns by remember { mutableIntStateOf(prefs.getInt("gallery_grid_columns", adaptiveCols)) }

    BackHandler(enabled = localSearchQuery.isNotEmpty()) {
        localSearchQuery = ""
    }

    BackHandler(enabled = isSelectionMode) {
        isSelectionMode = false
        selectedIds = persistentListOf<Long>().toImmutableSet()
        selectedSize = 0L
    }

    BackHandler(enabled = activeDialog != DetailUiDialog.None) {
        activeDialog = DetailUiDialog.None
    }

    BackHandler(enabled = metadataItemToShow != null) {
        metadataItemToShow = null
    }

    BackHandler(enabled = viewerState is GalleryViewerState.Open) {
        viewModel.closeViewer()
    }

    val filteredMedia: ImmutableList<MediaItem> = remember(albumMedia, mediaFilter, localSearchQuery, currentPhotoSort) {
        val base = albumMedia.filter { item ->
            when (mediaFilter) {
                AlbumMediaFilter.ALL -> true
                AlbumMediaFilter.PHOTOS -> !item.isVideo
                AlbumMediaFilter.VIDEOS -> item.isVideo
            }
        }

        val searched = if (localSearchQuery.isBlank()) {
            base
        } else {
            val q = localSearchQuery.trim().lowercase()
            base.filter { it.name.lowercase().contains(q) || getSmartName(it).lowercase().contains(q) }
        }

        val comparator = when (currentPhotoSort) {
            PhotoSort.DateDesc -> Comparator<MediaItem> { a, b -> b.dateAdded.compareTo(a.dateAdded) }
            PhotoSort.DateAsc -> Comparator<MediaItem> { a, b -> a.dateAdded.compareTo(b.dateAdded) }
            PhotoSort.NameAsc -> Comparator<MediaItem> { a, b -> a.name.compareTo(b.name, ignoreCase = true) }
            PhotoSort.NameDesc -> Comparator<MediaItem> { a, b -> b.name.compareTo(a.name, ignoreCase = true) }
            PhotoSort.SizeDesc -> Comparator<MediaItem> { a, b -> b.size.compareTo(a.size) }
        }

        searched.sortedWith(comparator).toImmutableList()
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
                if (isSelectionMode) {
                    TopAppBar(
                        title = {
                            Text(
                                text = "${selectedIds.size} selected",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = {
                                isSelectionMode = false
                                selectedIds = persistentListOf<Long>().toImmutableSet()
                                selectedSize = 0L
                            }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close Selection")
                            }
                        },
                        actions = {
                            val isAllSelected = selectedIds.size == filteredMedia.size && filteredMedia.isNotEmpty()
                            TextButton(onClick = {
                                if (isAllSelected) {
                                    selectedIds = persistentListOf<Long>().toImmutableSet()
                                    selectedSize = 0L
                                } else {
                                    selectedIds = filteredMedia.map { it.id }.toImmutableSet()
                                    selectedSize = filteredMedia.sumOf { it.size }
                                }
                            }) {
                                Text(
                                    text = if (isAllSelected) "Deselect All" else "Select All",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    )
                } else {
                    TopAppBar(
                        title = {
                            Text(
                                text = album?.name ?: "Album",
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = actions.onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                            }
                        },
                        actions = {
                            Box {
                                IconButton(onClick = { showMenu = true }) {
                                    Icon(Icons.Default.MoreVert, "More")
                                }
                                DropdownMenu(
                                    expanded = showMenu,
                                    onDismissRequest = { showMenu = false },
                                    modifier = Modifier.clip(RoundedCornerShape(12.dp))
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Select items") },
                                        onClick = { isSelectionMode = true; showMenu = false },
                                        leadingIcon = { Icon(Icons.Outlined.Checklist, null) }
                                    )
                                    if (!isVirtual) {
                                        DropdownMenuItem(
                                            text = { Text("Add Photos") },
                                            onClick = { actions.onAddMediaToAlbum?.invoke(albumId); showMenu = false },
                                            leadingIcon = { Icon(Icons.Rounded.AddPhotoAlternate, null) }
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text("Sort Media") },
                                        onClick = { activeDialog = DetailUiDialog.Sort; showMenu = false },
                                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, null) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Grid Size") },
                                        onClick = { activeDialog = DetailUiDialog.GridSize; showMenu = false },
                                        leadingIcon = { Icon(Icons.Default.Grid4x4, null) }
                                    )
                                    if (!isVirtual && album != null) {
                                        DropdownMenuItem(
                                            text = { Text(if (album.isPinned) "Unpin Album" else "Pin Album") },
                                            onClick = { viewModel.toggleAlbumPin(album); showMenu = false },
                                            leadingIcon = { Icon(if (album.isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin, null) }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Rename") },
                                            onClick = { showRenameSheet = true; showMenu = false },
                                            leadingIcon = { Icon(Icons.Outlined.Edit, null) }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Delete Album", color = MaterialTheme.colorScheme.error) },
                                            onClick = { activeDialog = DetailUiDialog.DeleteAlbum; showMenu = false },
                                            leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) }
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text("Lock App") },
                                        onClick = { actions.onLockApp(); showMenu = false },
                                        leadingIcon = { Icon(Icons.Outlined.Lock, null) }
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
            }
        ) { padding ->
            if (filteredMedia.isEmpty() && localSearchQuery.isBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Outlined.ImageNotSupported,
                            contentDescription = null,
                            modifier = Modifier.size(72.dp),
                            tint = Color.LightGray
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "No photos here",
                            color = Color.Gray,
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (!isVirtual) {
                            Spacer(Modifier.height(24.dp))
                            FilledTonalButton(
                                onClick = { actions.onAddMediaToAlbum?.invoke(albumId) },
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Add Photos")
                            }
                        }
                    }
                }
            } else {
                Column(Modifier.padding(padding)) {
                    BasicTextField(
                        value = localSearchQuery,
                        onValueChange = { localSearchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .height(46.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        decorationBox = { innerTextField ->
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Search,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Box(Modifier.weight(1f)) {
                                    if (localSearchQuery.isEmpty()) {
                                        Text(
                                            text = "Search photos inside album...",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                            fontSize = 15.sp
                                        )
                                    }
                                    innerTextField()
                                }
                                if (localSearchQuery.isNotEmpty()) {
                                    IconButton(
                                        onClick = { localSearchQuery = "" },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Close,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SamsungFilterChip(
                                selected = mediaFilter == AlbumMediaFilter.ALL,
                                label = "All"
                            ) { mediaFilter = AlbumMediaFilter.ALL }
                            SamsungFilterChip(
                                selected = mediaFilter == AlbumMediaFilter.PHOTOS,
                                label = "Photos"
                            ) { mediaFilter = AlbumMediaFilter.PHOTOS }
                            SamsungFilterChip(
                                selected = mediaFilter == AlbumMediaFilter.VIDEOS,
                                label = "Videos"
                            ) { mediaFilter = AlbumMediaFilter.VIDEOS }
                        }
                        Surface(
                            onClick = { activeDialog = DetailUiDialog.Sort },
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Sort,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = when (currentPhotoSort) {
                                        PhotoSort.DateDesc -> "Newest"
                                        PhotoSort.DateAsc -> "Oldest"
                                        PhotoSort.NameAsc -> "A-Z"
                                        PhotoSort.NameDesc -> "Z-A"
                                        PhotoSort.SizeDesc -> "Size"
                                    },
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    if (filteredMedia.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No matching items found",
                                color = Color.Gray,
                                fontSize = 15.sp
                            )
                        }
                    } else {
                        StatelessMediaGrid(
                            gridState = gridState,
                            mediaList = filteredMedia,
                            columnCount = detailColumns,
                            screenWidthPx = screenWidthPx,
                            isSelectionMode = isSelectionMode,
                            selectedIds = selectedIds,
                            onToggleSelection = { item ->
                                if (selectedIds.contains(item.id)) {
                                    selectedIds = (selectedIds - item.id).toImmutableSet()
                                    selectedSize = maxOf(0L, selectedSize - item.size)
                                } else {
                                    selectedIds =
                                        ((selectedIds + item.id).takeIf { it.size < 5000 } ?: selectedIds) as ImmutableSet<Long>
                                    selectedSize += item.size
                                }
                            },
                            onSelectAll = { isAllSelected ->
                                if (isAllSelected) {
                                    selectedIds = persistentListOf<Long>().toImmutableSet()
                                    selectedSize = 0L
                                } else {
                                    selectedIds = filteredMedia.map { it.id }.toImmutableSet()
                                    selectedSize = filteredMedia.sumOf { it.size }
                                }
                            },
                            onMediaClick = { item -> viewModel.openViewer(item.id) },
                            onMediaLongClick = {
                                if (!isSelectionMode) {
                                    isSelectionMode = true
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            },
                            onToggleFavorite = { viewModel.toggleFavorite(it) }
                        )
                    }
                }
            }
        }

        if (isSelectionMode) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 16.dp,
                shape = RectangleShape
            ) {
                Row(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    BottomBarActionItem(
                        icon = Icons.AutoMirrored.Outlined.DriveFileMove,
                        label = "Move"
                    ) {
                        actions.onNavigateToMoveCopy("MOVE", selectedIds.joinToString(","), albumId)
                        isSelectionMode = false
                        selectedIds = persistentListOf<Long>().toImmutableSet()
                        selectedSize = 0L
                    }

                    BottomBarActionItem(
                        icon = Icons.Outlined.Share,
                        label = "Share"
                    ) {
                        shareMediaItems(context, selectedIds.mapNotNull { mediaMap[it] })
                    }

                    BottomBarActionItem(
                        icon = Icons.Outlined.Delete,
                        label = "Delete",
                        isDestructive = true
                    ) {
                        activeDialog = DetailUiDialog.Delete(selectedIds.toList())
                    }

                    Box {
                        BottomBarActionItem(
                            icon = Icons.Default.MoreVert,
                            label = "More"
                        ) {
                            showMediaSelectionMenu = true
                        }

                        DropdownMenu(
                            expanded = showMediaSelectionMenu,
                            onDismissRequest = { showMediaSelectionMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Copy to album") },
                                onClick = {
                                    showMediaSelectionMenu = false
                                    actions.onNavigateToMoveCopy("COPY", selectedIds.joinToString(","), albumId)
                                    isSelectionMode = false
                                    selectedIds = persistentListOf<Long>().toImmutableSet()
                                    selectedSize = 0L
                                }
                            )
                            if (selectedIds.size == 1) {
                                DropdownMenuItem(
                                    text = { Text("Details") },
                                    onClick = {
                                        showMediaSelectionMenu = false
                                        metadataItemToShow = mediaMap[selectedIds.first()]
                                    }
                                )
                            }
                            if (albumId == ID_HIDDEN) {
                                DropdownMenuItem(
                                    text = { Text("Unhide") },
                                    onClick = {
                                        showMediaSelectionMenu = false
                                        viewModel.unhideMedia(selectedIds.toList())
                                        Toast.makeText(context, "Items restored", Toast.LENGTH_SHORT).show()
                                        isSelectionMode = false
                                        selectedIds = persistentListOf<Long>().toImmutableSet()
                                        selectedSize = 0L
                                    }
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text("Hide") },
                                    onClick = {
                                        showMediaSelectionMenu = false
                                        viewModel.hideItems(selectedIds.toList())
                                        Toast.makeText(context, "${selectedIds.size} items hidden", Toast.LENGTH_SHORT).show()
                                        isSelectionMode = false
                                        selectedIds = persistentListOf<Long>().toImmutableSet()
                                        selectedSize = 0L
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    when (val dialog = activeDialog) {
        is DetailUiDialog.QuickAction -> {} // Replaced by Bottom Nav Actions
        is DetailUiDialog.DeleteAlbum -> {
            AlertDialog(
                onDismissRequest = { activeDialog = DetailUiDialog.None },
                icon = { Icon(Icons.Outlined.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
                title = { Text("Delete Album?") },
                text = { Text("This will delete the manual album placeholder. Any physical media stored within this folder on your device will remain intact.") },
                confirmButton = {
                    Button(
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        onClick = {
                            actions.onDeleteAlbum?.invoke(albumId)
                            activeDialog = DetailUiDialog.None
                            actions.onBack()
                        }
                    ) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { activeDialog = DetailUiDialog.None }) {
                        Text("Cancel")
                    }
                }
            )
        }
        is DetailUiDialog.Delete -> {
            AlertDialog(
                onDismissRequest = { activeDialog = DetailUiDialog.None },
                icon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
                title = { Text("Move to Trash?") },
                text = { Text("Items will be moved to Trash. They can be recovered within 30 days.") },
                confirmButton = {
                    Button(
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        onClick = {
                            val itemsToTrash = albumMedia.filter { dialog.mediaIds.contains(it.id) }
                            trashViewModel.confirmPendingGalleryTrash(itemsToTrash)
                            activeDialog = DetailUiDialog.None
                            isSelectionMode = false
                            selectedIds = persistentListOf<Long>().toImmutableSet()
                            selectedSize = 0L
                            viewModel.closeViewer()
                        }
                    ) {
                        Text("Move to Trash")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { activeDialog = DetailUiDialog.None }) {
                        Text("Cancel")
                    }
                }
            )
        }
        is DetailUiDialog.GridSize -> {
            ModernGridSheet(
                currentColumns = detailColumns,
                max = 8,
                onDismiss = { activeDialog = DetailUiDialog.None },
                onUpdate = {
                    detailColumns = it
                    prefs.edit().putInt("gallery_grid_columns", it).apply()
                    activeDialog = DetailUiDialog.None
                }
            )
        }
        is DetailUiDialog.Sort -> {
            ModernMediaSortSheet(
                activeSort = currentPhotoSort,
                onDismiss = { activeDialog = DetailUiDialog.None },
                onSortSelected = {
                    currentPhotoSort = it
                    activeDialog = DetailUiDialog.None
                }
            )
        }
        else -> {}
    }

    if (showRenameSheet && album != null) {
        ModernInputSheet(
            title = "Rename Album",
            initial = album.name,
            onDismiss = { showRenameSheet = false },
            onConfirm = {
                viewModel.renameAlbum(album, it)
                showRenameSheet = false
            }
        )
    }

    if (metadataItemToShow != null) {
        MediaMetadataSheet(item = metadataItemToShow!!) {
            metadataItemToShow = null
        }
    }

    val openViewerState = viewerState as? GalleryViewerState.Open
    val viewerItemId = openViewerState?.mediaId
    val stableMediaList = if (viewerState is GalleryViewerState.Open) filteredMedia else emptyList()

    if (viewerState is GalleryViewerState.Open && stableMediaList.isNotEmpty()) {
        val stableStartIndex = stableMediaList.indexOfFirst { it.id == viewerItemId }.coerceAtLeast(0)

        key(viewerItemId, stableMediaList.size) {
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(200))
            ) {
                FullscreenMediaPager(
                    initialIndex = stableStartIndex,
                    mediaList = stableMediaList,
                    mediaMap = mediaMap,
                    favoriteIds = favoriteIds,
                    sharedPlayer = viewModel.getPlayer(),
                    onPageChanged = {},
                    onClose = { viewModel.closeViewer() },
                    onToggleFavorite = { id: Long ->
                        viewModel.toggleFavorite(id)
                        if (albumId == ID_FAVORITES) {
                            scope.launch {
                                delay(300)
                                viewModel.closeViewer()
                            }
                        }
                    },
                    onEdit = { item: MediaItem ->
                        viewModel.closeViewer()
                        if (item.isVideo) {
                            actions.onNavigateToVideoEditor(item.uri.toString(), item.id)
                        } else {
                            actions.onNavigateToPhotoEditor(item.uri.toString(), item.id)
                        }
                    },
                    onPlayVideo = { uri, playlist ->
                        actions.onNavigateToVideoPlayer(uri, playlist)
                    },
                    onDelete = { item: MediaItem ->
                        activeDialog = DetailUiDialog.Delete(listOf(item.id))
                    },
                    onMove = { item ->
                        viewModel.closeViewer()
                        actions.onNavigateToMoveCopy("MOVE", item.id.toString(), albumId)
                    },
                    onCopy = { item ->
                        viewModel.closeViewer()
                        actions.onNavigateToMoveCopy("COPY", item.id.toString(), albumId)
                    },
                    onWallpaper = { item ->
                        viewModel.closeViewer()
                        actions.onNavigateToWallpaper(item.uri.toString(), item.id)
                    }
                )
            }
        }
    }
}

// ============================================================================
// STATELESS GRIDS & PREFETCHERS
// ============================================================================
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StatelessAlbumGrid(
    gridState: LazyGridState,
    padding: PaddingValues,
    columnCount: Int,
    dynamicList: ImmutableList<Album>,
    albumPreviews: ImmutableMap<String, ImmutableList<Uri>>,
    isSelectionMode: Boolean,
    selectedIds: ImmutableSet<String>,
    sortOption: AlbumSort,
    searchQuery: String,
    screenWidthDp: Float,
    onListUpdate: (ImmutableList<Album>) -> Unit,
    onOrderSaved: (List<Album>) -> Unit,
    onAlbumClick: (Album) -> Unit,
    onAlbumLongClick: (Album) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onDragStateChange: (Boolean) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var draggedIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var scrollVelocity by remember { mutableFloatStateOf(0f) }

    val isScrolling by remember { derivedStateOf { gridState.isScrollInProgress } }

    val actualColumns = columnCount.coerceAtLeast(1)
    val dynamicThumbSize = remember(actualColumns, screenWidthDp, isScrolling) {
        if (isScrolling) 160 else maxOf(512, (screenWidthDp / actualColumns).toInt())
    }

    GridImagePrefetcher(gridState = gridState, items = dynamicList, previews = albumPreviews)

    LaunchedEffect(scrollVelocity) {
        if (scrollVelocity != 0f) {
            while (isActive) {
                val consumed = gridState.scrollBy(scrollVelocity)
                if (consumed != 0f) {
                    dragOffset += Offset(0f, consumed)
                }
                delay(16)
            }
        }
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(columnCount),
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 90.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        itemsIndexed(items = dynamicList, key = { _, album -> album.id }) { index, album ->
            val isBeingDragged = draggedIndex == index
            val modifierWithAnim = if (isScrolling) Modifier else Modifier.animateItem()

            val isVirtualNode = album.id.startsWith("virtual_")
            val canDrag = sortOption == AlbumSort.Custom && searchQuery.isBlank() &&
                    (!isSelectionMode || (selectedIds.size == 1 && selectedIds.contains(album.id)))

            val dragModifier = if (canDrag && !isVirtualNode) {
                Modifier.pointerInput(album.id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            draggedIndex = index
                            dragOffset = Offset.Zero
                            onDragStateChange(true)
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragOffset += dragAmount
                            val layoutInfo = gridState.layoutInfo
                            val visibleItems = layoutInfo.visibleItemsInfo
                            val draggedItemInfo = visibleItems.find { it.index == draggedIndex }

                            if (draggedItemInfo != null) {
                                val draggedCenterX = draggedItemInfo.offset.x + (draggedItemInfo.size.width / 2) + dragOffset.x.roundToInt()
                                val draggedCenterY = draggedItemInfo.offset.y + (draggedItemInfo.size.height / 2) + dragOffset.y.roundToInt()

                                scrollVelocity = when {
                                    draggedCenterY < layoutInfo.viewportStartOffset + 180 -> -15f
                                    draggedCenterY > layoutInfo.viewportEndOffset - 180 -> 15f
                                    else -> 0f
                                }

                                val targetItemInfo = visibleItems.find {
                                    it.index != draggedIndex &&
                                            it.index < dynamicList.size &&
                                            !dynamicList[it.index].id.startsWith("virtual_") &&
                                            draggedCenterX in it.offset.x..(it.offset.x + it.size.width) &&
                                            draggedCenterY in it.offset.y..(it.offset.y + it.size.height)
                                }

                                if (targetItemInfo != null) {
                                    val targetIndex = targetItemInfo.index
                                    val dx = targetItemInfo.offset.x - draggedItemInfo.offset.x
                                    val dy = targetItemInfo.offset.y - draggedItemInfo.offset.y
                                    dragOffset -= Offset(dx.toFloat(), dy.toFloat())

                                    val newList = dynamicList.toMutableList()
                                    val item = newList.removeAt(draggedIndex)
                                    newList.add(targetIndex, item)

                                    onListUpdate(newList.toImmutableList())
                                    draggedIndex = targetIndex
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            }
                        },
                        onDragEnd = {
                            if (draggedIndex != -1) onOrderSaved(dynamicList)
                            draggedIndex = -1
                            dragOffset = Offset.Zero
                            scrollVelocity = 0f
                            onDragStateChange(false)
                        },
                        onDragCancel = {
                            draggedIndex = -1
                            dragOffset = Offset.Zero
                            scrollVelocity = 0f
                            onDragStateChange(false)
                        }
                    )
                }
            } else Modifier

            Box(
                modifier = modifierWithAnim
                    .zIndex(if (isBeingDragged) 1f else 0f)
                    .graphicsLayer {
                        if (isBeingDragged) {
                            scaleX = 1.08f
                            scaleY = 1.08f
                            alpha = 0.9f
                            translationX = dragOffset.x
                            translationY = dragOffset.y
                            shadowElevation = 24f
                        }
                    }
            ) {
                OptimizedAlbumTile(
                    album = album,
                    previews = albumPreviews[album.id] ?: persistentListOf(),
                    isSelected = selectedIds.contains(album.id),
                    isSelectionMode = isSelectionMode,
                    canDrag = canDrag && !isVirtualNode,
                    dragModifier = dragModifier,
                    thumbSize = dynamicThumbSize,
                    onClick = { onAlbumClick(album) },
                    onLongClick = { onAlbumLongClick(album) }
                )
            }
        }
    }
}

@Composable
fun GridImagePrefetcher(
    gridState: LazyGridState,
    items: ImmutableList<Album>,
    previews: ImmutableMap<String, ImmutableList<Uri>>
) {
    val context = LocalContext.current
    val imageLoader = context.imageLoader

    LaunchedEffect(gridState, items, previews) {
        snapshotFlow { gridState.layoutInfo }.collect { layoutInfo ->
            val visibleItems = layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty() || items.isEmpty()) return@collect

            val firstVisible = visibleItems.first().index
            val lastVisible = visibleItems.last().index
            val prefetchStart = (firstVisible - 20).coerceAtLeast(0)
            val prefetchEnd = (lastVisible + 20).coerceAtMost(items.lastIndex)

            for (i in prefetchStart..prefetchEnd) {
                if (i !in firstVisible..lastVisible) {
                    val album = items[i]
                    val coverUri = if (album.coverUri != Uri.EMPTY) album.coverUri else previews[album.id]?.firstOrNull()

                    if (coverUri != null) {
                        val request = ImageRequest.Builder(context)
                            .data(coverUri)
                            .size(160)
                            .memoryCacheKey("thumb_${album.id}")
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .build()
                        imageLoader.enqueue(request)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StatelessMediaGrid(
    gridState: LazyGridState,
    mediaList: ImmutableList<MediaItem>,
    columnCount: Int,
    screenWidthPx: Int,
    isSelectionMode: Boolean,
    selectedIds: ImmutableSet<Long>,
    onToggleSelection: (MediaItem) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onMediaClick: (MediaItem) -> Unit,
    onMediaLongClick: () -> Unit,
    onToggleFavorite: (Long) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val isScrolling by remember { derivedStateOf { gridState.isScrollInProgress } }

    val dynamicThumbSize = remember(columnCount, screenWidthPx, isScrolling) {
        if (isScrolling) 160 else maxOf(160, screenWidthPx / columnCount)
    }

    MediaGridImagePrefetcher(gridState = gridState, mediaList = mediaList)

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(columnCount),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 3.dp, end = 3.dp, top = 8.dp, bottom = 90.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        items(
            count = mediaList.size,
            key = { i -> mediaList[i].id },
            contentType = { "media" }
        ) { index ->
            val currentItem = mediaList[index]
            val modifierWithAnim = if (isScrolling) Modifier else Modifier.animateItem()

            ModernMediaGridTile(
                modifier = modifierWithAnim,
                item = currentItem,
                thumbSize = dynamicThumbSize,
                isSelected = selectedIds.contains(currentItem.id),
                isSelectionMode = isSelectionMode,
                isScrolling = isScrolling,
                onClick = {
                    if (isSelectionMode) {
                        onToggleSelection(currentItem)
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    } else {
                        onMediaClick(currentItem)
                    }
                },
                onLongClick = {
                    onMediaLongClick()
                    onToggleSelection(currentItem)
                },
                onToggleFavorite = { onToggleFavorite(currentItem.id) }
            )
        }
    }
}

@Composable
fun MediaGridImagePrefetcher(
    gridState: LazyGridState,
    mediaList: ImmutableList<MediaItem>
) {
    val context = LocalContext.current
    val imageLoader = context.imageLoader

    LaunchedEffect(gridState, mediaList) {
        snapshotFlow { gridState.layoutInfo }.collect { layoutInfo ->
            val visibleItems = layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty() || mediaList.isEmpty()) return@collect

            val firstVisible = visibleItems.first().index
            val lastVisible = visibleItems.last().index
            val prefetchStart = (firstVisible - 20).coerceAtLeast(0)
            val prefetchEnd = (lastVisible + 20).coerceAtMost(mediaList.lastIndex)

            for (i in prefetchStart..prefetchEnd) {
                if (i !in firstVisible..lastVisible && i < mediaList.size) {
                    val item = mediaList[i]
                    val request = ImageRequest.Builder(context)
                        .data(item.uri)
                        .size(160)
                        .memoryCacheKey("thumb_${item.id}")
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .build()
                    imageLoader.enqueue(request)
                }
            }
        }
    }
}

// ============================================================================
// TILES & COMPONENTS
// ============================================================================
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OptimizedAlbumTile(
    album: Album,
    previews: ImmutableList<Uri>,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    canDrag: Boolean,
    thumbSize: Int,
    dragModifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale = if (isPressed) 0.96f else if (isSelected) 0.93f else 1f

    val clickModifier = if (canDrag) {
        Modifier
            .then(dragModifier)
            .clickable(
                interactionSource = interactionSource,
                indication = androidx.compose.material3.ripple(),
                onClick = onClick
            )
    } else {
        Modifier
            .combinedClickable(
                interactionSource = interactionSource,
                indication = androidx.compose.material3.ripple(),
                onClick = onClick,
                onLongClick = onLongClick
            )
    }

    Column(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(clickModifier)
    ) {
        Surface(
            modifier = Modifier
                .aspectRatio(1f)
                .shadow(elevation = 1.dp, shape = RoundedCornerShape(10.dp)),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            val actualCoverUri = remember(album.coverUri, previews) {
                if (album.coverUri != Uri.EMPTY) album.coverUri else previews.firstOrNull()
            }

            Box(Modifier.fillMaxSize()) {
                if (actualCoverUri == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.PhotoAlbum,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "Empty Album",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    AsyncImage(
                        model = rememberGridImageRequest(actualCoverUri, thumbSize, false),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.05f)))
                }

                if (isSelectionMode) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                if (isSelected) Color.White.copy(alpha = 0.25f)
                                else Color.Black.copy(alpha = 0.1f)
                            )
                    )
                    Box(Modifier.padding(8.dp).align(Alignment.TopStart)) {
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .size(22.dp)
                                    .background(Color.White, CircleShape)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.9f),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = album.name,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
        Text(
            text = "${album.mediaCount} items",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 2.dp)
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
    isScrolling: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    val cornerRadius by animateDpAsState(targetValue = if (isSelected) 16.dp else 12.dp, label = "cornerRadius")
    val scale by animateFloatAsState(targetValue = if (isSelected) 0.94f else 1f, animationSpec = spring(stiffness = 500f), label = "tileScale")
    val context = LocalContext.current

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                clip = true
                shape = RoundedCornerShape(cornerRadius)
            }
            .clip(RoundedCornerShape(cornerRadius))
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        val baseRequest = remember(context) {
            ImageRequest.Builder(context)
                .allowRgb565(true)
                .bitmapConfig(Bitmap.Config.RGB_565)
                .networkCachePolicy(CachePolicy.ENABLED)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .precision(Precision.INEXACT)
        }

        val request = remember(item.id, item.uri, thumbSize) {
            baseRequest
                .data(item.uri)
                .size(thumbSize)
                .memoryCacheKey("thumb_${item.id}")
                .diskCacheKey("thumb_${item.id}")
                .allowHardware(!item.isVideo)
                .crossfade(false)
                .error(android.R.drawable.ic_menu_report_image)
                .fallback(android.R.drawable.ic_menu_report_image)
                .apply {
                    if (item.isVideo) {
                        decoderFactory(coil.decode.VideoFrameDecoder.Factory())
                    }
                }
                .build()
        }

        AsyncImage(
            model = request,
            placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.Low,
            modifier = Modifier.fillMaxSize()
        )

        if (item.isVideo) {
            Box(
                Modifier.fillMaxSize().drawWithCache {
                    val brush = Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f))
                    onDrawBehind { drawRect(brush) }
                }
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp),
                shape = RoundedCornerShape(12.dp),
                color = Color.Black.copy(alpha = 0.6f)
            ) {
                Text(
                    text = formatDuration(item.duration),
                    fontSize = 11.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }
        }

        SelectionOverlay(
            isSelected = isSelected,
            isSelectionMode = isSelectionMode,
            cornerRadius = cornerRadius
        )
    }
}

@Composable
fun SelectionOverlay(isSelected: Boolean, isSelectionMode: Boolean, cornerRadius: Dp) {
    AnimatedVisibility(visible = isSelectionMode, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(cornerRadius))) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(if (isSelected) Color.White.copy(alpha = 0.25f) else Color.Black.copy(alpha = 0.1f))
            )
            Box(Modifier.padding(8.dp).align(Alignment.TopStart)) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp).background(Color.White, CircleShape)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun SamsungFilterChip(selected: Boolean, label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun ActionItem(icon: ImageVector, label: String, isDestructive: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val contentColor = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ElevatedCard(
        modifier = Modifier
            .width(86.dp)
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
            if (label.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) contentColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                    maxLines = 1
                )
            } else {
                Spacer(Modifier.height(10.dp))
                Text(" ", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
fun BottomBarActionItem(icon: ImageVector, label: String, isDestructive: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val contentColor = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    val alphaColor = if (enabled) contentColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = alphaColor, modifier = Modifier.size(24.dp))
        if (label.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(text = label, style = MaterialTheme.typography.labelMedium, color = alphaColor, maxLines = 1)
        }
    }
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
            FilledIconButton(
                onClick = onClose,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
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
                        placeholder = { Text("Search albums, photos...", color = MaterialTheme.colorScheme.onSurfaceVariant) },
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
                        FilledIconButton(
                            onClick = { onQueryChange("") },
                            modifier = Modifier.size(34.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                        ) {
                            Icon(Icons.Rounded.Close, "Clear", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernAlbumTopBar(
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
                Icon(Icons.Outlined.Search, "Search", tint = MaterialTheme.colorScheme.onSurface)
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
                    PremiumAlbumMenuItem("Grid Size", Icons.Rounded.GridView) { onMenuAction("grid"); showMenu = false }
                    PremiumAlbumMenuItem("Sort Albums", Icons.AutoMirrored.Filled.Sort) { onMenuAction("sort"); showMenu = false }
                    PremiumAlbumMenuItem("Create Album", Icons.Rounded.CreateNewFolder) { onMenuAction("create"); showMenu = false }
                    HorizontalDivider()
                    PremiumAlbumMenuItem("Duplicates", Icons.Outlined.FileCopy) { onMenuAction("duplicates"); showMenu = false }
                    PremiumAlbumMenuItem("Scan Library", Icons.Outlined.ImageSearch) { onMenuAction("scan"); showMenu = false }
                    HorizontalDivider()
                    PremiumAlbumMenuItem("Trash", Icons.Outlined.Delete) { onMenuAction("trash"); showMenu = false }
                    PremiumAlbumMenuItem("Hide Albums", Icons.Outlined.VisibilityOff) { onMenuAction("hidden"); showMenu = false }
                    PremiumAlbumMenuItem("Lock App", Icons.Outlined.Lock) { onMenuAction("lock_app"); showMenu = false }
                    HorizontalDivider()
                    PremiumAlbumMenuItem("Settings", Icons.Outlined.Settings) { onMenuAction("settings"); showMenu = false }
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
private fun PremiumAlbumMenuItem(text: String, icon: ImageVector, onClick: () -> Unit) {
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

@Composable
fun EmptyAlbumsOverlay(onCreateClick: () -> Unit) {
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
                    imageVector = Icons.Outlined.PhotoAlbum,
                    contentDescription = null,
                    modifier = Modifier.size(58.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(28.dp))
            Text(
                text = "No Albums",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Create albums to organize your memories.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(34.dp))
            Button(
                onClick = onCreateClick,
                modifier = Modifier
                    .height(58.dp)
                    .padding(horizontal = 24.dp),
                shape = RoundedCornerShape(22.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Create Album", fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ============================================================================
// BOTTOM SHEETS & DIALOGS
// ============================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernAlbumSortSheet(activeSort: AlbumSort, onDismiss: () -> Unit, onSortSelected: (AlbumSort) -> Unit) {
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
                        text = "Sort Albums",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Choose album arrangement",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            AlbumSort.entries.forEach { option ->
                val isSelected = activeSort == option
                val sortLabel = when (option.name) {
                    "DateDesc" -> "Newest First"
                    "DateAsc" -> "Oldest First"
                    "NameAsc" -> "A → Z"
                    "NameDesc" -> "Z → A"
                    "SizeDesc" -> "Largest First"
                    "CountDesc" -> "Most Items"
                    "Custom" -> "Manual Order"
                    else -> option.name
                }

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
                            text = sortLabel,
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
fun ModernMediaSortSheet(activeSort: PhotoSort, onDismiss: () -> Unit, onSortSelected: (PhotoSort) -> Unit) {
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
                        text = "Arrange photos and videos",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            PhotoSort.entries.forEach { option ->
                val isSelected = activeSort == option
                val sortLabel = when (option) {
                    PhotoSort.DateDesc -> "Newest First"
                    PhotoSort.DateAsc -> "Oldest First"
                    PhotoSort.NameAsc -> "Name (A → Z)"
                    PhotoSort.NameDesc -> "Name (Z → A)"
                    PhotoSort.SizeDesc -> "Largest First"
                }

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
                            text = sortLabel,
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
fun ModernGridSheet(currentColumns: Int, max: Int = 8, onDismiss: () -> Unit, onUpdate: (Int) -> Unit) {
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
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernCreateAlbumSheet(onDismiss: () -> Unit, onCreate: (String, Boolean) -> Unit) {
    var text by remember { mutableStateOf("") }
    var useSdCard by remember { mutableStateOf(false) }

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
                        imageVector = Icons.Rounded.CreateNewFolder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(30.dp)
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        text = "Create Album",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Organize your memories",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(28.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(22.dp),
                label = { Text("Album Name") },
                leadingIcon = { Icon(Icons.Rounded.Folder, null) }
            )
            Spacer(Modifier.height(18.dp))
            Surface(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { useSdCard = !useSdCard }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = useSdCard,
                        onCheckedChange = { useSdCard = it }
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Create on SD Card", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "Store album externally",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.height(26.dp))
            Button(
                onClick = { if (text.isNotBlank()) onCreate(text.trim(), useSdCard) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp),
                shape = RoundedCornerShape(22.dp)
            ) {
                Icon(Icons.Rounded.Add, null)
                Spacer(Modifier.width(10.dp))
                Text("Create Album", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernInputSheet(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .padding(bottom = 34.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(22.dp)
            )
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = { if (text.isNotBlank()) onConfirm(text.trim()) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp),
                shape = RoundedCornerShape(22.dp)
            ) {
                Text("Save", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernSmartDeleteSheet(count: Int, onDismiss: () -> Unit, onDeleteAll: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
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
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteForever,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(30.dp)
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        text = "Delete Albums",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "$count album(s) selected",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
            ) {
                Row(
                    Modifier.padding(18.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Rounded.WarningAmber,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "This action permanently deletes the selected albums and may remove their media from your device.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 22.sp
                    )
                }
            }
            Spacer(Modifier.height(30.dp))
            Button(
                onClick = onDeleteAll,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp),
                shape = RoundedCornerShape(22.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Rounded.Delete, null)
                Spacer(Modifier.width(10.dp))
                Text("Delete Permanently", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaMetadataSheet(item: MediaItem, onDismiss: () -> Unit) {
    val context = LocalContext.current

    val dateFormatter = remember {
        SimpleDateFormat("EEEE, MMMM dd, yyyy 'at' hh a", Locale.getDefault())
    }

    val dateStr = remember(item.dateAdded) {
        dateFormatter.format(Date(item.dateAdded * 1000))
    }

    val formattedSize = remember(item.size) {
        Formatter.formatFileSize(context, item.size)
    }

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
                }
            }
        }
    }
}

@Composable
fun MetadataRow(icon: ImageVector, label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
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
            Spacer(Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// ============================================================================
// VIEWER COMPONENTS
// ============================================================================
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FullscreenMediaPager(
    initialIndex: Int,
    mediaList: List<MediaItem>,
    mediaMap: Map<Long, MediaItem>,
    favoriteIds: List<Long>,
    sharedPlayer: Player,
    onPageChanged: (MediaItem) -> Unit,
    onClose: () -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onEdit: (MediaItem) -> Unit,
    onPlayVideo: (String, List<String>) -> Unit,
    onDelete: (MediaItem) -> Unit,
    onMove: (MediaItem) -> Unit,
    onCopy: (MediaItem) -> Unit,
    onWallpaper: (MediaItem) -> Unit
) {
    if (mediaList.isEmpty()) return

    val context = LocalContext.current
    val view = LocalView.current
    val safeInitialPage = initialIndex.coerceIn(0, maxOf(mediaList.lastIndex, 0))
    val pagerState = rememberPagerState(initialPage = safeInitialPage, pageCount = { mediaList.size })

    var showControls by remember { mutableStateOf(true) }
    var showMetadataSheet by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }

    val activity = remember { context.findActivity() }
    val zoomedPages = remember { mutableStateMapOf<Int, Boolean>() }
    val isCurrentPageZoomed = zoomedPages.getOrDefault(pagerState.currentPage, false)

    val videoItems = remember(mediaList) { mediaList.filter { it.isVideo } }

    LaunchedEffect(videoItems) {
        if (videoItems.isNotEmpty()) {
            val media3Items = videoItems.map { Media3Item.fromUri(it.uri) }
            sharedPlayer.setMediaItems(media3Items)
            sharedPlayer.prepare()
        }
    }

    LaunchedEffect(initialIndex, mediaList.size) {
        if (pagerState.currentPage != initialIndex && initialIndex in mediaList.indices) {
            pagerState.scrollToPage(initialIndex)
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        if (zoomedPages.size > 20) zoomedPages.clear()
        showControls = true
        mediaList.getOrNull(pagerState.currentPage)?.let(onPageChanged)
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

    BackHandler(enabled = !showControls) {
        showControls = true
    }

    BackHandler(enabled = showControls) {
        onClose()
    }

    val safePage = pagerState.currentPage.coerceIn(0, maxOf(mediaList.lastIndex, 0))
    val currentPageId = mediaList.getOrNull(safePage)?.id

    LaunchedEffect(currentPageId) {
        if (currentPageId == null && mediaList.isNotEmpty()) {
            onClose()
        }
    }

    val liveCurrentItem = remember(currentPageId, mediaMap, mediaList) {
        currentPageId?.let { id -> mediaMap[id] ?: mediaList.find { it.id == id } }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        HorizontalPager(
            state = pagerState,
            pageSpacing = 18.dp,
            userScrollEnabled = !isCurrentPageZoomed,
            key = { mediaList[it].id },
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val item = mediaList[page]
            if (item.isVideo) {
                VideoPreviewPage(
                    item = item,
                    videoItems = videoItems,
                    isCurrentPage = pagerState.currentPage == page,
                    showControls = showControls,
                    sharedPlayer = sharedPlayer,
                    onTap = { showControls = !showControls },
                    onPlay = {
                        val playlist = videoItems.map { it.uri.toString() }
                        onPlayVideo(item.uri.toString(), playlist)
                    }
                )
            } else {
                ZoomableImagePage(
                    item = item,
                    onTap = { showControls = !showControls },
                    onDismiss = onClose,
                    onZoomChanged = { isZoomed -> zoomedPages[page] = isZoomed },
                    onControlsVisibilityChange = { visible -> showControls = visible }
                )
            }
        }

        val topGradientBrush = remember {
            Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent))
        }
        val bottomGradientBrush = remember {
            Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f)))
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
                    .padding(horizontal = 18.dp, vertical = 16.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledIconButton(
                        onClick = onClose,
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White.copy(alpha = 0.16f))
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                    }
                    Spacer(Modifier.size(48.dp))
                }
            }
        }

        AnimatedVisibility(
            visible = showControls,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            val currentItem = liveCurrentItem ?: return@AnimatedVisibility
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(bottomGradientBrush)
                    .navigationBarsPadding()
                    .padding(bottom = 18.dp)
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PremiumViewerAction(
                        icon = Icons.Outlined.Edit,
                        label = "Edit"
                    ) {
                        onEdit(currentItem)
                    }
                    PremiumViewerAction(
                        icon = if (favoriteIds.contains(currentItem.id)) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        label = if (favoriteIds.contains(currentItem.id)) "Unfavorite" else "Favorite",
                        tint = if (favoriteIds.contains(currentItem.id)) Color.Red else Color.White
                    ) {
                        onToggleFavorite(currentItem.id)
                    }
                    PremiumViewerAction(Icons.Outlined.Share, "Share") {
                        val mimeType = if (currentItem.isVideo) "video/*" else "image/*"
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = mimeType
                            putExtra(Intent.EXTRA_STREAM, currentItem.uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }, "Share Media"))
                    }
                    PremiumViewerAction(Icons.Outlined.Delete, "Delete", Color.Red) {
                        onDelete(currentItem)
                    }
                    PremiumViewerAction(Icons.Default.MoreVert, "More") {
                        showMoreMenu = true
                    }
                }
            }
        }
    }

    if (showMetadataSheet) {
        liveCurrentItem?.let {
            MediaMetadataSheet(it) { showMetadataSheet = false }
        }
    }

    if (showMoreMenu) {
        val currentItem = liveCurrentItem ?: return
        ModalBottomSheet(
            onDismissRequest = { showMoreMenu = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.padding(bottom = 32.dp)) {
                ListItem(
                    headlineContent = { Text("Details", fontWeight = FontWeight.SemiBold) },
                    leadingContent = { Icon(Icons.Outlined.Info, null) },
                    modifier = Modifier.clickable {
                        showMoreMenu = false
                        showMetadataSheet = true
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
                if (currentItem.isVideo) {
                    ListItem(
                        headlineContent = { Text("Open In", fontWeight = FontWeight.SemiBold) },
                        leadingContent = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, null) },
                        modifier = Modifier.clickable {
                            showMoreMenu = false
                            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(currentItem.uri, "video/*")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            })
                        }
                    )
                } else {
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
    videoItems: List<MediaItem>,
    isCurrentPage: Boolean,
    showControls: Boolean,
    sharedPlayer: Player,
    onTap: () -> Unit,
    onPlay: () -> Unit
) {
    val context = LocalContext.current
    var muted by rememberSaveable(item.id) { mutableStateOf(true) }
    val videoIndex = remember(item.id, videoItems) { videoItems.indexOfFirst { it.id == item.id } }

    LaunchedEffect(muted) {
        sharedPlayer.volume = if (muted) 0f else 1f
    }

    LaunchedEffect(isCurrentPage) {
        if (isCurrentPage) {
            if (videoIndex >= 0 && sharedPlayer.currentMediaItemIndex != videoIndex) {
                sharedPlayer.seekTo(videoIndex, 0)
            }
            sharedPlayer.playWhenReady = false
        } else {
            sharedPlayer.pause()
        }
    }

    val playerView = remember {
        PlayerView(context).apply {
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            layoutParams = android.view.ViewGroup.LayoutParams(-1, -1)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { playerView },
            update = { view ->
                if (view.player != sharedPlayer) {
                    view.player = sharedPlayer
                }
            },
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
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 90.dp)
            ) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clickable {
                            sharedPlayer.pause()
                            onPlay()
                        },
                    shape = RoundedCornerShape(50.dp),
                    color = Color.Black.copy(alpha = 0.55f)
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Play video", color = Color.White, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun PremiumViewerAction(icon: ImageVector, label: String, tint: Color = Color.White, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            modifier = Modifier
                .size(58.dp)
                .clip(CircleShape)
                .clickable(onClick = onClick),
            shape = CircleShape,
            color = Color.White.copy(alpha = 0.12f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = tint,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = label,
            color = tint.copy(alpha = 0.95f),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun ZoomableImagePage(
    item: MediaItem,
    onTap: () -> Unit,
    onDismiss: () -> Unit,
    onZoomChanged: (Boolean) -> Unit,
    onControlsVisibilityChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current

    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.roundToPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.roundToPx() }
    val dismissThreshold = remember(configuration.screenHeightDp, density) {
        with(density) { configuration.screenHeightDp.dp.toPx() * 0.25f }
    }

    val scale = remember { Animatable(1f) }
    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }
    var backgroundAlpha by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(scale.value) {
        onZoomChanged(scale.value > 1.05f)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = backgroundAlpha))
            .graphicsLayer {
                val dismissScale = 1f - (abs(offsetY.value) / 2200f)
                scaleX = scale.value * dismissScale
                scaleY = scaleX
                alpha = (1f - (abs(offsetY.value) / 850f)).coerceIn(0f, 1f)
                translationX = offsetX.value
                translationY = offsetY.value
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        scope.launch {
                            scale.animateTo(
                                targetValue = if (scale.value > 1f) 1f else 2.5f,
                                animationSpec = spring(stiffness = Spring.StiffnessLow)
                            )
                            offsetX.animateTo(0f)
                            offsetY.animateTo(0f)
                        }
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

                        if (abs(zoom - 1f) > 0.005f) {
                            scope.launch {
                                scale.snapTo((scale.value * zoom).coerceIn(1f, 4f))
                            }
                        }

                        if (scale.value > 1.05f) {
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                            val maxX = (size.width * (scale.value - 1)) / 2f
                            val maxY = (size.height * (scale.value - 1)) / 2f

                            scope.launch {
                                offsetX.snapTo((offsetX.value + pan.x).coerceIn(-maxX, maxX))
                                offsetY.snapTo((offsetY.value + pan.y).coerceIn(-maxY, maxY))
                            }
                        } else {
                            val isVerticalDrag = abs(pan.y) > abs(pan.x)
                            if (isVerticalDrag && event.changes.size == 1) {
                                scope.launch { offsetY.snapTo(offsetY.value + pan.y) }
                                backgroundAlpha = (1f - abs(offsetY.value) / 900f).coerceIn(0.35f, 1f)

                                if (abs(offsetY.value) > 50f) {
                                    onControlsVisibilityChange(false)
                                }

                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    if (scale.value <= 1.05f) {
                        if (abs(offsetY.value) > dismissThreshold) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onDismiss()
                        } else {
                            scope.launch {
                                offsetY.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                                backgroundAlpha = 1f
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val requestBuilder = remember(item.id, screenWidthPx, screenHeightPx) {
            ImageRequest.Builder(context)
                .data(item.uri)
                .size(Size(screenWidthPx, screenHeightPx))
                .allowHardware(true)
                .precision(Precision.INEXACT)
                .networkCachePolicy(CachePolicy.ENABLED)
                .memoryCacheKey("full_${item.id}")
                .memoryCachePolicy(CachePolicy.ENABLED)
                .crossfade(true)
                .error(android.R.drawable.ic_menu_report_image)
                .build()
        }

        AsyncImage(
            model = requestBuilder,
            placeholder = ColorPainter(Color.Black),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )
    }
}