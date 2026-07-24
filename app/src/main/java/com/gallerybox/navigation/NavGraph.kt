@file:Suppress("unused")
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.gallerybox.navigation

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.MediaStore
import android.util.Base64
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import androidx.navigation.navDeepLink
import androidx.navigation.toRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass
import com.gallerybox.ui.screens.ScanLibraryScreen
import com.gallerybox.ui.screens.album.*
import com.gallerybox.ui.screens.editor.EditorScreen
import com.gallerybox.ui.screens.file.*
import com.gallerybox.ui.screens.music.*
import com.gallerybox.ui.screens.picture.PictureScreen
import com.gallerybox.ui.screens.setting.SettingScreen
import com.gallerybox.ui.screens.stories.StoriesScreen
import com.gallerybox.ui.screens.trash.TrashScreen
import com.gallerybox.ui.screens.vault.VaultSecureScreen
import com.gallerybox.ui.screens.videoplayer.VideoPlayerScreen
import com.gallerybox.ui.screens.wallpaper.WallpaperScreen
import com.gallerybox.viewmodel.*

sealed interface Route {
    // Gallery Main
    @Serializable data object Pictures : Route
    @Serializable data object Albums : Route
    @Serializable data object Stories : Route
    @Serializable data object Music : Route

    // Camera Shortcut
    @Serializable data object Camera : Route


    // General Utilities
    @Serializable data object Vault : Route
    @Serializable data object Radio : Route
    @Serializable data object Equalizer : Route
    @Serializable data object DuoMusic : Route
    @Serializable data object Settings : Route
    @Serializable data object ScanLibrary : Route
    @Serializable data object Trash : Route
    @Serializable data object Hidden : Route
    @Serializable data object Duplicates : Route

    // Core Dynamic Routes
    @Serializable data class VideoPlayer(val uri: String, val position: Long = 0L) : Route
    @Serializable data class AlbumView(val albumId: String) : Route
    @Serializable data class Slideshow(val albumId: String? = null) : Route
    @Serializable data class MediaEditor(val uri: String, val mediaId: Long? = null) : Route
    @Serializable data class MoveCopy(val mode: String, val ids: String, val sourceAlbumId: String? = null) : Route
    @Serializable data class Wallpaper(val uri: String, val mediaId: Long? = null) : Route
}

fun Context.findActivity(): Activity? {
    var c = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

fun String.toSafeRouteArgs() = Base64.encodeToString(this.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

fun String.fromSafeRouteArgs() = try {
    String(Base64.decode(this, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
} catch (e: Exception) {
    this
}

data class BottomTab(
    val route: Route,
    val routeClass: KClass<out Route>,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val label: String
)

enum class AppLockState { Initializing, Locked, Unlocked }

@RequiresApi(android.os.Build.VERSION_CODES.Q)
@Composable
fun GalleryNavHost(securityVM: SecurityViewModel = hiltViewModel()) {
    var appState by remember { mutableStateOf(AppLockState.Initializing) }

    LaunchedEffect(Unit) {
        val appLockEnabled = withContext(Dispatchers.IO) { securityVM.isAppLockEnabled() }
        appState = if (appLockEnabled) {
            securityVM.lock()
            AppLockState.Locked
        } else {
            AppLockState.Unlocked
        }
    }

    AnimatedContent(targetState = appState, label = "AppLockTransition") { state ->
        when (state) {
            AppLockState.Initializing -> Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            AppLockState.Locked -> VaultSecureScreen(
                isGlobalAppGuard = true,
                onBack = {},
                onUnlockGlobalSuccess = { appState = AppLockState.Unlocked }
            )
            AppLockState.Unlocked -> GalleryAppContent {
                securityVM.lock()
                appState = AppLockState.Locked
            }
        }
    }
}

@RequiresApi(android.os.Build.VERSION_CODES.Q)
@Composable
fun GalleryAppContent(onLockApp: () -> Unit) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("app_nav_prefs", Context.MODE_PRIVATE) }

    val initialStartDestination = remember {
        when (sharedPrefs.getString("last_main_tab", "Albums")) {
            "Pictures" -> Route.Pictures
            "Music" -> Route.Music
            else -> Route.Albums
        }
    }

    LaunchedEffect(navController) {
        navController.addOnDestinationChangedListener { _, dest, _ ->
            when {
                dest.hasRoute(Route.Pictures::class) -> sharedPrefs.edit().putString("last_main_tab", "Pictures").apply()
                dest.hasRoute(Route.Albums::class) -> sharedPrefs.edit().putString("last_main_tab", "Albums").apply()
                dest.hasRoute(Route.Music::class) -> sharedPrefs.edit().putString("last_main_tab", "Music").apply()
            }
        }
    }

    val tabs = remember {
        listOf(
            BottomTab(Route.Pictures, Route.Pictures::class, Icons.Filled.Photo, Icons.Outlined.Photo, "Photos"),
            BottomTab(Route.Albums, Route.Albums::class, Icons.Filled.PhotoAlbum, Icons.Outlined.PhotoAlbum, "Albums"),
            BottomTab(Route.Stories, Route.Stories::class, Icons.Filled.AutoStories, Icons.Outlined.AutoStories, "Stories"),
            BottomTab(Route.Music, Route.Music::class, Icons.Filled.MusicNote, Icons.Outlined.MusicNote, "Music")
        )
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    var isFullScreenMediaOpen by remember { mutableStateOf(false) }

    val showBottomBar by remember(currentDestination, isFullScreenMediaOpen) {
        derivedStateOf {
            if (isFullScreenMediaOpen) {
                false
            } else {
                currentDestination?.hasRoute(Route.Pictures::class) == true ||
                        currentDestination?.hasRoute(Route.Albums::class) == true ||
                        currentDestination?.hasRoute(Route.Stories::class) == true ||
                        currentDestination?.hasRoute(Route.Music::class) == true
            }
        }
    }

    val navigateToVideo = remember(navController) {
        { rawUriString: String, _: List<String> ->
            navController.navigate(Route.VideoPlayer(rawUriString.toSafeRouteArgs())) {
                popUpTo(Route.VideoPlayer::class) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    val sharedMusicViewModel: MusicViewModel = hiltViewModel()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBottomBar) {
                BottomNavigationBar(tabs, currentDestination, navController)
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = initialStartDestination,
            modifier = Modifier.padding(padding)
        ) {
            mainTabs(navController, context, navigateToVideo, onLockApp, { isFullScreenMediaOpen = it }, sharedMusicViewModel)
            albumGraphs(navController, navigateToVideo, onLockApp) { isFullScreenMediaOpen = it }
            editorGraphs(navController)
            toolsAndUtilityGraphs(navController, navigateToVideo, context, onLockApp, sharedMusicViewModel)
        }
    }
}

@RequiresApi(android.os.Build.VERSION_CODES.Q)
private fun NavGraphBuilder.mainTabs(
    nav: NavHostController,
    ctx: Context,
    navToVid: (String, List<String>) -> Unit,
    onLock: () -> Unit,
    onViewerStateChanged: (Boolean) -> Unit,
    musicViewModel: MusicViewModel
) {
    composable<Route.Pictures> {
        PictureScreen(
            viewModel = hiltViewModel(),
            trashViewModel = hiltViewModel(),
            onViewerStateChanged = onViewerStateChanged,
            onNavigateToCamera = { safeLaunchCamera(ctx) },
            onNavigateToTrash = { nav.navigate(Route.Trash) },
            onNavigateToHidden = { nav.navigate(Route.Hidden) },
            onLockApp = onLock,
            onNavigateToDuplicates = { nav.navigate(Route.Duplicates) },
            onNavigateToSlideshow = { nav.navigate(Route.Slideshow()) },
            onNavigateToWallpaper = { uri, id -> nav.navigate(Route.Wallpaper(uri.toSafeRouteArgs(), id)) },
            onNavigateToAlbum = { raw -> nav.navigate(Route.AlbumView(raw.toSafeRouteArgs())) },
            onNavigateToScan = { nav.navigate(Route.ScanLibrary) },
            onNavigateToSettings = { nav.navigate(Route.Settings) },
            onNavigateToVideoPlayer = navToVid,
            onNavigateToEditor = { uri, id -> nav.navigate(Route.MediaEditor(uri.toSafeRouteArgs(), id)) },
            onNavigateToMoveCopy = { m, ids, src -> nav.navigate(Route.MoveCopy(m, ids, src?.toSafeRouteArgs())) }
        )
    }

    composable<Route.Albums> {
        AlbumScreen(
            viewModel = hiltViewModel(),
            trashViewModel = hiltViewModel(),
            onViewerStateChanged = onViewerStateChanged,
            actions = AlbumActions(
                onAlbumClick = { a -> nav.navigate(Route.AlbumView(a.id.toSafeRouteArgs())) },
                onNavigateToFavorites = { nav.navigate(Route.AlbumView("virtual_favorites".toSafeRouteArgs())) },
                onNavigateToTrash = { nav.navigate(Route.Trash) },
                onNavigateToHidden = { nav.navigate(Route.Hidden) },
                onLockApp = onLock,
                onNavigateToSettings = { nav.navigate(Route.Settings) },
                onNavigateToDuplicates = { nav.navigate(Route.Duplicates) },
                onNavigateToScan = { nav.navigate(Route.ScanLibrary) }
            )
        )
    }

    composable<Route.Stories> {
        StoriesScreen(
            viewModel = hiltViewModel(),
            trashViewModel = hiltViewModel()
        )
    }

    composable<Route.Music> {
        MusicScreen(
            viewModel = musicViewModel,
            onViewerStateChanged = onViewerStateChanged,
            onNavigateToEqualizer = { nav.navigate(Route.Equalizer) },
            onNavigateToRadio = { nav.navigate(Route.Radio) },
            onNavigateToDuoPlayer = { nav.navigate(Route.DuoMusic) }
        )
    }
}

@RequiresApi(android.os.Build.VERSION_CODES.Q)
private fun NavGraphBuilder.albumGraphs(
    nav: NavHostController,
    navToVid: (String, List<String>) -> Unit,
    onLock: () -> Unit,
    onViewerStateChanged: (Boolean) -> Unit
) {
    composable<Route.AlbumView> { backStack ->
        AlbumDetailScreen(
            albumId = backStack.toRoute<Route.AlbumView>().albumId.fromSafeRouteArgs(),
            viewModel = hiltViewModel(),
            trashViewModel = hiltViewModel(),
            onViewerStateChanged = onViewerStateChanged,
            actions = DetailActions(
                onBack = { nav.popBackStack() },
                onNavigateToPhotoEditor = { uri, id -> nav.navigate(Route.MediaEditor(uri.toSafeRouteArgs(), id)) },
                onNavigateToVideoEditor = { uri, id -> nav.navigate(Route.MediaEditor(uri.toSafeRouteArgs(), id)) },
                onNavigateToVideoPlayer = navToVid,
                onNavigateToMoveCopy = { m, ids, src -> nav.navigate(Route.MoveCopy(m, ids, src?.toSafeRouteArgs())) },
                onNavigateToTrash = { nav.navigate(Route.Trash) },
                onNavigateToHidden = { nav.navigate(Route.Hidden) },
                onLockApp = onLock,
                onNavigateToWallpaper = { uri, id -> nav.navigate(Route.Wallpaper(uri.toSafeRouteArgs(), id)) }
            )
        )
    }
}

private fun NavGraphBuilder.editorGraphs(nav: NavHostController) {
    composable<Route.MediaEditor> { backStack ->
        val args = backStack.toRoute<Route.MediaEditor>()
        val mediaId = args.mediaId
        if (mediaId != null && mediaId != 0L) {
            EditorScreen(
                mediaId = mediaId,
                onBack = { nav.popBackStack() }
            )
        } else {
            LaunchedEffect(Unit) {
                nav.popBackStack()
            }
        }
    }
}

@RequiresApi(android.os.Build.VERSION_CODES.Q)
private fun NavGraphBuilder.toolsAndUtilityGraphs(
    nav: NavHostController,
    navToVid: (String, List<String>) -> Unit,
    ctx: Context,
    onLock: () -> Unit,
    musicViewModel: MusicViewModel
) {
    composable<Route.Radio> { RadioScreen(viewModel = hiltViewModel<RadioViewModel>(), onBack = { nav.popBackStack() }) }
    composable<Route.Equalizer> { EqualizerScreen(viewModel = musicViewModel, onBack = { nav.popBackStack() }) }
    composable<Route.DuoMusic> { DuoMusicScreen(viewModel = musicViewModel, onBack = { nav.popBackStack() }) }
    composable<Route.Settings> { SettingScreen(onBack = { nav.popBackStack() }) }

    composable<Route.Wallpaper> { backStack ->
        val galVM: GalleryViewModel = hiltViewModel()
        val args = backStack.toRoute<Route.Wallpaper>()
        val decodedUri = args.uri.fromSafeRouteArgs()
        val rawMedia by galVM.rawMedia.collectAsState()
        val mediaItem = galVM.getMediaItemById(args.mediaId ?: -1L) ?: rawMedia.find { m -> m.uri.toString() == decodedUri }

        if (mediaItem != null) {
            WallpaperScreen(item = mediaItem, onBack = { nav.popBackStack() })
        } else {
            val isBusy by galVM.isBusy.collectAsState()
            if (!isBusy) LaunchedEffect(Unit) { nav.popBackStack() }
        }
    }

    composable<Route.Trash> { TrashScreen(onBack = { nav.popBackStack() }) }
    composable<Route.Duplicates> { DuplicatesScreen(viewModel = hiltViewModel(), trashViewModel = hiltViewModel(), onBack = { nav.popBackStack() }) }

    composable<Route.ScanLibrary> {
        ScanLibraryScreen(
            onBack = { nav.popBackStack() },
            galleryViewModel = hiltViewModel(),
            musicViewModel = hiltViewModel(),
            onLockApp = onLock
        )
    }

    composable<Route.Slideshow> {
        SlideshowScreen(
            albumId = it.toRoute<Route.Slideshow>().albumId?.fromSafeRouteArgs(),
            viewModel = hiltViewModel(),
            onBack = { nav.popBackStack() }
        )
    }

    composable<Route.Vault> {
        VaultSecureScreen(
            onBack = { nav.navigateUp() },
            onNavigateToPicker = { nav.navigate(Route.Pictures) }
        )
    }

    composable<Route.MoveCopy> {
        val args = it.toRoute<Route.MoveCopy>()
        MoveCopyScreen(
            operationMode = if (args.mode == "COPY") OperationMode.COPY else OperationMode.MOVE,
            selectedMediaIds = args.ids.split(",").mapNotNull { id -> id.toLongOrNull() },
            sourceAlbumId = args.sourceAlbumId?.fromSafeRouteArgs(),
            onBack = { nav.popBackStack() },
            onOperationComplete = { nav.popBackStack() }
        )
    }

    composable<Route.VideoPlayer>(deepLinks = listOf(navDeepLink<Route.VideoPlayer>(basePath = "gallerybox://video"))) {
        val galVM: GalleryViewModel = hiltViewModel()
        val args = it.toRoute<Route.VideoPlayer>()
        val decodedUri = args.uri.fromSafeRouteArgs()

        VideoPlayerScreen(
            initialVideoUrl = decodedUri,
            viewModel = galVM,
            onBackPress = { nav.popBackStack() },
            onLockApp = onLock
        )
    }
}

@Composable
fun BottomNavigationBar(
    tabs: List<BottomTab>,
    currentDest: androidx.navigation.NavDestination?,
    nav: NavHostController
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        tonalElevation = 10.dp,
        shadowElevation = 4.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(top = 12.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEach { tab ->
                val selected = currentDest?.hierarchy?.any { it.hasRoute(tab.routeClass) } == true

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = true, radius = 36.dp)
                        ) {
                            if (!selected) {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { inclusive = false }
                                    launchSingleTop = true
                                }
                            }
                        }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                            contentDescription = tab.label,
                            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = tab.label,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
    }
}
fun safeLaunchCamera(context: Context) {
    try {
        context.startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
    } catch (_: Exception) {
        Toast.makeText(context, "Unable to launch camera", Toast.LENGTH_SHORT).show()
    }
}