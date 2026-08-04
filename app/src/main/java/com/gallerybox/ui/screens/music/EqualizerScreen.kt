@file:Suppress("unused", "UnsafeOptInUsageError", "DEPRECATION")

package com.gallerybox.ui.screens.music

import android.media.audiofx.PresetReverb
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.hilt.navigation.compose.hiltViewModel
import com.gallerybox.viewmodel.MusicViewModel
import com.gallerybox.viewmodel.Preset
import kotlinx.coroutines.launch
import kotlin.math.*

private val WarningAmberColor = Color(0xFFFFA000)

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun EqualizerScreen(viewModel: MusicViewModel = hiltViewModel(), onBack: () -> Unit) {
    var showSaveDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    val enabled by viewModel.eqEnabled.collectAsState()
    val pagerState = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Equalizer", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = colors.onSurface) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = colors.onSurface) } },
                actions = {
                    Box {
                        IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, "More", tint = colors.onSurface) }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, modifier = Modifier.background(colors.surface).clip(RoundedCornerShape(12.dp))) {
                            DropdownMenuItem(
                                text = { Text("Save Preset", color = colors.onSurface) },
                                leadingIcon = { Icon(Icons.Default.Save, null, tint = colors.onSurface) },
                                onClick = { showMenu = false; showSaveDialog = true }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { paddingValues ->
        Box(Modifier.fillMaxSize().background(colors.background)) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(colors.primary.copy(alpha = 0.05f), colors.background))))

            Column(Modifier.fillMaxSize().padding(paddingValues)) {
                Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                    Surface(shape = RoundedCornerShape(20.dp), color = colors.surfaceContainerHigh) {
                        Row(Modifier.height(40.dp).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            TabPill("Equalizer", pagerState.currentPage == 0) { scope.launch { pagerState.animateScrollToPage(0) } }
                            TabPill("Effects", pagerState.currentPage == 1) { scope.launch { pagerState.animateScrollToPage(1) } }
                        }
                    }
                }

                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                    if (page == 0) EqTab(viewModel, enabled) else VolTab(viewModel)
                }
            }
        }
    }

    if (showSaveDialog) {
        SavePresetDialog(
            onDismiss = { showSaveDialog = false },
            onSave = { presetName -> showSaveDialog = false }
        )
    }
}

@Composable
fun SavePresetDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme

    AlertDialog(
        shape = RoundedCornerShape(28.dp),
        containerColor = colors.surfaceContainerHigh,
        onDismissRequest = onDismiss,
        title = { Text("Save Custom Preset", color = colors.onSurface, fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Preset Name", color = colors.onSurfaceVariant) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = TextFieldDefaults.colors(
                    focusedTextColor = colors.onSurface,
                    unfocusedTextColor = colors.onSurface,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = colors.primary,
                    unfocusedIndicatorColor = colors.onSurfaceVariant
                )
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        Toast.makeText(context, "'$name' Saved", Toast.LENGTH_SHORT).show()
                        onSave(name)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Text("Save", color = colors.onPrimary, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.onSurfaceVariant, fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
fun TabPill(text: String, isSelected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(16.dp))
            .background(if (isSelected) colors.primary else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (isSelected) colors.onPrimary else colors.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )
    }
}

@Composable
fun EqTab(viewModel: MusicViewModel, enabled: Boolean) {
    val bands by viewModel.eqBands1.collectAsState()
    val bandsSize by remember(bands) { derivedStateOf { bands.size } }

    val freqs = remember(bandsSize) {
        when (bandsSize) {
            5 -> listOf("60", "230", "910", "3.6k", "14k")
            9 -> listOf("32", "64", "125", "250", "500", "1k", "2k", "4k", "8k")
            10 -> listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
            else -> List(bandsSize) { "" }
        }
    }

    val isDistorting by remember(bands) {
        derivedStateOf {
            if (bands.isEmpty()) false
            else (bands.maxOrNull() ?: 0f) > 0.9f || bands.take(2).average() > 0.85
        }
    }
    val colors = MaterialTheme.colorScheme
    val currentPreset by viewModel.currentPreset.collectAsState(initial = Preset.NORMAL)

    var expandedPresetMenu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp).padding(bottom = 48.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(
                modifier = Modifier.weight(1f).height(80.dp),
                shape = RoundedCornerShape(20.dp),
                color = colors.surfaceContainerHigh,
                shadowElevation = 2.dp
            ) {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Equalizer", color = colors.onSurface, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("Improve sound quality", color = colors.onSurfaceVariant, fontSize = 13.sp)
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { viewModel.toggleEq(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = colors.primary, uncheckedTrackColor = colors.onSurfaceVariant.copy(alpha = 0.3f))
                    )
                }
            }

            Surface(
                modifier = Modifier.weight(0.6f).height(80.dp),
                shape = RoundedCornerShape(20.dp),
                color = colors.surfaceContainerHigh,
                shadowElevation = 2.dp
            ) {
                Box {
                    Column(
                        Modifier.fillMaxSize().clickable { expandedPresetMenu = true }.padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("Preset", color = colors.onSurfaceVariant, fontSize = 13.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(currentPreset.name, color = colors.primary, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(Icons.Default.ArrowDropDown, null, tint = colors.primary)
                        }
                    }
                    DropdownMenu(expanded = expandedPresetMenu, onDismissRequest = { expandedPresetMenu = false }, modifier = Modifier.background(colors.surfaceContainerHigh).clip(RoundedCornerShape(12.dp))) {
                        Preset.entries.forEach { p ->
                            DropdownMenuItem(
                                text = { Text(p.name, color = colors.onSurface) },
                                onClick = {
                                    viewModel.applyPreset(p)
                                    expandedPresetMenu = false
                                }
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        Box(contentAlignment = Alignment.Center) {
            EqCurveGraph(bands = bands, enabled = enabled, labels = freqs)

            this@Column.AnimatedVisibility(visible = isDistorting && enabled, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                Surface(
                    shape = CircleShape,
                    color = WarningAmberColor.copy(alpha = 0.15f),
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.WarningAmber, "Warning", tint = WarningAmberColor, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("High gain may cause audio clipping", color = WarningAmberColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        Row(Modifier.weight(1f).fillMaxWidth().alpha(if (enabled) 1f else 0.4f)) {
            Column(Modifier.fillMaxHeight().padding(end = 12.dp, bottom = 24.dp, top = 14.dp), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.End) {
                Text("+15", fontSize = 11.sp, color = colors.onSurfaceVariant, fontWeight = FontWeight.Medium)
                Text("0", fontSize = 11.sp, color = colors.onSurfaceVariant, fontWeight = FontWeight.Medium)
                Text("-15", fontSize = 11.sp, color = colors.onSurfaceVariant, fontWeight = FontWeight.Medium)
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween) {
                for (i in 0 until bandsSize) {
                    VerticalEqSlider(
                        modifier = Modifier.weight(1f),
                        levelProvider = { bands.getOrNull(i) ?: 0.5f },
                        enabled = enabled,
                        onValueChange = { viewModel.updateEq(i, it) },
                        label = freqs.getOrElse(i) { "" }
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        Row(Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.4f), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            KnobCard("Bass Boost", viewModel.bassBoost, enabled) { viewModel.updateBass(it) }
            Spacer(Modifier.width(16.dp))
            KnobCard("Surround", viewModel.virtualizer, enabled) { viewModel.updateVirtualizer(it) }
        }
    }
}

@Composable
fun KnobCard(title: String, valueFlow: kotlinx.coroutines.flow.StateFlow<Float>, enabled: Boolean, onValueChange: (Float) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val value by valueFlow.collectAsState()

    Surface(
        modifier = Modifier.width(140.dp),
        shape = RoundedCornerShape(20.dp),
        color = colors.surfaceContainerHigh,
        shadowElevation = 2.dp
    ) {
        Column(Modifier.padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            RotaryKnob(enabled = enabled, value = value, onValueChange = onValueChange)
            Spacer(Modifier.height(16.dp))
            Text(title, color = if (enabled) colors.onSurface else colors.onSurfaceVariant, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("${(value * 100).toInt()}%", color = colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VolTab(viewModel: MusicViewModel) {
    val vol by viewModel.volume1.collectAsState()
    val currentReverb by viewModel.reverbPreset.collectAsState()
    val reverbNames = listOf("Off", "Small Room", "Med Room", "Large Room", "Med Hall", "Large Hall", "Plate")
    val reverbMap = remember { listOf(PresetReverb.PRESET_NONE, PresetReverb.PRESET_SMALLROOM, PresetReverb.PRESET_MEDIUMROOM, PresetReverb.PRESET_LARGEROOM, PresetReverb.PRESET_MEDIUMHALL, PresetReverb.PRESET_LARGEHALL, PresetReverb.PRESET_PLATE) }
    val colors = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp).verticalScroll(rememberScrollState())) {

        Surface(shape = RoundedCornerShape(20.dp), color = colors.surfaceContainerHigh, shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(24.dp)) {
                Text("Master Volume", color = colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(24.dp))
                CustomSlider(vol) { viewModel.updateVolume(it) }
            }
        }

        Spacer(Modifier.height(24.dp))

        Surface(shape = RoundedCornerShape(20.dp), color = colors.surfaceContainerHigh, shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(24.dp)) {
                Text("Environment (Reverb)", color = colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(24.dp))
                FlowRow(Modifier.fillMaxWidth(), maxItemsInEachRow = 3, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    reverbNames.forEachIndexed { i, name ->
                        val presetConst = reverbMap.getOrElse(i) { PresetReverb.PRESET_NONE }
                        ReverbChip(name, currentReverb == presetConst) { viewModel.setReverb(presetConst) }
                    }
                }
            }
        }

        Spacer(Modifier.height(48.dp))
    }
}

@Composable
fun EqCurveGraph(bands: List<Float>, enabled: Boolean, labels: List<String>) {
    val colors = MaterialTheme.colorScheme
    val grad = Brush.verticalGradient(listOf(colors.primary.copy(alpha = 0.6f), Color.Transparent))

    Column {
        Box(Modifier.fillMaxWidth().height(140.dp).alpha(if (enabled) 1f else 0.4f).clip(RoundedCornerShape(20.dp))) {
            Canvas(Modifier.fillMaxSize()) {
                drawRoundRect(color = colors.surfaceContainerHigh, cornerRadius = CornerRadius(20.dp.toPx()))
                if (bands.size < 2) return@Canvas

                val path = Path()
                val spacing = size.width / (bands.size - 1).coerceAtLeast(1)

                bands.forEachIndexed { i, lvl ->
                    val x = i * spacing
                    val y = size.height - (lvl * size.height)
                    if (i == 0) path.moveTo(x, y)
                    else {
                        val pX = (i - 1) * spacing
                        val pY = size.height - (bands[i - 1] * size.height)
                        val cX = pX + spacing / 2
                        path.cubicTo(cX, pY, cX, y, x, y)
                    }
                }

                val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
                val lineCol = colors.onSurfaceVariant.copy(alpha = 0.2f)

                drawLine(lineCol, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.dp.toPx(), pathEffect = dash)
                drawLine(lineCol, Offset(0f, size.height / 4), Offset(size.width, size.height / 4), 1.dp.toPx(), pathEffect = dash)
                drawLine(lineCol, Offset(0f, size.height - size.height / 4), Offset(size.width, size.height - size.height / 4), 1.dp.toPx(), pathEffect = dash)

                // Soft fill under the curve
                val fillPath = Path().apply {
                    addPath(path)
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(fillPath, grad)

                // Main line stroke
                drawPath(path, color = colors.primary, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            labels.forEach { label ->
                Text(label, fontSize = 11.sp, color = colors.onSurfaceVariant, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
fun VerticalEqSlider(modifier: Modifier, levelProvider: () -> Float, enabled: Boolean, onValueChange: (Float) -> Unit, label: String) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val view = LocalView.current

    var height by remember { mutableFloatStateOf(0f) }
    val rawLevel = levelProvider()

    var isDragging by remember { mutableStateOf(false) }
    var dragLevel by remember { mutableFloatStateOf(rawLevel) }
    var lastSent by remember { mutableFloatStateOf(rawLevel) }

    LaunchedEffect(rawLevel) {
        if (!isDragging) {
            dragLevel = rawLevel
            lastSent = rawLevel
        }
    }

    val displayLevel by animateFloatAsState(
        targetValue = if (isDragging) dragLevel else rawLevel,
        animationSpec = if (isDragging) snap() else tween(300, easing = FastOutSlowInEasing),
        label = "eqSliderAnimation"
    )

    val dbValue = ((displayLevel - 0.5f) * 30f).roundToInt()
    val isBoosted = abs(dbValue) > 10

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Box(
            Modifier
                .weight(1f)
                .width(44.dp)
                .onSizeChanged { height = it.height.toFloat() }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(onDoubleTap = {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        dragLevel = 0.5f
                        lastSent = 0.5f
                        onValueChange(0.5f)
                    })
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectVerticalDragGestures(
                        onDragStart = {
                            isDragging = true
                            dragLevel = rawLevel
                        },
                        onDragEnd = {
                            isDragging = false
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            onValueChange(dragLevel)
                        },
                        onDragCancel = {
                            isDragging = false
                        }
                    ) { c, d ->
                        c.consume()
                        var next = (dragLevel - d / height).coerceIn(0f, 1f)
                        if (abs(next - 0.5f) < 0.05f) next = 0.5f
                        dragLevel = next
                        if (abs(next - lastSent) > 0.02f) {
                            if (next == 0.5f && lastSent != 0.5f) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                            lastSent = next
                            onValueChange(next)
                        }
                    }
                },
            contentAlignment = Alignment.TopCenter
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val tW = 6.dp.toPx()
                val cX = size.width / 2
                val grad = Brush.verticalGradient(listOf(colors.primary, colors.secondary))

                drawRoundRect(colors.onSurfaceVariant.copy(alpha = 0.2f), Offset(cX - tW / 2, 0f), Size(tW, size.height), CornerRadius(10f))
                drawLine(colors.onSurfaceVariant.copy(alpha = 0.4f), Offset(cX - 12f, size.height / 2), Offset(cX + 12f, size.height / 2), 3f)

                if (enabled) {
                    drawRoundRect(grad, Offset(cX - tW / 2, size.height * (1f - displayLevel)), Size(tW, size.height * displayLevel), CornerRadius(10f))
                }
            }

            val handleH = 28.dp
            val handleY = if (height > 0) (height - with(density) { handleH.toPx() }) * (1f - displayLevel) else 0f

            Box(
                Modifier
                    .offset { IntOffset(0, handleY.toInt()) }
                    .size(28.dp, handleH)
                    .background(colors.primary, CircleShape)
                    .border(2.dp, colors.surface, CircleShape)
            )
        }
    }
}

@Composable
fun RotaryKnob(enabled: Boolean = true, value: Float, onValueChange: (Float) -> Unit) {
    val view = LocalView.current
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    val scale = remember(density) { with(density) { 200.dp.toPx() } }

    var internalValue by remember { mutableFloatStateOf(value) }
    var isDragging by remember { mutableStateOf(false) }
    var lastSent by remember { mutableFloatStateOf(internalValue) }

    LaunchedEffect(value) {
        if (!isDragging) {
            internalValue = value
            lastSent = value
        }
    }

    Box(
        Modifier
            .size(72.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectVerticalDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd = {
                        isDragging = false
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    },
                    onDragCancel = { isDragging = false }
                ) { c, d ->
                    c.consume()
                    val n = (internalValue - d / scale).coerceIn(0f, 1f)
                    internalValue = n
                    if (abs(n - lastSent) > 0.02f) {
                        lastSent = n
                        onValueChange(n)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val animatedRotation by animateFloatAsState(targetValue = internalValue * 270f, label = "knobRotation")

        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2.5f

            for (i in 0..15) {
                val dA = (135f + i * (270f / 15)) * (PI / 180f)
                val act = (i / 15f) <= internalValue && enabled
                drawCircle(if (act) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.3f), 4f, Offset(center.x + (r + 14f) * cos(dA).toFloat(), center.y + (r + 14f) * sin(dA).toFloat()))
            }

            drawCircle(colors.surfaceContainerHighest, r)
            drawCircle(colors.primaryContainer, r, style = Stroke(3f))

            val iA = (135f + animatedRotation) * (PI / 180f)
            drawCircle(if (enabled) colors.primary else colors.onSurfaceVariant, 6f, Offset(center.x + (r * 0.7f) * cos(iA).toFloat(), center.y + (r * 0.7f) * sin(iA).toFloat()))
        }
    }
}

@Composable
fun ReverbChip(text: String, active: Boolean, onClick: () -> Unit) {
    val view = LocalView.current
    val colors = MaterialTheme.colorScheme

    Surface(
        onClick = {
            onClick()
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        },
        shape = CircleShape,
        color = if (active) colors.primary else colors.surfaceContainerHighest,
        contentColor = if (active) colors.onPrimary else colors.onSurface,
        modifier = Modifier.height(36.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(text = text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun CustomSlider(value: Float, onValueChange: (Float) -> Unit) {
    var temp by remember(value) { mutableFloatStateOf(value) }
    val colors = MaterialTheme.colorScheme

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Rounded.VolumeUp, "Volume", tint = colors.onSurfaceVariant, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))

        Slider(
            value = temp,
            onValueChange = { temp = it },
            onValueChangeFinished = { onValueChange(temp) },
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = colors.primary, activeTrackColor = colors.primary, inactiveTrackColor = colors.onSurfaceVariant.copy(alpha = 0.3f))
        )

        Spacer(Modifier.width(16.dp))
        Text("${(temp * 100).toInt()}%", color = colors.onBackground, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(48.dp))
    }
}