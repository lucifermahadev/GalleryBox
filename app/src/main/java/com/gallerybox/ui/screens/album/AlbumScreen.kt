@file:Suppress("UnsafeOptInUsageError", "UnstableApiUsage", "OPT_IN_USAGE", "unused", "DEPRECATION")
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.gallerybox.ui.screens.album

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
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.Player
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
import com.gallerybox.viewmodel.SecurityViewModel
import com.gallerybox.viewmodel.TrashViewModel
import kotlinx.collections.immutable.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

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
enum class StorageFilter { ALL, PHONE, SD_CARD }

@Stable
data class AlbumActions(
    val onAlbumClick: (Album) -> Unit,
    val onNavigateToFavorites: () -> Unit,
    val onNavigateToTrash: () -> Unit,
    val onNavigateToHidden: () -> Unit,
    val onLockApp: () -> Unit = {},
    val onNavigateToDuplicates: () -> Unit,
    val onNavigateToScan: () -> Unit,
    val onNavigateToThemePicker: () -> Unit = {}
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
    val onLockApp: () -> Unit = {},
    val onNavigateToWallpaper: (String, Long) -> Unit,
    val onAddMediaToAlbum: ((String) -> Unit)? = null,
    val onDeleteAlbum: ((String) -> Unit)? = null
)

sealed class AlbumUiDialog {
    data object None : AlbumUiDialog()
    data object GridSize : AlbumUiDialog()
    data object Sort : AlbumUiDialog()
    data object Theme : AlbumUiDialog()
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

fun Modifier.glassEffect(isEnabled: Boolean): Modifier {
    return if (isEnabled) {
        this.background(Color.Black.copy(alpha = 0.4f))
    } else {
        this
    }
}

fun Context.findFragmentActivity(): FragmentActivity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is FragmentActivity) return context
        context = context.baseContext
    }
    return null
}

fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
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
    return try { File(path).parentFile?.name ?: "Unknown Folder" } catch (e: Exception) { "Unknown Folder" }
}

private fun isLowRAMDevice(context: Context): Boolean {
    val m = ActivityManager.MemoryInfo()
    (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(m)
    return m.totalMem <= 4L * 1024 * 1024 * 1024
}

fun formatDuration(durationMs: Long): String {
    val t = durationMs / 1000; val m = (t / 60) % 60; val h = t / 3600; val s = t % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
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
        val hasImg = items.any { !it.isVideo }; val hasVid = items.any { it.isVideo }
        type = if (hasVid && !hasImg) "video/*" else if (hasImg && !hasVid) "image/*" else "*/*"
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (items.size > 1) putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(items.map { it.uri }))
        else putExtra(Intent.EXTRA_STREAM, items.first().uri)
    }
    try { context.startActivity(Intent.createChooser(intent, "Share via")) } catch (e: Exception) { Toast.makeText(context, "No app found", Toast.LENGTH_SHORT).show() }
}

fun toggleAppLock(
    context: Context,
    securityViewModel: SecurityViewModel,
    currentState: Boolean,
    onStateChanged: (Boolean) -> Unit
) {
    val activity = context.findFragmentActivity()
    if (activity == null) {
        Toast.makeText(context, "Cannot authenticate (Context Invalid)", Toast.LENGTH_SHORT).show()
        return
    }
    if (!securityViewModel.canUseSystemAuthentication()) {
        Toast.makeText(context, "System authentication unavailable. Set up a screen lock first.", Toast.LENGTH_LONG).show()
        return
    }

    androidx.biometric.BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(context),
        object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                val newState = !currentState
                securityViewModel.setAppLockEnabled(newState)
                onStateChanged(newState)
                Toast.makeText(context, if (newState) "App Lock Enabled" else "App Lock Disabled", Toast.LENGTH_SHORT).show()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                Toast.makeText(context, "Authentication error: $errString", Toast.LENGTH_SHORT).show()
            }
        }
    ).authenticate(
        androidx.biometric.BiometricPrompt.PromptInfo.Builder()
            .setTitle("GalleryBox Security")
            .setSubtitle("Confirm identity to change lock settings")
            .setAllowedAuthenticators(
                androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
    )
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
            .apply { if (isVideo) decoderFactory(coil.decode.VideoFrameDecoder.Factory()) }
            .build()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumScreen(
    viewModel: GalleryViewModel = hiltViewModel(),
    trashViewModel: TrashViewModel = hiltViewModel(),
    securityViewModel: SecurityViewModel = hiltViewModel(),
    onViewerStateChanged: (Boolean) -> Unit = {},
    actions: AlbumActions
) {
    val context = LocalContext.current
    val isLowRam = remember { isLowRAMDevice(context) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())
    val enginePrefs = remember { context.getSharedPreferences("gallery_engine_prefs", Context.MODE_PRIVATE) }

    val vmAlbums by viewModel.albumsState.collectAsState(initial = emptyList())
    val rawAlbumPreviews by viewModel.albumPreviewMap.collectAsState()
    val sortOption by viewModel.albumSort.collectAsState()
    val viewerState by viewModel.viewerState.collectAsState()
    val allMedia by viewModel.media.collectAsState()
    val favoriteIds by viewModel.favoriteIds.collectAsState()
    val hiddenAlbums by viewModel.hiddenAlbums.collectAsState()

    var isAppLockEnabled by remember { mutableStateOf(securityViewModel.isAppLockEnabled()) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<ImmutableSet<String>>(persistentListOf<String>().toImmutableSet()) }
    var activeDialog by remember { mutableStateOf<AlbumUiDialog>(AlbumUiDialog.None) }
    var showSelectionMenu by remember { mutableStateOf(false) }
    var optimisticallyRemovedAlbums by remember { mutableStateOf(persistentSetOf<String>()) }

    var themeMode by remember { mutableStateOf(enginePrefs.getString("theme_mode", "DARK") ?: "DARK") }
    var bgUri by remember { mutableStateOf(enginePrefs.getString("bg_uri", null)) }
    val isGlassTheme = themeMode == "IMAGE" && bgUri != null

    LaunchedEffect(viewerState, isSelectionMode) { onViewerStateChanged(viewerState is GalleryViewerState.Open || isSelectionMode) }

    val albumPreviews = remember(rawAlbumPreviews) { rawAlbumPreviews.mapValues { it.value.toImmutableList() }.toImmutableMap() }

    val displayAlbums = remember(vmAlbums, searchQuery, sortOption, favoriteIds, allMedia, optimisticallyRemovedAlbums) {
        val favMedia = allMedia.filter { favoriteIds.contains(it.id) }
        val virtualAlbumsMutable = vmAlbums.filter { it.id.startsWith("virtual_") && it.id != ID_FAVORITES && albumMatchesQuery(it, searchQuery) }.toMutableList()
        if (favMedia.isNotEmpty()) {
            val updatedFavAlbum = vmAlbums.find { it.id == ID_FAVORITES }?.copy(mediaCount = favMedia.size, sizeBytes = favMedia.sumOf { it.size }, coverUri = favMedia.firstOrNull()?.uri ?: Uri.EMPTY) ?: Album(ID_FAVORITES, "Favorites", favMedia.firstOrNull()?.uri ?: Uri.EMPTY, favMedia.size, favMedia.sumOf { it.size }, true)
            if (albumMatchesQuery(updatedFavAlbum, searchQuery)) virtualAlbumsMutable.add(updatedFavAlbum)
        }
        val sortedVirtualAlbums = virtualAlbumsMutable.sortedBy { when (it.id) { ID_RECENT -> 0; ID_FAVORITES -> 1; ID_DOWNLOADS -> 2; else -> 99 } }
        val userAlbums = vmAlbums.filter { !it.id.startsWith("virtual_") && albumMatchesQuery(it, searchQuery) && !optimisticallyRemovedAlbums.contains(it.id) }
        val sortedUserAlbums = if (sortOption == AlbumSort.Custom) userAlbums else userAlbums.sortedWith(Comparator { a, b ->
            if (a.isPinned != b.isPinned) b.isPinned.compareTo(a.isPinned)
            else when (sortOption.name) { "NameAsc" -> a.name.compareTo(b.name, ignoreCase = true); "NameDesc" -> b.name.compareTo(a.name, ignoreCase = true); "SizeDesc" -> b.sizeBytes.compareTo(a.sizeBytes); "CountDesc" -> b.mediaCount.compareTo(a.mediaCount); else -> 0 }
        })
        (sortedVirtualAlbums + sortedUserAlbums).toImmutableList()
    }

    val sdCardAlbums = remember(allMedia) {
        allMedia.filter { it.path.startsWith("/storage/") && !it.path.startsWith("/storage/emulated/") && !it.path.startsWith("/storage/self/") }
            .map { it.bucketId }
            .toSet()
    }

    val dynamicList = remember { mutableStateListOf<Album>() }

    LaunchedEffect(displayAlbums) {
        if (!isDragging) {
            if (dynamicList.map { it.id } != displayAlbums.map { it.id }) {
                dynamicList.clear()
                dynamicList.addAll(displayAlbums)
            }
        }
    }

    val screenWidthDp = LocalConfiguration.current.screenWidthDp.toFloat()
    val prefs = remember { context.getSharedPreferences("gallery_prefs", Context.MODE_PRIVATE) }
    var columnCount by remember { mutableIntStateOf(prefs.getInt("gallery_album_grid_columns", 4)) }
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
                is GalleryEvent.OperationSuccess -> { isSelectionMode = false; selectedIds = persistentListOf<String>().toImmutableSet(); Toast.makeText(context, "Moved to Trash", Toast.LENGTH_SHORT).show() }
                is GalleryEvent.ShowToast -> Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                else -> {}
            }
        }
    }

    BackHandler(enabled = isSearchActive) { isSearchActive = false; searchQuery = "" }
    BackHandler(enabled = isSelectionMode) { isSelectionMode = false; selectedIds = persistentListOf<String>().toImmutableSet() }
    BackHandler(enabled = activeDialog != AlbumUiDialog.None) { activeDialog = AlbumUiDialog.None }
    BackHandler(enabled = viewerState is GalleryViewerState.Open) { viewModel.closeViewer() }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (isGlassTheme) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(bgUri).crossfade(true).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f))) // Dim Overlay
        }

        Scaffold(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                if (isSelectionMode) {
                    TopAppBar(
                        title = { Text(text = "${selectedIds.size} selected", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) },
                        navigationIcon = { IconButton(onClick = { isSelectionMode = false; selectedIds = persistentListOf<String>().toImmutableSet() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close Selection") } },
                        actions = {
                            val isAllSelected = selectedIds.size == dynamicList.size && dynamicList.isNotEmpty()
                            TextButton(onClick = { selectedIds = if (isAllSelected) persistentListOf<String>().toImmutableSet() else dynamicList.map { it.id }.toImmutableSet() }) {
                                Text(text = if (isAllSelected) "Deselect All" else "Select All", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = if(isGlassTheme) Color.Transparent else MaterialTheme.colorScheme.surface),
                        modifier = Modifier.glassEffect(isGlassTheme)
                    )
                } else if (isSearchActive) {
                    SearchTopBar(query = searchQuery, onQueryChange = { searchQuery = it }, onClose = { isSearchActive = false; searchQuery = "" }, isGlassTheme = isGlassTheme)
                } else {
                    ModernAlbumTopBar(
                        scrollBehavior = scrollBehavior,
                        isAppLockEnabled = isAppLockEnabled,
                        isGlassTheme = isGlassTheme,
                        onSearchClick = { isSearchActive = true },
                        onMenuAction = { action ->
                            when (action) {
                                "grid" -> activeDialog = AlbumUiDialog.GridSize
                                "sort" -> activeDialog = AlbumUiDialog.Sort
                                "theme" -> activeDialog = AlbumUiDialog.Theme
                                "create" -> activeDialog = AlbumUiDialog.CreateAlbum
                                "trash" -> actions.onNavigateToTrash()
                                "hidden" -> activeDialog = AlbumUiDialog.HiddenAlbums
                                "duplicates" -> actions.onNavigateToDuplicates()
                                "scan" -> actions.onNavigateToScan()
                                "toggle_lock" -> toggleAppLock(context, securityViewModel, isAppLockEnabled) { isAppLockEnabled = it }
                            }
                        }
                    )
                }
            }
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
                if (dynamicList.isEmpty()) {
                    Box(modifier = Modifier.weight(1f)) {
                        EmptyAlbumsOverlay(onCreateClick = { activeDialog = AlbumUiDialog.CreateAlbum })
                    }
                } else {
                    Box(modifier = Modifier.weight(1f)) {
                        StatelessAlbumGrid(
                            gridState = gridState,
                            padding = PaddingValues(bottom = padding.calculateBottomPadding()),
                            columnCount = columnCount,
                            dynamicList = dynamicList,
                            albumPreviews = albumPreviews,
                            isSelectionMode = isSelectionMode,
                            selectedIds = selectedIds,
                            sortOption = sortOption,
                            searchQuery = searchQuery,
                            screenWidthDp = screenWidthDp,
                            isLowRam = isLowRam,
                            sdCardAlbums = sdCardAlbums,
                            onOrderSaved = { albums: List<Album> -> viewModel.saveCustomAlbumOrder(albums) },
                            onAlbumClick = { album ->
                                if (isSelectionMode) selectedIds = if (selectedIds.contains(album.id)) (selectedIds - album.id).toImmutableSet() else (selectedIds + album.id).toImmutableSet()
                                else actions.onAlbumClick(album)
                            },
                            onAlbumLongClick = { album ->
                                isSelectionMode = true
                                selectedIds = persistentSetOf(album.id)
                            },
                            onDragStateChange = { dragging ->
                                isDragging = dragging
                                if (!dragging) {
                                    viewModel.saveCustomAlbumOrder(dynamicList.toList())
                                }
                            }
                        )
                    }
                }
            }
        }

        if (isSelectionMode) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().glassEffect(isGlassTheme),
                color = if(isGlassTheme) Color.Transparent else MaterialTheme.colorScheme.surface, shadowElevation = 16.dp, shape = RectangleShape
            ) {
                Row(modifier = Modifier.navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    BottomBarActionItem(icon = Icons.AutoMirrored.Outlined.DriveFileMove, label = "Move") { activeDialog = AlbumUiDialog.MoveCopy(dynamicList.filter { selectedIds.contains(it.id) }, true) }
                    BottomBarActionItem(icon = Icons.Outlined.Share, label = "Share") { shareMediaItems(context, allMedia.filter { selectedIds.contains(it.bucketId) }); isSelectionMode = false; selectedIds = persistentListOf<String>().toImmutableSet() }
                    BottomBarActionItem(icon = Icons.Outlined.Delete, label = "Delete", isDestructive = true) { activeDialog = AlbumUiDialog.Delete(dynamicList.filter { selectedIds.contains(it.id) }) }
                    Box {
                        BottomBarActionItem(icon = Icons.Default.MoreVert, label = "More") { showSelectionMenu = true }
                        DropdownMenu(expanded = showSelectionMenu, onDismissRequest = { showSelectionMenu = false }, modifier = Modifier.glassEffect(isGlassTheme)) {
                            val allPinned = dynamicList.filter { selectedIds.contains(it.id) }.all { it.isPinned }
                            if (selectedIds.size == 1) DropdownMenuItem(text = { Text("Rename") }, onClick = { showSelectionMenu = false; dynamicList.find { it.id == selectedIds.first() }?.let { activeDialog = AlbumUiDialog.Rename(it) } }, colors = MenuDefaults.itemColors(Color.Transparent))
                            DropdownMenuItem(text = { Text(if (allPinned) "Unpin" else "Pin") }, onClick = { showSelectionMenu = false; dynamicList.filter { selectedIds.contains(it.id) }.forEach { viewModel.toggleAlbumPin(it) }; isSelectionMode = false; selectedIds = persistentListOf<String>().toImmutableSet() }, colors = MenuDefaults.itemColors(Color.Transparent))
                            if (selectedIds.size == 1) DropdownMenuItem(text = { Text("Info") }, onClick = { showSelectionMenu = false; dynamicList.find { it.id == selectedIds.first() }?.let { activeDialog = AlbumUiDialog.Info(it) } }, colors = MenuDefaults.itemColors(Color.Transparent))
                            DropdownMenuItem(text = { Text("Copy") }, onClick = { showSelectionMenu = false; activeDialog = AlbumUiDialog.MoveCopy(dynamicList.filter { selectedIds.contains(it.id) }, false) }, colors = MenuDefaults.itemColors(Color.Transparent))
                            DropdownMenuItem(text = { Text("Hide Album") }, onClick = {
                                showSelectionMenu = false
                                val currentHidden = enginePrefs.getStringSet("hidden_albums", emptySet()) ?: emptySet()
                                val newHidden = currentHidden + selectedIds
                                enginePrefs.edit().putStringSet("hidden_albums", newHidden).apply()
                                selectedIds.forEach { id -> viewModel.toggleHiddenAlbum(id) }
                                isSelectionMode = false
                                selectedIds = persistentListOf<String>().toImmutableSet()
                                Toast.makeText(context, "Albums hidden", Toast.LENGTH_SHORT).show()
                            }, leadingIcon = { Icon(Icons.Outlined.VisibilityOff, null) }, colors = MenuDefaults.itemColors(Color.Transparent))
                        }
                    }
                }
            }
        }
    }

    when (val dialog = activeDialog) {
        is AlbumUiDialog.Theme -> {
            ModernThemeSheet(
                currentTheme = themeMode,
                hasBackgroundImage = bgUri != null,
                isGlassTheme = isGlassTheme,
                onDismiss = { activeDialog = AlbumUiDialog.None },
                onSelectLight = { themeMode = "LIGHT"; enginePrefs.edit().putString("theme_mode", "LIGHT").apply(); activeDialog = AlbumUiDialog.None },
                onSelectDark = { themeMode = "DARK"; enginePrefs.edit().putString("theme_mode", "DARK").apply(); activeDialog = AlbumUiDialog.None },
                onSelectImage = { actions.onNavigateToThemePicker(); activeDialog = AlbumUiDialog.None },
                onChangeImage = { actions.onNavigateToThemePicker(); activeDialog = AlbumUiDialog.None },
                onRemoveImage = { bgUri = null; themeMode = "DARK"; enginePrefs.edit().remove("bg_uri").putString("theme_mode", "DARK").apply(); activeDialog = AlbumUiDialog.None }
            )
        }
        is AlbumUiDialog.Info -> {
            ModalBottomSheet(onDismissRequest = { activeDialog = AlbumUiDialog.None }, containerColor = if(isGlassTheme) Color.Transparent else MaterialTheme.colorScheme.surface, modifier = Modifier.glassEffect(isGlassTheme)) {
                Column(Modifier.padding(24.dp).padding(bottom = 24.dp)) {
                    val albumItems = allMedia.filter { it.bucketId == dialog.album.id }
                    val oldestItem = albumItems.minByOrNull { it.dateAdded }
                    val dateStr = oldestItem?.let { SimpleDateFormat("MMMM dd, yyyy 'at' hh:mm a", Locale.getDefault()).format(Date(it.dateAdded * 1000)) } ?: "Unknown"
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
            ModalBottomSheet(onDismissRequest = { activeDialog = AlbumUiDialog.None }, containerColor = if(isGlassTheme) Color.Transparent else MaterialTheme.colorScheme.surface, modifier = Modifier.glassEffect(isGlassTheme)) {
                val allAlbumsState by viewModel.allAlbumsState.collectAsState(initial = emptyList())
                Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                    Text(text = if (dialog.isMove) "Move To..." else "Copy To...", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp))
                    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f, fill = false), contentPadding = PaddingValues(bottom = 12.dp)) {
                        item { ListItem(headlineContent = { Text("Create New Album", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) }, leadingContent = { Icon(Icons.Rounded.CreateNewFolder, null, tint = MaterialTheme.colorScheme.primary) }, modifier = Modifier.clickable { activeDialog = AlbumUiDialog.CreateAndMoveCopy(dialog.albums, dialog.isMove) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent)) }
                        items(allAlbumsState.filter { !it.id.startsWith("virtual_") && dialog.albums.none { sel -> sel.id == it.id } }.sortedBy { it.name.lowercase() }) { targetAlbum ->
                            ListItem(headlineContent = { Text(targetAlbum.name, fontWeight = FontWeight.Medium) }, leadingContent = { Icon(Icons.Outlined.Folder, null) }, modifier = Modifier.clickable {
                                if (dialog.isMove) optimisticallyRemovedAlbums = optimisticallyRemovedAlbums.addAll(dialog.albums.map { id -> id.id })
                                viewModel.mergeAlbums(sourceAlbumIds = dialog.albums.map { id -> id.id }, targetAlbumId = targetAlbum.id, mergeMode = if (dialog.isMove) MergeMode.MOVE_AND_DELETE else MergeMode.COPY)
                                viewModel.forceSync()
                                activeDialog = AlbumUiDialog.None; isSelectionMode = false; selectedIds = persistentListOf<String>().toImmutableSet()
                            }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                        }
                    }
                }
            }
        }
        is AlbumUiDialog.CreateAndMoveCopy -> {
            val initialName = if (dialog.albums.size == 1) "${dialog.albums.first().name} Copy" else "New Album"
            ModernInputSheet(title = if (dialog.isMove) "New Album & Move" else "New Album & Copy", initial = initialName, isGlassTheme = isGlassTheme, onDismiss = { activeDialog = AlbumUiDialog.None }, onConfirm = { newName: String ->
                val mediaIds = allMedia.filter { item -> dialog.albums.any { album -> album.id == item.bucketId } }.map { it.id }
                if (dialog.isMove) viewModel.createAndMove(mediaIds, newName) else viewModel.createAndCopy(mediaIds, newName)
                if (dialog.isMove) optimisticallyRemovedAlbums = optimisticallyRemovedAlbums.addAll(dialog.albums.map { id -> id.id })
                viewModel.forceSync()
                activeDialog = AlbumUiDialog.None; isSelectionMode = false; selectedIds = persistentListOf<String>().toImmutableSet()
            })
        }
        is AlbumUiDialog.Rename -> ModernInputSheet(title = "Rename Album", initial = dialog.album.name, isGlassTheme = isGlassTheme, onDismiss = { activeDialog = AlbumUiDialog.None }, onConfirm = { newName: String -> viewModel.renameAlbum(dialog.album, newName); viewModel.forceSync(); activeDialog = AlbumUiDialog.None; isSelectionMode = false; selectedIds = persistentListOf<String>().toImmutableSet() })
        is AlbumUiDialog.Delete -> ModernSmartDeleteSheet(count = dialog.albums.size, isGlassTheme = isGlassTheme, onDismiss = { activeDialog = AlbumUiDialog.None }, onDeleteAll = {
            optimisticallyRemovedAlbums = optimisticallyRemovedAlbums.addAll(dialog.albums.map { it.id })
            trashViewModel.confirmPendingAlbumTrash(dialog.albums, allMedia.toList())
            viewModel.forceSync()
            activeDialog = AlbumUiDialog.None; isSelectionMode = false; selectedIds = persistentListOf<String>().toImmutableSet()
        })
        is AlbumUiDialog.CreateAlbum -> ModernCreateAlbumSheet(isGlassTheme = isGlassTheme, onDismiss = { activeDialog = AlbumUiDialog.None }, onCreate = { name: String, sd: Boolean -> viewModel.createAlbum(name, sd); activeDialog = AlbumUiDialog.None })
        is AlbumUiDialog.Sort -> ModernAlbumSortSheet(activeSort = sortOption, isGlassTheme = isGlassTheme, onDismiss = { activeDialog = AlbumUiDialog.None }, onSortSelected = { sort: AlbumSort -> viewModel.updateAlbumSort(sort); activeDialog = AlbumUiDialog.None })
        is AlbumUiDialog.GridSize -> ModernGridSheet(currentColumns = columnCount, max = 8, isGlassTheme = isGlassTheme, onDismiss = { activeDialog = AlbumUiDialog.None }, onUpdate = { cols: Int -> columnCount = cols; prefs.edit().putInt("gallery_album_grid_columns", cols).apply() })
        is AlbumUiDialog.HiddenAlbums -> {
            ModalBottomSheet(onDismissRequest = { activeDialog = AlbumUiDialog.None }, containerColor = if(isGlassTheme) Color.Transparent else MaterialTheme.colorScheme.surface, modifier = Modifier.glassEffect(isGlassTheme)) {
                val allPossibleAlbums = remember(allMedia) { allMedia.groupBy { it.bucketId }.map { (id, items) -> val first = items.first(); Album(id = id, name = first.bucketName, coverUri = first.uri, mediaCount = items.size, sizeBytes = items.sumOf { it.size }, isPinned = false) }.filter { !it.id.startsWith("virtual_") }.sortedBy { it.name.lowercase() } }
                Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                    Text(text = "Hide or Unhide", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp))
                    LazyColumn(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 12.dp)) {
                        items(allPossibleAlbums, key = { it.id }) { album ->
                            Row(modifier = Modifier.fillMaxWidth().clickable {
                                viewModel.toggleHiddenAlbum(album.id)
                                val newHidden = if (hiddenAlbums.contains(album.id)) hiddenAlbums - album.id else hiddenAlbums + album.id
                                enginePrefs.edit().putStringSet("hidden_albums", newHidden).apply()
                            }.padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(modifier = Modifier.weight(1f)) { Text(text = album.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium); Text(text = "${album.mediaCount} items", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                Switch(checked = hiddenAlbums.contains(album.id), onCheckedChange = {
                                    viewModel.toggleHiddenAlbum(album.id)
                                    val newHidden = if (hiddenAlbums.contains(album.id)) hiddenAlbums - album.id else hiddenAlbums + album.id
                                    enginePrefs.edit().putStringSet("hidden_albums", newHidden).apply()
                                })
                            }
                        }
                    }
                }
            }
        }
        else -> {}
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernThemeSheet(
    currentTheme: String,
    hasBackgroundImage: Boolean,
    isGlassTheme: Boolean,
    onDismiss: () -> Unit,
    onSelectLight: () -> Unit,
    onSelectDark: () -> Unit,
    onSelectImage: () -> Unit,
    onChangeImage: () -> Unit,
    onRemoveImage: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = if(isGlassTheme) Color.Transparent else MaterialTheme.colorScheme.surface, modifier = Modifier.glassEffect(isGlassTheme)) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text("Theme", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp))

            ListItem(
                headlineContent = { Text("Light") },
                leadingContent = { Icon(Icons.Outlined.LightMode, null) },
                trailingContent = { if (currentTheme == "LIGHT") Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.clickable { onSelectLight() },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )
            ListItem(
                headlineContent = { Text("Dark") },
                leadingContent = { Icon(Icons.Outlined.DarkMode, null) },
                trailingContent = { if (currentTheme == "DARK") Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.clickable { onSelectDark() },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )
            ListItem(
                headlineContent = { Text("Background Image") },
                leadingContent = { Icon(Icons.Outlined.Image, null) },
                trailingContent = { if (currentTheme == "IMAGE") Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.clickable { onSelectImage() },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )

            if (hasBackgroundImage) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                ListItem(
                    headlineContent = { Text("Change Background") },
                    leadingContent = { Icon(Icons.Outlined.SwapHoriz, null) },
                    modifier = Modifier.clickable { onChangeImage() },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
                ListItem(
                    headlineContent = { Text("Remove Background", color = MaterialTheme.colorScheme.error) },
                    leadingContent = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
                    modifier = Modifier.clickable { onRemoveImage() },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AlbumDetailScreen(
    albumId: String,
    viewModel: GalleryViewModel = hiltViewModel(),
    trashViewModel: TrashViewModel = hiltViewModel(),
    securityViewModel: SecurityViewModel = hiltViewModel(),
    onViewerStateChanged: (Boolean) -> Unit = {},
    actions: DetailActions
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val snackbarHostState = remember { SnackbarHostState() }
    val gridState = rememberLazyGridState()
    val isLowRam = remember { isLowRAMDevice(context) }
    val enginePrefs = remember { context.getSharedPreferences("gallery_engine_prefs", Context.MODE_PRIVATE) }

    val mediaMap by viewModel.mediaMap.collectAsState()
    val favoriteIds by viewModel.favoriteIds.collectAsState()
    val vmAlbums by viewModel.albumsState.collectAsState(initial = emptyList())
    val viewerState by viewModel.viewerState.collectAsState()
    val allMedia by viewModel.media.collectAsState()

    var optimisticallyRemovedIds by remember { mutableStateOf(persistentSetOf<Long>()) }

    val albumMedia = remember(allMedia, albumId, favoriteIds) {
        allMedia.filter { item ->
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
    var isAppLockEnabled by remember { mutableStateOf(securityViewModel.isAppLockEnabled()) }

    var localSearchQuery by rememberSaveable { mutableStateOf("") }
    var debouncedSearchQuery by remember { mutableStateOf("") }
    LaunchedEffect(localSearchQuery) {
        delay(250)
        debouncedSearchQuery = localSearchQuery
    }

    var currentPhotoSort by rememberSaveable { mutableStateOf(PhotoSort.DateDesc) }
    var isSelectionMode by remember { mutableStateOf(false) }

    var selectedIds by remember { mutableStateOf<ImmutableSet<Long>>(persistentListOf<Long>().toImmutableSet()) }
    var selectedSize by remember { mutableLongStateOf(0L) }
    val dragSelection = remember { mutableSetOf<Long>() }

    val intentSenderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val g = result.resultCode == Activity.RESULT_OK
        trashViewModel.onPermissionResultGlobal(g)
        if (!g) Toast.makeText(context, "Permission Denied", Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(trashViewModel) {
        trashViewModel.events.collect { event ->
            when (event) {
                is GalleryEvent.RequestPermission -> intentSenderLauncher.launch(IntentSenderRequest.Builder(event.intentSender).build())
                is GalleryEvent.OperationSuccess -> { Toast.makeText(context, "Moved to Trash", Toast.LENGTH_SHORT).show() }
                is GalleryEvent.ShowToast -> Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                else -> {}
            }
        }
    }

    LaunchedEffect(viewerState, isSelectionMode) { onViewerStateChanged(viewerState is GalleryViewerState.Open || isSelectionMode) }

    LaunchedEffect(isSelectionMode) {
        if (!isSelectionMode) {
            dragSelection.clear()
            selectedIds = persistentListOf<Long>().toImmutableSet()
            selectedSize = 0L
        }
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

    val albumTitle = remember(albumId, album?.name) {
        album?.name ?: "Album"
    }

    val isVirtual = albumId.startsWith("virtual_")
    var showMediaSelectionMenu by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var mediaFilter by remember { mutableStateOf(AlbumMediaFilter.ALL) }
    var showRenameSheet by remember { mutableStateOf(false) }

    var themeMode by remember { mutableStateOf(enginePrefs.getString("theme_mode", "DARK") ?: "DARK") }
    var bgUri by remember { mutableStateOf(enginePrefs.getString("bg_uri", null)) }
    val isGlassTheme = themeMode == "IMAGE" && bgUri != null

    val configuration = LocalConfiguration.current; val density = LocalDensity.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.roundToPx() }
    val screenWidthDp = configuration.screenWidthDp.toFloat()

    val adaptiveCols = remember(screenWidthDp, isLowRam) {
        if (isLowRam) {
            if (screenWidthDp >= 800f) 6 else if (screenWidthDp >= 600f) 4 else 3
        } else {
            if (screenWidthDp >= 800f) 8 else if (screenWidthDp >= 600f) 6 else 4
        }
    }

    val prefs = remember { context.getSharedPreferences("gallery_prefs", Context.MODE_PRIVATE) }
    var detailColumns by remember { mutableIntStateOf(prefs.getInt("gallery_media_grid_columns", adaptiveCols)) }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())

    BackHandler(enabled = localSearchQuery.isNotEmpty()) { localSearchQuery = "" }
    BackHandler(enabled = isSelectionMode) { isSelectionMode = false }
    BackHandler(enabled = activeDialog != DetailUiDialog.None) { activeDialog = DetailUiDialog.None }
    BackHandler(enabled = metadataItemToShow != null) { metadataItemToShow = null }
    BackHandler(enabled = viewerState is GalleryViewerState.Open) { viewModel.closeViewer() }

    val filteredMedia = remember(albumMedia, mediaFilter, debouncedSearchQuery, currentPhotoSort) {
        val base = albumMedia.filter { item -> when (mediaFilter) { AlbumMediaFilter.ALL -> true; AlbumMediaFilter.PHOTOS -> !item.isVideo; AlbumMediaFilter.VIDEOS -> item.isVideo } }
        val searched = if (debouncedSearchQuery.isBlank()) base else { val q = debouncedSearchQuery.trim().lowercase(); base.filter { it.name.lowercase().contains(q) || getSmartName(it).lowercase().contains(q) } }
        val comparator = when (currentPhotoSort) { PhotoSort.DateDesc -> Comparator<MediaItem> { a, b -> b.dateAdded.compareTo(a.dateAdded) }; PhotoSort.DateAsc -> Comparator<MediaItem> { a, b -> a.dateAdded.compareTo(b.dateAdded) }; PhotoSort.NameAsc -> Comparator<MediaItem> { a, b -> a.name.compareTo(b.name, ignoreCase = true) }; PhotoSort.NameDesc -> Comparator<MediaItem> { a, b -> b.name.compareTo(a.name, ignoreCase = true) }; PhotoSort.SizeDesc -> Comparator<MediaItem> { a, b -> b.size.compareTo(a.size) } }
        searched.sortedWith(comparator).toImmutableList()
    }

    val actuallyFilteredMedia = remember(filteredMedia, optimisticallyRemovedIds) {
        filteredMedia.filter { !optimisticallyRemovedIds.contains(it.id) }.toImmutableList()
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (isGlassTheme) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(bgUri).crossfade(true).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f))) // Dim Overlay
        }

        Scaffold(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                if (isSelectionMode) {
                    TopAppBar(
                        title = { Text(text = "${selectedIds.size} selected", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) },
                        navigationIcon = { IconButton(onClick = { isSelectionMode = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close Selection") } },
                        actions = {
                            val isAllSelected = selectedIds.size == actuallyFilteredMedia.size && actuallyFilteredMedia.isNotEmpty()
                            TextButton(onClick = {
                                if (isAllSelected) {
                                    dragSelection.clear()
                                    selectedSize = 0L
                                } else {
                                    dragSelection.clear()
                                    actuallyFilteredMedia.forEach { dragSelection.add(it.id) }
                                    selectedSize = actuallyFilteredMedia.sumOf { it.size }
                                }
                                selectedIds = dragSelection.toImmutableSet()
                            }) { Text(text = if (isAllSelected) "Deselect All" else "Select All", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = if(isGlassTheme) Color.Transparent else MaterialTheme.colorScheme.surface),
                        modifier = Modifier.glassEffect(isGlassTheme)
                    )
                } else {
                    CenterAlignedTopAppBar(
                        title = { Text(text = albumTitle, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold) },
                        navigationIcon = { IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                        actions = {
                            Box {
                                IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, "More") }
                                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, modifier = Modifier.clip(RoundedCornerShape(12.dp)).glassEffect(isGlassTheme)) {
                                    DropdownMenuItem(text = { Text("Select items") }, onClick = { isSelectionMode = true; showMenu = false }, leadingIcon = { Icon(Icons.Outlined.Checklist, null) }, colors = MenuDefaults.itemColors(Color.Transparent))
                                    if (!isVirtual) DropdownMenuItem(text = { Text("Add Photos") }, onClick = { actions.onAddMediaToAlbum?.invoke(albumId); showMenu = false }, leadingIcon = { Icon(Icons.Rounded.AddPhotoAlternate, null) }, colors = MenuDefaults.itemColors(Color.Transparent))
                                    DropdownMenuItem(text = { Text("Sort Media") }, onClick = { activeDialog = DetailUiDialog.Sort; showMenu = false }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, null) }, colors = MenuDefaults.itemColors(Color.Transparent))
                                    DropdownMenuItem(text = { Text("Grid Size") }, onClick = { activeDialog = DetailUiDialog.GridSize; showMenu = false }, leadingIcon = { Icon(Icons.Default.Grid4x4, null) }, colors = MenuDefaults.itemColors(Color.Transparent))
                                    if (!isVirtual && album != null) {
                                        DropdownMenuItem(text = { Text("Hide Album") }, onClick = {
                                            viewModel.toggleHiddenAlbum(album.id)
                                            val currentHidden = enginePrefs.getStringSet("hidden_albums", emptySet()) ?: emptySet()
                                            enginePrefs.edit().putStringSet("hidden_albums", currentHidden + album.id).apply()
                                            Toast.makeText(context, "Album Hidden", Toast.LENGTH_SHORT).show()
                                            showMenu = false
                                            actions.onBack()
                                        }, leadingIcon = { Icon(Icons.Outlined.VisibilityOff, null) }, colors = MenuDefaults.itemColors(Color.Transparent))
                                        DropdownMenuItem(text = { Text(if (album.isPinned) "Unpin Album" else "Pin Album") }, onClick = { viewModel.toggleAlbumPin(album); showMenu = false }, leadingIcon = { Icon(if (album.isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin, null) }, colors = MenuDefaults.itemColors(Color.Transparent))
                                        DropdownMenuItem(text = { Text("Rename") }, onClick = { showRenameSheet = true; showMenu = false }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, colors = MenuDefaults.itemColors(Color.Transparent))
                                        DropdownMenuItem(text = { Text("Delete Album", color = MaterialTheme.colorScheme.error) }, onClick = { activeDialog = DetailUiDialog.DeleteAlbum; showMenu = false }, leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) }, colors = MenuDefaults.itemColors(Color.Transparent))
                                    }
                                    DropdownMenuItem(text = { Text(if (isAppLockEnabled) "Disable App Lock" else "Enable App Lock") }, onClick = { showMenu = false; toggleAppLock(context, securityViewModel, isAppLockEnabled) { isAppLockEnabled = it } }, leadingIcon = { Icon(if (isAppLockEnabled) Icons.Outlined.LockOpen else Icons.Outlined.Lock, null) }, colors = MenuDefaults.itemColors(Color.Transparent))
                                }
                            }
                        },
                        scrollBehavior = scrollBehavior,
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = if(isGlassTheme) Color.Transparent else MaterialTheme.colorScheme.surface),
                        modifier = Modifier.glassEffect(isGlassTheme)
                    )
                }
            }
        ) { padding ->
            if (actuallyFilteredMedia.isEmpty() && localSearchQuery.isBlank() && mediaFilter == AlbumMediaFilter.ALL) {
                Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(imageVector = Icons.Outlined.ImageNotSupported, contentDescription = null, modifier = Modifier.size(72.dp), tint = Color.LightGray)
                        Spacer(Modifier.height(16.dp))
                        Text(text = "No photos here", color = Color.Gray, style = MaterialTheme.typography.titleMedium)
                        if (!isVirtual) { Spacer(Modifier.height(24.dp)); FilledTonalButton(onClick = { actions.onAddMediaToAlbum?.invoke(albumId) }, shape = RoundedCornerShape(16.dp)) { Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("Add Photos") } }
                    }
                }
            } else {
                Column(Modifier.padding(padding)) {
                    StatelessMediaGrid(
                        gridState = gridState, mediaList = actuallyFilteredMedia, columnCount = detailColumns, screenWidthPx = screenWidthPx, isSelectionMode = isSelectionMode, selectedIds = selectedIds, isLowRam = isLowRam,
                        onToggleSelection = { item ->
                            if (dragSelection.contains(item.id)) {
                                dragSelection.remove(item.id)
                                selectedSize = maxOf(0L, selectedSize - item.size)
                            } else {
                                if (dragSelection.size < 5000) {
                                    dragSelection.add(item.id)
                                    selectedSize += item.size
                                }
                            }
                            selectedIds = dragSelection.toImmutableSet()
                        },
                        onForceSelect = { item ->
                            if (!dragSelection.contains(item.id) && dragSelection.size < 5000) {
                                dragSelection.add(item.id)
                                selectedSize += item.size
                                selectedIds = dragSelection.toImmutableSet()
                            }
                        },
                        onSelectAll = { isAllSelected ->
                            if (isAllSelected) {
                                dragSelection.clear()
                                selectedSize = 0L
                            } else {
                                dragSelection.clear()
                                actuallyFilteredMedia.forEach { dragSelection.add(it.id) }
                                selectedSize = actuallyFilteredMedia.sumOf { it.size }
                            }
                            selectedIds = dragSelection.toImmutableSet()
                        },
                        onMediaClick = { item -> viewModel.openViewer(item.id) },
                        onMediaLongClick = { if (!isSelectionMode) { isSelectionMode = true } },
                        onToggleFavorite = { viewModel.toggleFavorite(it) },
                        header = {
                            Column(modifier = Modifier.padding(horizontal = 13.dp)) {
                                BasicTextField(
                                    value = localSearchQuery, onValueChange = { localSearchQuery = it },
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).height(46.dp).clip(CircleShape).background(if(isGlassTheme) Color.White.copy(alpha=0.15f) else MaterialTheme.colorScheme.surfaceContainerHigh),
                                    singleLine = true, textStyle = LocalTextStyle.current.copy(fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface),
                                    decorationBox = { innerTextField ->
                                        Row(modifier = Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(imageVector = Icons.Rounded.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
                                            Spacer(Modifier.width(8.dp))
                                            Box(Modifier.weight(1f)) { if (localSearchQuery.isEmpty()) Text(text = "Search photos inside album...", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 15.sp); innerTextField() }
                                            if (localSearchQuery.isNotEmpty()) IconButton(onClick = { localSearchQuery = "" }, modifier = Modifier.size(28.dp)) { Icon(imageVector = Icons.Rounded.Close, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp)) }
                                        }
                                    }
                                )
                                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        SamsungFilterChip(selected = mediaFilter == AlbumMediaFilter.ALL, label = "All", isGlassTheme = isGlassTheme) { mediaFilter = AlbumMediaFilter.ALL }
                                        SamsungFilterChip(selected = mediaFilter == AlbumMediaFilter.PHOTOS, label = "Photos", isGlassTheme = isGlassTheme) { mediaFilter = AlbumMediaFilter.PHOTOS }
                                        SamsungFilterChip(selected = mediaFilter == AlbumMediaFilter.VIDEOS, label = "Videos", isGlassTheme = isGlassTheme) { mediaFilter = AlbumMediaFilter.VIDEOS }
                                    }
                                    Surface(onClick = { activeDialog = DetailUiDialog.Sort }, shape = RoundedCornerShape(16.dp), color = if(isGlassTheme) Color.White.copy(alpha=0.15f) else MaterialTheme.colorScheme.surfaceContainerHigh) {
                                        Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(imageVector = Icons.AutoMirrored.Filled.Sort, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text(text = when (currentPhotoSort) { PhotoSort.DateDesc -> "Newest"; PhotoSort.DateAsc -> "Oldest"; PhotoSort.NameAsc -> "A-Z"; PhotoSort.NameDesc -> "Z-A"; PhotoSort.SizeDesc -> "Size" }, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                                        }
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }

        if (isSelectionMode) {
            Surface(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().glassEffect(isGlassTheme), color = if(isGlassTheme) Color.Transparent else MaterialTheme.colorScheme.surface, shadowElevation = 16.dp, shape = RectangleShape) {
                Row(modifier = Modifier.navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    BottomBarActionItem(icon = Icons.AutoMirrored.Outlined.DriveFileMove, label = "Move") { actions.onNavigateToMoveCopy("MOVE", selectedIds.joinToString(","), albumId); isSelectionMode = false }
                    BottomBarActionItem(icon = Icons.Outlined.Share, label = "Share") { shareMediaItems(context, selectedIds.mapNotNull { mediaMap[it] }) }
                    BottomBarActionItem(icon = Icons.Outlined.Delete, label = "Delete", isDestructive = true) { activeDialog = DetailUiDialog.Delete(selectedIds.toList()) }
                    Box {
                        BottomBarActionItem(icon = Icons.Default.MoreVert, label = "More") { showMediaSelectionMenu = true }
                        DropdownMenu(expanded = showMediaSelectionMenu, onDismissRequest = { showMediaSelectionMenu = false }, modifier = Modifier.glassEffect(isGlassTheme)) {
                            DropdownMenuItem(text = { Text("Copy to album") }, onClick = { showMediaSelectionMenu = false; actions.onNavigateToMoveCopy("COPY", selectedIds.joinToString(","), albumId); isSelectionMode = false }, colors = MenuDefaults.itemColors(Color.Transparent))
                            if (selectedIds.size == 1) DropdownMenuItem(text = { Text("Details") }, onClick = { showMediaSelectionMenu = false; metadataItemToShow = mediaMap[selectedIds.first()] }, colors = MenuDefaults.itemColors(Color.Transparent))
                            if (albumId == ID_HIDDEN) DropdownMenuItem(text = { Text("Unhide") }, onClick = { showMediaSelectionMenu = false; viewModel.unhideMedia(selectedIds.toList()); Toast.makeText(context, "Items restored", Toast.LENGTH_SHORT).show(); isSelectionMode = false }, colors = MenuDefaults.itemColors(Color.Transparent))
                            else DropdownMenuItem(text = { Text("Hide") }, onClick = { showMediaSelectionMenu = false; viewModel.hideItems(selectedIds.toList()); Toast.makeText(context, "${selectedIds.size} items hidden", Toast.LENGTH_SHORT).show(); optimisticallyRemovedIds = optimisticallyRemovedIds.addAll(selectedIds); viewModel.forceSync(); isSelectionMode = false }, colors = MenuDefaults.itemColors(Color.Transparent))
                        }
                    }
                }
            }
        }
    }

    when (val dialog = activeDialog) {
        is DetailUiDialog.DeleteAlbum -> AlertDialog(onDismissRequest = { activeDialog = DetailUiDialog.None }, icon = { Icon(Icons.Outlined.DeleteForever, null, tint = MaterialTheme.colorScheme.error) }, title = { Text("Delete Album?") }, text = { Text("This will delete the manual album placeholder. Any physical media stored within this folder on your device will remain intact.") }, confirmButton = { Button(colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), onClick = { actions.onDeleteAlbum?.invoke(albumId); activeDialog = DetailUiDialog.None; actions.onBack() }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { activeDialog = DetailUiDialog.None }) { Text("Cancel") } })
        is DetailUiDialog.Delete -> AlertDialog(onDismissRequest = { activeDialog = DetailUiDialog.None }, icon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) }, title = { Text("Move to Trash?") }, text = { Text("Items will be moved to Trash. They can be recovered within 30 days.") }, confirmButton = { Button(colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), onClick = { val itemsToTrash = actuallyFilteredMedia.filter { dialog.mediaIds.contains(it.id) }; trashViewModel.confirmPendingGalleryTrash(itemsToTrash); optimisticallyRemovedIds = optimisticallyRemovedIds.addAll(dialog.mediaIds); viewModel.forceSync(); activeDialog = DetailUiDialog.None; isSelectionMode = false; viewModel.closeViewer() }) { Text("Move to Trash") } }, dismissButton = { TextButton(onClick = { activeDialog = DetailUiDialog.None }) { Text("Cancel") } })
        is DetailUiDialog.GridSize -> ModernGridSheet(currentColumns = detailColumns, max = 8, isGlassTheme = isGlassTheme, onDismiss = { activeDialog = DetailUiDialog.None }, onUpdate = { cols: Int -> detailColumns = cols; prefs.edit().putInt("gallery_media_grid_columns", cols).apply() })
        is DetailUiDialog.Sort -> ModernMediaSortSheet(activeSort = currentPhotoSort, isGlassTheme = isGlassTheme, onDismiss = { activeDialog = DetailUiDialog.None }, onSortSelected = { sort: PhotoSort -> currentPhotoSort = sort; activeDialog = DetailUiDialog.None })
        else -> {}
    }

    if (showRenameSheet && album != null) ModernInputSheet(title = "Rename Album", initial = album.name, isGlassTheme = isGlassTheme, onDismiss = { showRenameSheet = false }, onConfirm = { newName: String -> viewModel.renameAlbum(album, newName); showRenameSheet = false })

    metadataItemToShow?.let { MediaMetadataSheet(item = it, isGlassTheme = isGlassTheme, onDismiss = { metadataItemToShow = null }) }

    val openViewerState = viewerState as? GalleryViewerState.Open
    val viewerItemId = openViewerState?.mediaId
    val stableMediaList = if (viewerState is GalleryViewerState.Open) actuallyFilteredMedia else emptyList()

    if (viewerState is GalleryViewerState.Open && stableMediaList.isNotEmpty()) {
        val stableStartIndex = stableMediaList.indexOfFirst { it.id == viewerItemId }.coerceAtLeast(0)
        key(viewerItemId, stableMediaList.size) {
            FullscreenMediaPager(
                initialIndex = stableStartIndex,
                mediaList = stableMediaList,
                mediaMap = mediaMap,
                favoriteIds = favoriteIds,
                sharedPlayer = viewModel.getPlayer(),
                onPageChanged = {},
                onClose = { viewModel.closeViewer() },
                onToggleFavorite = { id: Long -> viewModel.toggleFavorite(id) },
                onEdit = { item: MediaItem -> viewModel.closeViewer(); if (item.isVideo) actions.onNavigateToVideoEditor(item.uri.toString(), item.id) else actions.onNavigateToPhotoEditor(item.uri.toString(), item.id) },
                onPlayVideo = { uri: String, playlist: List<String> -> actions.onNavigateToVideoPlayer(uri, playlist) },
                onDelete = { item: MediaItem -> activeDialog = DetailUiDialog.Delete(listOf(item.id)) },
                onMove = { item: MediaItem -> viewModel.closeViewer(); actions.onNavigateToMoveCopy("MOVE", item.id.toString(), albumId) },
                onCopy = { item: MediaItem -> viewModel.closeViewer(); actions.onNavigateToMoveCopy("COPY", item.id.toString(), albumId) },
                onWallpaper = { item: MediaItem -> viewModel.closeViewer(); actions.onNavigateToWallpaper(item.uri.toString(), item.id) }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StatelessAlbumGrid(
    gridState: LazyGridState, padding: PaddingValues, columnCount: Int, dynamicList: SnapshotStateList<Album>, albumPreviews: ImmutableMap<String, ImmutableList<Uri>>,
    isSelectionMode: Boolean, selectedIds: ImmutableSet<String>, sortOption: AlbumSort, searchQuery: String, screenWidthDp: Float, isLowRam: Boolean, sdCardAlbums: Set<String>,
    onOrderSaved: (List<Album>) -> Unit, onAlbumClick: (Album) -> Unit, onAlbumLongClick: (Album) -> Unit, onDragStateChange: (Boolean) -> Unit
) {
    val haptic = LocalHapticFeedback.current; var draggedIndex by remember { mutableIntStateOf(-1) }; var dragOffset by remember { mutableStateOf(Offset.Zero) }; var scrollVelocity by remember { mutableFloatStateOf(0f) }
    val actualColumns = columnCount.coerceAtLeast(1)

    val dynamicThumbSize = remember(actualColumns, screenWidthDp) {
        val raw = ((screenWidthDp / actualColumns) * 2).toInt().coerceIn(180, 480)
        (raw / 40) * 40
    }

    LaunchedEffect(scrollVelocity) {
        if (scrollVelocity != 0f) { while (isActive) { val consumed = gridState.scrollBy(scrollVelocity); if (consumed != 0f) dragOffset += Offset(0f, consumed); delay(16) } }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(columnCount), state = gridState, modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 90.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        itemsIndexed(items = dynamicList, key = { _, album -> album.id }, contentType = { _, _ -> "album" }) { index, album ->
            val isBeingDragged = draggedIndex == index
            val canSimpleDrag = sortOption == AlbumSort.Custom && searchQuery.isBlank() && isSelectionMode && selectedIds.size == 1 && selectedIds.contains(album.id)
            val canLongPressDrag = sortOption == AlbumSort.Custom && searchQuery.isBlank() && !canSimpleDrag

            val dragModifier = Modifier.pointerInput(album.id, canSimpleDrag, canLongPressDrag, isSelectionMode) {
                if (canSimpleDrag) {
                    detectDragGestures(
                        onDragStart = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            draggedIndex = index
                            dragOffset = Offset.Zero
                            onDragStateChange(true)
                        },
                        onDrag = { change, dragAmount ->
                            change.consume(); dragOffset += dragAmount
                            val layoutInfo = gridState.layoutInfo; val visibleItems = layoutInfo.visibleItemsInfo; val draggedItemInfo = visibleItems.find { it.index == draggedIndex }
                            if (draggedItemInfo != null) {
                                val draggedCenterX = draggedItemInfo.offset.x + (draggedItemInfo.size.width / 2) + dragOffset.x.roundToInt()
                                val draggedCenterY = draggedItemInfo.offset.y + (draggedItemInfo.size.height / 2) + dragOffset.y.roundToInt()
                                scrollVelocity = when { draggedCenterY < layoutInfo.viewportStartOffset + 180 -> -15f; draggedCenterY > layoutInfo.viewportEndOffset - 180 -> 15f; else -> 0f }
                                val targetItemInfo = visibleItems.find { it.index != draggedIndex && it.index < dynamicList.size && draggedCenterX in it.offset.x..(it.offset.x + it.size.width) && draggedCenterY in it.offset.y..(it.offset.y + it.size.height) }
                                if (targetItemInfo != null) {
                                    val targetIndex = targetItemInfo.index
                                    dragOffset -= Offset((targetItemInfo.offset.x - draggedItemInfo.offset.x).toFloat(), (targetItemInfo.offset.y - draggedItemInfo.offset.y).toFloat())
                                    val item = dynamicList.removeAt(draggedIndex)
                                    dynamicList.add(targetIndex, item)
                                    draggedIndex = targetIndex
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            }
                        },
                        onDragEnd = { draggedIndex = -1; dragOffset = Offset.Zero; scrollVelocity = 0f; onDragStateChange(false); onOrderSaved(dynamicList.toList()) },
                        onDragCancel = { draggedIndex = -1; dragOffset = Offset.Zero; scrollVelocity = 0f; onDragStateChange(false) }
                    )
                } else if (canLongPressDrag) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onAlbumLongClick(album)
                            draggedIndex = index
                            dragOffset = Offset.Zero
                            onDragStateChange(true)
                        },
                        onDrag = { change, dragAmount ->
                            change.consume(); dragOffset += dragAmount
                            val layoutInfo = gridState.layoutInfo; val visibleItems = layoutInfo.visibleItemsInfo; val draggedItemInfo = visibleItems.find { it.index == draggedIndex }
                            if (draggedItemInfo != null) {
                                val draggedCenterX = draggedItemInfo.offset.x + (draggedItemInfo.size.width / 2) + dragOffset.x.roundToInt()
                                val draggedCenterY = draggedItemInfo.offset.y + (draggedItemInfo.size.height / 2) + dragOffset.y.roundToInt()
                                scrollVelocity = when { draggedCenterY < layoutInfo.viewportStartOffset + 180 -> -15f; draggedCenterY > layoutInfo.viewportEndOffset - 180 -> 15f; else -> 0f }
                                val targetItemInfo = visibleItems.find { it.index != draggedIndex && it.index < dynamicList.size && draggedCenterX in it.offset.x..(it.offset.x + it.size.width) && draggedCenterY in it.offset.y..(it.offset.y + it.size.height) }
                                if (targetItemInfo != null) {
                                    val targetIndex = targetItemInfo.index
                                    dragOffset -= Offset((targetItemInfo.offset.x - draggedItemInfo.offset.x).toFloat(), (targetItemInfo.offset.y - draggedItemInfo.offset.y).toFloat())
                                    val item = dynamicList.removeAt(draggedIndex)
                                    dynamicList.add(targetIndex, item)
                                    draggedIndex = targetIndex
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            }
                        },
                        onDragEnd = { draggedIndex = -1; dragOffset = Offset.Zero; scrollVelocity = 0f; onDragStateChange(false); onOrderSaved(dynamicList.toList()) },
                        onDragCancel = { draggedIndex = -1; dragOffset = Offset.Zero; scrollVelocity = 0f; onDragStateChange(false) }
                    )
                }
            }

            Box(modifier = Modifier
                .animateItem()
                .zIndex(if (isBeingDragged) 1f else 0f)
                .graphicsLayer {
                    if (isBeingDragged) {
                        translationX = dragOffset.x; translationY = dragOffset.y;
                        scaleX = 1.04f; scaleY = 1.04f; shadowElevation = 16.dp.toPx()
                    }
                }
            ) {
                OptimizedAlbumTile(
                    album = album,
                    previews = albumPreviews[album.id] ?: persistentListOf(),
                    isSelected = selectedIds.contains(album.id),
                    isSelectionMode = isSelectionMode,
                    canDrag = canSimpleDrag || canLongPressDrag,
                    isSdCard = sdCardAlbums.contains(album.id),
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
fun StatelessMediaGrid(
    gridState: LazyGridState, mediaList: ImmutableList<MediaItem>, columnCount: Int, screenWidthPx: Int, isSelectionMode: Boolean, selectedIds: ImmutableSet<Long>, isLowRam: Boolean,
    onToggleSelection: (MediaItem) -> Unit, onForceSelect: (MediaItem) -> Unit, onSelectAll: (Boolean) -> Unit, onMediaClick: (MediaItem) -> Unit, onMediaLongClick: () -> Unit, onToggleFavorite: (Long) -> Unit,
    header: @Composable () -> Unit
) {
    val haptic = LocalHapticFeedback.current

    val dynamicThumbSize = remember(columnCount, screenWidthPx) {
        val raw = (screenWidthPx / columnCount.coerceAtLeast(1)).coerceIn(180, 480)
        (raw / 40) * 40
    }

    var autoScrollSpeed by remember { mutableFloatStateOf(0f) }
    var lastPointerPosition by remember { mutableStateOf<Offset?>(null) }

    fun getMediaItemAt(offset: Offset): MediaItem? {
        val layoutInfo = gridState.layoutInfo
        val itemInfo = layoutInfo.visibleItemsInfo.find {
            it.index > 0 &&
                    offset.x >= it.offset.x && offset.x <= it.offset.x + it.size.width &&
                    offset.y >= it.offset.y && offset.y <= it.offset.y + it.size.height
        }
        if (itemInfo != null) {
            val mediaIndex = itemInfo.index - 1
            if (mediaIndex in mediaList.indices) {
                return mediaList[mediaIndex]
            }
        }
        return null
    }

    val currentOnForceSelect by rememberUpdatedState(onForceSelect)

    LaunchedEffect(autoScrollSpeed) {
        if (autoScrollSpeed != 0f) {
            while (isActive) {
                gridState.scrollBy(autoScrollSpeed)
                lastPointerPosition?.let { pos ->
                    val item = getMediaItemAt(pos)
                    if (item != null) {
                        currentOnForceSelect(item)
                    }
                }
                delay(16)
            }
        }
    }

    val currentIsSelectionMode by rememberUpdatedState(isSelectionMode)
    val currentOnMediaLongClick by rememberUpdatedState(onMediaLongClick)

    val slideModifier = Modifier.pointerInput(Unit) {
        detectDragGesturesAfterLongPress(
            onDragStart = { offset ->
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                if (!currentIsSelectionMode) {
                    currentOnMediaLongClick()
                }
                lastPointerPosition = offset
                val item = getMediaItemAt(offset)
                if (item != null) {
                    currentOnForceSelect(item)
                }
            },
            onDrag = { change, _ ->
                change.consume()
                lastPointerPosition = change.position
                val item = getMediaItemAt(change.position)
                if (item != null) {
                    currentOnForceSelect(item)
                }

                val y = change.position.y
                val height = size.height
                val d = density

                val top10 = 10 * d
                val top25 = 25 * d
                val top40 = 40 * d

                autoScrollSpeed = when {
                    y < top10 -> -18f
                    y < top25 -> -10f
                    y < top40 -> -5f
                    y > height - top10 -> 18f
                    y > height - top25 -> 10f
                    y > height - top40 -> 5f
                    else -> 0f
                }
            },
            onDragEnd = {
                autoScrollSpeed = 0f
                lastPointerPosition = null
            },
            onDragCancel = {
                autoScrollSpeed = 0f
                lastPointerPosition = null
            }
        )
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(columnCount), state = gridState, modifier = Modifier.fillMaxSize().then(slideModifier),
        contentPadding = PaddingValues(start = 3.dp, end = 3.dp, top = 8.dp, bottom = 90.dp), verticalArrangement = Arrangement.spacedBy(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
            header()
        }
        if (mediaList.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(modifier = Modifier.fillMaxWidth().height(250.dp), contentAlignment = Alignment.Center) {
                    Text(text = "No matching items found", color = Color.Gray, fontSize = 15.sp)
                }
            }
        } else {
            items(count = mediaList.size, key = { i -> mediaList[i].id }, contentType = { if (mediaList[it].isVideo) "video" else "photo" }) { index ->
                val currentItem = mediaList[index]
                val itemSelected = selectedIds.contains(currentItem.id)
                ModernMediaGridTile(
                    modifier = Modifier, item = currentItem, thumbSize = dynamicThumbSize, isSelected = itemSelected, isSelectionMode = isSelectionMode, isLowRam = isLowRam,
                    onClick = {
                        if (isSelectionMode) {
                            onToggleSelection(currentItem)
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        } else onMediaClick(currentItem)
                    },
                    onToggleFavorite = { onToggleFavorite(currentItem.id) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OptimizedAlbumTile(
    album: Album, previews: ImmutableList<Uri>, isSelected: Boolean, isSelectionMode: Boolean, canDrag: Boolean, isSdCard: Boolean,
    thumbSize: Int, dragModifier: Modifier = Modifier, onClick: () -> Unit, onLongClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val clickModifier = if (canDrag) Modifier.then(dragModifier).clickable(interactionSource = interactionSource, indication = null, onClick = onClick) else Modifier.combinedClickable(interactionSource = interactionSource, indication = null, onClick = onClick, onLongClick = onLongClick)
    val tileShape = RoundedCornerShape(16.dp)

    Column(modifier = Modifier.fillMaxWidth().then(clickModifier)) {
        Surface(modifier = Modifier.aspectRatio(1f).shadow(elevation = 1.dp, shape = tileShape), shape = tileShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            val actualCoverUri = remember(album.coverUri, previews) { if (album.coverUri != Uri.EMPTY) album.coverUri else previews.firstOrNull() }
            Box(modifier = Modifier.fillMaxSize()) {
                if (actualCoverUri == null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(modifier = Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) { Icon(imageVector = Icons.Outlined.PhotoAlbum, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) }
                            Spacer(Modifier.height(8.dp)); Text(text = "Empty Album", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    val request = rememberGridImageRequest(uri = actualCoverUri, size = thumbSize, isVideo = false)
                    AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.05f)))
                }
                if (isSelectionMode) {
                    Box(modifier = Modifier.fillMaxSize().background(if (isSelected) Color.White.copy(alpha = 0.25f) else Color.Black.copy(alpha = 0.1f)))
                    Box(modifier = Modifier.padding(8.dp).align(Alignment.TopStart)) {
                        if (isSelected) Icon(imageVector = Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp).background(Color.White, CircleShape))
                        else Icon(imageVector = Icons.Outlined.RadioButtonUnchecked, contentDescription = null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text = album.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f, fill = false))
            if (isSdCard) {
                Spacer(Modifier.width(4.dp))
                Icon(imageVector = Icons.Rounded.SdStorage, contentDescription = "SD Card", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(text = "${album.mediaCount} items", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 2.dp))
    }
}

@Composable
fun ModernMediaGridTile(
    modifier: Modifier = Modifier, item: MediaItem, thumbSize: Int, isSelected: Boolean, isSelectionMode: Boolean, isLowRam: Boolean,
    onClick: () -> Unit, onToggleFavorite: () -> Unit
) {
    val cornerRadius = 10.dp
    val scale by animateFloatAsState(targetValue = if (isSelected) 0.85f else 1f, animationSpec = tween(100), label = "scale")

    Box(modifier = modifier.aspectRatio(1f).scale(scale).clip(RoundedCornerShape(cornerRadius)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)) {

        val request = rememberGridImageRequest(uri = item.uri, size = thumbSize, isVideo = item.isVideo)
        AsyncImage(model = request, placeholder = null, contentDescription = null, contentScale = ContentScale.Crop, filterQuality = FilterQuality.Low, modifier = Modifier.fillMaxSize())

        if (item.isVideo) {
            Box(modifier = Modifier.fillMaxSize().drawWithCache { val brush = Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f)); onDrawBehind { drawRect(brush) } })
            Surface(modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp), shape = RoundedCornerShape(12.dp), color = Color.Black.copy(alpha = 0.6f)) { Text(text = formatDuration(item.duration), fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)) }
        }
        SelectionOverlay(isSelected = isSelected, isSelectionMode = isSelectionMode, cornerRadius = cornerRadius)
    }
}

@Composable
fun SelectionOverlay(isSelected: Boolean, isSelectionMode: Boolean, cornerRadius: Dp) {
    if (isSelectionMode) {
        Box(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(cornerRadius))) {
            Box(modifier = Modifier.fillMaxSize().background(if (isSelected) Color.White.copy(alpha = 0.25f) else Color.Transparent))
            Box(modifier = Modifier.padding(8.dp).align(Alignment.TopStart)) {
                if (isSelected) Icon(imageVector = Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp).background(Color.White, CircleShape))
                else Icon(imageVector = Icons.Outlined.RadioButtonUnchecked, contentDescription = null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
fun SamsungFilterChip(selected: Boolean, label: String, isGlassTheme: Boolean = false, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, color = if (selected) MaterialTheme.colorScheme.onSurface else if(isGlassTheme) Color.White.copy(alpha=0.15f) else MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface) {
        Text(text = label, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun ActionItem(icon: ImageVector, label: String, isDestructive: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val contentColor = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ElevatedCard(modifier = Modifier.width(86.dp).clip(RoundedCornerShape(24.dp)).clickable(enabled = enabled, onClick = onClick), shape = RoundedCornerShape(24.dp), elevation = CardDefaults.elevatedCardElevation(3.dp), colors = CardDefaults.elevatedCardColors(containerColor = if (enabled) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(modifier = Modifier.size(46.dp).clip(CircleShape).background(contentColor.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) { Icon(imageVector = icon, contentDescription = label, tint = if (enabled) contentColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f), modifier = Modifier.size(24.dp)) }
            if (label.isNotEmpty()) { Spacer(Modifier.height(10.dp)); Text(text = label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = if (enabled) contentColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f), maxLines = 1) }
            else { Spacer(Modifier.height(10.dp)); Text(text = " ", style = MaterialTheme.typography.labelMedium) }
        }
    }
}

@Composable
fun BottomBarActionItem(icon: ImageVector, label: String, isDestructive: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val contentColor = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface; val alphaColor = if (enabled) contentColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    Column(modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(imageVector = icon, contentDescription = label, tint = alphaColor, modifier = Modifier.size(24.dp))
        if (label.isNotEmpty()) { Spacer(Modifier.height(4.dp)); Text(text = label, style = MaterialTheme.typography.labelMedium, color = alphaColor, maxLines = 1) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchTopBar(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit, isGlassTheme: Boolean = false) {
    Surface(modifier = Modifier.fillMaxWidth().glassEffect(isGlassTheme), tonalElevation = 6.dp, shadowElevation = 10.dp, color = if(isGlassTheme) Color.Transparent else MaterialTheme.colorScheme.surface) {
        Row(modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledIconButton(onClick = onClose, colors = IconButtonDefaults.filledIconButtonColors(containerColor = if(isGlassTheme) Color.White.copy(alpha=0.15f) else MaterialTheme.colorScheme.surfaceContainerHigh)) { Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurface) }
            Spacer(Modifier.width(14.dp))
            Surface(modifier = Modifier.weight(1f), shape = RoundedCornerShape(28.dp), color = if(isGlassTheme) Color.White.copy(alpha=0.15f) else MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Rounded.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    TextField(value = query, onValueChange = onQueryChange, modifier = Modifier.weight(1f), placeholder = { Text("Search albums, photos...", color = MaterialTheme.colorScheme.onSurfaceVariant) }, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge, colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, focusedTextColor = MaterialTheme.colorScheme.onSurface, unfocusedTextColor = MaterialTheme.colorScheme.onSurface, cursorColor = MaterialTheme.colorScheme.primary))
                    if (query.isNotEmpty()) { FilledIconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(34.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))) { Icon(imageVector = Icons.Rounded.Close, contentDescription = "Clear", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) } }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernAlbumTopBar(
    scrollBehavior: TopAppBarScrollBehavior,
    isAppLockEnabled: Boolean,
    isGlassTheme: Boolean = false,
    onSearchClick: () -> Unit,
    onMenuAction: (String) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    CenterAlignedTopAppBar(
        title = { Text(text = "Albums", maxLines = 1, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) },
        actions = {
            IconButton(onClick = onSearchClick) { Icon(imageVector = Icons.Outlined.Search, contentDescription = "Search", tint = MaterialTheme.colorScheme.onSurface) }
            Box {
                IconButton(onClick = { showMenu = true }) { Icon(imageVector = Icons.Rounded.MoreVert, contentDescription = "More", tint = MaterialTheme.colorScheme.onSurface) }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, modifier = Modifier.clip(RoundedCornerShape(24.dp)).glassEffect(isGlassTheme).background(if(isGlassTheme) Color.Transparent else MaterialTheme.colorScheme.surface)) {
                    PremiumAlbumMenuItem("Grid Size", Icons.Rounded.GridView) { onMenuAction("grid"); showMenu = false }
                    PremiumAlbumMenuItem("Sort Albums", Icons.AutoMirrored.Filled.Sort) { onMenuAction("sort"); showMenu = false }
                    PremiumAlbumMenuItem("Theme", Icons.Outlined.Palette) { onMenuAction("theme"); showMenu = false }
                    PremiumAlbumMenuItem("Create Album", Icons.Rounded.CreateNewFolder) { onMenuAction("create"); showMenu = false }
                    HorizontalDivider()
                    PremiumAlbumMenuItem("Duplicates", Icons.Outlined.FileCopy) { onMenuAction("duplicates"); showMenu = false }
                    PremiumAlbumMenuItem("Scan Library", Icons.Outlined.ImageSearch) { onMenuAction("scan"); showMenu = false }
                    HorizontalDivider()
                    PremiumAlbumMenuItem("Trash", Icons.Outlined.Delete) { onMenuAction("trash"); showMenu = false }
                    PremiumAlbumMenuItem("Hidden Albums", Icons.Outlined.VisibilityOff) { onMenuAction("hidden"); showMenu = false }
                    PremiumAlbumMenuItem(if (isAppLockEnabled) "Disable App Lock" else "Enable App Lock", if (isAppLockEnabled) Icons.Outlined.LockOpen else Icons.Outlined.Lock) { onMenuAction("toggle_lock"); showMenu = false }
                }
            }
        },
        scrollBehavior = scrollBehavior, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = if(isGlassTheme) Color.Transparent else MaterialTheme.colorScheme.surface),
        modifier = Modifier.glassEffect(isGlassTheme)
    )
}

@Composable
private fun PremiumAlbumMenuItem(text: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text, fontWeight = FontWeight.SemiBold) }, onClick = onClick, leadingIcon = { Box(modifier = Modifier.size(34.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) { Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) } }, colors = MenuDefaults.itemColors(Color.Transparent))
}

@Composable
fun EmptyAlbumsOverlay(onCreateClick: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(modifier = Modifier.size(112.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) { Icon(imageVector = Icons.Outlined.PhotoAlbum, contentDescription = null, modifier = Modifier.size(58.dp), tint = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.height(28.dp)); Text(text = "No Albums", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface); Spacer(Modifier.height(10.dp)); Text(text = "Create albums to organize your memories.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center); Spacer(Modifier.height(34.dp))
            Button(onClick = onCreateClick, modifier = Modifier.height(58.dp).padding(horizontal = 24.dp), shape = RoundedCornerShape(22.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) { Icon(imageVector = Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Create Album", fontWeight = FontWeight.Bold) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernAlbumSortSheet(activeSort: AlbumSort, isGlassTheme: Boolean = false, onDismiss: () -> Unit, onSortSelected: (AlbumSort) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = if(isGlassTheme) Color.Transparent else MaterialTheme.colorScheme.surface, modifier = Modifier.glassEffect(isGlassTheme), dragHandle = { Box(modifier = Modifier.padding(top = 10.dp).width(54.dp).height(5.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f))) }) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 34.dp)) {
            Row(modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) { Icon(imageVector = Icons.AutoMirrored.Filled.Sort, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp)) }
                Spacer(Modifier.width(16.dp))
                Column { Text(text = "Sort Albums", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Spacer(Modifier.height(4.dp)); Text(text = "Choose album arrangement", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Spacer(Modifier.height(8.dp))
            AlbumSort.entries.forEach { option ->
                val isSelected = activeSort == option; val sortLabel = when (option.name) { "DateDesc" -> "Newest First"; "DateAsc" -> "Oldest First"; "NameAsc" -> "A → Z"; "NameDesc" -> "Z → A"; "SizeDesc" -> "Largest First"; "CountDesc" -> "Most Items"; "Custom" -> "Manual Order"; else -> option.name }
                Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp).clip(RoundedCornerShape(24.dp)).clickable { onSortSelected(option) }, shape = RoundedCornerShape(24.dp), color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else if(isGlassTheme) Color.White.copy(alpha=0.15f) else MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = if (isSelected) 4.dp else 0.dp) {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(28.dp).clip(CircleShape).background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) { Icon(imageVector = if (isSelected) Icons.Rounded.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked, contentDescription = null, tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) }
                        Spacer(Modifier.width(16.dp)); Text(text = sortLabel, style = MaterialTheme.typography.bodyLarge, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium, color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface); Spacer(Modifier.weight(1f)); if (isSelected) { Icon(imageVector = Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernMediaSortSheet(activeSort: PhotoSort, isGlassTheme: Boolean = false, onDismiss: () -> Unit, onSortSelected: (PhotoSort) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = if(isGlassTheme) Color.Transparent else MaterialTheme.colorScheme.surface, modifier = Modifier.glassEffect(isGlassTheme), dragHandle = { Box(modifier = Modifier.padding(top = 10.dp).width(54.dp).height(5.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f))) }) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 34.dp)) {
            Row(modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) { Icon(imageVector = Icons.AutoMirrored.Filled.Sort, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp)) }
                Spacer(Modifier.width(16.dp))
                Column { Text(text = "Sort Media", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Spacer(Modifier.height(4.dp)); Text(text = "Arrange photos and videos", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Spacer(Modifier.height(8.dp))
            PhotoSort.entries.forEach { option ->
                val isSelected = activeSort == option; val sortLabel = when (option) { PhotoSort.DateDesc -> "Newest First"; PhotoSort.DateAsc -> "Oldest First"; PhotoSort.NameAsc -> "Name (A → Z)"; PhotoSort.NameDesc -> "Name (Z → A)"; PhotoSort.SizeDesc -> "Largest First" }
                Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp).clip(RoundedCornerShape(24.dp)).clickable { onSortSelected(option) }, shape = RoundedCornerShape(24.dp), color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else if(isGlassTheme) Color.White.copy(alpha=0.15f) else MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = if (isSelected) 4.dp else 0.dp) {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(28.dp).clip(CircleShape).background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) { Icon(imageVector = if (isSelected) Icons.Rounded.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked, contentDescription = null, tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) }
                        Spacer(Modifier.width(16.dp)); Text(text = sortLabel, style = MaterialTheme.typography.bodyLarge, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium, color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface); Spacer(Modifier.weight(1f)); if (isSelected) { Icon(imageVector = Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernGridSheet(currentColumns: Int, max: Int = 8, isGlassTheme: Boolean = false, onDismiss: () -> Unit, onUpdate: (Int) -> Unit) {
    var sliderValue by remember { mutableFloatStateOf(currentColumns.toFloat()) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = if(isGlassTheme) Color.Transparent else MaterialTheme.colorScheme.surface, modifier = Modifier.glassEffect(isGlassTheme), dragHandle = { Box(modifier = Modifier.padding(top = 10.dp, bottom = 10.dp).width(40.dp).height(4.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))) }) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp, top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "Grid Layout", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(24.dp))
            Slider(value = sliderValue, onValueChange = { sliderValue = it }, valueRange = 1f..max.toFloat(), steps = (max - 2).coerceAtLeast(0), onValueChangeFinished = { onUpdate(sliderValue.toInt()) }, colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary, inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)))
            Spacer(Modifier.height(16.dp)); Text(text = "${sliderValue.toInt()} Columns", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernCreateAlbumSheet(isGlassTheme: Boolean = false, onDismiss: () -> Unit, onCreate: (String, Boolean) -> Unit) {
    var text by remember { mutableStateOf("") }; var useSdCard by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = if(isGlassTheme) Color.Transparent else MaterialTheme.colorScheme.surface, modifier = Modifier.glassEffect(isGlassTheme), dragHandle = { Box(modifier = Modifier.padding(top = 10.dp).width(54.dp).height(5.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f))) }) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 34.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(58.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) { Icon(imageVector = Icons.Rounded.CreateNewFolder, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp)) }
                Spacer(Modifier.width(16.dp)); Column { Text(text = "Create Album", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Spacer(Modifier.height(4.dp)); Text(text = "Organize your memories", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Spacer(Modifier.height(28.dp)); OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(22.dp), label = { Text("Album Name") }, leadingIcon = { Icon(Icons.Rounded.Folder, null) }); Spacer(Modifier.height(18.dp))
            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = if(isGlassTheme) Color.White.copy(alpha=0.15f) else MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(modifier = Modifier.fillMaxWidth().clickable { useSdCard = !useSdCard }.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = useSdCard, onCheckedChange = { useSdCard = it }); Spacer(Modifier.width(10.dp)); Column { Text("Create on SD Card", fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(2.dp)); Text(text = "Store album externally", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            Spacer(Modifier.height(26.dp)); Button(onClick = { if (text.isNotBlank()) onCreate(text.trim(), useSdCard) }, modifier = Modifier.fillMaxWidth().height(58.dp), shape = RoundedCornerShape(22.dp)) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(10.dp)); Text("Create Album", fontWeight = FontWeight.Bold) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernInputSheet(title: String, initial: String, isGlassTheme: Boolean = false, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = if(isGlassTheme) Color.Transparent else MaterialTheme.colorScheme.surface, modifier = Modifier.glassEffect(isGlassTheme)) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 34.dp)) {
            Text(text = title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Spacer(Modifier.height(24.dp))
            OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(22.dp)); Spacer(Modifier.height(28.dp))
            Button(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }, modifier = Modifier.fillMaxWidth().height(58.dp), shape = RoundedCornerShape(22.dp)) { Text("Save", fontWeight = FontWeight.Bold) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernSmartDeleteSheet(count: Int, isGlassTheme: Boolean = false, onDismiss: () -> Unit, onDeleteAll: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = if(isGlassTheme) Color.Transparent else MaterialTheme.colorScheme.surface, modifier = Modifier.glassEffect(isGlassTheme)) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 34.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(58.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) { Icon(imageVector = Icons.Rounded.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(30.dp)) }
                Spacer(Modifier.width(16.dp)); Column { Text(text = "Delete Albums", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Spacer(Modifier.height(4.dp)); Text(text = "$count album(s) selected", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Spacer(Modifier.height(24.dp))
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)) {
                Row(modifier = Modifier.padding(18.dp), verticalAlignment = Alignment.Top) { Icon(imageVector = Icons.Rounded.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error); Spacer(Modifier.width(12.dp)); Text(text = "This action permanently deletes the selected albums and may remove their media.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, lineHeight = 22.sp) }
            }
            Spacer(Modifier.height(30.dp)); Button(onClick = onDeleteAll, modifier = Modifier.fillMaxWidth().height(58.dp), shape = RoundedCornerShape(22.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Icon(Icons.Rounded.Delete, null); Spacer(Modifier.width(10.dp)); Text("Delete Permanently", fontWeight = FontWeight.Bold) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaMetadataSheet(item: MediaItem, isGlassTheme: Boolean = false, onDismiss: () -> Unit) {
    val context = LocalContext.current; val ds = remember(item) { SimpleDateFormat("EEEE, MMMM dd, yyyy 'at' hh a", Locale.getDefault()).format(Date(item.dateAdded * 1000)) }; val sz = remember(item) { Formatter.formatFileSize(context, item.size) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = if(isGlassTheme) Color.Transparent else MaterialTheme.colorScheme.surface, modifier = Modifier.glassEffect(isGlassTheme), dragHandle = { Box(modifier = Modifier.padding(top = 10.dp).width(54.dp).height(5.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f))) }) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 34.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(58.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) { Icon(imageVector = Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp)) }
                Spacer(Modifier.width(16.dp)); Column { Text(text = "Media Details", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Spacer(Modifier.height(4.dp)); Text(text = "Information & metadata", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Spacer(Modifier.height(28.dp))
            Surface(shape = RoundedCornerShape(30.dp), color = if(isGlassTheme) Color.White.copy(alpha=0.15f) else MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(modifier = Modifier.padding(18.dp)) {
                    MetadataRow(icon = Icons.Outlined.Title, label = "Name", value = item.name); MetadataRow(icon = Icons.Outlined.Folder, label = "Path", value = item.path); MetadataRow(icon = Icons.Outlined.CalendarToday, label = "Date", value = ds); MetadataRow(icon = Icons.Outlined.Storage, label = "Size", value = sz)
                    if (item.width > 0 && item.height > 0) MetadataRow(icon = Icons.Outlined.AspectRatio, label = "Resolution", value = "${item.width} × ${item.height}")
                    if (item.isVideo && item.duration > 0L) MetadataRow(icon = Icons.Outlined.Timer, label = "Duration", value = formatDuration(item.duration))
                }
            }
        }
    }
}

@Composable
fun MetadataRow(icon: ImageVector, label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.Top) {
        Box(modifier = Modifier.size(42.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) { Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(16.dp)); Column(modifier = Modifier.weight(1f)) { Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(4.dp)); Text(text = value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FullscreenMediaPager(
    initialIndex: Int, mediaList: List<MediaItem>, mediaMap: Map<Long, MediaItem>, favoriteIds: List<Long>, sharedPlayer: Player,
    onPageChanged: (MediaItem) -> Unit, onClose: () -> Unit, onToggleFavorite: (Long) -> Unit, onEdit: (MediaItem) -> Unit, onPlayVideo: (String, List<String>) -> Unit, onDelete: (MediaItem) -> Unit, onMove: (MediaItem) -> Unit, onCopy: (MediaItem) -> Unit, onWallpaper: (MediaItem) -> Unit
) {
    if (mediaList.isEmpty()) return
    val ctx = LocalContext.current
    val vi = LocalView.current
    val safe = initialIndex.coerceIn(0, maxOf(mediaList.lastIndex, 0))
    val st = rememberPagerState(initialPage = safe) { mediaList.size }
    var ctrl by remember { mutableStateOf(true) }
    var meta by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    val act = remember { ctx.findActivity() }
    val vid = remember(mediaList) { mediaList.filter { it.isVideo } }

    LaunchedEffect(initialIndex, mediaList.size) { if (st.currentPage != initialIndex && initialIndex in mediaList.indices) st.scrollToPage(initialIndex) }

    val performClose = {
        sharedPlayer.playWhenReady = false
        sharedPlayer.pause()
        sharedPlayer.stop()
        sharedPlayer.clearMediaItems()
        onClose()
    }

    val currId = mediaList.getOrNull(st.currentPage.coerceIn(0, maxOf(mediaList.lastIndex, 0)))?.id
    LaunchedEffect(currId) { if (currId == null && mediaList.isNotEmpty()) performClose() }
    val curr = remember(currId, mediaMap, mediaList) { currId?.let { mediaMap[it] ?: mediaList.find { i -> i.id == it } } }

    LaunchedEffect(curr) {
        ctrl = true
        if (curr != null) {
            onPageChanged(curr)
            if (curr.isVideo) {
                sharedPlayer.setMediaItem(Media3Item.fromUri(curr.uri))
                sharedPlayer.prepare()
                sharedPlayer.playWhenReady = false
            } else {
                sharedPlayer.playWhenReady = false
                sharedPlayer.pause()
                sharedPlayer.stop()
                sharedPlayer.clearMediaItems()
            }
        }
    }

    DisposableEffect(act) {
        val w = act?.window
        if (w != null) {
            val c = WindowCompat.getInsetsController(w, vi)
            c.hide(WindowInsetsCompat.Type.systemBars())
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose { w?.let { WindowCompat.getInsetsController(it, vi).show(WindowInsetsCompat.Type.systemBars()) } }
    }

    BackHandler(enabled = !ctrl) { ctrl = true }
    BackHandler(enabled = ctrl) { performClose() }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = st, pageSpacing = 18.dp, key = { mediaList[it].id }, modifier = Modifier.fillMaxSize()) { p ->
            val itm = mediaList[p]
            if (itm.isVideo) {
                VideoPreviewPage(
                    item = itm, videoItems = vid, isCurrentPage = st.currentPage == p, showControls = ctrl,
                    sharedPlayer = sharedPlayer, onTap = { ctrl = !ctrl }, onPlay = { onPlayVideo(itm.uri.toString(), vid.map { it.uri.toString() }) }
                )
            } else {
                ZoomableImagePage(
                    item = itm, onTap = { ctrl = !ctrl }, onDismiss = { performClose() },
                    onZoomChanged = {}, onControlsVisibilityChange = { ctrl = it }
                )
            }
        }
        if (ctrl) Box(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent))).statusBarsPadding().padding(horizontal = 18.dp, vertical = 16.dp)) { Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { FilledIconButton(onClick = { performClose() }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White.copy(alpha = 0.16f))) { Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White) }; Spacer(Modifier.size(48.dp)) } }
        if (ctrl && curr != null) Column(modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f)))).navigationBarsPadding().padding(bottom = 18.dp)) { Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) { PremiumViewerAction(icon = Icons.Outlined.Edit, label = "Edit") { onEdit(curr) }; PremiumViewerAction(icon = if (favoriteIds.contains(curr.id)) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, label = if (favoriteIds.contains(curr.id)) "Unfavorite" else "Favorite", tint = if (favoriteIds.contains(curr.id)) Color.Red else Color.White) { onToggleFavorite(curr.id) }; PremiumViewerAction(icon = Icons.Outlined.Share, label = "Share") { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = if (curr.isVideo) "video/*" else "image/*"; putExtra(Intent.EXTRA_STREAM, curr.uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "Share Media")) }; PremiumViewerAction(icon = Icons.Outlined.Delete, label = "Delete", tint = Color.Red) { onDelete(curr) }; PremiumViewerAction(icon = Icons.Default.MoreVert, label = "More") { more = true } } }
    }
    if (meta && curr != null) MediaMetadataSheet(item = curr) { meta = false }
    if (more && curr != null) {
        @OptIn(ExperimentalMaterial3Api::class)
        ModalBottomSheet(onDismissRequest = { more = false }, containerColor = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.padding(bottom = 32.dp)) {
                ListItem(headlineContent = { Text("Details", fontWeight = FontWeight.SemiBold) }, leadingContent = { Icon(imageVector = Icons.Outlined.Info, contentDescription = null) }, modifier = Modifier.clickable { more = false; meta = true })
                ListItem(headlineContent = { Text("Move to Album", fontWeight = FontWeight.SemiBold) }, leadingContent = { Icon(imageVector = Icons.AutoMirrored.Outlined.DriveFileMove, contentDescription = null) }, modifier = Modifier.clickable { more = false; onMove(curr) })
                ListItem(headlineContent = { Text("Copy to Album", fontWeight = FontWeight.SemiBold) }, leadingContent = { Icon(imageVector = Icons.Outlined.FileCopy, contentDescription = null) }, modifier = Modifier.clickable { more = false; onCopy(curr) })
                if (curr.isVideo) {
                    ListItem(headlineContent = { Text("Open In", fontWeight = FontWeight.SemiBold) }, leadingContent = { Icon(imageVector = Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) }, modifier = Modifier.clickable { more = false; ctx.startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(curr.uri, "video/*"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }) })
                }
                ListItem(headlineContent = { Text("Set as Wallpaper", fontWeight = FontWeight.SemiBold) }, leadingContent = { Icon(imageVector = Icons.Outlined.Wallpaper, contentDescription = null) }, modifier = Modifier.clickable { more = false; onWallpaper(curr) })
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun VideoPreviewPage(item: MediaItem, videoItems: List<MediaItem>, isCurrentPage: Boolean, showControls: Boolean, sharedPlayer: Player, onTap: () -> Unit, onPlay: () -> Unit) {
    val ctx = LocalContext.current
    var m by rememberSaveable(item.id) { mutableStateOf(true) }

    LaunchedEffect(m) { sharedPlayer.volume = if (m) 0f else 1f }
    LaunchedEffect(isCurrentPage) {
        if (!isCurrentPage) {
            sharedPlayer.pause()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = {
                PlayerView(ctx).apply {
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    layoutParams = android.view.ViewGroup.LayoutParams(-1, -1)
                }
            },
            update = {
                if (it.player != sharedPlayer) it.player = sharedPlayer
            },
            modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }
        )
        if (showControls) Box(modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(bottom = 90.dp)) {
            Surface(modifier = Modifier.align(Alignment.Center).clickable { sharedPlayer.pause(); onPlay() }, shape = RoundedCornerShape(50.dp), color = Color.Black.copy(alpha = 0.55f)) {
                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(text = "Play video", color = Color.White, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
fun PremiumViewerAction(icon: ImageVector, label: String, tint: Color = Color.White, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) { Surface(modifier = Modifier.size(58.dp).clip(CircleShape).clickable(onClick = onClick), shape = CircleShape, color = Color.White.copy(alpha = 0.12f), border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))) { Box(contentAlignment = Alignment.Center) { Icon(imageVector = icon, contentDescription = label, tint = tint, modifier = Modifier.size(24.dp)) } }; Spacer(modifier = Modifier.height(8.dp)); Text(text = label, color = tint.copy(alpha = 0.95f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun ZoomableImagePage(item: MediaItem, onTap: () -> Unit, onDismiss: () -> Unit, onZoomChanged: (Boolean) -> Unit, onControlsVisibilityChange: (Boolean) -> Unit) {
    val ctx = LocalContext.current; val d = LocalDensity.current; val hap = LocalHapticFeedback.current; val conf = LocalConfiguration.current; val wPx = with(d) { conf.screenWidthDp.dp.roundToPx() }; val hPx = with(d) { conf.screenHeightDp.dp.roundToPx() }; val thr = remember(conf.screenHeightDp, d) { with(d) { conf.screenHeightDp.dp.toPx() * 0.25f } }; var sc by remember { mutableFloatStateOf(1f) }; var oX by remember { mutableFloatStateOf(0f) }; var oY by remember { mutableFloatStateOf(0f) }; var bA by remember { mutableFloatStateOf(1f) }; LaunchedEffect(sc) { onZoomChanged(sc > 1.05f) }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = bA)).graphicsLayer { val ds = 1f - (abs(oY) / 2200f); scaleX = sc * ds; scaleY = scaleX; alpha = (1f - (abs(oY) / 850f)).coerceIn(0f, 1f); translationX = oX; translationY = oY }.pointerInput(Unit) { detectTapGestures(onTap = { onTap() }, onDoubleTap = { sc = if (sc > 1f) 1f else 2.5f; oX = 0f; oY = 0f }, onLongPress = { hap.performHapticFeedback(HapticFeedbackType.LongPress) }) }.pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false); do { val ev = awaitPointerEvent(); val z = ev.calculateZoom(); val p = ev.calculatePan(); if (abs(z - 1f) > 0.005f) sc = (sc * z).coerceIn(1f, 4f); if (sc > 1.05f) { ev.changes.forEach { if (it.positionChanged()) it.consume() }; val mx = (size.width * (sc - 1)) / 2f; val my = (size.height * (sc - 1)) / 2f; oX = (oX + p.x).coerceIn(-mx, mx); oY = (oY + p.y).coerceIn(-my, my) } else { val isV = abs(p.y) > abs(p.x); if (isV && ev.changes.size == 1) { oY += p.y; bA = (1f - abs(oY) / 900f).coerceIn(0.35f, 1f); if (abs(oY) > 50f) onControlsVisibilityChange(false); ev.changes.forEach { if (it.positionChanged()) it.consume() } } } } while (ev.changes.any { it.pressed }); if (sc <= 1.05f) { if (abs(oY) > thr) { hap.performHapticFeedback(HapticFeedbackType.LongPress); onDismiss() } else { oY = 0f; bA = 1f } } } }, contentAlignment = Alignment.Center) { AsyncImage(model = remember(item.id, wPx, hPx) { ImageRequest.Builder(ctx).data(item.uri).size(Size(wPx, hPx)).allowHardware(true).precision(Precision.INEXACT).networkCachePolicy(CachePolicy.ENABLED).memoryCacheKey("full_${item.id}").memoryCachePolicy(CachePolicy.ENABLED).crossfade(false).error(android.R.drawable.ic_menu_report_image).build() }, placeholder = null, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
}