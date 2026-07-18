@file:Suppress("unused", "UnsafeOptInUsageError")

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
import androidx.compose.ui.unit.*
import androidx.hilt.navigation.compose.hiltViewModel
import com.gallerybox.viewmodel.MusicViewModel
import com.gallerybox.viewmodel.Preset
import kotlinx.coroutines.launch
import kotlin.math.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EqualizerScreen(viewModel: MusicViewModel = hiltViewModel(), onBack: () -> Unit) {
    var showSaveDialog by remember { mutableStateOf(false) }
    val enabled by viewModel.eqEnabled.collectAsState()
    val bgAlpha by animateFloatAsState(if (enabled) 1f else 0.96f, label = "bgAlpha")
    val pagerState = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme

    Surface(Modifier.fillMaxSize(), color = colors.background.copy(alpha = bgAlpha)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = colors.onBackground) }
                Spacer(Modifier.weight(1f))
                Row(Modifier.background(colors.surfaceContainerHigh, CircleShape).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TabPill("EQUALIZER", pagerState.currentPage == 0) { scope.launch { pagerState.animateScrollToPage(0) } }
                    TabPill("EFFECTS", pagerState.currentPage == 1) { scope.launch { pagerState.animateScrollToPage(1) } }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showSaveDialog = true }) { Icon(Icons.Default.Save, "Save", tint = colors.onBackground) }
            }
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                if (page == 0) EqTab(viewModel, enabled) else VolTab(viewModel)
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
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceContainerHigh,
        title = { Text("Save Custom Preset", color = colors.onSurface) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Preset Name", color = colors.onSurfaceVariant) },
                singleLine = true,
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
                colors = ButtonDefaults.buttonColors(containerColor = colors.primary)
            ) {
                Text("Save", color = colors.onPrimary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.onSurfaceVariant)
            }
        }
    )
}

@Composable
fun TabPill(text: String, isSelected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .clip(CircleShape)
            .background(if (isSelected) Brush.horizontalGradient(listOf(colors.primaryContainer, colors.secondaryContainer)) else SolidColor(Color.Transparent))
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (isSelected) colors.onPrimaryContainer else colors.onSurfaceVariant,
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

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Enable EQ", color = colors.onBackground, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = enabled,
                    onCheckedChange = { viewModel.toggleEq(it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = colors.primary, uncheckedTrackColor = colors.onSurfaceVariant.copy(alpha = 0.3f))
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { viewModel.applyPreset(Preset.NORMAL) }, enabled = enabled) {
                    Text("Reset", color = if (enabled) colors.primary else colors.onSurfaceVariant, fontWeight = FontWeight.Bold)
                }
                PresetSelector(viewModel)
            }
        }
        Spacer(Modifier.height(16.dp))
        EqCurveGraph(bands = bands, enabled = enabled, labels = freqs)

        AnimatedVisibility(visible = isDistorting && enabled, enter = expandVertically(), exit = shrinkVertically()) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp).background(colors.errorContainer, RoundedCornerShape(20.dp)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Rounded.WarningAmber, "Warning", tint = colors.onErrorContainer, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("High gain may cause audio clipping", color = colors.onErrorContainer, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(24.dp))

        Row(Modifier.weight(1f).fillMaxWidth().alpha(if (enabled) 1f else 0.4f)) {
            Column(Modifier.fillMaxHeight().padding(end = 12.dp, bottom = 28.dp, top = 22.dp), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.End) {
                Text("+15", fontSize = 10.sp, color = colors.onSurfaceVariant, fontWeight = FontWeight.Bold)
                Text(" 0", fontSize = 10.sp, color = colors.onSurfaceVariant, fontWeight = FontWeight.Bold)
                Text("-15", fontSize = 10.sp, color = colors.onSurfaceVariant, fontWeight = FontWeight.Bold)
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

        Spacer(Modifier.height(32.dp))

        Row(Modifier.fillMaxWidth().padding(bottom = 32.dp).alpha(if (enabled) 1f else 0.4f), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            RotaryKnob("BASS BOOST", enabled, viewModel.bassBoost) { viewModel.updateBass(it) }
            RotaryKnob("SURROUND", enabled, viewModel.virtualizer) { viewModel.updateVirtualizer(it) }
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

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp).verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(16.dp))
        EqSectionHeader("MASTER VOLUME")
        Spacer(Modifier.height(16.dp))

        CustomSlider(vol) { viewModel.updateVolume(it) }

        Spacer(Modifier.height(48.dp))
        EqSectionHeader("ENVIRONMENT (REVERB)")
        Spacer(Modifier.height(16.dp))

        FlowRow(Modifier.fillMaxWidth(), maxItemsInEachRow = 3, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            reverbNames.forEachIndexed { i, name ->
                val presetConst = reverbMap.getOrElse(i) { PresetReverb.PRESET_NONE }
                ReverbBtn(name, currentReverb == presetConst) { viewModel.setReverb(presetConst) }
            }
        }
    }
}

@Composable
fun EqCurveGraph(bands: List<Float>, enabled: Boolean, labels: List<String>) {
    val colors = MaterialTheme.colorScheme
    val grad = Brush.horizontalGradient(listOf(colors.primary, colors.secondary))

    Column {
        Canvas(Modifier.fillMaxWidth().height(100.dp).alpha(if (enabled) 1f else 0.4f).clip(RoundedCornerShape(20.dp))) {
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
            drawPath(path, grad, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(labels.firstOrNull() ?: "", fontSize = 10.sp, color = colors.onSurfaceVariant, fontWeight = FontWeight.Bold)
            Text(labels.getOrNull(labels.size / 2) ?: "", fontSize = 10.sp, color = colors.onSurfaceVariant, fontWeight = FontWeight.Bold)
            Text(labels.lastOrNull() ?: "", fontSize = 10.sp, color = colors.onSurfaceVariant, fontWeight = FontWeight.Bold)
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

    // Sync external changes when not actively dragging
    LaunchedEffect(rawLevel) {
        if (!isDragging) {
            dragLevel = rawLevel
            lastSent = rawLevel
        }
    }

    // OPTIMIZATION: Snap instantly during drag to prevent input latency. Animate only on external preset changes.
    val displayLevel by animateFloatAsState(
        targetValue = if (isDragging) dragLevel else rawLevel,
        animationSpec = if (isDragging) snap() else tween(300, easing = FastOutSlowInEasing),
        label = "eqSliderAnimation"
    )

    val dbValue = ((displayLevel - 0.5f) * 30f).roundToInt()
    val isBoosted = abs(dbValue) > 10

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Text(
            text = if (dbValue > 0) "+$dbValue" else "$dbValue",
            color = if (dbValue == 0) colors.onSurfaceVariant else colors.onBackground,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))

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
                    if (isBoosted) {
                        drawRoundRect(colors.errorContainer.copy(alpha = 0.5f), Offset(cX - tW, size.height * (1f - displayLevel)), Size(tW * 2, size.height * displayLevel), CornerRadius(10f))
                    }
                    drawRoundRect(grad, Offset(cX - tW / 2, size.height * (1f - displayLevel)), Size(tW, size.height * displayLevel), CornerRadius(10f))
                }
            }

            val handleH = 24.dp
            val handleY = if (height > 0) (height - with(density) { handleH.toPx() }) * (1f - displayLevel) else 0f

            // OPTIMIZATION: Reduced max shadow to 4.dp to prevent GPU rendering lag
            Box(
                Modifier
                    .offset { IntOffset(0, handleY.toInt()) }
                    .size(36.dp, handleH)
                    .shadow(if (isDragging) 4.dp else 2.dp, RoundedCornerShape(12.dp))
                    .background(colors.surface, RoundedCornerShape(12.dp))
                    .border(1.dp, colors.outlineVariant, RoundedCornerShape(12.dp))
            ) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .height(3.dp)
                        .fillMaxWidth(0.5f)
                        .background(if (enabled) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.5f), CircleShape)
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(label, color = colors.onSurfaceVariant, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun RotaryKnob(label: String, enabled: Boolean = true, valueFlow: kotlinx.coroutines.flow.StateFlow<Float>, onValueChange: (Float) -> Unit) {
    val value by valueFlow.collectAsState()
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

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(100.dp)
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
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2.5f
                val a = internalValue * 270f

                for (i in 0..15) {
                    val dA = (135f + i * (270f / 15)) * (PI / 180f)
                    val act = (i / 15f) <= internalValue && enabled
                    drawCircle(if (act) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.3f), 4f, Offset(center.x + (r + 20f) * cos(dA).toFloat(), center.y + (r + 20f) * sin(dA).toFloat()))
                }

                drawCircle(colors.surfaceContainerHigh, r)
                drawCircle(colors.primaryContainer, r, style = Stroke(3f))

                val iA = (135f + a) * (PI / 180f)
                drawCircle(if (enabled) colors.primary else colors.onSurfaceVariant, 6f, Offset(center.x + (r * 0.7f) * cos(iA).toFloat(), center.y + (r * 0.7f) * sin(iA).toFloat()))
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(label, color = if (enabled) colors.onBackground else colors.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Black)
        Text("${(internalValue * 100).toInt()}%", color = colors.onSurfaceVariant, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun PresetSelector(viewModel: MusicViewModel) {
    var expanded by remember { mutableStateOf(false) }
    val current by viewModel.currentPreset.collectAsState(initial = Preset.NORMAL)
    val colors = MaterialTheme.colorScheme

    Box {
        Row(
            Modifier
                .background(Brush.horizontalGradient(listOf(colors.primaryContainer, colors.secondaryContainer)), RoundedCornerShape(20.dp))
                .clip(RoundedCornerShape(20.dp))
                .clickable { expanded = true }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(current.name, color = colors.onPrimaryContainer, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Icon(Icons.Default.ArrowDropDown, null, tint = colors.onPrimaryContainer)
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.background(colors.surfaceContainerHigh)) {
            Preset.entries.forEach { p ->
                DropdownMenuItem(
                    text = { Text(p.name, color = colors.onSurface) },
                    onClick = {
                        viewModel.applyPreset(p)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun ReverbBtn(text: String, active: Boolean, onClick: () -> Unit) {
    val view = LocalView.current
    val colors = MaterialTheme.colorScheme

    Box(
        Modifier
            .height(48.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(if (active) Brush.horizontalGradient(listOf(colors.primaryContainer, colors.secondaryContainer)) else SolidColor(colors.surfaceContainerHigh))
            .clickable {
                onClick()
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (active) colors.onPrimaryContainer else colors.onSurfaceVariant,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(4.dp)
        )
    }
}

@Composable
fun CustomSlider(value: Float, onValueChange: (Float) -> Unit) {
    var temp by remember(value) { mutableFloatStateOf(value) }
    val colors = MaterialTheme.colorScheme

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Rounded.VolumeUp, "Volume", tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))

        Slider(
            value = temp,
            onValueChange = { temp = it },
            onValueChangeFinished = { onValueChange(temp) },
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = colors.primary, activeTrackColor = colors.primary, inactiveTrackColor = colors.onSurfaceVariant.copy(alpha = 0.3f))
        )

        Spacer(Modifier.width(16.dp))
        Text("${(temp * 100).toInt()}%", color = colors.onBackground, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(40.dp))
    }
}

@Composable
private fun EqSectionHeader(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = 1.5.sp
    )
}