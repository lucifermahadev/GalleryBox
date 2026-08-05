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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.navigation.compose.*
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.gallerybox.viewmodel.AudioTrack
import com.gallerybox.viewmodel.MusicViewModel
import com.gallerybox.viewmodel.TrashViewModel
import kotlinx.collections.immutable.*
import kotlinx.coroutines.flow.Flow
import java.util.Locale

sealed class MusicRoute(val route: String) {
    data object Dashboard : MusicRoute("dashboard")
    data object Library : MusicRoute("library")
    data object Folders : MusicRoute("folders")
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

    // Bottom Sheets States
    var showQueueSheet by remember { mutableStateOf(false) }
    var showAudioInfoSheet by remember { mutableStateOf(false) }

    val currentTrack by viewModel.currentTrack.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val loadedSongsRaw by viewModel.allAudioTracks.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()

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
                    DropdownMenuItem(text = { Text("Equalizer") }, leadingIcon = { Icon(Icons.Rounded.GraphicEq, null) }, onClick = { showTopMenu = false; onNavigateToEqualizer() })
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
                        onQueue = { showQueueSheet = true }
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
                composable(MusicRoute.Dashboard.route) {
                    DashboardScreen(
                        viewModel = viewModel,
                        loadedSongs = loadedSongs,
                        onNavigateToAllSongs = { navController.navigate(MusicRoute.Library.route) { launchSingleTop = true } },
                        onNavigateToRadio = onNavigateToRadio,
                        onNavigateToFolders = { navController.navigate(MusicRoute.Folders.route) },
                        onShowQueue = { showQueueSheet = true },
                        onNavigateToDuoMode = onNavigateToDuoPlayer,
                        onNavigateToFavorites = { navController.navigate(MusicRoute.Favorites.route) }
                    )
                }
                composable(MusicRoute.Library.route) { LibraryContent(displaySongs, viewModel) { trackToTrash = it } }
                composable(MusicRoute.Favorites.route) { FavoritesScreen(viewModel, loadedSongs) { trackToTrash = it } }
                composable(MusicRoute.Folders.route) { FolderList(displaySongs, viewModel) { trackToTrash = it } }
            }
        }
    }

    if (showFullPlayer) {
        PlayerScreen(
            onBack = { showFullPlayer = false },
            viewModel = viewModel,
            currentTrack = currentTrack,
            isPlaying = isPlaying,
            onShowQueue = { showFullPlayer = false; showQueueSheet = true },
            onShowAudioInfo = { showFullPlayer = false; showAudioInfoSheet = true }
        )
    }

    if (showQueueSheet) {
        QueueBottomSheet(
            viewModel = viewModel,
            currentTrack = currentTrack,
            onDismiss = { showQueueSheet = false },
            onTrashClick = { trackToTrash = it; showQueueSheet = false }
        )
    }

    if (showAudioInfoSheet) {
        AudioInfoBottomSheet(
            currentTrack = currentTrack,
            onDismiss = { showAudioInfoSheet = false }
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
        val title = when (currentRoute) {
            MusicRoute.Library.route -> "All Songs"
            MusicRoute.Folders.route -> "Folders"
            MusicRoute.Favorites.route -> "Favorites"
            MusicRoute.Dashboard.route -> "Music"
            else -> "Music"
        }
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
fun DashboardScreen(
    viewModel: MusicViewModel,
    loadedSongs: ImmutableList<AudioTrack>,
    onNavigateToAllSongs: () -> Unit,
    onNavigateToRadio: () -> Unit,
    onNavigateToFolders: () -> Unit,
    onShowQueue: () -> Unit,
    onNavigateToDuoMode: () -> Unit,
    onNavigateToFavorites: () -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 90.dp)) {
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Quick Access", color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    QuickActionIcon(Icons.Rounded.Favorite, "Favorites", onNavigateToFavorites)
                    QuickActionIcon(Icons.Rounded.Folder, "Folders", onNavigateToFolders)
                    QuickActionIcon(Icons.AutoMirrored.Rounded.QueueMusic, "Queue", onShowQueue)
                    QuickActionIcon(Icons.Rounded.LibraryMusic, "All Songs", onNavigateToAllSongs)
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    QuickActionIcon(Icons.Rounded.Radio, "Radio", onNavigateToRadio)
                    QuickActionIcon(Icons.Rounded.Headset, "Duo Mode", onNavigateToDuoMode)
                    QuickActionIcon(Icons.Rounded.Shuffle, "Shuffle") {
                        if (loadedSongs.isNotEmpty()) {
                            val toPlay = if (loadedSongs.size > 1000) loadedSongs.shuffled().take(1000) else loadedSongs.shuffled()
                            toPlay.firstOrNull()?.let { track -> viewModel.playQueue(toPlay, track) }
                        }
                    }
                    Spacer(Modifier.width(72.dp)) // Placeholder to align
                }
                Spacer(Modifier.height(24.dp))
            }
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
fun FavoritesScreen(viewModel: MusicViewModel, allSongs: ImmutableList<AudioTrack>, onTrashClick: (AudioTrack) -> Unit) {
    val favoriteIdsRaw by viewModel.favoriteIds.collectAsStateWithLifecycle()
    val favoriteIds = remember(favoriteIdsRaw) { favoriteIdsRaw.toImmutableSet() }
    val favoriteSongs = remember(allSongs, favoriteIds) { allSongs.filter { it.id in favoriteIds }.toImmutableList() }

    if (favoriteSongs.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { Text("No favorites yet", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 90.dp)) {
            items(favoriteSongs, key = { it.id }, contentType = { "song" }) { song ->
                InteractiveSongRow(song, viewModel, { viewModel.playQueue(favoriteSongs, song) }, { onTrashClick(song) })
            }
        }
    }
}

@Composable
fun LibraryContent(displaySongs: ImmutableList<AudioTrack>, vm: MusicViewModel, onTrashClick: (AudioTrack) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 90.dp)) {
        items(displaySongs, key = { it.id }, contentType = { "song" }) { song ->
            InteractiveSongRow(song, vm, { vm.playQueue(displaySongs, song) }, { onTrashClick(song) })
        }
    }
}

@Composable
fun FolderList(songs: ImmutableList<AudioTrack>, vm: MusicViewModel, onTrashClick: (AudioTrack) -> Unit) {
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
                            InteractiveSongRow(song, vm, { vm.playQueue(tracks, song) }, { onTrashClick(song) })
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueBottomSheet(viewModel: MusicViewModel, currentTrack: AudioTrack?, onDismiss: () -> Unit, onTrashClick: (AudioTrack) -> Unit) {
    val activeQueueRaw by viewModel.currentQueue.collectAsStateWithLifecycle()
    val activeQueue = remember(activeQueueRaw) { activeQueueRaw.toImmutableList() }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            if (currentTrack != null) {
                Text("NOW PLAYING", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                InteractiveSongRow(currentTrack, viewModel, { }, { onTrashClick(currentTrack) })
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.surfaceVariant)
            }
            Text("UP NEXT", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            if (activeQueue.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), Alignment.Center) { Text("No more tracks in queue", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioInfoBottomSheet(currentTrack: AudioTrack?, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 48.dp, top = 8.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
                Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Text("Audio Info", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            }

            AudioInfoItem("Title", currentTrack?.title ?: "N/A")
            AudioInfoItem("Artist", currentTrack?.artist ?: "N/A")
            AudioInfoItem("Album", currentTrack?.album ?: "N/A")
            AudioInfoItem("Duration", currentTrack?.let { formatTime(it.duration) } ?: "N/A")
            AudioInfoItem("Format", currentTrack?.path?.substringAfterLast('.')?.uppercase(Locale.US) ?: "N/A")
            AudioInfoItem("Bitrate", "Unknown") // Placeholder as Bitrate might not be in generic AudioTrack model
            AudioInfoItem("Sample Rate", "Unknown") // Placeholder as Sample Rate might not be in generic AudioTrack model
            AudioInfoItem("Path", currentTrack?.path ?: "N/A")
        }
    }
}

@Composable
fun AudioInfoItem(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(value, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(onBack: () -> Unit, viewModel: MusicViewModel, currentTrack: AudioTrack?, isPlaying: Boolean, onShowQueue: () -> Unit, onShowAudioInfo: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current

    val favoriteIds by viewModel.favoriteIds.collectAsStateWithLifecycle()
    val isFavorite = remember(currentTrack?.id, favoriteIds) { favoriteIds.contains(currentTrack?.id) }

    var showSleepTimer by remember { mutableStateOf(false) }
    var showEffectsSheet by remember { mutableStateOf(false) }

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
                    IconButton(onClick = onShowQueue) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Queue", tint = MaterialTheme.colorScheme.onSurface) }
                    IconButton(onClick = onShowAudioInfo) { Icon(Icons.Rounded.Info, "Audio Info", tint = MaterialTheme.colorScheme.onSurface) }
                }
            }
        }
    }
    if (showSleepTimer) SleepTimerDialog({ showSleepTimer = false }, { viewModel.startSleepTimer(it) }, { viewModel.cancelSleepTimer() })
    if (showEffectsSheet) AdvancedEffectsBottomSheet(viewModel) { showEffectsSheet = false }
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
fun InteractiveSongRow(song: AudioTrack, vm: MusicViewModel, onClick: () -> Unit, onTrashClick: () -> Unit) {
    var showMenu by remember { mutableStateOf(false) }
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
            DropdownMenuItem(text = { Text("Move to Trash", color = MaterialTheme.colorScheme.error) }, onClick = { showMenu = false; onTrashClick() }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) })
        }
    }
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