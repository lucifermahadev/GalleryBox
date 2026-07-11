@file:Suppress("unused", "UnsafeOptInUsageError")

package com.gallerybox.ui.screens.music

import android.content.ContentUris
import android.net.Uri
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.gallerybox.viewmodel.AudioTrack
import com.gallerybox.viewmodel.MusicViewModel
import java.util.Locale
import kotlin.math.log2
import kotlin.math.roundToInt

// --- SHARED THEME COLORS ---
private val Player1BaseColor = Color(0xFF64B5F6)
private val Player2BaseColor = Color(0xFFFF8DA1)

// --- OPTIMIZATION: Reusable Album Art Request Caching ---
@Composable
fun rememberAlbumArtRequest(albumId: Long, size: Int, crossfade: Boolean): ImageRequest {
    val context = LocalContext.current
    return remember(albumId, size, crossfade) {
        val artUri = if (albumId > 0) ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId) else null
        ImageRequest.Builder(context)
            .data(artUri)
            .size(size)
            .allowHardware(true)
            .crossfade(crossfade)
            .error(android.R.drawable.ic_menu_gallery)
            .build()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuoMusicScreen(viewModel: MusicViewModel, onBack: () -> Unit) {
    val pagedSongs = viewModel.pagedAudio.collectAsLazyPagingItems()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()

    val track1 by viewModel.currentTrack.collectAsStateWithLifecycle()
    val isPlaying1 by viewModel.isPlaying.collectAsStateWithLifecycle()
    val pos1 by viewModel.currentPosition.collectAsStateWithLifecycle()
    val speed1 by viewModel.speedPlayer1.collectAsStateWithLifecycle()
    val pitch1 by viewModel.pitchPlayer1.collectAsStateWithLifecycle()

    val track2 by viewModel.currentTrack2.collectAsStateWithLifecycle()
    val isPlaying2 by viewModel.isPlaying2.collectAsStateWithLifecycle()
    val pos2 by viewModel.currentPosition2.collectAsStateWithLifecycle()
    val speed2 by viewModel.speedPlayer2.collectAsStateWithLifecycle()
    val pitch2 by viewModel.pitchPlayer2.collectAsStateWithLifecycle()

    var showSheet by remember { mutableStateOf(false) }
    var activePlayerForSelection by remember { mutableIntStateOf(1) }
    var isLinked by remember { mutableStateOf(false) }

    val view = LocalView.current
    val p1Color by animateColorAsState(targetValue = if (isLinked) Color(0xFFB39DDB) else Player1BaseColor, label = "p1Color")
    val p2Color by animateColorAsState(targetValue = if (isLinked) Color(0xFFB39DDB) else Player2BaseColor, label = "p2Color")

    DisposableEffect(Unit) {
        viewModel.setDuoMode(true)
        onDispose {
            viewModel.setDuoMode(false)
            viewModel.setSearchQuery("")
        }
    }

    BackHandler { onBack() }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                DuoPlayerHalf(
                    label = "LEFT EARPHONE", color = p1Color, track = track1, isPlaying = isPlaying1,
                    position = pos1, speedVal = speed1, pitchVal = pitch1, isTop = true,
                    onPlayPause = {
                        if (isPlaying1) if (isLinked) viewModel.pauseBothSynced() else viewModel.pause(false)
                        else if (isLinked) viewModel.playBothSynced() else viewModel.play(false)
                    },
                    onSeek = { ms ->
                        viewModel.seekTo(ms, false)
                        if (isLinked) viewModel.seekTo(ms, true)
                    },
                    onOpenLibrary = { activePlayerForSelection = 1; showSheet = true },
                    onSpeed = {
                        viewModel.setPlayerSpeed(false, it)
                        if (isLinked) viewModel.setPlayerSpeed(true, it)
                    },
                    onPitch = {
                        viewModel.setPlayerPitch(false, it)
                        if (isLinked) viewModel.setPlayerPitch(true, it)
                    }
                )
            }

            PremiumDJDivider(
                p1Color = p1Color, p2Color = p2Color, isPlaying = isPlaying1 || isPlaying2, isLinked = isLinked,
                onSync = {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    viewModel.setPlayerSpeed(true, speed1)
                    viewModel.setPlayerPitch(true, pitch1)
                },
                onLink = {
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    isLinked = !isLinked
                    if (isLinked) {
                        viewModel.seekTo(pos1, true)
                        viewModel.setPlayerSpeed(true, speed1)
                        viewModel.setPlayerPitch(true, pitch1)
                    }
                },
                onCrossfade = {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    viewModel.crossfadePlayers()
                }
            )

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                DuoPlayerHalf(
                    label = "RIGHT EARPHONE", color = p2Color, track = track2, isPlaying = isPlaying2,
                    position = pos2, speedVal = speed2, pitchVal = pitch2, isTop = false,
                    onPlayPause = {
                        if (isPlaying2) if (isLinked) viewModel.pauseBothSynced() else viewModel.pause(true)
                        else if (isLinked) viewModel.playBothSynced() else viewModel.play(true)
                    },
                    onSeek = { ms ->
                        viewModel.seekTo(ms, true)
                        if (isLinked) viewModel.seekTo(ms, false)
                    },
                    onOpenLibrary = { activePlayerForSelection = 2; showSheet = true },
                    onSpeed = {
                        viewModel.setPlayerSpeed(true, it)
                        if (isLinked) viewModel.setPlayerSpeed(false, it)
                    },
                    onPitch = {
                        viewModel.setPlayerPitch(true, it)
                        if (isLinked) viewModel.setPlayerPitch(false, it)
                    }
                )
            }
        }

        FilledIconButton(
            onClick = onBack,
            modifier = Modifier.statusBarsPadding().padding(16.dp).align(Alignment.TopStart),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f))
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurface)
        }
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false; viewModel.setSearchQuery("") },
            containerColor = MaterialTheme.colorScheme.background,
            dragHandle = { BottomSheetDefaults.DragHandle(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)) }
        ) {
            SongPickerSheet(
                pagedSongs = pagedSongs,
                searchQuery = searchQuery,
                onSearchQueryChange = { viewModel.setSearchQuery(it) },
                playerLabel = if (activePlayerForSelection == 1) "Left Earphone" else "Right Earphone",
                onSongSelected = { track ->
                    viewModel.playDuoTrack(track = track, isPlayer2 = activePlayerForSelection == 2)
                    showSheet = false
                    viewModel.setSearchQuery("")
                }
            )
        }
    }
}

@Composable
fun PremiumDJDivider(
    p1Color: Color, p2Color: Color, isPlaying: Boolean, isLinked: Boolean,
    onSync: () -> Unit, onLink: () -> Unit, onCrossfade: () -> Unit
) {
    val transition = rememberInfiniteTransition(label = "DJDividerTransition")
    val animatedPulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseAnimation"
    )

    val activePulse = if (isPlaying) animatedPulse else 0.35f

    Box(modifier = Modifier.fillMaxWidth().height(88.dp).background(MaterialTheme.colorScheme.background)) {
        Box(modifier = Modifier.align(Alignment.Center).fillMaxWidth().height(4.dp).background(Brush.horizontalGradient(listOf(p1Color.copy(alpha = activePulse), p2Color.copy(alpha = activePulse)))))
        Row(modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            FilledIconButton(onClick = onSync, colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) { Icon(Icons.Rounded.Sync, "Sync", tint = p1Color) }
            Surface(modifier = Modifier.size(68.dp), shape = CircleShape, color = if (isLinked) p1Color else MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = if (isLinked) 6.dp else 2.dp) {
                Box(modifier = Modifier.fillMaxSize().clickable { onLink() }, contentAlignment = Alignment.Center) { Icon(if (isLinked) Icons.Default.Lock else Icons.Default.LockOpen, null, modifier = Modifier.size(28.dp), tint = if (isLinked) Color.White else MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            FilledIconButton(onClick = onCrossfade, colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) { Icon(Icons.Rounded.CompareArrows, "Crossfade", tint = p2Color) }
        }
    }
}
@Composable
fun DuoPlayerHalf(
    label: String, color: Color, track: AudioTrack?, isPlaying: Boolean, position: Long,
    speedVal: Float, pitchVal: Float, isTop: Boolean,
    onPlayPause: () -> Unit, onSeek: (Long) -> Unit, onOpenLibrary: () -> Unit,
    onSpeed: (Float) -> Unit, onPitch: (Float) -> Unit
) {
    var showFx by remember { mutableStateOf(false) }
    LaunchedEffect(track?.id) { showFx = false }
    val view = LocalView.current

    val artRequest = rememberAlbumArtRequest(albumId = track?.albumId ?: -1L, size = 500, crossfade = true)

    // FIX 1: Added `isTop` to the key. This prevents state collisions if the user
    // loads the exact same song onto both the Top and Bottom decks simultaneously.
    key(track?.id, isTop) {
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (track != null) AsyncImage(model = artRequest, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.08f)
            Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(if (isTop) listOf(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.background.copy(alpha = 0.82f), Color.Transparent) else listOf(Color.Transparent, MaterialTheme.colorScheme.background.copy(alpha = 0.82f), MaterialTheme.colorScheme.background))))
            Text(text = if (isTop) "L" else "R", color = color.copy(alpha = 0.08f), fontSize = 170.sp, fontWeight = FontWeight.Black, modifier = Modifier.align(if (isTop) Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal = 18.dp))

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(shape = CircleShape, color = color.copy(alpha = 0.14f), border = BorderStroke(1.dp, color.copy(alpha = 0.25f))) {
                    Text(text = label, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.weight(0.5f))

                if (track != null) {
                    val transition = rememberInfiniteTransition(label = "DiscRotation")
                    val rotation by transition.animateFloat(
                        initialValue = 0f, targetValue = 360f,
                        animationSpec = infiniteRepeatable(animation = tween(12000, easing = LinearEasing), repeatMode = RepeatMode.Restart),
                        label = "Rotation"
                    )

                    // Applied Dynamic Seek Formula: max(10s, Duration * 0.01)
                    val dynamicSeekStep = maxOf(10000L, (track.duration * 0.01).toLong())

                    Surface(
                        modifier = Modifier
                            .size(130.dp)
                            .clip(CircleShape)
                            .pointerInput(track.id) {
                                detectTapGestures(onDoubleTap = {
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    onSeek((position + dynamicSeekStep).coerceAtMost(track.duration))
                                })
                            },
                        shape = CircleShape, color = Color.White, shadowElevation = 6.dp
                    ) {
                        Box {
                            AsyncImage(model = artRequest, contentDescription = null, modifier = Modifier.fillMaxSize().graphicsLayer { rotationZ = if (isPlaying) rotation else 0f }, contentScale = ContentScale.Crop)
                            Box(modifier = Modifier.align(Alignment.Center).size(28.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color.Black))
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(text = track.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(text = track.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                    Box(modifier = Modifier.size(130.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, null, tint = color.copy(alpha = 0.5f), modifier = Modifier.size(60.dp)) }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(text = "No Song Selected", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(text = "Tap library to choose a track", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Spacer(modifier = Modifier.weight(0.5f))

                // --- FIX 2: UPDATED SLIDER LOGIC ---
                // Removed `animateFloatAsState` from the draggable thumb value.
                // That causes severe input latency and slider lag while being dragged.
                val duration = (track?.duration ?: 1000L).toFloat().coerceAtLeast(1f)
                val safePosition = if (track != null) position.toFloat().coerceIn(0f, duration) else 0f

                var isDragging by remember { mutableStateOf(false) }
                var sliderPos by remember { mutableFloatStateOf(0f) }

                // Read direct state during drag to bypass sluggish animation
                val displayPosition = if (isDragging) sliderPos else safePosition

                Slider(
                    value = displayPosition,
                    valueRange = 0f..duration,
                    enabled = track != null,
                    onValueChange = {
                        if (track != null) {
                            isDragging = true
                            sliderPos = it
                        }
                    },
                    onValueChangeFinished = {
                        if (track != null) {
                            onSeek(sliderPos.toLong())
                        }
                        isDragging = false
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = color,
                        activeTrackColor = color,
                        inactiveTrackColor = color.copy(alpha = 0.18f)
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (track != null) formatDuoTime(displayPosition.toLong()) else "00:00",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(
                        text = if (track != null) formatDuoTime(track.duration) else "00:00",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // --- END UPDATED SLIDER LOGIC ---

                Spacer(modifier = Modifier.height(8.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    FilledIconButton(modifier = Modifier.size(40.dp), onClick = { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); showFx = !showFx }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (showFx) color else MaterialTheme.colorScheme.surfaceContainerHigh)) {
                        Icon(Icons.Rounded.Tune, null, tint = if (showFx) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }

                    Surface(modifier = Modifier.size(56.dp).clip(CircleShape).pointerInput(track?.id) { detectTapGestures(onLongPress = { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); onSeek(0L) }, onTap = { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); onPlayPause() }) }, shape = CircleShape, color = color, shadowElevation = if (isPlaying) 6.dp else 2.dp) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(32.dp))
                        }
                    }

                    FilledIconButton(modifier = Modifier.size(40.dp), onClick = { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); onOpenLibrary() }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                        Icon(Icons.AutoMirrored.Rounded.QueueMusic, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                }

                AnimatedVisibility(visible = showFx, enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 3 }, exit = fadeOut(tween(200)) + slideOutVertically(tween(200)) { it / 3 }) {
                    ElevatedCard(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), shape = RoundedCornerShape(20.dp), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            FxSlider("Speed", speedVal, 0.5f..2f, color, isPitch = false) { onSpeed(it) }
                            Spacer(modifier = Modifier.height(6.dp))
                            FxSlider("Pitch", pitchVal, 0.5f..2.0f, color, isPitch = true) { onPitch(it) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FxSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, activeColor: Color, isPitch: Boolean, onValueChange: (Float) -> Unit) {

    // Semitone calculation from Multiplier: n = 12 * log2(Multiplier)
    val displayValue = if (isPitch) {
        val semitones = (12 * log2(value)).roundToInt()
        if (semitones > 0) "+$semitones st" else "$semitones st"
    } else {
        String.format(Locale.US, "%.1fx", value)
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp), shape = RoundedCornerShape(28.dp), elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 18.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(48.dp).clip(CircleShape).background(activeColor.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) { Box(modifier = Modifier.size(18.dp).clip(CircleShape).background(activeColor)) }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) { Text(text = label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface); Spacer(modifier = Modifier.height(2.dp)); Text(text = "Audio Effect", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Surface(shape = RoundedCornerShape(16.dp), color = activeColor.copy(alpha = 0.14f)) { Text(text = displayValue, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), color = activeColor, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge) }
            }
            Spacer(modifier = Modifier.height(24.dp))
            Slider(value = value, valueRange = range, onValueChange = onValueChange, modifier = Modifier.fillMaxWidth(), colors = SliderDefaults.colors(thumbColor = activeColor, activeTrackColor = activeColor, inactiveTrackColor = activeColor.copy(alpha = 0.18f), activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent))
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = if (isPitch) "-12 st" else "${range.start.toInt()}x", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(text = if (isPitch) "+12 st" else "${range.endInclusive.toInt()}x", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongPickerSheet(pagedSongs: LazyPagingItems<AudioTrack>, searchQuery: String, onSearchQueryChange: (String) -> Unit, playerLabel: String, onSongSelected: (AudioTrack) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 18.dp).padding(top = 12.dp)) {
        Box(modifier = Modifier.align(Alignment.CenterHorizontally).width(54.dp).height(5.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)))
        Spacer(modifier = Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(58.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.LibraryMusic, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp)) }
            Spacer(modifier = Modifier.width(16.dp))
            Column { Text("Select Audio", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground); Spacer(Modifier.height(3.dp)); Text(playerLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(modifier = Modifier.height(24.dp))
        OutlinedTextField(value = searchQuery, onValueChange = onSearchQueryChange, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(28.dp), placeholder = { Text("Search songs, artists...", color = MaterialTheme.colorScheme.onSurfaceVariant) }, leadingIcon = { Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.primary) }, colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh, focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), unfocusedBorderColor = Color.Transparent))
        Spacer(modifier = Modifier.height(22.dp))

        when {
            pagedSongs.loadState.refresh is LoadState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) }
            pagedSongs.itemCount == 0 -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text("No Songs Found", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 120.dp)) {
                items(count = pagedSongs.itemCount, key = { index -> pagedSongs.peek(index)?.id ?: index }) { index ->
                    pagedSongs[index]?.let { song ->
                        val artRequest = rememberAlbumArtRequest(albumId = song.albumId, size = 200, crossfade = false)
                        ElevatedCard(modifier = Modifier.fillMaxWidth().clickable { onSongSelected(song) }, shape = RoundedCornerShape(28.dp), elevation = CardDefaults.elevatedCardElevation(2.dp), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                            Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.size(72.dp).clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                                    AsyncImage(model = artRequest, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                    Box(modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.PlayArrow, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp)) }
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) { Text(song.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis); Spacer(Modifier.height(4.dp)); Text(song.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

fun formatDuoTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d", m, s)
}