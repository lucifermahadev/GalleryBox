@file:Suppress("UnsafeOptInUsageError", "UnstableApiUsage", "OPT_IN_USAGE", "unused", "UNCHECKED_CAST", "DEPRECATION", "SpellCheckingInspection", "NonSkippableComposable", "RedundantRequireNotNullCall")
package com.gallerybox.ui.screens.editor

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.*
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.effect.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.gallerybox.data.*
import com.gallerybox.viewmodel.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.Locale
import kotlin.math.*

enum class EditorTab { ADJUST, CROP, LUT, FRAMES, TEXT, STICKER, TRIM }
@Immutable data class AdjustTool(val name: String, val icon: ImageVector, val value: Float, val range: ClosedFloatingPointRange<Float>, val onValueChange: (Float) -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(mediaId: Long, galleryViewModel: GalleryViewModel = hiltViewModel(), editorViewModel: EditorViewModel = hiltViewModel(), onBack: () -> Unit) {
    val ctx = LocalContext.current
    val mediaMap by galleryViewModel.mediaMap.collectAsState()
    val rawMedia by galleryViewModel.rawMedia.collectAsState()
    val mediaItem = remember(mediaId, mediaMap, rawMedia) { mediaMap[mediaId] ?: rawMedia.find { it.id == mediaId } }

    val editState by editorViewModel.currentEditState.collectAsState()
    val previewBitmap by editorViewModel.previewBitmap.collectAsState()
    val fileOpState by editorViewModel.fileOperationState.collectAsState()
    val isExporting = fileOpState is FileOperationState.Editing
    val isPreviewUpdating by editorViewModel.isPreviewUpdating.collectAsState()

    var activeTab by rememberSaveable(mediaId) { mutableStateOf(if (mediaItem?.isVideo == true) EditorTab.TRIM.name else EditorTab.ADJUST.name) }
    var selectedLayerId by rememberSaveable { mutableStateOf<String?>(null) }
    var currentPlayerPos by remember { mutableLongStateOf(0L) }
    var videoDuration by remember { mutableLongStateOf(1L) }
    var gridType by remember { mutableIntStateOf(0) }
    var showExportDialog by remember { mutableStateOf(false) }
    var splitComparePos by remember { mutableFloatStateOf(-1f) }

    val lutItems by editorViewModel.lutItems.collectAsState()
    val lutCategories = remember(lutItems) { lutItems.map { it.category }.distinct() }
    var selectedLutCategory by remember { mutableStateOf<String?>(null) }
    var wasExporting by remember { mutableStateOf(false) }

    var seekRequest by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(lutCategories) { if (selectedLutCategory == null && lutCategories.isNotEmpty()) selectedLutCategory = lutCategories.first() }
    val handleBack = { if (selectedLayerId != null) selectedLayerId = null else onBack() }
    BackHandler(enabled = !isExporting) { handleBack() }
    LaunchedEffect(mediaItem) { if (mediaItem != null) editorViewModel.initializeEditor(mediaItem) }
    LaunchedEffect(fileOpState) {
        if (fileOpState is FileOperationState.Editing) wasExporting = true
        else if (fileOpState is FileOperationState.Idle && wasExporting) {
            wasExporting = false; galleryViewModel.forceSync(); delay(400); onBack()
        }
    }

    if (mediaItem == null) return Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), Alignment.Center) { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) }
    if (showExportDialog) ExportSettingsDialog(onDismiss = { showExportDialog = false }) { res, fps -> editorViewModel.saveMedia(mediaItem, res.first, res.second, fps, (res.first * res.second * fps * 0.1f).toInt(), false); showExportDialog = false }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { ModernEditorTopBar(onBack = handleBack, onUndo = { editorViewModel.undo() }, onRedo = { editorViewModel.redo() }, onReset = { editorViewModel.resetEditor() }, onSave = { showExportDialog = true }, isExporting = isExporting) },
        bottomBar = {
            if (!isExporting) {
                Column(Modifier.background(MaterialTheme.colorScheme.surface).navigationBarsPadding()) {
                    AnimatedContent(targetState = activeTab, transitionSpec = { slideInVertically(tween(250)) { it } + fadeIn() togetherWith slideOutVertically(tween(250)) { it } + fadeOut() }, label = "tool_panel") { tab ->
                        Box(Modifier.fillMaxWidth().wrapContentHeight().padding(vertical = 12.dp), Alignment.Center) {
                            when (tab) {
                                EditorTab.ADJUST.name -> AdjustToolPanel(editState, mediaItem.isVideo) { editorViewModel.updateEditState(it) }
                                EditorTab.CROP.name -> CropToolPanel(editorViewModel, gridType) { gridType = it }
                                EditorTab.LUT.name -> FilterToolPanel(editorViewModel, selectedLutCategory, lutCategories) { selectedLutCategory = it }
                                EditorTab.FRAMES.name -> FramesToolPanel(editorViewModel)
                                EditorTab.TEXT.name -> TextToolPanel(editorViewModel)
                                EditorTab.STICKER.name -> StickerToolPanel(editorViewModel)
                                EditorTab.TRIM.name -> TrimToolPanel(editorViewModel, editState, videoDuration) { seekRequest = it }
                            }
                        }
                    }
                    AnimatedVisibility(selectedLayerId != null && !mediaItem.isVideo) {
                        LayerControlToolbar(
                            onDelete = { editorViewModel.removeText(selectedLayerId!!); editorViewModel.removeSticker(selectedLayerId!!); selectedLayerId = null },
                            onDuplicate = { editorViewModel.duplicateText(selectedLayerId!!); editorViewModel.duplicateSticker(selectedLayerId!!) },
                            onMoveUp = { editorViewModel.moveTextLayer(selectedLayerId!!, true); editorViewModel.moveStickerLayer(selectedLayerId!!, true) }
                        )
                    }
                    LazyRow(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(horizontal = 24.dp)) {
                        val tabs = if (mediaItem.isVideo) listOf(EditorTab.TRIM to Icons.Rounded.ContentCut, EditorTab.CROP to Icons.Rounded.Crop, EditorTab.ADJUST to Icons.Rounded.Tune) else listOf(EditorTab.ADJUST to Icons.Rounded.Tune, EditorTab.CROP to Icons.Rounded.Crop, EditorTab.LUT to Icons.Rounded.AutoAwesome, EditorTab.TEXT to Icons.Rounded.TextFields, EditorTab.STICKER to Icons.Rounded.EmojiEmotions, EditorTab.FRAMES to Icons.Rounded.Wallpaper)
                        items(tabs) { (tb, ic) -> TabItem(tb.name.lowercase().replaceFirstChar { it.uppercase() }, ic, activeTab == tb.name) { activeTab = tb.name; selectedLayerId = null } }
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background), Alignment.Center) {
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { selectedLayerId = null } }, Alignment.Center) {

                if (mediaItem.isVideo) {
                    EditorVideoPreview(
                        item = mediaItem,
                        state = editState,
                        isComp = splitComparePos != -1f,
                        seekRequest = seekRequest,
                        onDurationReady = { videoDuration = it },
                        onPos = { currentPlayerPos = it }
                    )
                }
                else {
                    EditorImagePreview(mediaItem, editState, previewBitmap, splitComparePos != -1f, isPreviewUpdating, isCropping = activeTab == EditorTab.CROP.name)
                }

                if (!mediaItem.isVideo) {
                    FramesOverlay(editState)

                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
                        StickerOverlay(editState, w, h, selectedLayerId,
                            onSel = { id -> selectedLayerId = id },
                            onDelete = { id -> editorViewModel.removeSticker(id); selectedLayerId = null },
                            up = { id, updater -> editorViewModel.updateSticker(id, updater) }
                        )
                    }

                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
                        TextOverlay(editState, w, h, selectedLayerId,
                            onSel = { id -> selectedLayerId = id },
                            onDelete = { id -> editorViewModel.removeText(id); selectedLayerId = null },
                            up = { id, updater -> editorViewModel.updateText(id, updater) }
                        )
                    }
                }

                if (activeTab == EditorTab.CROP.name) InteractiveCropOverlay(editState.cropRect, editState.aspectRatio, gridType) { editorViewModel.updateCropRect(it) }
                if (splitComparePos != -1f) SplitCompareOverlay(splitComparePos) { splitComparePos = it }
            }

            Box(Modifier.align(Alignment.BottomEnd).padding(16.dp).size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant.copy(0.85f)).clickable { splitComparePos = if (splitComparePos == -1f) 0.5f else -1f }, Alignment.Center) { Icon(if (splitComparePos != -1f) Icons.Rounded.Close else Icons.Rounded.Compare, "Compare", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (isExporting) SavingOverlay((fileOpState as? FileOperationState.Editing)?.progress ?: 0f)
    }
}

@Composable fun LayerControlToolbar(onDelete: () -> Unit, onDuplicate: () -> Unit, onMoveUp: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(0.5f)).padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onMoveUp) { Icon(Icons.Rounded.FlipToFront, "Bring Forward", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        IconButton(onClick = onDuplicate) { Icon(Icons.Rounded.ContentCopy, "Duplicate", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
    }
}

@Composable fun ModernEditorTopBar(onBack: () -> Unit, onUndo: () -> Unit, onRedo: () -> Unit, onReset: () -> Unit, onSave: () -> Unit, isExporting: Boolean) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        IconButton(onBack, enabled = !isExporting) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Cancel", tint = MaterialTheme.colorScheme.onBackground) }
        Row { IconButton(onUndo, enabled = !isExporting) { Icon(Icons.AutoMirrored.Rounded.Undo, "Undo", tint = MaterialTheme.colorScheme.onBackground) }; IconButton(onRedo, enabled = !isExporting) { Icon(Icons.AutoMirrored.Rounded.Redo, "Redo", tint = MaterialTheme.colorScheme.onBackground) } }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onReset, enabled = !isExporting) { Text("Reset", color = MaterialTheme.colorScheme.onBackground) }; Spacer(Modifier.width(8.dp))
            Button(onSave, enabled = !isExporting, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary), shape = RoundedCornerShape(24.dp), contentPadding = PaddingValues(horizontal = 20.dp)) { Text("Save", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary) }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable fun EditorVideoPreview(item: MediaItem, state: EditState, isComp: Boolean, seekRequest: Long?, onDurationReady: (Long) -> Unit, onPos: (Long) -> Unit) {
    val ctx = LocalContext.current; var ctrlVis by remember { mutableStateOf(true) }; var isPlay by remember { mutableStateOf(false) }; var dur by remember { mutableLongStateOf(0L) }; var sVal by remember { mutableFloatStateOf(0f) }
    val exo = remember { ExoPlayer.Builder(ctx).build().apply { repeatMode = Player.REPEAT_MODE_ONE; playWhenReady = true } }

    LaunchedEffect(item.uri) {
        exo.setMediaItem(ExoMediaItem.fromUri(item.uri))
        exo.prepare()
        exo.playWhenReady = true
        exo.play()
    }

    LaunchedEffect(seekRequest) {
        seekRequest?.let {
            exo.pause()
            exo.seekTo(it)
            onPos(it)
        }
    }

    LaunchedEffect(isComp, state) {
        exo.volume = if(state.isMuted) 0f else state.videoVolume / 100f
        if (isComp) {
            exo.setVideoEffects(emptyList())
        } else {
            val ef = mutableListOf<Effect>()
            state.cropRect?.let { r -> if(r.left > 0f || r.top > 0f || r.right < 1f || r.bottom < 1f) ef.add(Crop(r.left * 2f - 1f, r.right * 2f - 1f, 1f - r.bottom * 2f, 1f - r.top * 2f)) }
            if(state.rotationDegrees != 0f || state.flipHorizontal || state.flipVertical) ef.add(ScaleAndRotateTransformation.Builder().setRotationDegrees(state.rotationDegrees).setScale(if(state.flipHorizontal) -1f else 1f, if(state.flipVertical) -1f else 1f).build())
            exo.setVideoEffects(ef)
        }
    }

    DisposableEffect(Unit) {
        val l = object:Player.Listener{
            override fun onIsPlayingChanged(p: Boolean){ isPlay=p; if(p) ctrlVis=false }
            override fun onPlaybackStateChanged(s: Int){ if(s==Player.STATE_READY) { dur=exo.duration; onDurationReady(exo.duration) } }
            override fun onPositionDiscontinuity(o: Player.PositionInfo, n: Player.PositionInfo, r: Int){ onPos(n.positionMs) }
        }
        exo.addListener(l); onDispose { exo.removeListener(l); exo.release() }
    }

    LaunchedEffect(isPlay) { while(isPlay) { onPos(exo.currentPosition); if(dur>0L) sVal = exo.currentPosition.toFloat()/dur.toFloat(); delay(50) } }

    Box(Modifier.fillMaxSize().pointerInput(Unit){ detectTapGestures(onTap={ctrlVis=!ctrlVis}) }, Alignment.Center) {
        AndroidView({ PlayerView(ctx).apply { player=exo; useController=false; setShutterBackgroundColor(android.graphics.Color.TRANSPARENT) } }, Modifier.fillMaxSize())
        AnimatedVisibility(ctrlVis || !isPlay, enter=fadeIn(), exit=fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(0.2f)), Alignment.Center) {
                Surface(modifier = Modifier.size(64.dp).clip(CircleShape).clickable{ onPos(exo.currentPosition); if(isPlay) exo.pause() else exo.play() }.align(Alignment.Center), shape = CircleShape, color = Color.Black.copy(0.5f), border = BorderStroke(1.dp, Color.White.copy(0.2f))) { Icon(if(isPlay) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint=Color.White, modifier=Modifier.padding(16.dp)) }
            }
        }
    }
}

@Composable fun EditorImagePreview(item: MediaItem, state: EditState, bmp: Bitmap?, isComp: Boolean, isUpd: Boolean, isCropping: Boolean) {
    var sc by remember { mutableFloatStateOf(1f) }; var ox by remember { mutableFloatStateOf(0f) }; var oy by remember { mutableFloatStateOf(0f) }

    val mod = Modifier.fillMaxSize()
        .then(if (!isCropping) Modifier.pointerInput(Unit) {
            detectTransformGestures { _,p,z,_ -> sc=(sc*z).coerceIn(1f,5f); val mo=(sc-1f)*1000f; ox=(ox+p.x).coerceIn(-mo,mo); oy=(oy+p.y).coerceIn(-mo,mo) }
        }.pointerInput(Unit){ detectTapGestures(onDoubleTap={ sc=1f; ox=0f; oy=0f }) } else Modifier)
        .graphicsLayer(scaleX=sc*(if(!isComp&&state.flipHorizontal) -1f else 1f), scaleY=sc*(if(!isComp&&state.flipVertical) -1f else 1f), rotationZ=if(!isComp) state.rotationDegrees+state.straightenDegrees else 0f, translationX=ox, translationY=oy)

    Box(Modifier.fillMaxSize()) {
        if(!isComp&&bmp!=null) Image(bmp.asImageBitmap(), "Preview", mod, contentScale=ContentScale.Fit) else AsyncImage(ImageRequest.Builder(LocalContext.current).data(item.uri).build(), "Original", mod, contentScale=ContentScale.Fit)
        if(isUpd&&!isComp) CircularProgressIndicator(Modifier.align(Alignment.Center).size(24.dp), MaterialTheme.colorScheme.primary, 2.dp)
    }
}

@Composable fun TabItem(lbl: String, ic: ImageVector, sel: Boolean, onClk: () -> Unit) { val c = if(sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant; Column(Modifier.clickable(onClick=onClk).padding(8.dp), horizontalAlignment=Alignment.CenterHorizontally) { Icon(ic, lbl, tint=c, modifier=Modifier.size(24.dp)); Spacer(Modifier.height(4.dp)); Text(lbl, fontSize=11.sp, color=c, fontWeight=if(sel) FontWeight.Bold else FontWeight.Medium) } }

@Composable fun SavingOverlay(prg: Float) { Box(Modifier.fillMaxSize().background(Color.Black.copy(0.8f)).pointerInput(Unit){}, Alignment.Center) { Column(horizontalAlignment=Alignment.CenterHorizontally) { CircularProgressIndicator({ prg }, Modifier.size(64.dp), color=MaterialTheme.colorScheme.primary, strokeWidth = 6.dp); Spacer(Modifier.height(16.dp)); Text("Processing...", color=Color.White, fontWeight=FontWeight.Bold, fontSize=18.sp); Text("${(prg*100).toInt()}%", color=Color.LightGray, fontSize=14.sp) } } }

@Composable fun InteractiveCropOverlay(cCrop: RectF?, cAsp: Float?, grid: Int, onCrop: (RectF) -> Unit) {
    var crop by remember(cCrop) { mutableStateOf(cCrop?.let { RectF(it) } ?: RectF(0.1f, 0.1f, 0.9f, 0.9f)) }; val d = LocalDensity.current; val hap = LocalHapticFeedback.current; val ho=with(d){16.dp.toPx()}; val hi=with(d){10.dp.toPx()}; val eh=with(d){8.dp.toPx()}; val gw=with(d){1.5.dp.toPx()}; val sl=with(d){24.dp.toPx()}; var ah by remember { mutableIntStateOf(-1) }
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize().pointerInput(Unit) { detectDragGestures(onDragStart={ off-> val nx=off.x/size.width; val ny=off.y/size.height; val sx=sl/size.width; val sy=sl/size.height; val l=crop.left; val t=crop.top; val r=crop.right; val b=crop.bottom; ah = when { abs(nx-l)<sx&&abs(ny-t)<sy->0; abs(nx-r)<sx&&abs(ny-t)<sy->1; abs(nx-l)<sx&&abs(ny-b)<sy->2; abs(nx-r)<sx&&abs(ny-b)<sy->3; abs(nx-l)<sx&&ny in (t-sy)..(b+sy)->4; abs(ny-t)<sy&&nx in (l-sx)..(r+sx)->5; abs(nx-r)<sx&&ny in (t-sy)..(b+sy)->6; abs(ny-b)<sy&&nx in (l-sx)..(r+sx)->7; nx in l..r&&ny in t..b->8; else->-1 }; if(ah!=-1) hap.performHapticFeedback(HapticFeedbackType.TextHandleMove) }, onDragEnd={ ah=-1 }, onDragCancel={ ah=-1 }) { ch, dr -> ch.consume(); if(ah==-1) return@detectDragGestures; val dx=dr.x/size.width; val dy=dr.y/size.height; val nr=RectF(crop); if(cAsp!=null && ah in 0..7) { val rel=cAsp/(size.width/size.height); if(ah in 0..3) { val dd=max(abs(dx),abs(dy*rel)); val sx=if(ah==0||ah==2) -1 else 1; val sy=if(ah<=1) -1 else 1; val rx=dd*sx; val ry=(dd/rel)*sy; if(!((nr.left+rx<0f&&sx<0)||(nr.right+rx>1f&&sx>0))&&!((nr.top+ry<0f&&sy<0)||(nr.bottom+ry>1f&&sy>0))) { when(ah) { 0->{nr.left=(nr.left+rx).coerceIn(0f,nr.right-0.05f); nr.top=(nr.top+ry).coerceIn(0f,nr.bottom-0.05f)}; 1->{nr.right=(nr.right+rx).coerceIn(nr.left+0.05f,1f); nr.top=(nr.top+ry).coerceIn(0f,nr.bottom-0.05f)}; 2->{nr.left=(nr.left+rx).coerceIn(0f,nr.right-0.05f); nr.bottom=(nr.bottom+ry).coerceIn(nr.top+0.05f,1f)}; 3->{nr.right=(nr.right+rx).coerceIn(nr.left+0.05f,1f); nr.bottom=(nr.bottom+ry).coerceIn(nr.top+0.05f,1f)} } } } else { val cx=(crop.left+crop.right)/2f; val cy=(crop.top+crop.bottom)/2f; var nw=crop.width(); var nh=crop.height(); when(ah) { 4,6->{nw=if(ah==4) crop.right-(crop.left+dx).coerceIn(0f,crop.right-0.05f) else (crop.right+dx).coerceIn(crop.left+0.05f,1f)-crop.left; nh=nw/rel}; 5,7->{nh=if(ah==5) crop.bottom-(crop.top+dy).coerceIn(0f,crop.bottom-0.05f) else (crop.bottom+dy).coerceIn(crop.top+0.05f,1f)-crop.top; nw=nh*rel} }; if(cx-nw/2f>=0f&&cx+nw/2f<=1f&&cy-nh/2f>=0f&&cy+nh/2f<=1f) nr.set(cx-nw/2f, cy-nh/2f, cx+nw/2f, cy+nh/2f) } } else { when(ah) { 0->{nr.left=(nr.left+dx).coerceIn(0f,nr.right-0.05f); nr.top=(nr.top+dy).coerceIn(0f,nr.bottom-0.05f)}; 1->{nr.right=(nr.right+dx).coerceIn(nr.left+0.05f,1f); nr.top=(nr.top+dy).coerceIn(0f,nr.bottom-0.05f)}; 2->{nr.left=(nr.left+dx).coerceIn(0f,nr.right-0.05f); nr.bottom=(nr.bottom+dy).coerceIn(nr.top+0.05f,1f)}; 3->{nr.right=(nr.right+dx).coerceIn(nr.left+0.05f,1f); nr.bottom=(nr.bottom+dy).coerceIn(nr.top+0.05f,1f)}; 4->nr.left=(nr.left+dx).coerceIn(0f,nr.right-0.05f); 5->nr.top=(nr.top+dy).coerceIn(0f,nr.bottom-0.05f); 6->nr.right=(nr.right+dx).coerceIn(nr.left+0.05f,1f); 7->nr.bottom=(nr.bottom+dy).coerceIn(nr.top+0.05f,1f); 8->{val w=nr.width(); val h=nr.height(); val nl=(nr.left+dx).coerceIn(0f,1f-w); val nt=(nr.top+dy).coerceIn(0f,1f-h); nr.set(nl,nt,nl+w,nt+h)} } }; crop=nr; onCrop(nr) } }) { val rw=size.width*crop.width(); val rh=size.height*crop.height(); val l=size.width*crop.left; val t=size.height*crop.top; val dim=Color.Black.copy(0.75f); val gc=Color.White.copy(0.6f); drawRect(dim, Offset.Zero, Size(size.width,t)); drawRect(dim, Offset(0f,t+rh), Size(size.width,size.height-(t+rh))); drawRect(dim, Offset(0f,t), Size(l,rh)); drawRect(dim, Offset(l+rw,t), Size(size.width-(l+rw),rh)); when(grid) { 0->{listOf(l+rw/3f to t, l+rw/3f*2f to t).forEach { drawLine(gc, Offset(it.first,it.second), Offset(it.first,it.second+rh), gw) }; listOf(l to t+rh/3f, l to t+rh/3f*2f).forEach { drawLine(gc, Offset(it.first,it.second), Offset(it.first+rw,it.second), gw) }}; 1->{listOf(l+rw*.25f to t, l+rw*.5f to t, l+rw*.75f to t).forEach { drawLine(gc, Offset(it.first,it.second), Offset(it.first,it.second+rh), gw) }; listOf(l to t+rh*.25f, l to t+rh*.5f, l to t+rh*.75f).forEach { drawLine(gc, Offset(it.first,it.second), Offset(it.first+rw,it.second), gw) }}; 2->{listOf(l+rw*.382f to t, l+rw*.618f to t).forEach { drawLine(gc, Offset(it.first,it.second), Offset(it.first,it.second+rh), gw) }; listOf(l to t+rh*.382f, l to t+rh*.618f).forEach { drawLine(gc, Offset(it.first,it.second), Offset(it.first+rw,it.second), gw) }}; 3->{drawLine(gc, Offset(l,t), Offset(l+rw,t+rh), gw); drawLine(gc, Offset(l+rw,t), Offset(l,t+rh), gw)} }; drawRect(Color.White, Offset(l,t), Size(rw,rh), style=Stroke(3.dp.toPx())); drawLine(Color.White.copy(0.6f), Offset(l+rw/2f-16f, t+rh/2f), Offset(l+rw/2f+16f, t+rh/2f), gw); drawLine(Color.White.copy(0.6f), Offset(l+rw/2f, t+rh/2f-16f), Offset(l+rw/2f, t+rh/2f+16f), gw); listOf(Offset(l,t+rh/2f), Offset(l+rw/2f,t), Offset(l+rw,t+rh/2f), Offset(l+rw/2f,t+rh)).forEachIndexed { i,o -> val act=ah==i+4; drawCircle(if(act) Color(0xFF4CAF50) else Color.White, if(act) eh*1.3f else eh, o); drawCircle(Color.Black, eh*0.6f, o) }; listOf(Offset(l,t), Offset(l+rw,t), Offset(l,t+rh), Offset(l+rw,t+rh)).forEachIndexed { i,o -> drawCircle(if(ah==i) Color(0xFF4CAF50) else Color.White, if(ah==i) ho*1.2f else ho, o); drawCircle(Color.Black, hi, o) } }
        if (cAsp != null) Box(Modifier.fillMaxSize().padding(16.dp), Alignment.TopCenter) { Text(when(cAsp){1f->"1:1";4f/3f->"4:3";3f/4f->"3:4";16f/9f->"16:9";9f/16f->"9:16";else->""}, color=Color.White, fontSize=12.sp, modifier=Modifier.background(Color.Black.copy(0.5f), RoundedCornerShape(4.dp)).padding(horizontal=8.dp, vertical=4.dp)) }
    }
}

@Composable fun AdjustToolPanel(s: EditState, isVideo: Boolean, up: ((EditState) -> EditState) -> Unit) {
    val hap = LocalHapticFeedback.current
    val baseTools = if (isVideo) emptyList() else listOf(
        AdjustTool("Brightness", Icons.Rounded.BrightnessMedium, s.brightness, -1f..1f) { v -> up { it.copy(brightness = v) } },
        AdjustTool("Contrast", Icons.Rounded.Contrast, s.contrast, 0f..2f) { v -> up { it.copy(contrast = v) } },
        AdjustTool("Saturation", Icons.Rounded.ColorLens, s.saturation, 0f..2f) { v -> up { it.copy(saturation = v) } },
        AdjustTool("Exposure", Icons.Rounded.Exposure, s.exposure, -2f..2f) { v -> up { it.copy(exposure = v) } }
    )

    val tools = if (isVideo) {
        listOf(AdjustTool("Volume", Icons.Rounded.VolumeUp, s.videoVolume / 100f, 0f..1f) { v -> up { it.copy(videoVolume = (v * 100).toFloat(), isMuted = v == 0f) } })
    } else {
        baseTools + listOf(
            AdjustTool("Highlights", Icons.Rounded.Highlight, s.highlights, -1f..1f) { v -> up { it.copy(highlights = v) } },
            AdjustTool("Shadows", Icons.Rounded.Tonality, s.shadows, -1f..1f) { v -> up { it.copy(shadows = v) } },
            AdjustTool("Temp", Icons.Rounded.Thermostat, s.temperature, -1f..1f) { v -> up { it.copy(temperature = v) } },
            AdjustTool("Tint", Icons.Rounded.Palette, s.tint, -1f..1f) { v -> up { it.copy(tint = v) } }
        )
    }

    var sel by rememberSaveable { mutableIntStateOf(0) }; val a = tools[sel.coerceIn(0, tools.lastIndex)]; val dv = when(a.name) { "Contrast", "Saturation" -> ((a.value - 1f) * 100f).toInt(); "Exposure" -> ((a.value / 2f) * 100f).toInt(); else -> (a.value * 100f).toInt() }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text(a.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface); Text(if (dv > 0) "+$dv" else "$dv", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp) }
        Slider(a.value, a.onValueChange, Modifier.fillMaxWidth().padding(vertical = 8.dp), valueRange = a.range, colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary, inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant))
        LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) { items(tools.size) { i -> val tl = tools[i]; val isSel = i == sel; Column(Modifier.pointerInput(Unit) { detectTapGestures(onTap = { sel = i; hap.performHapticFeedback(HapticFeedbackType.TextHandleMove) }, onDoubleTap = { sel = i; tl.onValueChange(if (tl.name in listOf("Contrast", "Saturation", "Volume")) 1f else 0f); hap.performHapticFeedback(HapticFeedbackType.LongPress) }) }.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) { Box(Modifier.size(48.dp).clip(CircleShape).background(if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant), Alignment.Center) { Icon(tl.icon, tl.name, tint = if (isSel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant) }; Spacer(Modifier.height(6.dp)); Text(tl.name, fontSize = 11.sp, color = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal) } } }
    }
}

@Composable fun FilterToolPanel(vm: EditorViewModel, selCat: String?, cats: List<String>, onCatSelect: (String) -> Unit) {
    val luts by vm.lutItems.collectAsState(); val s by vm.currentEditState.collectAsState(); val catLuts = remember(luts, selCat) { luts.filter { it.category == selCat } }
    Column(Modifier.fillMaxWidth()) {
        AnimatedVisibility(s.filterId != null) { Slider(s.lutIntensity, { vm.setLutIntensity(it) }, valueRange = 0f..1f, modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 8.dp), colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary)) }
        ScrollableTabRow(cats.indexOf(selCat).coerceAtLeast(0), containerColor = Color.Transparent, divider = {}, indicator = {}, edgePadding = 16.dp) { cats.forEach { c -> Tab(selCat == c, { onCatSelect(c) }, text = { Text(c, color = if (selCat == c) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = if (selCat == c) FontWeight.Bold else FontWeight.Normal) }) } }
        LazyRow(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            item { Column(Modifier.width(80.dp).clickable { vm.clearLut() }, horizontalAlignment = Alignment.CenterHorizontally) { Box(Modifier.size(80.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).border(if (s.filterId == null) 2.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)), Alignment.Center) { Icon(Icons.Rounded.Block, "None", tint = MaterialTheme.colorScheme.onSurfaceVariant) }; Spacer(Modifier.height(8.dp)); Text("None", maxLines = 1, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface) } }
            items(catLuts) { l -> Column(Modifier.width(80.dp).clickable { vm.applyLut(l) }, horizontalAlignment = Alignment.CenterHorizontally) { if (l.thumbnail != null) AsyncImage(l.thumbnail, l.name, Modifier.size(80.dp).clip(RoundedCornerShape(16.dp)).border(if (s.filterId == l.name) 2.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)), contentScale = ContentScale.Crop) else Box(Modifier.size(80.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant), Alignment.Center) { Text("LUT", color = MaterialTheme.colorScheme.onSurfaceVariant) }; Spacer(Modifier.height(8.dp)); Text(l.name, maxLines = 1, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface, fontWeight = if (s.filterId == l.name) FontWeight.Bold else FontWeight.Normal) } }
        }
    }
}

@Composable fun FramesToolPanel(vm: EditorViewModel) {
    val s by vm.currentEditState.collectAsState(); val frames by vm.frameItems.collectAsState(); val activeFrame = s.frames.firstOrNull()?.assetPath
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        AnimatedVisibility(activeFrame != null) { val opacity = s.frames.firstOrNull()?.opacity ?: 1f; Slider(value = opacity, onValueChange = { op -> vm.updateEditState { it.copy(frames = it.frames.map { f -> f.copy(opacity = op) }) } }, valueRange = 0f..1f, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) }
        LazyRow(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            item { FrameItem("None", null, activeFrame == null) { vm.updateEditState { it.copy(frames = emptyList()) } } }
            items(frames) { f ->
                FrameItem(f.name, f.assetPath, activeFrame == f.assetPath) {
                    vm.updateEditState {
                        it.copy(frames = listOf(FrameLayer(assetPath = f.assetPath)))
                    }
                }
            }
        }
    }
}

@Composable fun FrameItem(name: String, path: String?, isSelected: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(80.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(80.dp).clip(RoundedCornerShape(16.dp)).background(if (isSelected) MaterialTheme.colorScheme.primary.copy(0.2f) else MaterialTheme.colorScheme.surfaceVariant).border(if (isSelected) 2.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)), Alignment.Center) { if (path == null) Icon(Icons.Rounded.Block, "None", tint = MaterialTheme.colorScheme.onSurfaceVariant) else AsyncImage(model = ImageRequest.Builder(LocalContext.current).data("file:///android_asset/$path").build(), contentDescription = name, modifier = Modifier.padding(12.dp)) }
        Spacer(Modifier.height(8.dp)); Text(name, maxLines = 1, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable fun CropToolPanel(vm: EditorViewModel, grid: Int, onGrid: (Int) -> Unit) {
    val asp by vm.aspectRatio.collectAsState(); val s by vm.currentEditState.collectAsState(); val rs: List<Pair<String, Float?>> = listOf("Free" to null, "1:1" to 1f, "4:3" to 4f/3f, "3:4" to 3f/4f, "16:9" to 16f/9f, "9:16" to 9f/16f)
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { IconButton({ vm.rotateLeft() }) { Icon(Icons.Rounded.RotateLeft, "Rot L", tint = MaterialTheme.colorScheme.onSurface) }; IconButton({ vm.toggleFlipHorizontal() }) { Icon(Icons.Rounded.Flip, "Flip H", tint = MaterialTheme.colorScheme.onSurface) }; IconButton({ vm.toggleFlipVertical() }) { Icon(Icons.Rounded.Flip, "Flip V", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.rotate(90f)) }; IconButton({ onGrid((grid + 1) % 4) }) { Icon(Icons.Rounded.GridOn, "Grid", tint = MaterialTheme.colorScheme.onSurface) }; IconButton({ vm.resetCrop() }) { Icon(Icons.Rounded.SettingsBackupRestore, "Reset", tint = MaterialTheme.colorScheme.error) } }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.ScreenRotation, "Straighten", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(16.dp)); Slider(s.straightenDegrees, { vm.updateEditState { e -> e.copy(straightenDegrees = it) } }, Modifier.weight(1f), valueRange = -45f..45f, colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary, inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant)); Text("${s.straightenDegrees.toInt()}°", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, modifier = Modifier.width(40.dp).padding(start = 8.dp)) }
        Spacer(Modifier.height(12.dp))
        LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = 24.dp)) { items(rs) { (l, r) -> FilterChip(selected = asp == r, onClick = { vm.setAspectRatio(r) }, label = { Text(l) }, shape = RoundedCornerShape(16.dp)) } }
    }
}

@Composable fun TextToolPanel(vm: EditorViewModel) {
    var t by remember { mutableStateOf("") }; val cs = listOf(Color.White, Color.Black, Color.Red, Color.Yellow, Color.Green, Color.Blue, Color.Cyan, Color.Magenta); var sc by remember { mutableStateOf(Color.White) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        OutlinedTextField(t, { t = it }, Modifier.fillMaxWidth().padding(vertical = 8.dp), placeholder = { Text("Type something...") }, singleLine = true, shape = RoundedCornerShape(16.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant))
        LazyRow(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) { items(cs) { c -> Box(Modifier.size(32.dp).clip(CircleShape).background(c).border(2.dp, if (sc == c) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape).clickable { sc = c }) } }
        Button({ if (t.isNotBlank()) { vm.addText(t, sc.toArgb(), 50f); t = "" } }, Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(24.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) { Text("Add Text", fontWeight = FontWeight.Bold) }
    }
}

@Composable fun StickerToolPanel(vm: EditorViewModel) {
    val sts by vm.stickerItems.collectAsState(); val cats = remember(sts) { sts.map { it.category }.distinct() }; var selCat by remember { mutableStateOf<String?>(null) }; val ctx = LocalContext.current; val il = remember { ImageLoader.Builder(ctx).components { add(SvgDecoder.Factory()) }.build() }
    LaunchedEffect(cats) { if (selCat == null && cats.isNotEmpty()) selCat = cats.first() }; val cSts = remember(sts, selCat) { sts.filter { it.category == selCat } }
    Column(Modifier.fillMaxWidth()) {
        ScrollableTabRow(cats.indexOf(selCat).coerceAtLeast(0), containerColor = Color.Transparent, divider = {}, indicator = {}, edgePadding = 16.dp) { cats.forEach { c -> Tab(selCat == c, { selCat = c }, text = { Text(c, color = if (selCat == c) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = if (selCat == c) FontWeight.Bold else FontWeight.Normal) }) } }
        LazyVerticalGrid(columns = GridCells.Adaptive(80.dp), modifier = Modifier.fillMaxWidth().height(160.dp).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { items(cSts) { st -> AsyncImage(ImageRequest.Builder(ctx).data("file:///android_asset/${st.assetPath}").crossfade(true).build(), st.name, il, Modifier.size(80.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(0.3f)).clickable { vm.addSticker(st.assetPath) }.padding(12.dp)) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun TrimToolPanel(vm: EditorViewModel, s: EditState, exoDuration: Long, onSeekReq: (Long) -> Unit) {
    var sliderRange by remember(s.trimStartMs, s.trimEndMs, exoDuration) {
        val start = if (exoDuration > 0) (s.trimStartMs.toFloat() / exoDuration).coerceIn(0f, 1f) else 0f
        val end = if (exoDuration > 0 && s.trimEndMs > 0) (s.trimEndMs.toFloat() / exoDuration).coerceIn(0f, 1f) else 1f
        mutableStateOf(start..end)
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) {
        Box(Modifier.fillMaxWidth().height(48.dp)) {
            Row(Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                repeat(8) {
                    Box(Modifier.weight(1f).fillMaxHeight().border(0.5.dp, MaterialTheme.colorScheme.surface))
                }
            }
            RangeSlider(
                value = sliderRange,
                onValueChange = { range ->
                    val oldRange = sliderRange
                    val movingStart = abs(range.start - oldRange.start) > abs(range.endInclusive - oldRange.endInclusive)

                    sliderRange = range
                    val startMs = (range.start * exoDuration).toLong()
                    val endMs = (range.endInclusive * exoDuration).toLong()
                    vm.setTrimRange(startMs, endMs)

                    if (movingStart) {
                        onSeekReq(startMs)
                    } else {
                        onSeekReq(endMs)
                    }
                },
                valueRange = 0f..1f,
                modifier = Modifier.fillMaxSize(),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Black.copy(alpha = 0.6f)
                )
            )
        }
    }
}

@Composable fun ExportSettingsDialog(onDismiss: () -> Unit, onExport: (Pair<Int, Int>, Int) -> Unit) {
    var r by remember { mutableStateOf(Pair(1920, 1080)) }; var f by remember { mutableIntStateOf(30) }; val rs = listOf(Pair(1280, 720) to "720p", Pair(1920, 1080) to "1080p", Pair(3840, 2160) to "4K"); val fs = listOf(24, 30, 60)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Export Settings", fontWeight = FontWeight.Bold) }, text = { Column(Modifier.fillMaxWidth()) { Text("Resolution", fontWeight = FontWeight.SemiBold, fontSize = 14.sp); LazyRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(rs) { (rv, l) -> FilterChip(r == rv, { r = rv }, label = { Text(l) }) } }; Text("Frame Rate", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp)); LazyRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(fs) { fv -> FilterChip(f == fv, { f = fv }, label = { Text("${fv}fps") }) } } } }, confirmButton = { Button({ onExport(r, f) }) { Text("Export") } }, dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

@Composable fun FramesOverlay(s: EditState) {
    val ctx = LocalContext.current
    s.frames.firstOrNull()?.let { frame -> AsyncImage(model = ImageRequest.Builder(ctx).data("file:///android_asset/${frame.assetPath}").build(), contentDescription = "Frame", modifier = Modifier.fillMaxSize().alpha(frame.opacity), contentScale = ContentScale.Crop) }
}

@Composable fun StickerOverlay(s: EditState, parentWidth: Float, parentHeight: Float, selId: String?, onSel: (String) -> Unit, onDelete: (String) -> Unit, up: (String, (StickerLayer) -> StickerLayer) -> Unit) {
    val ctx = LocalContext.current
    s.stickers.filter { it.isVisible }.forEach { st ->
        val isSel = st.id == selId; val b = 150f * st.scale; val posX = st.x * parentWidth; val posY = st.y * parentHeight

        Box(
            Modifier
                .offset { IntOffset((posX - b / 2).toInt(), (posY - b / 2).toInt()) }
                .size(with(LocalDensity.current) { b.toDp() })
                .graphicsLayer { rotationZ = st.rotation }
                .pointerInput(st.id) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        onSel(st.id)
                        up(st.id) { it.copy(x = (it.x + (drag.x / parentWidth)).coerceIn(0f, 1f), y = (it.y + (drag.y / parentHeight)).coerceIn(0f, 1f)) }
                    }
                }
                .pointerInput(st.id) { detectTapGestures { onSel(st.id) } }
        ) {
            AsyncImage(model = ImageRequest.Builder(ctx).data("file:///android_asset/${st.assetPath}").build(), contentDescription = "Sticker", modifier = Modifier.fillMaxSize())
            if (isSel) {
                Box(Modifier.matchParentSize().border(2.dp, Color.White))
                IconButton(onClick = { onDelete(st.id) }, modifier = Modifier.align(Alignment.TopStart).offset(x = (-12).dp, y = (-12).dp).size(24.dp).background(Color.Red, CircleShape)) {
                    Icon(Icons.Rounded.Close, "Delete", tint = Color.White, modifier = Modifier.size(16.dp))
                }
                Box(Modifier.align(Alignment.BottomEnd).offset(x = 12.dp, y = 12.dp).size(24.dp).background(Color.Blue, CircleShape)
                    .pointerInput(st.id) {
                        detectDragGestures { _, drag ->
                            val distance = drag.getDistance()
                            val isScalingUp = (drag.x + drag.y) > 0
                            val delta = if (isScalingUp) distance / 300f else -distance / 300f
                            up(st.id) { it.copy(scale = (it.scale + delta).coerceIn(0.2f, 5f)) }
                        }
                    }
                )
            }
        }
    }
}

@Composable fun TextOverlay(s: EditState, parentWidth: Float, parentHeight: Float, selId: String?, onSel: (String) -> Unit, onDelete: (String) -> Unit, up: (String, (TextLayer) -> TextLayer) -> Unit) {
    s.textLayers.filter { it.isVisible }.forEach { t ->
        val isSel = t.id == selId; val posX = t.x * parentWidth; val posY = t.y * parentHeight
        var measuredWidth by remember { mutableIntStateOf(0) }
        var measuredHeight by remember { mutableIntStateOf(0) }

        Box(
            Modifier
                .offset { IntOffset((posX - measuredWidth / 2f).toInt(), (posY - measuredHeight / 2f).toInt()) }
                .onGloballyPositioned {
                    measuredWidth = it.size.width
                    measuredHeight = it.size.height
                }
                .graphicsLayer { rotationZ = t.rotation }
                .pointerInput(t.id) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        onSel(t.id)
                        up(t.id) { it.copy(x = (it.x + (drag.x / parentWidth)).coerceIn(0f, 1f), y = (it.y + (drag.y / parentHeight)).coerceIn(0f, 1f)) }
                    }
                }
                .pointerInput(t.id) { detectTapGestures { onSel(t.id) } }
        ) {
            Text(text = t.text, color = Color(t.color).copy(t.opacity), fontSize = t.size.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
            if (isSel) {
                Box(Modifier.matchParentSize().border(2.dp, Color.White))
                IconButton(onClick = { onDelete(t.id) }, modifier = Modifier.align(Alignment.TopStart).offset(x = (-12).dp, y = (-12).dp).size(24.dp).background(Color.Red, CircleShape)) {
                    Icon(Icons.Rounded.Close, "Delete", tint = Color.White, modifier = Modifier.size(16.dp))
                }
                Box(Modifier.align(Alignment.BottomEnd).offset(x = 12.dp, y = 12.dp).size(24.dp).background(Color.Blue, CircleShape)
                    .pointerInput(t.id) {
                        detectDragGestures { _, drag ->
                            val distance = drag.getDistance()
                            val isScalingUp = (drag.x + drag.y) > 0
                            val delta = if (isScalingUp) distance / 5f else -distance / 5f
                            up(t.id) { it.copy(size = (it.size + delta).coerceIn(10f, 200f)) }
                        }
                    }
                )
            }
        }
    }
}

@Composable fun SplitCompareOverlay(pos: Float, onPos: (Float) -> Unit) {
    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectHorizontalDragGestures { change, dragAmount -> change.consume(); onPos((pos + (dragAmount / size.width.toFloat())).coerceIn(0f, 1f)) } }) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val lx = pos * size.width
            drawLine(color = Color.White, start = Offset(lx, 0f), end = Offset(lx, size.height), strokeWidth = 6.dp.toPx())
            drawCircle(color = Color.White, radius = 24.dp.toPx(), center = Offset(lx, size.height / 2f))
            drawCircle(color = Color.Black, radius = 16.dp.toPx(), center = Offset(lx, size.height / 2f))
        }
    }
}