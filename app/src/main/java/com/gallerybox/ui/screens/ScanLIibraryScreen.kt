@file:Suppress("unused")
package com.gallerybox.ui.screens

import android.text.format.Formatter
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gallerybox.viewmodel.DocumentViewModel
import com.gallerybox.viewmodel.GalleryViewModel
import com.gallerybox.viewmodel.MusicViewModel
import kotlinx.coroutines.launch // <-- ADDED IMPORT

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanLibraryScreen(
    onBack: () -> Unit, onLockApp: () -> Unit = {},
    galleryViewModel: GalleryViewModel = hiltViewModel(),
    musicViewModel: MusicViewModel = hiltViewModel(),
    documentViewModel: DocumentViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope() // <-- ADDED COROUTINE SCOPE

    // 1. Clean State Collection
    val isGalleryBusy by galleryViewModel.isBusy.collectAsState()
    val isDocsBusy by documentViewModel.isListLoading.collectAsState()
    val isScanning = isGalleryBusy || isDocsBusy

    val mediaList by galleryViewModel.media.collectAsState()
    val docList by documentViewModel.documents.collectAsState()
    val allSongs by musicViewModel.allAudioTracks.collectAsState()

    // 2. Safe Derived States
    val photoCount = remember(mediaList) { mediaList.count { !it.isVideo } }
    val videoCount = remember(mediaList) { mediaList.count { it.isVideo } }
    val docCount = remember(docList) { docList.size }
    val songCount = remember(allSongs) { allSongs.size }

    val totalSize = remember(mediaList, docList) {
        Formatter.formatShortFileSize(context, mediaList.sumOf { it.size } + docList.sumOf { it.size })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Library Scanner", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = onLockApp) { Icon(Icons.Outlined.Lock, "Lock") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(32.dp))
            ScannerVisual(isScanning)
            Spacer(Modifier.height(40.dp))
            ScannerStatusText(isScanning, totalSize)
            Spacer(Modifier.height(48.dp))
            LibraryStatsGrid(photoCount, videoCount, songCount, docCount)
            Spacer(Modifier.weight(1f))
            RefreshButton(isScanning) {
                // <-- WRAPPED SUSPEND CALL IN COROUTINE LAUNCH
                coroutineScope.launch {
                    galleryViewModel.forceSync()
                }
                documentViewModel.loadAllDocuments()
                musicViewModel.loadAllAudioTracks()
            }
        }
    }
}

// --- OPTIMIZED COMPONENTS ---

@Composable
fun ScannerVisual(isScanning: Boolean) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(200.dp)) {
        var rotation = 0f
        if (isScanning) {
            val transition = rememberInfiniteTransition(label = "scanner")
            rotation = transition.animateFloat(0f, 360f, infiniteRepeatable(tween(2000, easing = LinearEasing)), "rot").value
            val s1 by transition.animateFloat(0.8f, 1.4f, infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Restart), "s1")
            val a1 by transition.animateFloat(0.6f, 0f, infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Restart), "a1")

            Box(Modifier.size(140.dp).scale(s1).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = a1)))
        }
        Box(Modifier.size(120.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(if (isScanning) Icons.Default.Refresh else Icons.Rounded.Storage, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp).rotate(rotation))
        }
    }
}

@Composable
fun ScannerStatusText(isScanning: Boolean, totalSize: String) {
    Crossfade(targetState = isScanning, animationSpec = tween(300), label = "Status") { scanning ->
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (scanning) "Scanning Photos, Videos and Documents..." else "Library Up to Date", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(if (scanning) "Searching for new files." else "All files are indexed.\nTotal Vault Size: $totalSize", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun LibraryStatsGrid(photos: Int, videos: Int, audio: Int, docs: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(16.dp)) {
            StatCard(Modifier.weight(1f), photos.toString(), "Photos", Icons.Rounded.Photo)
            StatCard(Modifier.weight(1f), videos.toString(), "Videos", Icons.Rounded.Videocam)
        }
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(16.dp)) {
            StatCard(Modifier.weight(1f), audio.toString(), "Audio", Icons.Rounded.MusicNote)
            StatCard(Modifier.weight(1f), docs.toString(), "Docs", Icons.Rounded.Description)
        }
    }
}

@Composable
fun RefreshButton(isScanning: Boolean, onClick: () -> Unit) {
    Button(onClick = { if (!isScanning) onClick() }, enabled = !isScanning, modifier = Modifier.fillMaxWidth().height(56.dp).padding(bottom = 24.dp), shape = RoundedCornerShape(16.dp)) {
        Crossfade(targetState = isScanning, label = "BtnContent") { scanning ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (scanning) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp) else Icon(Icons.Default.Refresh, null)
                Spacer(Modifier.width(12.dp))
                Text(if (scanning) "Scanning..." else "Refresh Library", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun StatCard(modifier: Modifier = Modifier, count: String, label: String, icon: ImageVector) {
    val upperLabel = remember(label) { label.uppercase() }

    Surface(modifier.height(100.dp), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 0.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Text(count, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Text(upperLabel, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.sp)
        }
    }
}