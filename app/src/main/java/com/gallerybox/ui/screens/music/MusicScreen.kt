@file:Suppress("unused", "UnsafeOptInUsageError")
package com.gallerybox.ui.screens.music

import android.app.Activity
import android.content.ContentUris
import android.net.Uri
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.navigation.compose.*
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.gallerybox.viewmodel.AudioTrack
import com.gallerybox.viewmodel.MusicViewModel
import com.gallerybox.viewmodel.Playlist
import com.gallerybox.viewmodel.TrashViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private val BgColor = Color(0xFFFFFFFF)
private val SurfaceColor = Color(0xFFF2F2F7)
private val PrimaryColor = Color(0xFF007AFF)
private val TextPrimary = Color(0xFF000000)
private val TextSecondary = Color(0xFF8E8E93)

sealed class MusicRoute(val route: String) {
    object Dashboard : MusicRoute("dashboard")
    object Library : MusicRoute("library")
    object Playlists : MusicRoute("playlists")
    object Folders : MusicRoute("folders")
    object History : MusicRoute("history")
    object Queue : MusicRoute("queue")
    object Settings : MusicRoute("settings")
    object AudioInfo : MusicRoute("audio_info")
    object Radio : MusicRoute("radio")
    object Favorites : MusicRoute("favorites")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicScreen(
    viewModel: MusicViewModel,
    trashViewModel: TrashViewModel = hiltViewModel(),
    onViewerStateChanged: (Boolean) -> Unit = {},
    onNavigateToEqualizer: () -> Unit,
    onNavigateToRadio: () -> Unit,
    onNavigateToDuoPlayer: () -> Unit
) {
    val navController = rememberNavController()
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    val context = LocalContext.current
    var showFullPlayer by remember { mutableStateOf(false) }
    var isSearchActive by remember { mutableStateOf(false) }
    var trackToTrash by remember { mutableStateOf<AudioTrack?>(null) }

    // Optimization: collectAsStateWithLifecycle and localized collections to prevent full screen recomposition
    val currentTrack by viewModel.currentTrack.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val loadedSongs by viewModel.allAudioTracks.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        trashViewModel.onRefreshMusic = {
            viewModel.loadAllAudioTracks()
        }
    }

    val displaySongs = remember(loadedSongs, searchQuery) {
        if (searchQuery.isBlank()) loadedSongs
        else loadedSongs.filter { it.title.contains(searchQuery, true) || it.artist.contains(searchQuery, true) }
    }

    LaunchedEffect(showFullPlayer) { onViewerStateChanged(showFullPlayer) }

    val trashLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val isGranted = result.resultCode == Activity.RESULT_OK
        trashViewModel.onPermissionResultMusic(isGranted)
        if (isGranted) Toast.makeText(context, "Song moved to trash", Toast.LENGTH_SHORT).show()
        else Toast.makeText(context, "Trash permission denied", Toast.LENGTH_SHORT).show()
    }

    trackToTrash?.let { song ->
        AlertDialog(
            onDismissRequest = { trackToTrash = null },
            title = { Text("Move to Trash?", fontWeight = FontWeight.Bold) },
            text = { Text("Move '${song.title}' to the trash?") },
            confirmButton = {
                Button(
                    onClick = { trashViewModel.confirmPendingMusicTrash(listOf(song), trashLauncher::launch); trackToTrash = null },
                    colors = ButtonDefaults.buttonColors(MaterialTheme.colorScheme.error)
                ) { Text("Move to Trash") }
            },
            dismissButton = { TextButton(onClick = { trackToTrash = null }) { Text("Cancel") } }
        )
    }

    BackHandler(showFullPlayer || isSearchActive) { if (showFullPlayer) showFullPlayer = false else { isSearchActive = false; viewModel.setSearchQuery("") } }

    Scaffold(
        topBar = { if (!showFullPlayer) MusicTopAppBar(currentRoute, isSearchActive, searchQuery, viewModel::setSearchQuery, { isSearchActive = it; if (!it) viewModel.setSearchQuery("") }, navController::popBackStack, onNavigateToEqualizer, { navController.navigate(MusicRoute.Dashboard.route) }, { navController.navigate(MusicRoute.Settings.route) }) },
        bottomBar = { if (currentTrack != null && !showFullPlayer) ModernMiniPlayer(currentTrack!!, isPlaying, viewModel.currentPosition, viewModel::togglePlayPause) { showFullPlayer = true } }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            NavHost(navController = navController, startDestination = MusicRoute.Dashboard.route, enterTransition = { fadeIn(tween(300)) }, exitTransition = { fadeOut(tween(300)) }) {
                composable(MusicRoute.Dashboard.route) { DashboardScreen(viewModel, loadedSongs, { navController.navigate(MusicRoute.Library.route) { launchSingleTop = true } }, onNavigateToRadio, { navController.navigate(MusicRoute.History.route) }, { navController.navigate(MusicRoute.Playlists.route) }, { navController.navigate(MusicRoute.Folders.route) }, { navController.navigate(MusicRoute.Queue.route) }, { navController.navigate(MusicRoute.Settings.route) }, onNavigateToDuoPlayer, { navController.navigate(MusicRoute.Favorites.route) }) }
                composable(MusicRoute.Library.route) { LibraryContent(displaySongs, viewModel) { trackToTrash = it } }
                composable(MusicRoute.History.route) { HistoryScreen(viewModel, loadedSongs) { trackToTrash = it } }
                composable(MusicRoute.Favorites.route) { FavoritesScreen(viewModel, loadedSongs) { trackToTrash = it } }
                composable(MusicRoute.Playlists.route) { PlaylistsScreen(viewModel, loadedSongs, currentTrack?.id) { viewModel.playQueue(it.first, it.second) } }
                composable(MusicRoute.Folders.route) { FolderList(displaySongs, viewModel) { trackToTrash = it } }
                composable(MusicRoute.Queue.route) { QueueScreen(viewModel, currentTrack) { trackToTrash = it } }
                composable(MusicRoute.Settings.route) { SettingsScreen() }
                composable(MusicRoute.AudioInfo.route) { AudioInfoScreen(currentTrack) }
            }
        }
    }

    if (showFullPlayer) {
        PlayerScreen(
            onBack = { showFullPlayer = false },
            viewModel = viewModel,
            currentTrack = currentTrack,
            isPlaying = isPlaying,
            onNavigateToQueue = { showFullPlayer = false; navController.navigate(MusicRoute.Queue.route) },
            onNavigateToAudioInfo = { showFullPlayer = false; navController.navigate(MusicRoute.AudioInfo.route) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicTopAppBar(currentRoute: String?, isSearchActive: Boolean, searchQuery: String, onSearchChange: (String) -> Unit, onToggleSearch: (Boolean) -> Unit, onNavigateBack: () -> Unit, onNavigateToEqualizer: () -> Unit, onNavigateToDashboard: () -> Unit, onNavigateToSettings: () -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    var showMenu by remember { mutableStateOf(false) }

    if (isSearchActive) {
        TopAppBar(
            title = { TextField(value = searchQuery, onValueChange = onSearchChange, placeholder = { Text("Global Search...", color = TextSecondary) }, singleLine = true, colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() })) },
            navigationIcon = { IconButton(onClick = { onToggleSearch(false); keyboard?.hide() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor)
        )
    } else {
        val title = when (currentRoute) { MusicRoute.Library.route -> "All Songs"; MusicRoute.Playlists.route -> "Playlists"; MusicRoute.History.route -> "Playback History"; MusicRoute.Folders.route -> "Folders"; MusicRoute.Queue.route -> "Up Next"; MusicRoute.Favorites.route -> "Favorites"; MusicRoute.Settings.route -> "Settings"; MusicRoute.AudioInfo.route -> "Audio Info"; MusicRoute.Dashboard.route -> "Dashboard"; else -> "Music" }
        TopAppBar(
            title = { Text(text = title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { if (currentRoute != MusicRoute.Dashboard.route) IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary) } },
            actions = {
                IconButton(onClick = { onToggleSearch(true) }) { Icon(Icons.Default.Search, "Search", tint = TextPrimary) }
                if (currentRoute == MusicRoute.Library.route || currentRoute == MusicRoute.History.route) IconButton(onClick = onNavigateToDashboard) { Icon(Icons.Rounded.Dashboard, "Dashboard", tint = TextPrimary) }
                if (currentRoute == MusicRoute.Dashboard.route || currentRoute == MusicRoute.Library.route) IconButton(onClick = onNavigateToEqualizer) { Icon(Icons.Rounded.GraphicEq, "Equalizer", tint = TextPrimary) }
                Box {
                    IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, "More", tint = TextPrimary) }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, modifier = Modifier.background(SurfaceColor).clip(RoundedCornerShape(12.dp))) {
                        DropdownMenuItem(text = { Text("Settings", color = TextPrimary) }, onClick = { showMenu = false; onNavigateToSettings() }, leadingIcon = { Icon(Icons.Outlined.Settings, null, tint = TextPrimary) })
                    }
                }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor)
        )
    }
}

@Composable
fun DashboardScreen(viewModel: MusicViewModel, loadedSongs: List<AudioTrack>, onNavigateToAllSongs: () -> Unit, onNavigateToRadio: () -> Unit, onNavigateToHistory: () -> Unit, onNavigateToPlaylists: () -> Unit, onNavigateToFolders: () -> Unit, onNavigateToQueue: () -> Unit, onNavigateToSettings: () -> Unit, onNavigateToDuoMode: () -> Unit, onNavigateToFavorites: () -> Unit) {
    val recentHistory by viewModel.recentHistory.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()

    // Optimization: Build mapping once to avoid massive iteration loops
    val songMap = remember(loadedSongs) { loadedSongs.associateBy { it.id } }

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 90.dp)) {
        item {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("Library & Actions", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold); IconButton(onClick = onNavigateToSettings) { Icon(Icons.Rounded.Settings, "Settings", tint = TextSecondary) } }

            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                QuickActionIcon(Icons.Rounded.Favorite, "Favorites", Color.Red, onNavigateToFavorites)
                QuickActionIcon(Icons.Rounded.History, "Recent", PrimaryColor, onNavigateToHistory)
                QuickActionIcon(Icons.Rounded.Folder, "Folders", Color(0xFFFDD835), onNavigateToFolders)
                QuickActionIcon(Icons.Rounded.LibraryMusic, "All Songs", Color(0xFF00ACC1), onNavigateToAllSongs)
            }
            Spacer(Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                QuickActionIcon(Icons.Rounded.Shuffle, "Shuffle", Color(0xFFFF9800)) { if (loadedSongs.isNotEmpty()) viewModel.playQueue(loadedSongs.shuffled(), 0) }
                QuickActionIcon(Icons.AutoMirrored.Rounded.QueueMusic, "Queue", Color(0xFF8E24AA), onNavigateToQueue)
                QuickActionIcon(Icons.Rounded.Radio, "Radio", Color(0xFFE53935), onNavigateToRadio)
                QuickActionIcon(Icons.Rounded.Headset, "Duo", Color(0xFF43A047), onNavigateToDuoMode)
            }
            Spacer(Modifier.height(24.dp))
        }

        if (recentHistory.isNotEmpty()) {
            item {
                Text("Recently Played", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 12.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    val historySongs = recentHistory.take(10).mapNotNull { id -> songMap[id] }
                    items(historySongs) { song -> SquareSongCard(song) { viewModel.playQueue(historySongs, historySongs.indexOf(song)) } }
                }
            }
        }
        item {
            Row(modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 32.dp, bottom = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("Your Playlists", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold); IconButton(onClick = onNavigateToPlaylists) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "All", tint = PrimaryColor) } }
            if (playlists.isEmpty()) Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(120.dp).background(SurfaceColor, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, null, tint = TextSecondary, modifier = Modifier.size(36.dp)); Spacer(Modifier.height(8.dp)); Text("No playlists yet", color = TextSecondary, fontSize = 14.sp) } }
            else LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) { items(playlists) { playlist -> PlaylistCard(playlist) { if (playlist.tracks.isNotEmpty()) viewModel.playQueue(playlist.tracks, 0) } } }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(viewModel: MusicViewModel, allSongs: List<AudioTrack>, onTrashClick: (AudioTrack) -> Unit) {
    val history by viewModel.recentHistory.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val songMap = remember(allSongs) { allSongs.associateBy { it.id } }
    val playCounts = remember(history) { history.groupingBy { it }.eachCount() }
    var sortDescending by remember { mutableStateOf(true) }
    val pagerState = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = pagerState.currentPage, containerColor = BgColor, contentColor = TextPrimary, indicator = { TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(it[pagerState.currentPage]), color = PrimaryColor) }, divider = { HorizontalDivider(color = Color.Transparent) }) {
            Tab(selected = pagerState.currentPage == 0, onClick = { scope.launch { pagerState.animateScrollToPage(0) } }) { Text("Recently Played", modifier = Modifier.padding(16.dp), fontWeight = if (pagerState.currentPage == 0) FontWeight.Bold else FontWeight.Normal, color = if (pagerState.currentPage == 0) PrimaryColor else TextSecondary) }
            Tab(selected = pagerState.currentPage == 1, onClick = { scope.launch { pagerState.animateScrollToPage(1) } }) { Text("Top Played Stats", modifier = Modifier.padding(16.dp), fontWeight = if (pagerState.currentPage == 1) FontWeight.Bold else FontWeight.Normal, color = if (pagerState.currentPage == 1) PrimaryColor else TextSecondary) }
        }
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.Top) { page ->
            if (page == 0) {
                val recentList = history.mapNotNull { id -> songMap[id] }
                if (recentList.isEmpty()) Box(modifier = Modifier.fillMaxWidth().padding(top = 80.dp), contentAlignment = Alignment.Center) { Text("No recent history", color = TextSecondary) }
                else LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 90.dp, top = 8.dp)) { items(recentList) { song -> InteractiveSongRow(song, playlists, viewModel, { viewModel.playQueue(recentList, recentList.indexOf(song)) }, { onTrashClick(song) }) } }
            } else {
                val playedSongs = playCounts.keys.mapNotNull { id -> songMap[id] }
                val sortedSongs = if (sortDescending) playedSongs.sortedByDescending { playCounts[it.id] ?: 0 } else playedSongs.sortedBy { playCounts[it.id] ?: 0 }
                if (sortedSongs.isEmpty()) Box(modifier = Modifier.fillMaxWidth().padding(top = 80.dp), contentAlignment = Alignment.Center) { Text("No play statistics yet", color = TextSecondary) }
                else Column(Modifier.fillMaxSize()) {
                    Surface(color = SurfaceColor, modifier = Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Total Unique Plays: ${playedSongs.size}", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { sortDescending = !sortDescending }) {
                                Text(if (sortDescending) "Highest First" else "Lowest First", color = PrimaryColor, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                Icon(if (sortDescending) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward, "Toggle Sort", tint = PrimaryColor, modifier = Modifier.padding(start = 4.dp).size(20.dp))
                            }
                        }
                    }
                    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 90.dp)) { items(sortedSongs) { song -> HistoryStatRow(song, playCounts[song.id] ?: 0, viewModel, sortedSongs) { onTrashClick(song) } } }
                }
            }
        }
    }
}

@Composable
fun FavoritesScreen(viewModel: MusicViewModel, allSongs: List<AudioTrack>, onTrashClick: (AudioTrack) -> Unit) {
    val favoriteIds by viewModel.favoriteIds.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()

    val favoriteSongs = remember(allSongs, favoriteIds) { allSongs.filter { it.id in favoriteIds } }

    if (favoriteSongs.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { Text("No favorites yet", color = TextSecondary) }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 90.dp)) {
            itemsIndexed(favoriteSongs) { i, song ->
                InteractiveSongRow(song, playlists, viewModel, { viewModel.playQueue(favoriteSongs, i) }, { onTrashClick(song) })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryContent(displaySongs: List<AudioTrack>, vm: MusicViewModel, onTrashClick: (AudioTrack) -> Unit) {
    val pagerState = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()
    val playlists by vm.playlists.collectAsStateWithLifecycle()

    val tabs = listOf("Tracks", "Artists", "Albums")

    // Optimization: Cache massive string groupings to stop UI stuttering
    val artistGroups = remember(displaySongs) { displaySongs.groupBy { it.artist } }
    val albumGroups = remember(displaySongs) { displaySongs.groupBy { it.albumId.toString() } }

    Column {
        ScrollableTabRow(selectedTabIndex = pagerState.currentPage, containerColor = BgColor, contentColor = TextPrimary, indicator = { if (pagerState.currentPage < it.size) TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(it[pagerState.currentPage]), color = PrimaryColor) }, divider = { HorizontalDivider(color = Color.Transparent) }, edgePadding = 16.dp) {
            tabs.forEachIndexed { i, title -> Tab(selected = pagerState.currentPage == i, onClick = { scope.launch { pagerState.animateScrollToPage(i) } }, text = { Text(title, fontWeight = if (pagerState.currentPage == i) FontWeight.Bold else FontWeight.Normal) }, selectedContentColor = PrimaryColor, unselectedContentColor = TextSecondary) }
        }
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.Top) { page ->
            when (page) {
                0 -> LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 90.dp)) { itemsIndexed(displaySongs) { i, song -> InteractiveSongRow(song, playlists, vm, { vm.playQueue(displaySongs, i) }, { onTrashClick(song) }) } }
                1 -> GroupList(groups = artistGroups, isAlbum = false, vm = vm)
                2 -> GroupList(groups = albumGroups, isAlbum = true, vm = vm)
            }
        }
    }
}

@Composable
fun FolderList(songs: List<AudioTrack>, vm: MusicViewModel, onTrashClick: (AudioTrack) -> Unit) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val folders = remember(songs) { songs.filter { it.path.isNotBlank() }.groupBy { it.path.substringBeforeLast("/", "Unknown").trim() }.toSortedMap() }
    var selectedFolder by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = selectedFolder != null) { selectedFolder = null }

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(targetState = selectedFolder, transitionSpec = { if (targetState == null) slideInHorizontally { -it } togetherWith slideOutHorizontally { it } else slideInHorizontally { it } togetherWith slideOutHorizontally { -it } }, label = "FolderTransition") { activeFolder ->
            if (activeFolder == null) {
                LazyVerticalGrid(columns = GridCells.Adaptive(160.dp), contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 90.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxSize()) {
                    items(folders.keys.toList()) { path ->
                        Surface(shape = RoundedCornerShape(16.dp), color = SurfaceColor, modifier = Modifier.fillMaxWidth().aspectRatio(1f).clickable { selectedFolder = path }) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Rounded.Folder, null, tint = PrimaryColor, modifier = Modifier.size(56.dp))
                                Spacer(Modifier.height(12.dp)); Text(path.substringAfterLast("/"), fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(4.dp)); Text("${folders[path]?.size ?: 0} Audio Files", color = TextSecondary, fontSize = 12.sp)
                            }
                        }
                    }
                }
            } else {
                val tracks = folders[activeFolder] ?: emptyList()
                Column(Modifier.fillMaxSize()) {
                    Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = { selectedFolder = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary) }; Text(activeFolder.substringAfterLast("/"), fontWeight = FontWeight.Bold, fontSize = 20.sp, color = TextPrimary, modifier = Modifier.padding(start = 8.dp)) }
                    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 90.dp)) { itemsIndexed(tracks) { index, song -> InteractiveSongRow(song, playlists, vm, { vm.playQueue(tracks, index) }, { onTrashClick(song) }) } }
                }
            }
        }
    }
}

@Composable
fun QueueScreen(viewModel: MusicViewModel, currentTrack: AudioTrack?, onTrashClick: (AudioTrack) -> Unit) {
    val activeQueue by viewModel.currentQueue.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("Up Next", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
        if (currentTrack != null) {
            Text("Now Playing", color = PrimaryColor, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            InteractiveSongRow(currentTrack, playlists, viewModel, { }, { onTrashClick(currentTrack) })
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = SurfaceColor)
        }
        if (activeQueue.isEmpty()) Box(Modifier.fillMaxSize().weight(1f), Alignment.Center) { Text("No more tracks in queue", color = TextSecondary) }
        else LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 90.dp)) {
            itemsIndexed(activeQueue) { idx, song ->
                Row(modifier = Modifier.fillMaxWidth().clickable { viewModel.playQueue(activeQueue, idx) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.DragHandle, "Reorder", tint = TextSecondary); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) { Text(song.title, color = TextPrimary, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(song.artist, color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    IconButton(onClick = { viewModel.removeFromQueue(song) }) { Icon(Icons.Default.Close, "Remove", tint = TextSecondary) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsScreen(vm: MusicViewModel, allSongs: List<AudioTrack>, currentTrackId: Long?, onPlayQueue: (Pair<List<AudioTrack>, Int>) -> Unit) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()

    // Optimization: Resolve derived state manually to bypass stale caching bugs
    var openPlaylistId by remember { mutableStateOf<Long?>(null) }
    val activePlaylist = remember(playlists, openPlaylistId) { playlists.find { it.id == openPlaylistId } }

    var showCreateDialog by remember { mutableStateOf(false) }
    var showAddSongsDialog by remember { mutableStateOf(false) }
    var playlistToRename by remember { mutableStateOf<Playlist?>(null) }
    var playlistToDelete by remember { mutableStateOf<Playlist?>(null) }

    BackHandler(enabled = openPlaylistId != null) { openPlaylistId = null }

    Box(modifier = Modifier.fillMaxSize().background(BgColor)) {
        AnimatedContent(targetState = openPlaylistId, transitionSpec = { if (targetState == null) slideInHorizontally { -it } togetherWith slideOutHorizontally { it } else slideInHorizontally { it } togetherWith slideOutHorizontally { -it } }, label = "ScreenTransition") { targetId ->
            if (targetId == null) {
                Scaffold(floatingActionButton = { FloatingActionButton(onClick = { showCreateDialog = true }, containerColor = PrimaryColor, contentColor = Color.White) { Icon(Icons.Default.Add, "Create Playlist") } }, containerColor = Color.Transparent) { padding ->
                    if (playlists.isEmpty()) Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Box(modifier = Modifier.size(120.dp).background(PrimaryColor.copy(alpha = 0.1f), CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = PrimaryColor, modifier = Modifier.size(64.dp)) }; Spacer(Modifier.height(24.dp)); Text("Your library is empty", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold) } }
                    else LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 100.dp, top = 16.dp)) { items(playlists, key = { it.id }) { pl -> PlaylistRow(pl, { openPlaylistId = pl.id }, { playlistToRename = pl }, { playlistToDelete = pl }) } }
                }
            } else {
                activePlaylist?.let { playlist ->
                    val currentSongs = playlist.tracks
                    var searchQuery by remember { mutableStateOf("") }
                    var filteredSongs by remember { mutableStateOf(currentSongs) }

                    LaunchedEffect(currentSongs) {
                        snapshotFlow { searchQuery }
                            .debounce(250)
                            .collect { query ->
                                withContext(Dispatchers.Default) { filteredSongs = if (query.isBlank()) currentSongs else currentSongs.filter { it.title.contains(query, true) || it.artist.contains(query, true) } }
                            }
                    }

                    Scaffold(topBar = { TopAppBar(title = { Text(playlist.name, fontWeight = FontWeight.Bold, color = TextPrimary) }, navigationIcon = { IconButton(onClick = { openPlaylistId = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary) } }, actions = { IconButton(onClick = { showAddSongsDialog = true }) { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, "Add Songs", tint = TextPrimary) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor)) }, floatingActionButton = { if (currentSongs.isNotEmpty()) FloatingActionButton(onClick = { onPlayQueue(currentSongs.shuffled() to 0) }, containerColor = PrimaryColor, contentColor = Color.White) { Icon(Icons.Default.Shuffle, "Shuffle Play") } }, containerColor = BgColor) { padding ->
                        Column(Modifier.padding(padding).fillMaxSize()) {
                            PlaylistHeader(playlist)
                            if (currentSongs.isNotEmpty()) OutlinedTextField(searchQuery, { searchQuery = it }, placeholder = { Text("Search in playlist...") }, leadingIcon = { Icon(Icons.Default.Search, null, tint = TextSecondary) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), shape = CircleShape, singleLine = true, colors = TextFieldDefaults.colors(focusedContainerColor = SurfaceColor, unfocusedContainerColor = SurfaceColor, focusedIndicatorColor = PrimaryColor, unfocusedIndicatorColor = Color.Transparent))
                            if (filteredSongs.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) { Text(if (searchQuery.isNotEmpty()) "No matches found" else "Playlist is empty", color = TextSecondary) }
                            else LazyColumn(modifier = Modifier.fillMaxSize().weight(1f), contentPadding = PaddingValues(bottom = 80.dp)) { items(filteredSongs, key = { it.id }) { song -> PlaylistEditableSongRow(song, currentTrackId == song.id, { onPlayQueue(currentSongs to currentSongs.indexOf(song)) }, { vm.removeSongFromPlaylist(playlist, song) }) } }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) CreatePlaylistDialog(playlists, { showCreateDialog = false }, { vm.createPlaylist(it); showCreateDialog = false })
    if (playlistToRename != null) RenamePlaylistDialog(playlistToRename!!, playlists, { playlistToRename = null }, { vm.renamePlaylist(playlistToRename!!, it); playlistToRename = null })

    if (showAddSongsDialog && activePlaylist != null) AddSongsDialog(activePlaylist!!, allSongs, { showAddSongsDialog = false }, { selectedSongs -> selectedSongs.forEach { vm.addSongToPlaylist(activePlaylist!!, it) }; showAddSongsDialog = false })

    if (playlistToDelete != null) {
        AlertDialog(
            onDismissRequest = { playlistToDelete = null },
            title = { Text("Delete Playlist", fontWeight = FontWeight.Bold, color = TextPrimary) },
            text = { Text("Are you sure you want to delete '${playlistToDelete?.name}'?") },
            confirmButton = { Button(onClick = { vm.deletePlaylist(playlistToDelete!!); playlistToDelete = null; openPlaylistId = null }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { playlistToDelete = null }) { Text("Cancel", color = TextSecondary) } },
            containerColor = SurfaceColor
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(onBack: () -> Unit, viewModel: MusicViewModel, currentTrack: AudioTrack?, isPlaying: Boolean, onNavigateToQueue: () -> Unit, onNavigateToAudioInfo: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val isFavorite = viewModel.favoriteIds.collectAsStateWithLifecycle().value.contains(currentTrack?.id)
    var showSleepTimer by remember { mutableStateOf(false) }
    var showEffectsSheet by remember { mutableStateOf(false) }
    var showPlaylistDialog by remember { mutableStateOf(false) }

    val bgArtworkReq = remember(currentTrack?.albumId) { ImageRequest.Builder(ctx).data(getAlbumArtUri(currentTrack?.albumId ?: -1)).size(400).allowHardware(true).crossfade(true).build() }
    val artworkReq = remember(currentTrack?.albumId) { ImageRequest.Builder(ctx).data(getAlbumArtUri(currentTrack?.albumId ?: -1)).size(800).crossfade(true).error(android.R.drawable.ic_media_play).build() }

    Box(Modifier.fillMaxSize().background(BgColor)) {
        AsyncImage(bgArtworkReq, null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.15f)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(BgColor.copy(alpha = 0.6f), BgColor))))

        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            PlayerHeader(currentTrack?.album, viewModel.sleepTimeRemaining, onBack, { showSleepTimer = true }, { showEffectsSheet = true })

            Box(modifier = Modifier.weight(0.6f).aspectRatio(1f).padding(16.dp).pointerInput(Unit) { detectTapGestures(onDoubleTap = { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); currentTrack?.let { viewModel.toggleFavorite(listOf(it.id)) } }) }) {
                AsyncImage(artworkReq, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().shadow(24.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)))
            }

            TrackMetadata(currentTrack, isFavorite) { currentTrack?.let { viewModel.toggleFavorite(listOf(it.id)) } }
            Spacer(Modifier.height(16.dp))
            IsolatedProgressBar(viewModel.currentPosition, currentTrack?.duration ?: 1L) { viewModel.seekTo(it, false) }
            Spacer(Modifier.height(24.dp))

            PlaybackControls(isPlaying, viewModel.isShuffleEnabled, viewModel.repeatMode,
                onToggleShuffle = { viewModel.toggleShuffle() },
                onSkipPrev = { viewModel.skipPrevious() },
                onRewind = { viewModel.seekTo((viewModel.currentPosition.value - 10000).coerceAtLeast(0L), false) },
                onTogglePlayPause = { viewModel.togglePlayPause() },
                onForward = { viewModel.seekTo((viewModel.currentPosition.value + 10000).coerceAtMost(currentTrack?.duration ?: 1L), false) },
                onSkipNext = { viewModel.skipNext() },
                onToggleRepeat = { viewModel.toggleRepeat() }
            )

            Spacer(Modifier.height(24.dp))

            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                IconButton(onClick = { currentTrack?.let { viewModel.toggleFavorite(listOf(it.id)) } }) { Icon(if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Favorite", tint = if(isFavorite) Color.Red else TextPrimary) }
                IconButton(onClick = { showPlaylistDialog = true }) { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, "Add to Playlist", tint = TextPrimary) }
                IconButton(onClick = onNavigateToQueue) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Queue", tint = TextPrimary) }
                IconButton(onClick = onNavigateToAudioInfo) { Icon(Icons.Rounded.Info, "Audio Info", tint = TextPrimary) }
            }
        }
    }
    if (showSleepTimer) SleepTimerDialog({ showSleepTimer = false }, { viewModel.startSleepTimer(it) }, { viewModel.cancelSleepTimer() })
    if (showEffectsSheet) AdvancedEffectsBottomSheet(viewModel) { showEffectsSheet = false }
    if (showPlaylistDialog && currentTrack != null) SelectPlaylistDialog(playlists, { showPlaylistDialog = false }) { pl -> viewModel.addSongToPlaylist(pl, currentTrack); showPlaylistDialog = false }
}

@Composable
fun IsolatedProgressBar(positionFlow: Flow<Long>, duration: Long, onSeek: (Long) -> Unit) {
    val position by positionFlow.collectAsStateWithLifecycle(initialValue = 0L)
    var isDragging by remember { mutableStateOf(false) }
    var sliderVal by remember { mutableFloatStateOf(0f) }
    val safeDur = duration.coerceAtLeast(1L).toFloat()

    Column {
        Slider(value = if (isDragging) sliderVal else (position.toFloat() / safeDur).coerceIn(0f, 1f), onValueChange = { isDragging = true; sliderVal = it }, onValueChangeFinished = { onSeek((safeDur * sliderVal).toLong()); isDragging = false }, colors = SliderDefaults.colors(thumbColor = PrimaryColor, activeTrackColor = PrimaryColor, inactiveTrackColor = SurfaceColor), modifier = Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
            Text(formatTime(if (isDragging) (safeDur * sliderVal).toLong() else position), style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            Text(formatTime(duration), style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        }
    }
}

@Composable
fun PlaybackControls(isPlaying: Boolean, isShuffleEnabled: Boolean, repeatMode: Int, onToggleShuffle: () -> Unit, onSkipPrev: () -> Unit, onRewind: () -> Unit, onTogglePlayPause: () -> Unit, onForward: () -> Unit, onSkipNext: () -> Unit, onToggleRepeat: () -> Unit) {
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly, Alignment.CenterVertically) {
        IconButton(onClick = onToggleShuffle, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.Shuffle, "Shuffle", tint = if(isShuffleEnabled) PrimaryColor else TextPrimary, modifier = Modifier.size(24.dp)) }
        IconButton(onClick = onSkipPrev, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.SkipPrevious, "Prev", modifier = Modifier.size(28.dp), tint = TextPrimary) }
        IconButton(onClick = onRewind, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.Replay10, "Rewind", modifier = Modifier.size(28.dp), tint = TextPrimary) }
        Surface(onClick = onTogglePlayPause, shape = CircleShape, color = PrimaryColor, modifier = Modifier.size(64.dp)) { Box(contentAlignment = Alignment.Center) { Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play", tint = Color.White, modifier = Modifier.size(36.dp)) } }
        IconButton(onClick = onForward, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.Forward10, "Forward", modifier = Modifier.size(28.dp), tint = TextPrimary) }
        IconButton(onClick = onSkipNext, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.SkipNext, "Next", modifier = Modifier.size(28.dp), tint = TextPrimary) }
        IconButton(onClick = onToggleRepeat, modifier = Modifier.size(36.dp)) { Icon(if (repeatMode != Player.REPEAT_MODE_OFF) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repeat", tint = if(repeatMode != Player.REPEAT_MODE_OFF) PrimaryColor else TextPrimary, modifier = Modifier.size(24.dp)) }
    }
}

@Composable
fun SettingsScreen() {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 90.dp)) {
        item { SettingsHeader("Playback") }
        item { SettingsSwitchRow("Gapless Playback", "Eliminate gaps between consecutive tracks", true) }
        item { SettingsSwitchRow("Crossfade", "Fade out current track while fading in next", false) }
        item { SettingsSwitchRow("Pause on Unplug", "Automatically pause when headphones disconnect", true) }
        item { SettingsSwitchRow("Resume Playback", "Resume when headphones are plugged in", false) }
        item { SettingsHeader("Audio Engine") }
        item { SettingsNavRow("10-Band Equalizer & Effects", "Configure Parametric EQ, Bass, Reverb") }
        item { SettingsSwitchRow("Mono Audio", "Combine left and right channels", false) }
        item { SettingsHeader("Library") }
        item { SettingsNavRow("Force Rescan Media", "Detect newly added or modified music") }
    }
}

@Composable
fun AudioInfoScreen(currentTrack: AudioTrack?) {
    Box(modifier = Modifier.fillMaxSize().background(BgColor), contentAlignment = Alignment.Center) {
        Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.HighQuality, null, tint = PrimaryColor, modifier = Modifier.size(64.dp)); Spacer(Modifier.height(16.dp))
            Text("Format: ${currentTrack?.path?.substringAfterLast('.')?.uppercase(Locale.US) ?: "N/A"}", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Path: ${currentTrack?.path}", color = TextSecondary, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun GroupList(groups: Map<String, List<AudioTrack>>, isAlbum: Boolean, vm: MusicViewModel) {
    var selectedGroup by remember { mutableStateOf<Pair<String, List<AudioTrack>>?>(null) }
    val playlists by vm.playlists.collectAsStateWithLifecycle()

    BackHandler(selectedGroup != null) { selectedGroup = null }

    AnimatedContent(selectedGroup, label = "group_transition") { activeGroup ->
        if (activeGroup == null) {
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 90.dp)) {
                items(groups.toList()) { (key, tracks) ->
                    val firstTrack = tracks.first()
                    val title = if (isAlbum) firstTrack.album else key

                    ListItem(
                        modifier = Modifier.clickable { selectedGroup = key to tracks },
                        headlineContent = { Text(title, fontWeight = FontWeight.Bold, maxLines = 1, color = TextPrimary) },
                        supportingContent = { Text("${tracks.size} Songs", fontSize = 12.sp, color = TextSecondary) },
                        leadingContent = {
                            if (isAlbum) {
                                AsyncImage(model = getArtRequest(firstTrack.albumId), contentDescription = null, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(SurfaceColor), contentScale = ContentScale.Crop)
                            } else {
                                Box(modifier = Modifier.size(48.dp).background(SurfaceColor, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Default.Person, null, tint = PrimaryColor) }
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                }
            }
        } else {
            val (title, tracks) = activeGroup
            val headerTitle = if (isAlbum) tracks.first().album else title
            Column(Modifier.fillMaxSize()) {
                Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { selectedGroup = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = TextPrimary) }
                    Text(text = headerTitle, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 90.dp)) {
                    itemsIndexed(tracks) { index, song -> InteractiveSongRow(song, playlists, vm, { vm.playQueue(tracks, index) }, { }) }
                }
            }
        }
    }
}

@Composable
fun InteractiveSongRow(song: AudioTrack, playlists: List<Playlist>, vm: MusicViewModel, onClick: () -> Unit, onTrashClick: () -> Unit) {
    var showMenu by remember { mutableStateOf(false) }
    var showTagEditor by remember { mutableStateOf(false) }
    var showPlaylistDialog by remember { mutableStateOf(false) }
    val isPlaying = vm.currentTrack.collectAsStateWithLifecycle().value?.id == song.id

    Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(getArtRequest(song.albumId), null, contentScale = ContentScale.Crop, modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(SurfaceColor))
        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(song.title, color = if (isPlaying) PrimaryColor else TextPrimary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${song.artist} • ${formatTotalDuration(song.duration)}", color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, "More", tint = TextSecondary) }
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, modifier = Modifier.background(SurfaceColor)) {
            DropdownMenuItem(text = { Text("Play Next", color = TextPrimary) }, onClick = { showMenu = false; vm.playNext(song) }, leadingIcon = { Icon(Icons.Rounded.QueuePlayNext, null, tint = TextPrimary) })
            DropdownMenuItem(text = { Text("Add to Queue", color = TextPrimary) }, onClick = { showMenu = false; vm.addToQueue(song) }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null, tint = TextPrimary) })
            DropdownMenuItem(text = { Text("Add to Playlist", color = TextPrimary) }, onClick = { showMenu = false; showPlaylistDialog = true }, leadingIcon = { Icon(Icons.Rounded.PlaylistAddCircle, null, tint = TextPrimary) })
            DropdownMenuItem(text = { Text("Edit Tags", color = TextPrimary) }, onClick = { showMenu = false; showTagEditor = true }, leadingIcon = { Icon(Icons.Rounded.Edit, null, tint = TextPrimary) })
            DropdownMenuItem(text = { Text("Move to Trash", color = MaterialTheme.colorScheme.error) }, onClick = { showMenu = false; onTrashClick() }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) })
        }
    }
    if (showTagEditor) TagEditorDialog(song, { showTagEditor = false }, { })
    if (showPlaylistDialog) SelectPlaylistDialog(playlists, { showPlaylistDialog = false }) { pl -> vm.addSongToPlaylist(pl, song); showPlaylistDialog = false }
}

@Composable
fun SelectPlaylistDialog(playlists: List<Playlist>, onDismiss: () -> Unit, onSelect: (Playlist) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to Playlist", fontWeight = FontWeight.Bold, color = TextPrimary) },
        text = {
            if (playlists.isEmpty()) Text("No playlists available.")
            else LazyColumn { items(playlists) { pl -> TextButton(onClick = { onSelect(pl) }, modifier = Modifier.fillMaxWidth()) { Text(pl.name, color = PrimaryColor) } } }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) } },
        containerColor = SurfaceColor
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedEffectsBottomSheet(viewModel: MusicViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SurfaceColor) {
        Column(Modifier.padding(24.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Audio Engineering", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = TextPrimary)
                TextButton(onClick = { viewModel.resetAudioEffects() }) { Text("Reset", color = MaterialTheme.colorScheme.error) }
            }
            Spacer(Modifier.height(16.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(listOf("Normal", "Nightcore", "Slowed", "Vaporwave")) { effect -> AssistChip(onClick = { viewModel.setPlaybackEffect(false, effect) }, label = { Text(effect, color = TextPrimary) }, colors = AssistChipDefaults.assistChipColors(containerColor = BgColor)) } }
            Spacer(Modifier.height(24.dp))

            // Connected to ViewModel Flow state with custom value ranges
            CompactSlider("Pitch (Semitones)", viewModel.pitchPlayer1.collectAsStateWithLifecycle().value, 0.5f..2.0f, PrimaryColor) { viewModel.setPlayerPitch(false, it) }
            Spacer(Modifier.height(12.dp))
            CompactSlider("Speed", viewModel.speedPlayer1.collectAsStateWithLifecycle().value, 0.5f..2.0f, PrimaryColor) { viewModel.setPlayerSpeed(false, it) }
            Spacer(Modifier.height(12.dp))
            CompactSlider("Volume", viewModel.volume1.collectAsStateWithLifecycle().value, 0.0f..1.0f, PrimaryColor) { viewModel.updateVolume(it, false) }
            Spacer(Modifier.height(12.dp))
            CompactSlider("Stereo Balance (L/R)", viewModel.balance1.collectAsStateWithLifecycle().value, -1.0f..1.0f, PrimaryColor) { viewModel.updateBalance(it, false) }

            Spacer(Modifier.height(48.dp))
        }
    }
}

@Composable
fun CompactSlider(label: String, value: Float, valueRange: ClosedFloatingPointRange<Float>, activeColor: Color, onValueChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextSecondary)
            Text(String.format(Locale.US, "%.2f", value), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(thumbColor = activeColor, activeTrackColor = activeColor, inactiveTrackColor = SurfaceColor)
        )
    }
}

@Composable
fun ModernMiniPlayer(track: AudioTrack, isPlaying: Boolean, positionFlow: Flow<Long>, onPlayPause: () -> Unit, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val view = LocalView.current
    Surface(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp).fillMaxWidth().height(64.dp).pointerInput(Unit) {}.clickable(onClick = onClick), shape = RoundedCornerShape(8.dp), color = colors.surface, tonalElevation = 4.dp, shadowElevation = 4.dp) {
        Box(Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(getArtRequest(track.albumId), "Album Art", contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)).background(colors.surfaceVariant))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) { Text(track.title, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 14.sp); Text(track.artist, color = colors.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                IconButton(onClick = { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); onPlayPause() }, interactionSource = remember { MutableInteractionSource() }) { Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = colors.onSurface, modifier = Modifier.size(32.dp)) }
            }
            MiniPlayerProgressBar(positionFlow, track.duration)
        }
    }
}

@Composable
private fun BoxScope.MiniPlayerProgressBar(positionFlow: Flow<Long>, duration: Long) {
    val position by positionFlow.collectAsStateWithLifecycle(initialValue = 0L)
    val progress = if (duration > 0) (position.coerceIn(0, duration)).toFloat() / duration else 0f
    LinearProgressIndicator({ progress }, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp), color = MaterialTheme.colorScheme.primary, trackColor = Color.Transparent)
}

@Composable
fun QuickActionIcon(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
        Box(modifier = Modifier.size(56.dp).background(color.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) { Icon(icon, label, tint = color, modifier = Modifier.size(28.dp)) }
        Spacer(Modifier.height(8.dp)); Text(label, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun SquareSongCard(song: AudioTrack, onClick: () -> Unit) {
    Column(Modifier.width(120.dp).clickable(onClick = onClick)) {
        AsyncImage(getArtRequest(song.albumId), null, contentScale = ContentScale.Crop, modifier = Modifier.size(120.dp).clip(RoundedCornerShape(12.dp)).background(SurfaceColor))
        Text(song.title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(song.artist, color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun PlaylistCard(playlist: Playlist, onClick: () -> Unit) {
    Column(modifier = Modifier.width(140.dp).clickable(onClick = onClick)) {
        Box(modifier = Modifier.size(140.dp).clip(RoundedCornerShape(16.dp)).background(Brush.verticalGradient(listOf(SurfaceColor, BgColor))), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Rounded.QueueMusic, null, tint = TextSecondary.copy(alpha = 0.3f), modifier = Modifier.size(64.dp))
            if (playlist.tracks.isNotEmpty()) Box(modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).size(36.dp).background(PrimaryColor, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.PlayArrow, "Play", tint = Color.White, modifier = Modifier.size(20.dp)) }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(playlist.name, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
        Text("${playlist.tracks.size} Tracks", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

@Composable
fun HistoryStatRow(song: AudioTrack, count: Int, vm: MusicViewModel, sortedSongs: List<AudioTrack>, onTrashClick: () -> Unit) {
    var showMenu by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(song.title, fontWeight = FontWeight.Bold, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text("${song.artist}  •  Played $count times", color = PrimaryColor, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = { AsyncImage(getArtRequest(song.albumId), null, Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(SurfaceColor), contentScale = ContentScale.Crop) },
        trailingContent = {
            Box {
                IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, "More", tint = TextSecondary) }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, modifier = Modifier.background(SurfaceColor)) {
                    DropdownMenuItem(text = { Text("Play", color = TextPrimary) }, onClick = { showMenu = false; vm.playQueue(sortedSongs, sortedSongs.indexOf(song)) }, leadingIcon = { Icon(Icons.Rounded.PlayArrow, null, tint = TextPrimary) })
                    DropdownMenuItem(text = { Text("Move to Trash", color = MaterialTheme.colorScheme.error) }, onClick = { showMenu = false; onTrashClick() }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) })
                }
            }
        }, colors = ListItemDefaults.colors(containerColor = Color.Transparent), modifier = Modifier.clickable { vm.playQueue(sortedSongs, sortedSongs.indexOf(song)) }
    )
}

@Composable
fun PlaylistHeader(playlist: Playlist) {
    // Optimization: Cache duration loop
    val totalDurationMs = remember(playlist.tracks) { playlist.tracks.sumOf { it.duration } }

    Box(modifier = Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(PrimaryColor.copy(alpha = 0.15f), BgColor))), Alignment.Center) {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 24.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val imgReq = getArtRequest(playlist.tracks.firstOrNull { it.albumId > 0 }?.albumId ?: -1L)
            if (imgReq != null) AsyncImage(imgReq, null, contentScale = ContentScale.Crop, modifier = Modifier.size(110.dp).shadow(8.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp))) else Box(modifier = Modifier.size(110.dp).shadow(8.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)).background(SurfaceColor), Alignment.Center) { Icon(Icons.Default.MusicNote, null, tint = TextSecondary, modifier = Modifier.size(48.dp)) }
            Spacer(Modifier.width(24.dp))
            Column { Text(playlist.name, fontSize = 28.sp, fontWeight = FontWeight.Black, color = TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis); Spacer(Modifier.height(8.dp)); Text("${playlist.tracks.size} songs", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = PrimaryColor); Text(formatTotalDuration(totalDurationMs), fontSize = 13.sp, color = TextSecondary) }
        }
    }
}

@Composable
fun PlaylistRow(playlist: Playlist, onClick: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var showMenu by remember { mutableStateOf(false) }
    val coverTrack = remember(playlist.tracks) { playlist.tracks.firstOrNull { it.albumId > 0 } }

    Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        val imgReq = getArtRequest(coverTrack?.albumId ?: -1L)
        if (imgReq != null) AsyncImage(imgReq, null, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp))) else Box(modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(PrimaryColor.copy(alpha = 0.1f)), Alignment.Center) { Icon(Icons.Default.MusicNote, null, tint = PrimaryColor) }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) { Text(playlist.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis); Spacer(Modifier.height(4.dp)); Text("${playlist.tracks.size} Songs", fontSize = 13.sp, color = TextSecondary) }
        Box { IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, "More", tint = TextSecondary) }; DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, modifier = Modifier.background(SurfaceColor)) { DropdownMenuItem(text = { Text("Rename", color = TextPrimary) }, onClick = { showMenu = false; onRename() }, leadingIcon = { Icon(Icons.Default.Edit, null, tint = TextPrimary) }); DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, onClick = { showMenu = false; onDelete() }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistEditableSongRow(song: AudioTrack, isPlaying: Boolean, onClick: () -> Unit, onRemove: () -> Unit) {
    val density = LocalDensity.current
    val dismissState = remember(song.id) { SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, density, { if (it == SwipeToDismissBoxValue.EndToStart) { onRemove(); true } else false }, { it * 0.5f }) }
    SwipeToDismissBox(state = dismissState, enableDismissFromStartToEnd = false, backgroundContent = { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.error).padding(horizontal = 24.dp), Alignment.CenterEnd) { Icon(Icons.Default.Delete, "Remove", tint = MaterialTheme.colorScheme.onError) } }) {
        Row(modifier = Modifier.fillMaxWidth().background(BgColor).clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(getArtRequest(song.albumId), null, contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(SurfaceColor)); Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) { Text(song.title, color = if (isPlaying) PrimaryColor else TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold); Spacer(Modifier.height(2.dp)); Text(song.artist, color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            if (isPlaying) Icon(Icons.Default.GraphicEq, null, tint = PrimaryColor, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
fun PlayerHeader(albumName: String?, sleepTimeRemaining: Long, onBack: () -> Unit, onSleepTimerClick: () -> Unit, onEffectsClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.Rounded.KeyboardArrowDown, "Minimize", tint = TextPrimary) }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) { Text("PLAYING FROM", fontSize = 10.sp, letterSpacing = 2.sp, color = TextSecondary, fontWeight = FontWeight.Bold); Text(albumName ?: "Unknown Album", fontSize = 12.sp, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (sleepTimeRemaining > 0) Text("${sleepTimeRemaining / 60000L}m", color = PrimaryColor, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 4.dp))
            IconButton(onClick = onSleepTimerClick) { Icon(Icons.Filled.Bedtime, "Sleep Timer", tint = if (sleepTimeRemaining > 0) PrimaryColor else TextPrimary) }
            IconButton(onClick = onEffectsClick) { Icon(Icons.Rounded.GraphicEq, "Effects", tint = TextPrimary) }
        }
    }
}

@Composable
fun TrackMetadata(track: AudioTrack?, isFavorite: Boolean, onToggleFavorite: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(track?.title ?: "Not Playing", fontSize = 22.sp, color = TextPrimary, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(track?.artist ?: "Unknown", fontSize = 16.sp, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
fun SettingsHeader(title: String) { Text(title, color = PrimaryColor, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp)) }

@Composable
fun SettingsSwitchRow(title: String, sub: String, def: Boolean) { var checked by remember { mutableStateOf(def) }; Row(Modifier.fillMaxWidth().clickable { checked = !checked }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title, color = TextPrimary, fontSize = 16.sp); Text(sub, color = TextSecondary, fontSize = 12.sp) }; Switch(checked = checked, onCheckedChange = { checked = it }, colors = SwitchDefaults.colors(checkedThumbColor = PrimaryColor, checkedTrackColor = PrimaryColor.copy(alpha=0.5f))) } }

@Composable
fun SettingsNavRow(title: String, sub: String) { Row(Modifier.fillMaxWidth().clickable {}.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title, color = TextPrimary, fontSize = 16.sp); Text(sub, color = TextSecondary, fontSize = 12.sp) } } }

@Composable
fun SleepTimerDialog(onDismiss: () -> Unit, onSet: (Int) -> Unit, onCancel: () -> Unit) { AlertDialog(onDismissRequest = onDismiss, title = { Text("Sleep Timer", color = TextPrimary) }, text = { Column { listOf(15, 30, 45, 60).forEach { mins -> TextButton(onClick = { onSet(mins); onDismiss() }, modifier = Modifier.fillMaxWidth()) { Text("$mins Minutes", color = PrimaryColor) } } } }, confirmButton = { TextButton(onClick = { onCancel(); onDismiss() }) { Text("Cancel Timer", color = MaterialTheme.colorScheme.error) } }, containerColor = SurfaceColor) }

@Composable
fun TagEditorDialog(song: AudioTrack, onDismiss: () -> Unit, onSave: () -> Unit) { AlertDialog(onDismissRequest = onDismiss, containerColor = SurfaceColor, title = { Text("Edit Metadata", color = TextPrimary, fontWeight = FontWeight.Bold) }, text = { Column { OutlinedTextField(song.title, {}, label = { Text("Title") }); OutlinedTextField(song.artist, {}, label = { Text("Artist") }) } }, confirmButton = { TextButton(onClick = { onSave(); onDismiss() }) { Text("Save", color = PrimaryColor) } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) } }) }

@Composable
fun CreatePlaylistDialog(existingPlaylists: List<Playlist>, onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var newName by remember { mutableStateOf("") }; var isError by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("New Playlist", fontWeight = FontWeight.Bold, color = TextPrimary) }, text = { Column { OutlinedTextField(newName, { newName = it; isError = false }, label = { Text("Playlist Name") }, singleLine = true, isError = isError, colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent)); if (isError) Text("Name already exists", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) } }, confirmButton = { Button(onClick = { if (newName.isNotBlank()) { if (existingPlaylists.any { it.name.equals(newName, true) }) isError = true else onCreate(newName) } }, colors = ButtonDefaults.buttonColors(containerColor = PrimaryColor)) { Text("Create") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) } }, containerColor = SurfaceColor)
}

@Composable
fun RenamePlaylistDialog(playlist: Playlist, existingPlaylists: List<Playlist>, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var newName by remember { mutableStateOf(playlist.name) }; var isError by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Rename Playlist", fontWeight = FontWeight.Bold, color = TextPrimary) }, text = { Column { OutlinedTextField(newName, { newName = it; isError = false }, label = { Text("New Name") }, singleLine = true, isError = isError, colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent)); if (isError) Text("Name already exists", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) } }, confirmButton = { Button(onClick = { if (newName.isNotBlank() && newName != playlist.name) { if (existingPlaylists.any { it.name.equals(newName, true) }) isError = true else onRename(newName) } else if (newName == playlist.name) onDismiss() }, colors = ButtonDefaults.buttonColors(containerColor = PrimaryColor)) { Text("Rename") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) } }, containerColor = SurfaceColor)
}

@Composable
fun AddSongsDialog(playlist: Playlist, allSongs: List<AudioTrack>, onDismiss: () -> Unit, onAddSongs: (List<AudioTrack>) -> Unit) {
    val availableSongs = remember(allSongs, playlist.tracks) { val existingIds = playlist.tracks.map { it.id }.toSet(); allSongs.filter { it.id !in existingIds } }
    val selectedIds = remember { mutableStateListOf<Long>() }
    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = SurfaceColor)) {
            Column(Modifier.fillMaxSize()) {
                Text("Add Songs", modifier = Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = TextPrimary)
                if (availableSongs.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) { Text("No more songs to add", color = TextSecondary) }
                else LazyColumn(modifier = Modifier.weight(1f)) { items(availableSongs, key = { it.id }) { song -> val isSel = selectedIds.contains(song.id); Row(Modifier.fillMaxWidth().clickable { if (isSel) selectedIds.remove(song.id) else selectedIds.add(song.id) }.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = isSel, onCheckedChange = { if (it) selectedIds.add(song.id) else selectedIds.remove(song.id) }, colors = CheckboxDefaults.colors(checkedColor = PrimaryColor)); Spacer(Modifier.width(12.dp)); Column { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, color = TextPrimary); Text(song.artist, style = MaterialTheme.typography.bodySmall, color = TextSecondary) } } } }
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) { TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) }; Spacer(Modifier.width(8.dp)); Button(onClick = { onAddSongs(availableSongs.filter { it.id in selectedIds }) }, enabled = selectedIds.isNotEmpty(), colors = ButtonDefaults.buttonColors(containerColor = PrimaryColor)) { Text("Add (${selectedIds.size})") } }
            }
        }
    }
}

@Composable
fun getArtRequest(albumId: Long): ImageRequest? { val ctx = LocalContext.current; return remember(albumId) { if (albumId > 0) ImageRequest.Builder(ctx).data(ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)).size(200).build() else null } }

fun getAlbumArtUri(albumId: Long): Uri? { return if (albumId > 0) ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId) else null }
fun formatTotalDuration(ms: Long): String { val t = ms / 1000; val h = t / 3600; val m = (t % 3600) / 60; return if (h > 0) String.format(Locale.US, "%dh %dm", h, m) else String.format(Locale.US, "%dm", m) }
fun formatTime(ms: Long): String { val t = ms / 1000; return String.format(Locale.US, "%02d:%02d", t / 60, t % 60) }