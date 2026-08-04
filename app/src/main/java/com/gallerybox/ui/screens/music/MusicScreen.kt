@file:Suppress("unused", "UnsafeOptInUsageError", "DEPRECATION")
@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
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
import kotlinx.collections.immutable.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

sealed class MusicRoute(val route: String) {
    data object Dashboard : MusicRoute("dashboard")
    data object Library : MusicRoute("library")
    data object Playlists : MusicRoute("playlists")
    data object Folders : MusicRoute("folders")
    data object History : MusicRoute("history")
    data object Queue : MusicRoute("queue")
    data object AudioInfo : MusicRoute("audio_info")
    data object Favorites : MusicRoute("favorites")
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
    var showTopMenu by remember { mutableStateOf(false) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }

    val currentTrack by viewModel.currentTrack.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val loadedSongsRaw by viewModel.allAudioTracks.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val playlistsRaw by viewModel.playlists.collectAsStateWithLifecycle(initialValue = emptyList())
    val playlists = remember(playlistsRaw) { playlistsRaw.toImmutableList() }

    val loadedSongs = remember(loadedSongsRaw) { loadedSongsRaw.toImmutableList() }

    LaunchedEffect(Unit) {
        viewModel.loadAllAudioTracks()
        trashViewModel.onRefreshMusic = {
            viewModel.loadAllAudioTracks()
        }
    }

    val displaySongs = remember(loadedSongs, searchQuery) {
        if (searchQuery.isBlank()) loadedSongs
        else loadedSongs.filter { it.title.contains(searchQuery, true) || it.artist.contains(searchQuery, true) }.toImmutableList()
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
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            onDismissRequest = { trackToTrash = null },
            title = { Text("Move to Trash?", fontWeight = FontWeight.Bold) },
            text = { Text("Move '${song.title}' to the trash?") },
            confirmButton = {
                Button(
                    onClick = { trashViewModel.confirmPendingMusicTrash(listOf(song), trashLauncher::launch); trackToTrash = null },
                    colors = ButtonDefaults.buttonColors(MaterialTheme.colorScheme.error)
                ) { Text("Move to Trash", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { trackToTrash = null }) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        )
    }

    if (showCreatePlaylistDialog) {
        CreatePlaylistDialog(playlists, { showCreatePlaylistDialog = false }, { viewModel.createPlaylist(it); showCreatePlaylistDialog = false })
    }

    BackHandler(showFullPlayer || isSearchActive) {
        if (showFullPlayer) showFullPlayer = false else { isSearchActive = false; viewModel.setSearchQuery("") }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (!showFullPlayer) {
                MusicTopAppBar(
                    currentRoute = currentRoute,
                    isSearchActive = isSearchActive,
                    searchQuery = searchQuery,
                    onSearchChange = viewModel::setSearchQuery,
                    onToggleSearch = { isSearchActive = it; if (!it) viewModel.setSearchQuery("") },
                    onNavigateBack = navController::popBackStack,
                    onMenuClick = { showTopMenu = true }
                )

                DropdownMenu(
                    expanded = showTopMenu,
                    onDismissRequest = { showTopMenu = false },
                    modifier = Modifier.background(MaterialTheme.colorScheme.surface).clip(RoundedCornerShape(12.dp))
                ) {
                    if (currentRoute == MusicRoute.Playlists.route) {
                        DropdownMenuItem(text = { Text("New Playlist") }, leadingIcon = { Icon(Icons.Rounded.Add, null) }, onClick = { showTopMenu = false; showCreatePlaylistDialog = true })
                    } else {
                        DropdownMenuItem(text = { Text("Equalizer") }, leadingIcon = { Icon(Icons.Rounded.GraphicEq, null) }, onClick = { showTopMenu = false; onNavigateToEqualizer() })
                    }
                }
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = currentTrack != null && !showFullPlayer,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                currentTrack?.let { track ->
                    ModernMiniPlayer(
                        track = track,
                        isPlaying = isPlaying,
                        positionFlow = viewModel.currentPosition,
                        onPlayPause = viewModel::togglePlayPause,
                        onClick = { showFullPlayer = true },
                        onNext = viewModel::skipNext,
                        onPrev = viewModel::skipPrevious,
                        onFavorite = { viewModel.toggleFavorite(listOf(track.id)) },
                        onQueue = { navController.navigate(MusicRoute.Queue.route) }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            NavHost(
                navController = navController,
                startDestination = MusicRoute.Dashboard.route,
                enterTransition = { slideInHorizontally { it / 2 } + fadeIn(tween(300)) },
                exitTransition = { fadeOut(tween(300)) },
                popEnterTransition = { fadeIn(tween(300)) },
                popExitTransition = { slideOutHorizontally { it / 2 } + fadeOut(tween(300)) }
            ) {
                composable(MusicRoute.Dashboard.route) { DashboardScreen(viewModel, loadedSongs, { navController.navigate(MusicRoute.Library.route) { launchSingleTop = true } }, onNavigateToRadio, { navController.navigate(MusicRoute.History.route) }, { navController.navigate(MusicRoute.Playlists.route) }, { navController.navigate(MusicRoute.Folders.route) }, { navController.navigate(MusicRoute.Queue.route) }, onNavigateToDuoPlayer, { navController.navigate(MusicRoute.Favorites.route) }) }
                composable(MusicRoute.Library.route) { LibraryContent(displaySongs, viewModel) { trackToTrash = it } }
                composable(MusicRoute.History.route) { HistoryScreen(viewModel, loadedSongs) { trackToTrash = it } }
                composable(MusicRoute.Favorites.route) { FavoritesScreen(viewModel, loadedSongs) { trackToTrash = it } }
                composable(MusicRoute.Playlists.route) { PlaylistsScreen(viewModel, loadedSongs, currentTrack?.id) { viewModel.playQueue(it.first, it.second) } }
                composable(MusicRoute.Folders.route) { FolderList(displaySongs, viewModel) { trackToTrash = it } }
                composable(MusicRoute.Queue.route) { QueueScreen(viewModel, currentTrack) { trackToTrash = it } }
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
fun MusicTopAppBar(currentRoute: String?, isSearchActive: Boolean, searchQuery: String, onSearchChange: (String) -> Unit, onToggleSearch: (Boolean) -> Unit, onNavigateBack: () -> Unit, onMenuClick: () -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current

    if (isSearchActive) {
        Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).statusBarsPadding(), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onToggleSearch(false); keyboard?.hide() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onSurface) }
                TextField(value = searchQuery, onValueChange = onSearchChange, placeholder = { Text("Search Music...", color = MaterialTheme.colorScheme.onSurfaceVariant) }, singleLine = true, colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, focusedTextColor = MaterialTheme.colorScheme.onSurface, unfocusedTextColor = MaterialTheme.colorScheme.onSurface), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }), modifier = Modifier.weight(1f))
            }
        }
    } else {
        val title = when (currentRoute) { MusicRoute.Library.route -> "All Songs"; MusicRoute.Playlists.route -> "Playlists"; MusicRoute.History.route -> "Playback History"; MusicRoute.Folders.route -> "Folders"; MusicRoute.Queue.route -> "Up Next"; MusicRoute.Favorites.route -> "Favorites"; MusicRoute.AudioInfo.route -> "Audio Info"; MusicRoute.Dashboard.route -> "Music"; else -> "Music" }
        CenterAlignedTopAppBar(
            title = { Text(text = title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { if (currentRoute != MusicRoute.Dashboard.route) IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onSurface) } },
            actions = {
                IconButton(onClick = { onToggleSearch(true) }) { Icon(Icons.Default.Search, "Search", tint = MaterialTheme.colorScheme.onSurface) }
                IconButton(onClick = onMenuClick) { Icon(Icons.Default.MoreVert, "More", tint = MaterialTheme.colorScheme.onSurface) }
            }, colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent)
        )
    }
}

@Composable
fun DashboardScreen(viewModel: MusicViewModel, loadedSongs: ImmutableList<AudioTrack>, onNavigateToAllSongs: () -> Unit, onNavigateToRadio: () -> Unit, onNavigateToHistory: () -> Unit, onNavigateToPlaylists: () -> Unit, onNavigateToFolders: () -> Unit, onNavigateToQueue: () -> Unit, onNavigateToDuoMode: () -> Unit, onNavigateToFavorites: () -> Unit) {
    val recentHistoryRaw by viewModel.recentHistory.collectAsStateWithLifecycle(initialValue = emptyList())
    val playlistsRaw by viewModel.playlists.collectAsStateWithLifecycle(initialValue = emptyList())

    val recentHistory = remember(recentHistoryRaw) { recentHistoryRaw.toImmutableList() }
    val playlists = remember(playlistsRaw) { playlistsRaw.toImmutableList() }
    val songMap = remember(loadedSongs) { loadedSongs.associateBy { it.id }.toImmutableMap() }

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 90.dp)) {
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Quick Access", color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    QuickActionIcon(Icons.Rounded.Favorite, "Favorites", onNavigateToFavorites)
                    QuickActionIcon(Icons.Rounded.Schedule, "Recent", onNavigateToHistory)
                    QuickActionIcon(Icons.Rounded.Folder, "Folders", onNavigateToFolders)
                    QuickActionIcon(Icons.Rounded.LibraryMusic, "All Songs", onNavigateToAllSongs)
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    QuickActionIcon(Icons.Rounded.Radio, "Radio", onNavigateToRadio)
                    QuickActionIcon(Icons.Rounded.Headset, "Duo Mode", onNavigateToDuoMode)
                    QuickActionIcon(Icons.AutoMirrored.Rounded.QueueMusic, "Queue", onNavigateToQueue)
                    QuickActionIcon(Icons.Rounded.Shuffle, "Shuffle") {
                        if (loadedSongs.isNotEmpty()) {
                            val toPlay = if (loadedSongs.size > 1000) loadedSongs.shuffled().take(1000) else loadedSongs.shuffled()
                            toPlay.firstOrNull()?.let { track -> viewModel.playQueue(toPlay, track) }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }

        if (recentHistory.isNotEmpty()) {
            item {
                Text("Recently Played", color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, bottom = 12.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    val historySongs = recentHistory.take(10).mapNotNull { id -> songMap[id] }.toImmutableList()
                    items(historySongs, key = { it.id }, contentType = { "song" }) { song ->
                        HorizontalSongCard(song) { viewModel.playQueue(historySongs, song) }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }

        item {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Your Playlists", color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                TextButton(onClick = onNavigateToPlaylists) { Text("See All", color = MaterialTheme.colorScheme.primary) }
            }

            if (playlists.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(120.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🎵", fontSize = 32.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("No Playlists", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                        Text("Create your first playlist.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
            } else {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(playlists, key = { it.id }, contentType = { "playlist" }) { playlist ->
                        PlaylistCard(playlist) {
                            if (playlist.tracks.isNotEmpty()) {
                                viewModel.playQueue(playlist.tracks, playlist.tracks.first())
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        item {
            Text("Recently Added", color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, bottom = 12.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                val recentAdded = loadedSongs.sortedByDescending { it.dateAdded }.take(15).toImmutableList()
                items(recentAdded, key = { it.id }, contentType = { "song" }) { song ->
                    HorizontalSongCard(song) { viewModel.playQueue(recentAdded, song) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        item {
            Text("Most Played", color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, bottom = 12.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                val mostPlayedIds = viewModel.recentHistory.value.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(15).map { it.key }
                val mostPlayed = mostPlayedIds.mapNotNull { id -> songMap[id] }.toImmutableList()
                if (mostPlayed.isNotEmpty()) {
                    items(mostPlayed, key = { it.id }, contentType = { "song" }) { song ->
                        HorizontalSongCard(song) { viewModel.playQueue(mostPlayed, song) }
                    }
                } else {
                    item { Text("Play more music to see your favorites here", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun QuickActionIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick, interactionSource = remember { MutableInteractionSource() }, indication = null).width(72.dp)) {
        Surface(modifier = Modifier.size(56.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, label, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun HorizontalSongCard(song: AudioTrack, onClick: () -> Unit) {
    Column(Modifier.width(160.dp).clickable(onClick = onClick)) {
        AsyncImage(getArtRequest(song.albumId), null, contentScale = ContentScale.Crop, modifier = Modifier.size(160.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh))
        Text(song.title, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(song.artist, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun PlaylistCard(playlist: Playlist, onClick: () -> Unit) {
    Column(modifier = Modifier.width(160.dp).clickable(onClick = onClick)) {
        Surface(modifier = Modifier.size(160.dp), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Box(contentAlignment = Alignment.Center) {
                val coverTrack = playlist.tracks.firstOrNull { it.albumId > 0 }
                if (coverTrack != null) {
                    AsyncImage(getArtRequest(coverTrack.albumId), null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(0.3f)))
                } else {
                    Icon(Icons.AutoMirrored.Rounded.QueueMusic, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(64.dp))
                }

                if (playlist.tracks.isNotEmpty()) {
                    Box(modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).size(36.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.PlayArrow, "Play", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(playlist.name, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
        Text("${playlist.tracks.size} Songs", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(viewModel: MusicViewModel, allSongs: ImmutableList<AudioTrack>, onTrashClick: (AudioTrack) -> Unit) {
    val historyRaw by viewModel.recentHistory.collectAsStateWithLifecycle(initialValue = emptyList())
    val playlistsRaw by viewModel.playlists.collectAsStateWithLifecycle(initialValue = emptyList())

    val history = remember(historyRaw) { historyRaw.toImmutableList() }
    val playlists = remember(playlistsRaw) { playlistsRaw.toImmutableList() }
    val songMap = remember(allSongs) { allSongs.associateBy { it.id }.toImmutableMap() }

    val playCounts = remember(history) { history.groupingBy { it }.eachCount().toImmutableMap() }

    var selectedIndex by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            SegmentedButton(
                selected = selectedIndex == 0,
                onClick = { selectedIndex = 0 },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
            ) { Text("Recently Played") }
            SegmentedButton(
                selected = selectedIndex == 1,
                onClick = { selectedIndex = 1 },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
            ) { Text("Top Played") }
        }

        if (selectedIndex == 0) {
            val recentList = remember(history, songMap) { history.mapNotNull { id -> songMap[id] }.toImmutableList() }
            if (recentList.isEmpty()) Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No recent history", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 90.dp)) {
                items(recentList, key = { it.id }, contentType = { "song" }) { song ->
                    InteractiveSongRow(song, playlists, viewModel, { viewModel.playQueue(recentList, song) }, { onTrashClick(song) })
                }
            }
        } else {
            val sortedSongs = remember(playCounts, songMap) {
                playCounts.keys.mapNotNull { id -> songMap[id] }.sortedByDescending { playCounts[it.id] ?: 0 }.toImmutableList()
            }

            if (sortedSongs.isEmpty()) Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No play statistics yet", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 90.dp)) {
                items(sortedSongs, key = { it.id }, contentType = { "song" }) { song ->
                    HistoryStatRow(song, playCounts[song.id] ?: 0, viewModel, sortedSongs) { onTrashClick(song) }
                }
            }
        }
    }
}

@Composable
fun FavoritesScreen(viewModel: MusicViewModel, allSongs: ImmutableList<AudioTrack>, onTrashClick: (AudioTrack) -> Unit) {
    val favoriteIdsRaw by viewModel.favoriteIds.collectAsStateWithLifecycle()
    val playlistsRaw by viewModel.playlists.collectAsStateWithLifecycle()

    val favoriteIds = remember(favoriteIdsRaw) { favoriteIdsRaw.toImmutableSet() }
    val playlists = remember(playlistsRaw) { playlistsRaw.toImmutableList() }
    val favoriteSongs = remember(allSongs, favoriteIds) { allSongs.filter { it.id in favoriteIds }.toImmutableList() }

    if (favoriteSongs.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { Text("No favorites yet", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 90.dp)) {
            items(favoriteSongs, key = { it.id }, contentType = { "song" }) { song ->
                InteractiveSongRow(song, playlists, viewModel, { viewModel.playQueue(favoriteSongs, song) }, { onTrashClick(song) })
            }
        }
    }
}

@Composable
fun LibraryContent(displaySongs: ImmutableList<AudioTrack>, vm: MusicViewModel, onTrashClick: (AudioTrack) -> Unit) {
    val playlistsRaw by vm.playlists.collectAsStateWithLifecycle()
    val playlists = remember(playlistsRaw) { playlistsRaw.toImmutableList() }

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 90.dp)) {
        items(displaySongs, key = { it.id }, contentType = { "song" }) { song ->
            InteractiveSongRow(song, playlists, vm, { vm.playQueue(displaySongs, song) }, { onTrashClick(song) })
        }
    }
}

@Composable
fun FolderList(songs: ImmutableList<AudioTrack>, vm: MusicViewModel, onTrashClick: (AudioTrack) -> Unit) {
    val playlistsRaw by vm.playlists.collectAsStateWithLifecycle()
    val playlists = remember(playlistsRaw) { playlistsRaw.toImmutableList() }
    val folders = remember(songs) { songs.filter { it.path.isNotBlank() }.groupBy { it.path.substringBeforeLast("/", "Unknown").trim() }.toSortedMap() }
    var selectedFolder by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = selectedFolder != null) { selectedFolder = null }

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(targetState = selectedFolder, transitionSpec = { if (targetState == null) slideInHorizontally { -it } togetherWith slideOutHorizontally { it } else slideInHorizontally { it } togetherWith slideOutHorizontally { -it } }, label = "FolderTransition") { activeFolder ->
            if (activeFolder == null) {
                val folderKeys = remember(folders) { folders.keys.toList().toImmutableList() }
                LazyVerticalGrid(columns = GridCells.Adaptive(160.dp), contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 90.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxSize()) {
                    items(folderKeys, key = { it }, contentType = { "folder" }) { path ->
                        val firstTrack = folders[path]?.firstOrNull { it.albumId > 0 }
                        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().aspectRatio(1f).clickable { selectedFolder = path }) {
                            Box {
                                if (firstTrack != null) {
                                    AsyncImage(getArtRequest(firstTrack.albumId), null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                    Box(Modifier.fillMaxSize().background(Color.Black.copy(0.4f)))
                                }
                                Column(modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                                    Icon(Icons.Rounded.Folder, null, tint = Color.White)
                                    Spacer(Modifier.height(8.dp))
                                    Text(path.substringAfterLast("/"), fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${folders[path]?.size ?: 0} Files", color = Color.White.copy(0.7f), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            } else {
                val tracks = remember(activeFolder, folders) { folders[activeFolder]?.toImmutableList() ?: persistentListOf() }
                Column(Modifier.fillMaxSize()) {
                    Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { selectedFolder = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onSurface) }
                        Text(activeFolder.substringAfterLast("/"), fontWeight = FontWeight.Bold, fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(start = 8.dp))
                    }
                    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 90.dp)) {
                        items(tracks, key = { it.id }, contentType = { "song" }) { song ->
                            InteractiveSongRow(song, playlists, vm, { vm.playQueue(tracks, song) }, { onTrashClick(song) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun QueueScreen(viewModel: MusicViewModel, currentTrack: AudioTrack?, onTrashClick: (AudioTrack) -> Unit) {
    val activeQueueRaw by viewModel.currentQueue.collectAsStateWithLifecycle()
    val playlistsRaw by viewModel.playlists.collectAsStateWithLifecycle()

    val activeQueue = remember(activeQueueRaw) { activeQueueRaw.toImmutableList() }
    val playlists = remember(playlistsRaw) { playlistsRaw.toImmutableList() }

    Column(Modifier.fillMaxSize()) {
        if (currentTrack != null) {
            Text("NOW PLAYING", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            InteractiveSongRow(currentTrack, playlists, viewModel, { }, { onTrashClick(currentTrack) })
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.surfaceVariant)
        }
        Text("UP NEXT", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        if (activeQueue.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), Alignment.Center) { Text("No more tracks in queue", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 90.dp)) {
                items(activeQueue, key = { it.id }, contentType = { "song" }) { song ->
                    Row(modifier = Modifier.fillMaxWidth().clickable { viewModel.playQueue(activeQueue, song) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.DragHandle, "Reorder", tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(song.title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(song.artist, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { viewModel.removeFromQueue(song) }) { Icon(Icons.Default.Close, "Remove", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsScreen(vm: MusicViewModel, allSongs: ImmutableList<AudioTrack>, currentTrackId: Long?, onPlayQueue: (Pair<List<AudioTrack>, AudioTrack>) -> Unit) {
    val playlistsRaw by vm.playlists.collectAsStateWithLifecycle()
    val playlists = remember(playlistsRaw) { playlistsRaw.toImmutableList() }

    var openPlaylistId by remember { mutableStateOf<Long?>(null) }
    val activePlaylist = remember(playlists, openPlaylistId) { playlists.find { it.id == openPlaylistId } }

    var showAddSongsDialog by remember { mutableStateOf(false) }
    var playlistToRename by remember { mutableStateOf<Playlist?>(null) }
    var playlistToDelete by remember { mutableStateOf<Playlist?>(null) }

    BackHandler(enabled = openPlaylistId != null) { openPlaylistId = null }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AnimatedContent(
            targetState = openPlaylistId,
            transitionSpec = {
                if (targetState == null) {
                    (fadeIn() + scaleIn(initialScale = 0.9f)) togetherWith (fadeOut() + scaleOut(targetScale = 0.9f))
                } else {
                    (fadeIn() + scaleIn(initialScale = 0.9f)) togetherWith (fadeOut() + scaleOut(targetScale = 0.9f))
                }
            },
            label = "ScreenTransition"
        ) { targetId ->
            if (targetId == null) {
                if (playlists.isEmpty()) Box(Modifier.fillMaxSize(), Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("🎵", fontSize = 48.sp); Spacer(Modifier.height(16.dp)); Text("No Playlists", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface); Text("Create your first playlist.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                else LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 100.dp, top = 16.dp)) { items(playlists, key = { it.id }, contentType = { "playlist" }) { pl -> PlaylistRow(pl, { openPlaylistId = pl.id }, { playlistToRename = pl }, { playlistToDelete = pl }) } }
            } else {
                activePlaylist?.let { playlist ->
                    val currentSongs = remember(playlist.tracks) { playlist.tracks.toImmutableList() }
                    var searchQuery by remember { mutableStateOf("") }
                    var filteredSongs by remember { mutableStateOf(currentSongs) }

                    LaunchedEffect(currentSongs) {
                        snapshotFlow { searchQuery }
                            .debounce(250)
                            .collect { query ->
                                withContext(Dispatchers.Default) {
                                    filteredSongs = if (query.isBlank()) currentSongs
                                    else currentSongs.filter { it.title.contains(query, true) || it.artist.contains(query, true) }.toImmutableList()
                                }
                            }
                    }

                    Scaffold(
                        topBar = {
                            TopAppBar(
                                title = {
                                    Column {
                                        Text(playlist.name, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${currentSongs.size} Songs", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                navigationIcon = { IconButton(onClick = { openPlaylistId = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onSurface) } },
                                actions = { IconButton(onClick = { showAddSongsDialog = true }) { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, "Add Songs", tint = MaterialTheme.colorScheme.onSurface) } },
                                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                            )
                        },
                        floatingActionButton = {
                            if (currentSongs.isEmpty()) {
                                ExtendedFloatingActionButton(
                                    onClick = { showAddSongsDialog = true },
                                    icon = { Icon(Icons.Rounded.Add, "Add") },
                                    text = { Text("Add Songs", fontWeight = FontWeight.Bold) },
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = Color.White
                                )
                            } else {
                                FloatingActionButton(
                                    onClick = { currentSongs.firstOrNull()?.let { onPlayQueue(currentSongs.shuffled() to it) } },
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = Color.White,
                                    shape = RoundedCornerShape(16.dp)
                                ) { Icon(Icons.Default.Shuffle, "Shuffle Play") }
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.background
                    ) { padding ->
                        Column(Modifier.padding(padding).fillMaxSize()) {
                            if (currentSongs.isNotEmpty()) {
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    placeholder = { Text("Search in playlist...") },
                                    leadingIcon = { Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                    shape = CircleShape,
                                    singleLine = true,
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent
                                    )
                                )
                            }

                            if (currentSongs.isEmpty()) {
                                Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("🎵", fontSize = 48.sp)
                                        Spacer(Modifier.height(16.dp))
                                        Text("No songs yet", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp)
                                        Text("Tap + to add songs", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                                    }
                                }
                            } else if (filteredSongs.isEmpty()) {
                                Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) { Text(if (searchQuery.isNotEmpty()) "No matches found" else "Playlist is empty", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            } else {
                                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 80.dp)) {
                                    items(filteredSongs, key = { it.id }, contentType = { "song" }) { song ->
                                        PlaylistEditableSongRow(song, currentTrackId == song.id, { onPlayQueue(currentSongs to song) }, { vm.removeSongFromPlaylist(playlist, song) })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (playlistToRename != null) RenamePlaylistDialog(playlistToRename!!, playlists, { playlistToRename = null }, { vm.renamePlaylist(playlistToRename!!, it); playlistToRename = null })

    if (showAddSongsDialog && activePlaylist != null) AddSongsDialog(activePlaylist!!, allSongs, { showAddSongsDialog = false }, { selectedSongs -> selectedSongs.forEach { vm.addSongToPlaylist(activePlaylist, it) }; showAddSongsDialog = false })

    if (playlistToDelete != null) {
        AlertDialog(
            shape = RoundedCornerShape(24.dp),
            onDismissRequest = { playlistToDelete = null },
            title = { Text("Delete Playlist", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) },
            text = { Text("Are you sure you want to delete '${playlistToDelete?.name}'?") },
            confirmButton = { Button(onClick = { vm.deletePlaylist(playlistToDelete!!); playlistToDelete = null; openPlaylistId = null }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { playlistToDelete = null }) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) } },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(onBack: () -> Unit, viewModel: MusicViewModel, currentTrack: AudioTrack?, isPlaying: Boolean, onNavigateToQueue: () -> Unit, onNavigateToAudioInfo: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val playlistsRaw by viewModel.playlists.collectAsStateWithLifecycle()
    val playlists = remember(playlistsRaw) { playlistsRaw.toImmutableList() }

    val favoriteIds by viewModel.favoriteIds.collectAsStateWithLifecycle()
    val isFavorite = remember(currentTrack?.id, favoriteIds) { favoriteIds.contains(currentTrack?.id) }

    var showSleepTimer by remember { mutableStateOf(false) }
    var showEffectsSheet by remember { mutableStateOf(false) }
    var showPlaylistDialog by remember { mutableStateOf(false) }

    val bgArtworkReq = remember(currentTrack?.albumId) {
        ImageRequest.Builder(ctx).data(getAlbumArtUri(currentTrack?.albumId ?: -1)).size(400).allowHardware(true).build()
    }
    val artworkReq = remember(currentTrack?.albumId) {
        ImageRequest.Builder(ctx).data(getAlbumArtUri(currentTrack?.albumId ?: -1)).size(800).error(android.R.drawable.ic_media_play).build()
    }

    val artScale by animateFloatAsState(if (isPlaying) 1f else 0.9f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "artScale")
    val artAlpha by animateFloatAsState(if (isPlaying) 1f else 0.8f, label = "artAlpha")

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AsyncImage(bgArtworkReq, null, modifier = Modifier.fillMaxSize().blur(32.dp), contentScale = ContentScale.Crop, alpha = 0.3f)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.4f), Color.Black.copy(alpha = 0.8f)))))

        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            PlayerHeader(currentTrack?.album, viewModel.sleepTimeRemaining, onBack, { showSleepTimer = true }, { showEffectsSheet = true })

            Box(modifier = Modifier.weight(0.6f).aspectRatio(1f).padding(vertical = 16.dp).pointerInput(Unit) { detectTapGestures(onDoubleTap = { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); currentTrack?.let { viewModel.toggleFavorite(listOf(it.id)) } }) }) {
                AsyncImage(artworkReq, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().scale(artScale).graphicsLayer { alpha = artAlpha }.shadow(12.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)))
            }

            TrackMetadata(currentTrack)
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

            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
                Row(modifier = Modifier.padding(vertical = 12.dp, horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    IconButton(onClick = { currentTrack?.let { viewModel.toggleFavorite(listOf(it.id)) } }) { Icon(if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, "Favorite", tint = if(isFavorite) Color.Red else MaterialTheme.colorScheme.onSurface) }
                    IconButton(onClick = { showPlaylistDialog = true }) { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, "Add to Playlist", tint = MaterialTheme.colorScheme.onSurface) }
                    IconButton(onClick = onNavigateToQueue) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Queue", tint = MaterialTheme.colorScheme.onSurface) }
                    IconButton(onClick = onNavigateToAudioInfo) { Icon(Icons.Rounded.Info, "Audio Info", tint = MaterialTheme.colorScheme.onSurface) }
                }
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

    val animatedProgress by animateFloatAsState(
        targetValue = if (isDragging) sliderVal else (position.toFloat() / safeDur).coerceIn(0f, 1f),
        label = "progressBarAnimation"
    )

    Column {
        Slider(
            value = animatedProgress,
            onValueChange = { isDragging = true; sliderVal = it },
            onValueChangeFinished = { onSeek((safeDur * sliderVal).toLong()); isDragging = false },
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
            modifier = Modifier.height(24.dp)
        )
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
            Text(formatTime(if (isDragging) (safeDur * sliderVal).toLong() else position), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
            Text(formatTime(duration), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
        }
    }
}

@Composable
fun PlaybackControls(isPlaying: Boolean, isShuffleEnabled: Boolean, repeatMode: Int, onToggleShuffle: () -> Unit, onSkipPrev: () -> Unit, onRewind: () -> Unit, onTogglePlayPause: () -> Unit, onForward: () -> Unit, onSkipNext: () -> Unit, onToggleRepeat: () -> Unit) {
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly, Alignment.CenterVertically) {
        IconButton(onClick = onToggleShuffle, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.Shuffle, "Shuffle", tint = if(isShuffleEnabled) MaterialTheme.colorScheme.primary else Color.White, modifier = Modifier.size(24.dp)) }
        IconButton(onClick = onSkipPrev, modifier = Modifier.size(52.dp)) { Icon(Icons.Rounded.SkipPrevious, "Prev", modifier = Modifier.size(32.dp), tint = Color.White) }
        Surface(onClick = onTogglePlayPause, shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play", tint = Color.White, modifier = Modifier.size(40.dp))
            }
        }
        IconButton(onClick = onSkipNext, modifier = Modifier.size(52.dp)) { Icon(Icons.Rounded.SkipNext, "Next", modifier = Modifier.size(32.dp), tint = Color.White) }
        IconButton(onClick = onToggleRepeat, modifier = Modifier.size(36.dp)) { Icon(if (repeatMode != Player.REPEAT_MODE_OFF) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repeat", tint = if(repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else Color.White, modifier = Modifier.size(24.dp)) }
    }
}

@Composable
fun AudioInfoScreen(currentTrack: AudioTrack?) {
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.HighQuality, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp)); Spacer(Modifier.height(16.dp))
            Text("Format: ${currentTrack?.path?.substringAfterLast('.')?.uppercase(Locale.US) ?: "N/A"}", color = MaterialTheme.colorScheme.onBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Path: ${currentTrack?.path}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun InteractiveSongRow(song: AudioTrack, playlists: ImmutableList<Playlist>, vm: MusicViewModel, onClick: () -> Unit, onTrashClick: () -> Unit) {
    var showMenu by remember { mutableStateOf(false) }
    var showTagEditor by remember { mutableStateOf(false) }
    var showPlaylistDialog by remember { mutableStateOf(false) }
    val currentTrackRaw by vm.currentTrack.collectAsStateWithLifecycle()
    val isPlaying = remember(currentTrackRaw?.id, song.id) { currentTrackRaw?.id == song.id }

    val titleColor by animateColorAsState(targetValue = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground, label = "titleColor")

    Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp).drawBehind { if(isPlaying) drawLine(color = titleColor, start = Offset(0f, 0f), end = Offset(0f, size.height), strokeWidth = 4.dp.toPx()) }, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(60.dp)) {
            AsyncImage(getArtRequest(song.albumId), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).let { if(isPlaying) it.border(2.dp, titleColor, RoundedCornerShape(8.dp)) else it })
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(song.title, color = titleColor, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${song.artist} • ${formatTotalDuration(song.duration)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, "More", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
            DropdownMenuItem(text = { Text("Play Next", color = MaterialTheme.colorScheme.onSurface) }, onClick = { showMenu = false; vm.playNext(song) }, leadingIcon = { Icon(Icons.Rounded.QueuePlayNext, null, tint = MaterialTheme.colorScheme.onSurface) })
            DropdownMenuItem(text = { Text("Add to Queue", color = MaterialTheme.colorScheme.onSurface) }, onClick = { showMenu = false; vm.addToQueue(song) }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null, tint = MaterialTheme.colorScheme.onSurface) })
            DropdownMenuItem(text = { Text("Add to Playlist", color = MaterialTheme.colorScheme.onSurface) }, onClick = { showMenu = false; showPlaylistDialog = true }, leadingIcon = { Icon(Icons.Rounded.PlaylistAddCircle, null, tint = MaterialTheme.colorScheme.onSurface) })
            DropdownMenuItem(text = { Text("Edit Tags", color = MaterialTheme.colorScheme.onSurface) }, onClick = { showMenu = false; showTagEditor = true }, leadingIcon = { Icon(Icons.Rounded.Edit, null, tint = MaterialTheme.colorScheme.onSurface) })
            DropdownMenuItem(text = { Text("Move to Trash", color = MaterialTheme.colorScheme.error) }, onClick = { showMenu = false; onTrashClick() }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) })
        }
    }
    if (showTagEditor) TagEditorDialog(song, { showTagEditor = false }, { })
    if (showPlaylistDialog) SelectPlaylistDialog(playlists, { showPlaylistDialog = false }) { pl -> vm.addSongToPlaylist(pl, song); showPlaylistDialog = false }
}

@Composable
fun SelectPlaylistDialog(playlists: ImmutableList<Playlist>, onDismiss: () -> Unit, onSelect: (Playlist) -> Unit) {
    AlertDialog(
        shape = RoundedCornerShape(24.dp),
        onDismissRequest = onDismiss,
        title = { Text("Add to Playlist", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) },
        text = {
            if (playlists.isEmpty()) Text("No playlists available.")
            else LazyColumn { items(playlists, key = { it.id }) { pl -> TextButton(onClick = { onSelect(pl) }, modifier = Modifier.fillMaxWidth()) { Text(pl.name, color = MaterialTheme.colorScheme.primary) } } }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        containerColor = MaterialTheme.colorScheme.surface
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedEffectsBottomSheet(viewModel: MusicViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(24.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Playback", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                TextButton(onClick = { viewModel.resetAudioEffects() }) { Text("Reset", color = MaterialTheme.colorScheme.error) }
            }
            Spacer(Modifier.height(16.dp))
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    CompactSlider("Pitch (Semitones)", viewModel.pitchPlayer1.collectAsStateWithLifecycle().value, 0.5f..2.0f, MaterialTheme.colorScheme.primary) { viewModel.setPlayerPitch(false, it) }
                    Spacer(Modifier.height(12.dp))
                    CompactSlider("Speed", viewModel.speedPlayer1.collectAsStateWithLifecycle().value, 0.5f..2.0f, MaterialTheme.colorScheme.primary) { viewModel.setPlayerSpeed(false, it) }
                }
            }
            Spacer(Modifier.height(16.dp))
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    CompactSlider("Volume", viewModel.volume1.collectAsStateWithLifecycle().value, 0.0f..1.0f, MaterialTheme.colorScheme.primary) { viewModel.updateVolume(it, false) }
                    Spacer(Modifier.height(12.dp))
                    CompactSlider("Stereo Balance (L/R)", viewModel.balance1.collectAsStateWithLifecycle().value, -1.0f..1.0f, MaterialTheme.colorScheme.primary) { viewModel.updateBalance(it, false) }
                }
            }
            Spacer(Modifier.height(48.dp))
        }
    }
}

@Composable
fun CompactSlider(label: String, value: Float, valueRange: ClosedFloatingPointRange<Float>, activeColor: Color, onValueChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(String.format(Locale.US, "%.2f", value), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(thumbColor = activeColor, activeTrackColor = activeColor, inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
        )
    }
}
@Composable
fun ModernMiniPlayer(
    track: AudioTrack,
    isPlaying: Boolean,
    positionFlow: Flow<Long>,
    onPlayPause: () -> Unit,
    onClick: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onFavorite: () -> Unit,
    onQueue: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val view = LocalView.current
    var swipeDirection by remember { mutableIntStateOf(1) }

    Surface(
        modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(14.dp))
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onClick() },
                    onDoubleTap = { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); onFavorite() },
                    onLongPress = { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); onQueue() }
                )
            }
            .pointerInput(Unit) {
                var accumulatedX = 0f
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (accumulatedX > 120f) {
                            swipeDirection = -1
                            onPrev()
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        } else if (accumulatedX < -120f) {
                            swipeDirection = 1
                            onNext()
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        }
                        accumulatedX = 0f
                    }
                ) { change, dragAmount ->
                    change.consume()
                    accumulatedX += dragAmount
                }
            },
        shape = RoundedCornerShape(14.dp),
        color = colors.surface,
        tonalElevation = 4.dp,
        shadowElevation = 4.dp
    ) {
        Box(Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = track,
                transitionSpec = {
                    (slideInHorizontally { width -> swipeDirection * width } + fadeIn()) togetherWith
                            (slideOutHorizontally { width -> -swipeDirection * width } + fadeOut())
                },
                label = "MiniPlayerTrackTransition"
            ) { animatedTrack ->
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        getArtRequest(animatedTrack.albumId),
                        "Album Art",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.surfaceVariant)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f).padding(end = 48.dp)) {
                        Text(
                            animatedTrack.title,
                            color = colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        Text(
                            animatedTrack.artist,
                            color = colors.onSurfaceVariant,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            IconButton(
                onClick = { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); onPlayPause() },
                interactionSource = remember { MutableInteractionSource() },
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp)
            ) {
                Icon(
                    if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    null,
                    tint = colors.onSurface,
                    modifier = Modifier.size(32.dp)
                )
            }
            MiniPlayerProgressBar(positionFlow, track.duration)
        }
    }
}

@Composable
private fun BoxScope.MiniPlayerProgressBar(positionFlow: Flow<Long>, duration: Long) {
    val position by positionFlow.collectAsStateWithLifecycle(initialValue = 0L)
    val progress = remember(position, duration) { if (duration > 0) (position.coerceIn(0, duration)).toFloat() / duration else 0f }

    val animatedProgress by animateFloatAsState(targetValue = progress, label = "miniProgressBar")

    LinearProgressIndicator(
        progress = { animatedProgress },
        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
        color = MaterialTheme.colorScheme.primary,
        trackColor = Color.Transparent
    )
}

@Composable
fun HistoryStatRow(song: AudioTrack, count: Int, vm: MusicViewModel, sortedSongs: ImmutableList<AudioTrack>, onTrashClick: () -> Unit) {
    var showMenu by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(song.title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text("${song.artist}  •  Played $count times", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = { AsyncImage(getArtRequest(song.albumId), null, Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentScale = ContentScale.Crop) },
        trailingContent = {
            Box {
                IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, "More", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                    DropdownMenuItem(text = { Text("Play", color = MaterialTheme.colorScheme.onSurface) }, onClick = { showMenu = false; vm.playQueue(sortedSongs, song) }, leadingIcon = { Icon(Icons.Rounded.PlayArrow, null, tint = MaterialTheme.colorScheme.onSurface) })
                    DropdownMenuItem(text = { Text("Move to Trash", color = MaterialTheme.colorScheme.error) }, onClick = { showMenu = false; onTrashClick() }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) })
                }
            }
        }, colors = ListItemDefaults.colors(containerColor = Color.Transparent), modifier = Modifier.clickable { vm.playQueue(sortedSongs, song) }
    )
}

@Composable
fun PlaylistRow(playlist: Playlist, onClick: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var showMenu by remember { mutableStateOf(false) }
    val coverTrack = remember(playlist.tracks) { playlist.tracks.firstOrNull { it.albumId > 0 } }
    val totalDurationMs = remember(playlist.tracks) { playlist.tracks.sumOf { it.duration } }

    Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        val imgReq = getArtRequest(coverTrack?.albumId ?: -1L)
        if (imgReq != null) {
            AsyncImage(imgReq, null, contentScale = ContentScale.Crop, modifier = Modifier.size(72.dp).clip(RoundedCornerShape(18.dp)))
        } else {
            Box(modifier = Modifier.size(72.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)), Alignment.Center) {
                Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(playlist.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text("${playlist.tracks.size} Songs • ${formatTotalDuration(totalDurationMs)}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, "More", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                DropdownMenuItem(text = { Text("Rename", color = MaterialTheme.colorScheme.onSurface) }, onClick = { showMenu = false; onRename() }, leadingIcon = { Icon(Icons.Default.Edit, null, tint = MaterialTheme.colorScheme.onSurface) })
                DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, onClick = { showMenu = false; onDelete() }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistEditableSongRow(song: AudioTrack, isPlaying: Boolean, onClick: () -> Unit, onRemove: () -> Unit) {
    val density = LocalDensity.current
    val dismissState = remember(song.id) { SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, density, { if (it == SwipeToDismissBoxValue.EndToStart) { onRemove(); true } else false }, { it * 0.5f }) }

    val titleColor by animateColorAsState(targetValue = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, label = "editableTitleColor")

    SwipeToDismissBox(state = dismissState, enableDismissFromStartToEnd = false, backgroundContent = { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.error).padding(horizontal = 24.dp), Alignment.CenterEnd) { Icon(Icons.Default.Delete, "Remove", tint = MaterialTheme.colorScheme.onError) } }) {
        Row(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(getArtRequest(song.albumId), null, contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(song.artist, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (isPlaying) Icon(Icons.Default.GraphicEq, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
fun PlayerHeader(albumName: String?, sleepTimeRemaining: Long, onBack: () -> Unit, onSleepTimerClick: () -> Unit, onEffectsClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Surface(onClick = onBack, shape = CircleShape, color = Color.Black.copy(0.2f), modifier = Modifier.size(44.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.KeyboardArrowDown, "Minimize", tint = Color.White) }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
            Text("PLAYING FROM", fontSize = 10.sp, letterSpacing = 2.sp, color = Color.White.copy(0.7f), fontWeight = FontWeight.Bold)
            Text(albumName ?: "Unknown Album", fontSize = 12.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (sleepTimeRemaining > 0) Text("${sleepTimeRemaining / 60000L}m", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 4.dp))
            IconButton(onClick = onSleepTimerClick) { Icon(Icons.Filled.Bedtime, "Sleep Timer", tint = if (sleepTimeRemaining > 0) MaterialTheme.colorScheme.primary else Color.White) }
            IconButton(onClick = onEffectsClick) { Icon(Icons.Rounded.GraphicEq, "Effects", tint = Color.White) }
        }
    }
}

@Composable
fun TrackMetadata(track: AudioTrack?) {
    Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(track?.title ?: "Not Playing", fontSize = 22.sp, color = Color.White, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track?.artist ?: "Unknown", fontSize = 16.sp, color = Color.White.copy(0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun SleepTimerDialog(onDismiss: () -> Unit, onSet: (Int) -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        shape = RoundedCornerShape(24.dp),
        onDismissRequest = onDismiss,
        title = { Text("Sleep Timer", color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Column {
                listOf(15, 30, 45, 60).forEach { mins ->
                    TextButton(onClick = { onSet(mins); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                        Text("$mins Minutes", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onCancel(); onDismiss() }) { Text("Cancel Timer", color = MaterialTheme.colorScheme.error) } },
        containerColor = MaterialTheme.colorScheme.surface
    )
}

@Composable
fun TagEditorDialog(song: AudioTrack, onDismiss: () -> Unit, onSave: () -> Unit) {
    AlertDialog(
        shape = RoundedCornerShape(24.dp),
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Edit Metadata", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(song.title, {}, label = { Text("Title") })
                OutlinedTextField(song.artist, {}, label = { Text("Artist") })
            }
        },
        confirmButton = { TextButton(onClick = { onSave(); onDismiss() }) { Text("Save", color = MaterialTheme.colorScheme.primary) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
    )
}

@Composable
fun CreatePlaylistDialog(existingPlaylists: ImmutableList<Playlist>, onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var newName by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    AlertDialog(
        shape = RoundedCornerShape(24.dp),
        onDismissRequest = onDismiss,
        title = { Text("New Playlist", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Column {
                OutlinedTextField(newName, { newName = it; isError = false }, label = { Text("Playlist Name") }, singleLine = true, isError = isError, colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent))
                if (isError) Text("Name already exists", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
        },
        confirmButton = {
            Button(onClick = {
                if (newName.isNotBlank()) {
                    if (existingPlaylists.any { it.name.equals(newName, true) }) isError = true else onCreate(newName)
                }
            }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        containerColor = MaterialTheme.colorScheme.surface
    )
}

@Composable
fun RenamePlaylistDialog(playlist: Playlist, existingPlaylists: ImmutableList<Playlist>, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var newName by remember { mutableStateOf(playlist.name) }
    var isError by remember { mutableStateOf(false) }
    AlertDialog(
        shape = RoundedCornerShape(24.dp),
        onDismissRequest = onDismiss,
        title = { Text("Rename Playlist", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Column {
                OutlinedTextField(newName, { newName = it; isError = false }, label = { Text("New Name") }, singleLine = true, isError = isError, colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent))
                if (isError) Text("Name already exists", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
        },
        confirmButton = {
            Button(onClick = {
                if (newName.isNotBlank() && newName != playlist.name) {
                    if (existingPlaylists.any { it.name.equals(newName, true) }) isError = true else onRename(newName)
                } else if (newName == playlist.name) onDismiss()
            }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        containerColor = MaterialTheme.colorScheme.surface
    )
}

@Composable
fun AddSongsDialog(playlist: Playlist, allSongs: ImmutableList<AudioTrack>, onDismiss: () -> Unit, onAddSongs: (List<AudioTrack>) -> Unit) {
    val availableSongs = remember(allSongs, playlist.tracks) { val existingIds = playlist.tracks.map { it.id }.toSet(); allSongs.filter { it.id !in existingIds }.toImmutableList() }
    val selectedIds = remember { mutableStateListOf<Long>() }
    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxSize()) {
                Text("Add Songs", modifier = Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
                if (availableSongs.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) { Text("No more songs to add", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(availableSongs, key = { it.id }) { song ->
                            val isSel = selectedIds.contains(song.id)
                            Row(Modifier.fillMaxWidth().clickable { if (isSel) selectedIds.remove(song.id) else selectedIds.add(song.id) }.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = isSel, onCheckedChange = { if (it) selectedIds.add(song.id) else selectedIds.remove(song.id) }, colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary))
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                    Text(song.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onAddSongs(availableSongs.filter { it.id in selectedIds }) }, enabled = selectedIds.isNotEmpty(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) { Text("Add (${selectedIds.size})") }
                }
            }
        }
    }
}

@Composable
fun getArtRequest(albumId: Long): ImageRequest? {
    val ctx = LocalContext.current
    return remember(albumId) {
        if (albumId > 0) ImageRequest.Builder(ctx).data(ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)).size(200).build() else null
    }
}

fun getAlbumArtUri(albumId: Long): Uri? {
    return if (albumId > 0) ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId) else null
}

fun formatTotalDuration(ms: Long): String {
    val t = ms / 1000
    val h = t / 3600
    val m = (t % 3600) / 60
    return if (h > 0) String.format(Locale.US, "%dh %dm", h, m) else String.format(Locale.US, "%dm", m)
}

fun formatTime(ms: Long): String {
    val t = ms / 1000
    return String.format(Locale.US, "%02d:%02d", t / 60, t % 60)
}