@file:Suppress("unused", "UnsafeOptInUsageError")

package com.gallerybox.ui.screens.music

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gallerybox.viewmodel.RadioViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale
import kotlin.math.roundToInt

// --- UPDATED LIGHT THEME PALETTE ---
private val BgColor = Color(0xFFF8F9FA)
private val SurfaceColor = Color(0xFFFFFFFF)
private val PrimaryColor = Color(0xFFE53935)
private val TextPrimary = Color(0xFF212121)
private val TextSecondary = Color(0xFF757575)

private inline fun Float.toStationInt() = (this * 10).roundToInt()
private inline fun Float.isSameFreq(other: Float) = this.toStationInt() == other.toStationInt()
private fun formatRadioTime(ms: Long) = String.format(Locale.US, "%02d:%02d", ms / 60000, (ms / 1000) % 60)
private enum class RadioView { TUNER, PRESETS, RECORDINGS, DIAGNOSTICS, SETTINGS, DRIVE_MODE }

@Composable
fun RadioScreen(viewModel: RadioViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    var currentView by remember { mutableStateOf(RadioView.TUNER) }

    val isPlaying by viewModel.isPlaying.collectAsState()
    val currentFreq by viewModel.currentFrequency.collectAsState()
    val isHeadsetConnected by viewModel.isHeadsetConnected.collectAsState()
    val favorites by viewModel.favoriteStations.collectAsState()
    val signalStrength by viewModel.signalStrength.collectAsState()
    val stereoBlend by viewModel.stereoBlend.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val isSpeakerEnabled by viewModel.isSpeakerEnabled.collectAsState()
    val isMuted by viewModel.isMuted.collectAsState()
    val stationName by viewModel.rdsStationName.collectAsState()
    val errorMessage by viewModel.error.collectAsState()

    var isRecording by remember { mutableStateOf(false) }
    var recordingTime by remember { mutableStateOf(0L) }

    DisposableEffect(LocalLifecycleOwner.current) {
        onDispose { if (isPlaying) viewModel.stopRadioIfNeeded() }
    }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearError()
        }
    }

    BackHandler(enabled = currentView != RadioView.TUNER) {
        currentView = RadioView.TUNER
    }

    // OPTIMIZATION: Use system time for accurate duration recording without incremental drift
    LaunchedEffect(isRecording) {
        if (isRecording) {
            val startTime = System.currentTimeMillis()
            while (isActive) {
                recordingTime = System.currentTimeMillis() - startTime
                delay(1000L)
            }
        } else {
            recordingTime = 0L
        }
    }

    Scaffold(
        containerColor = BgColor,
        topBar = {
            if (currentView != RadioView.DRIVE_MODE) {
                RadioTopAppBar(
                    currentView = currentView,
                    isHeadsetConnected = isHeadsetConnected,
                    isMuted = isMuted,
                    isSpeakerEnabled = isSpeakerEnabled,
                    onBack = { if (currentView == RadioView.TUNER) onBack() else currentView = RadioView.TUNER },
                    onToggleMute = { viewModel.toggleMute() },
                    onToggleSpeaker = { viewModel.toggleSpeaker() },
                    onNavigateToDriveMode = { currentView = RadioView.DRIVE_MODE }
                )
            }
        }
    ) { paddingValues ->
        Box(Modifier.fillMaxSize().padding(paddingValues)) {
            Crossfade(targetState = currentView, label = "RadioNavigation") { view ->
                when (view) {
                    RadioView.TUNER -> RadioTunerContent(
                        currentFreq = currentFreq,
                        isPlaying = isPlaying,
                        isScanning = isScanning,
                        isHeadsetConnected = isHeadsetConnected,
                        signalStrength = signalStrength,
                        favorites = favorites,
                        stationName = stationName,
                        isRecording = isRecording,
                        recordingTime = recordingTime,
                        viewModel = viewModel,
                        onToggleRecording = { isRecording = !isRecording },
                        onNavigate = { currentView = it }
                    )
                    RadioView.PRESETS -> RadioPresetsContent(favorites, currentFreq, viewModel)
                    RadioView.RECORDINGS -> RadioRecordingsContent()
                    RadioView.DIAGNOSTICS -> RadioDiagnosticsContent(currentFreq, signalStrength, stereoBlend, stationName)
                    RadioView.SETTINGS -> RadioSettingsContent()
                    RadioView.DRIVE_MODE -> DriveModeContent(currentFreq, isPlaying, stationName, favorites, viewModel) { currentView = RadioView.TUNER }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RadioTopAppBar(
    currentView: RadioView,
    isHeadsetConnected: Boolean,
    isMuted: Boolean,
    isSpeakerEnabled: Boolean,
    onBack: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onNavigateToDriveMode: () -> Unit
) {
    TopAppBar(
        title = {
            Text(
                text = when (currentView) {
                    RadioView.TUNER -> "FM Radio"
                    RadioView.PRESETS -> "Presets Manager"
                    RadioView.RECORDINGS -> "FM Recordings"
                    RadioView.DIAGNOSTICS -> "Signal Diagnostics"
                    RadioView.SETTINGS -> "Radio Settings"
                    RadioView.DRIVE_MODE -> ""
                },
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary)
            }
        },
        actions = {
            if (currentView == RadioView.TUNER && isHeadsetConnected) {
                IconButton(onClick = onToggleMute) {
                    Icon(
                        imageVector = if (isMuted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                        contentDescription = "Mute Toggle",
                        tint = if (isMuted) PrimaryColor else TextPrimary
                    )
                }
                IconButton(onClick = onToggleSpeaker) {
                    Icon(
                        imageVector = if (isSpeakerEnabled) Icons.Rounded.Speaker else Icons.Rounded.Headset,
                        contentDescription = "Speaker Toggle",
                        tint = if (isSpeakerEnabled) PrimaryColor else TextPrimary
                    )
                }
                IconButton(onClick = onNavigateToDriveMode) {
                    Icon(Icons.Rounded.DirectionsCar, "Drive Mode", tint = TextPrimary)
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor)
    )
}

@Composable
private fun RadioTunerContent(
    currentFreq: Float,
    isPlaying: Boolean,
    isScanning: Boolean,
    isHeadsetConnected: Boolean,
    signalStrength: Int,
    favorites: List<Float>,
    stationName: String?,
    isRecording: Boolean,
    recordingTime: Long,
    viewModel: RadioViewModel,
    onToggleRecording: () -> Unit,
    onNavigate: (RadioView) -> Unit
) {
    val frequencyList = remember { (875..1080 step 1).map { it / 10f } }
    val isFavorite = favorites.any { it.isSameFreq(currentFreq) }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AnimatedVisibility(visible = !isHeadsetConnected, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Headset, "Headset Required", tint = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(Modifier.width(12.dp))
                    Text("Wired headphones required as antenna.", color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        Spacer(modifier = Modifier.weight(0.1f))

        AnimatedVisibility(visible = isPlaying && isHeadsetConnected, enter = fadeIn(), exit = fadeOut()) {
            Surface(
                color = if (isRecording) PrimaryColor.copy(alpha = 0.15f) else SurfaceColor,
                shape = CircleShape,
                border = BorderStroke(1.dp, if (isRecording) PrimaryColor else Color.Transparent),
                shadowElevation = 2.dp,
                modifier = Modifier.fillMaxWidth(0.6f).clickable { onToggleRecording() }
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isRecording) Icons.Rounded.StopCircle else Icons.Rounded.FiberManualRecord,
                        contentDescription = "Record",
                        tint = if (isRecording) PrimaryColor else TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (isRecording) formatRadioTime(recordingTime) else "Tap to Record",
                        color = if (isRecording) PrimaryColor else TextSecondary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        AdvancedRadioDisplay(
            frequency = currentFreq,
            stationName = stationName,
            isPlaying = isPlaying,
            isFavorite = isFavorite,
            signalStrength = signalStrength,
            onToggleFavorite = { viewModel.toggleFavorite(currentFreq) },
            onFineTuneDown = { viewModel.tuneDown() },
            onFineTuneUp = { viewModel.tuneUp() }
        )

        Spacer(modifier = Modifier.height(32.dp))

        RadioTunerDial(
            frequencyList = frequencyList,
            currentFreq = currentFreq,
            isHeadsetConnected = isHeadsetConnected,
            isScanning = isScanning,
            onTune = { viewModel.tuneToFrequency(it) }
        )

        Spacer(modifier = Modifier.weight(0.1f))

        if (!isHeadsetConnected) {
            Box(modifier = Modifier.height(120.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.HeadsetOff, "Disconnected", modifier = Modifier.size(64.dp), tint = TextSecondary.copy(alpha = 0.3f))
            }
        } else {
            RadioControls(
                isPlaying = isPlaying,
                isScanning = isScanning,
                onPowerClick = { viewModel.toggleRadio() },
                onScanPrev = { viewModel.scanPrevious() },
                onScanNext = { viewModel.autoScan() }
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
        RadioBottomNavRow(onNavigate)
        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun AdvancedRadioDisplay(
    frequency: Float,
    stationName: String?,
    isPlaying: Boolean,
    isFavorite: Boolean,
    signalStrength: Int,
    onToggleFavorite: () -> Unit,
    onFineTuneDown: () -> Unit,
    onFineTuneUp: () -> Unit
) {
    // OPTIMIZATION: Explicitly controlled while-loop to stop animation ticks completely when paused.
    val pulseAnim = remember { Animatable(1f) }
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (isActive) {
                pulseAnim.animateTo(1.02f, animationSpec = tween(1500, easing = FastOutSlowInEasing))
                pulseAnim.animateTo(1f, animationSpec = tween(1500, easing = FastOutSlowInEasing))
            }
        } else {
            pulseAnim.animateTo(1f, animationSpec = tween(500, easing = FastOutSlowInEasing))
        }
    }

    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().scale(pulseAnim.value)) {
        // OPTIMIZATION: Reduced shadow elevation from 16.dp to 8.dp to prevent GPU rendering lag
        Surface(
            shape = RoundedCornerShape(32.dp),
            color = SurfaceColor,
            shadowElevation = if (isPlaying) 8.dp else 2.dp,
            border = BorderStroke(1.dp, if (isPlaying) PrimaryColor.copy(alpha = 0.3f) else Color.Transparent),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = BgColor, border = BorderStroke(1.dp, TextSecondary.copy(alpha = 0.3f))) {
                        Text("FM", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        repeat(5) { i ->
                            Box(
                                modifier = Modifier
                                    .width(4.dp)
                                    .height((6 + i * 3).dp)
                                    .background(
                                        color = if (isPlaying && signalStrength >= (i + 1) * 20) PrimaryColor else TextSecondary.copy(alpha = 0.3f),
                                        shape = RoundedCornerShape(2.dp)
                                    )
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text(
                    text = stationName ?: (if (isPlaying) "TUNING..." else "STOPPED"),
                    color = if (stationName != null) PrimaryColor else TextSecondary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                    IconButton(onClick = onFineTuneDown) {
                        Icon(Icons.Rounded.ChevronLeft, "-0.1", tint = TextSecondary)
                    }
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("%.1f".format(frequency), color = if (isPlaying) TextPrimary else TextSecondary.copy(alpha = 0.5f), fontWeight = FontWeight.ExtraBold, fontSize = 64.sp)
                        Text("MHz", color = TextSecondary, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp, start = 4.dp))
                    }
                    IconButton(onClick = onFineTuneUp) {
                        Icon(Icons.Rounded.ChevronRight, "+0.1", tint = TextSecondary)
                    }
                }

                Spacer(Modifier.height(24.dp))
                IconButton(onClick = onToggleFavorite, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Save Station",
                        tint = if (isFavorite) PrimaryColor else TextSecondary,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun RadioTunerDial(
    frequencyList: List<Float>,
    currentFreq: Float,
    isHeadsetConnected: Boolean,
    isScanning: Boolean,
    onTune: (Float) -> Unit
) {
    val listState = rememberLazyListState()

    LaunchedEffect(currentFreq) {
        val idx = frequencyList.indexOfFirst { it.isSameFreq(currentFreq) }
        if (idx != -1 && !listState.isScrollInProgress) {
            listState.scrollToItem(maxOf(0, idx - 3))
        }
    }

    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().height(80.dp).clip(RoundedCornerShape(12.dp)).background(SurfaceColor)) {
        LazyRow(state = listState, modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 150.dp)) {
            items(frequencyList) { freq ->
                val dec = (freq * 10).roundToInt() % 10
                val isMajor = dec == 0 || dec == 5
                val isCurr = freq.isSameFreq(currentFreq)

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(24.dp).clickable(enabled = isHeadsetConnected && !isScanning) { onTune(freq) }
                ) {
                    Box(modifier = Modifier.width(2.dp).height(if (isMajor) 24.dp else 12.dp).background(if (isCurr) PrimaryColor else TextSecondary.copy(alpha = 0.5f)))
                    Spacer(Modifier.height(4.dp))
                    if (isMajor) {
                        Text("%.1f".format(freq), fontSize = 10.sp, color = if (isCurr) PrimaryColor else TextSecondary, fontWeight = if (isCurr) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
        Box(modifier = Modifier.width(2.dp).height(50.dp).background(PrimaryColor).align(Alignment.TopCenter))
    }
}

@Composable
private fun RadioControls(
    isPlaying: Boolean,
    isScanning: Boolean,
    onPowerClick: () -> Unit,
    onScanPrev: () -> Unit,
    onScanNext: () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onScanPrev, enabled = !isScanning, modifier = Modifier.size(56.dp).background(SurfaceColor, CircleShape)) {
            Icon(Icons.Rounded.KeyboardDoubleArrowLeft, "Scan Previous", tint = TextPrimary)
        }

        Surface(onClick = onPowerClick, shape = CircleShape, color = if (isPlaying) PrimaryColor else SurfaceColor, shadowElevation = if (isPlaying) 8.dp else 2.dp, modifier = Modifier.size(80.dp)) {
            Box(contentAlignment = Alignment.Center) {
                if (isScanning) {
                    CircularProgressIndicator(color = if (isPlaying) Color.White else PrimaryColor, strokeWidth = 3.dp, modifier = Modifier.size(40.dp))
                } else {
                    Icon(if (isPlaying) Icons.Rounded.PowerSettingsNew else Icons.Rounded.PlayArrow, "Power", tint = if (isPlaying) Color.White else TextPrimary, modifier = Modifier.size(40.dp))
                }
            }
        }

        IconButton(onClick = onScanNext, enabled = !isScanning, modifier = Modifier.size(56.dp).background(SurfaceColor, CircleShape)) {
            Icon(Icons.Rounded.KeyboardDoubleArrowRight, "Scan Next", tint = TextPrimary)
        }
    }
}

@Composable
private fun RadioBottomNavRow(onNavigate: (RadioView) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        RadioNavIcon(Icons.Rounded.FormatListBulleted, "Presets") { onNavigate(RadioView.PRESETS) }
        RadioNavIcon(Icons.Rounded.Mic, "Recordings") { onNavigate(RadioView.RECORDINGS) }
        RadioNavIcon(Icons.Rounded.Timeline, "Signal") { onNavigate(RadioView.DIAGNOSTICS) }
        RadioNavIcon(Icons.Rounded.Settings, "Settings") { onNavigate(RadioView.SETTINGS) }
    }
}

@Composable
private fun RadioNavIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
        Box(modifier = Modifier.size(48.dp).background(SurfaceColor, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = TextPrimary, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RadioPresetsContent(favorites: List<Float>, currentFreq: Float, viewModel: RadioViewModel) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Your Stations", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = { viewModel.autoScanAndSaveAll() }, colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryColor), border = BorderStroke(1.dp, PrimaryColor)) {
                Icon(Icons.Rounded.AutoAwesome, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Auto-Save All", fontSize = 12.sp)
            }
        }

        if (favorites.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No saved presets. Tap the heart to save.", color = TextSecondary)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 90.dp), modifier = Modifier.fillMaxSize()) {
                items(favorites.sorted()) { freq ->
                    val isPlaying = freq.isSameFreq(currentFreq)
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { viewModel.tuneToFrequency(freq) }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.DragHandle, "Reorder", tint = TextSecondary)
                        Spacer(Modifier.width(16.dp))
                        Box(
                            modifier = Modifier.size(48.dp).background(if (isPlaying) PrimaryColor else SurfaceColor, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("%.1f".format(freq), color = if (isPlaying) Color.White else TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Station %.1f".format(freq), color = if (isPlaying) PrimaryColor else TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text("Local Radio", color = TextSecondary, fontSize = 12.sp)
                        }
                        IconButton(onClick = {}) { Icon(Icons.Rounded.Edit, "Edit", tint = TextSecondary) }
                        IconButton(onClick = { viewModel.toggleFavorite(freq) }) { Icon(Icons.Rounded.Favorite, "Remove", tint = PrimaryColor) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RadioRecordingsContent() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.Mic, null, tint = PrimaryColor, modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(16.dp))
            Text("No Recordings Yet", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Record live radio directly from the tuner screen.", color = TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun RadioDiagnosticsContent(currentFreq: Float, signalStrength: Int, stereoBlend: Float, stationName: String?) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.Timeline, null, tint = PrimaryColor, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(24.dp))
        Text("Tuned Frequency: %.1f MHz".format(currentFreq), color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(32.dp))
        DiagnosticRow("Signal Strength", "$signalStrength%")
        DiagnosticRow("Signal-to-Noise Ratio (Est.)", "${(signalStrength * 0.4).toInt()} dB")
        DiagnosticRow("Stereo Separation", "${(stereoBlend * 100).toInt()}%")
        DiagnosticRow("RDS Data Stream", if (stationName != null) "Active" else "Inactive")
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = {}, colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryColor), modifier = Modifier.fillMaxWidth()) {
            Text("Auto-Optimize Reception")
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TextSecondary, fontSize = 14.sp)
        Text(value, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
    HorizontalDivider(color = SurfaceColor)
}

@Composable
private fun RadioSettingsContent() {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item { RadioSettingsHeader("Reception & Scanning") }
        item { RadioSettingsNavRow("FM Frequency Region", "Current: 87.5 - 108.0 MHz (Global)") }
        item { RadioSettingsNavRow("Scan Sensitivity", "Current: Medium (Default)") }
        item { RadioSettingsSwitchRow("Force Mono Playback", "Improves clarity on weak signals", false) }
        item { RadioSettingsHeader("Audio Engine") }
        item { RadioSettingsSwitchRow("Noise Reduction", "Apply software filter to static hiss", true) }
        item { RadioSettingsNavRow("Radio Equalizer", "Dedicated EQ for FM broadcasts") }
        item { RadioSettingsHeader("Automation") }
        item { RadioSettingsSwitchRow("Resume Last Station", "Play automatically on app start", true) }
        item { RadioSettingsNavRow("Sleep Timer", "Turn off radio after set duration") }
    }
}

@Composable
private fun RadioSettingsHeader(title: String) {
    Text(title, color = PrimaryColor, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp))
}

@Composable
private fun RadioSettingsSwitchRow(title: String, sub: String, def: Boolean) {
    var checked by remember { mutableStateOf(def) }
    Row(
        modifier = Modifier.fillMaxWidth().clickable { checked = !checked }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 16.sp)
            Text(sub, color = TextSecondary, fontSize = 12.sp)
        }
        Switch(checked, { checked = it }, colors = SwitchDefaults.colors(checkedThumbColor = PrimaryColor, checkedTrackColor = PrimaryColor.copy(alpha=0.5f)))
    }
}

@Composable
private fun RadioSettingsNavRow(title: String, sub: String) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable {}.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 16.sp)
            Text(sub, color = TextSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun DriveModeContent(
    currentFreq: Float,
    isPlaying: Boolean,
    stationName: String?,
    favorites: List<Float>,
    viewModel: RadioViewModel,
    onExitDriveMode: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().background(BgColor).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onExitDriveMode, Modifier.size(64.dp)) {
                Icon(Icons.Rounded.Close, "Exit", tint = TextSecondary, modifier = Modifier.size(48.dp))
            }
            Icon(Icons.Rounded.DirectionsCar, "Drive Mode", tint = PrimaryColor, modifier = Modifier.size(48.dp))
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stationName ?: "FM RADIO", color = PrimaryColor, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Text("%.1f".format(currentFreq), color = TextPrimary, fontSize = 100.sp, fontWeight = FontWeight.Black)
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            // OPTIMIZATION: Reduced shadow elevation from 4.dp to 2.dp for rendering performance
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = SurfaceColor,
                shadowElevation = 2.dp,
                modifier = Modifier.size(100.dp).clickable { viewModel.scanPrevious() }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.SkipPrevious, null, tint = TextPrimary, modifier = Modifier.size(64.dp))
                }
            }

            // OPTIMIZATION: Reduced shadow elevation from 12.dp to 8.dp
            Surface(
                shape = CircleShape,
                color = PrimaryColor,
                shadowElevation = 8.dp,
                modifier = Modifier.size(120.dp).clickable { viewModel.toggleRadio() }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Power", tint = Color.White, modifier = Modifier.size(80.dp))
                }
            }

            Surface(
                shape = RoundedCornerShape(24.dp),
                color = SurfaceColor,
                shadowElevation = 2.dp,
                modifier = Modifier.size(100.dp).clickable { viewModel.autoScan() }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.SkipNext, null, tint = TextPrimary, modifier = Modifier.size(64.dp))
                }
            }
        }

        if (favorites.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(favorites.take(5)) { freq ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = SurfaceColor,
                        shadowElevation = 2.dp,
                        modifier = Modifier.height(80.dp).width(120.dp).clickable { viewModel.tuneToFrequency(freq) }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("%.1f".format(freq), color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}