@file:Suppress("unused")

package com.gallerybox.ui.screens

import android.content.Context
import android.net.Uri
import android.text.format.Formatter
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanLibraryScreen(
    onBack: () -> Unit,
    onLockApp: () -> Unit = {},
    galleryViewModel: GalleryViewModel = hiltViewModel(),
    musicViewModel: MusicViewModel = hiltViewModel(),
    documentViewModel: DocumentViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Access SharedPreferences to fetch the SAF folder URI
    val prefs = remember { context.getSharedPreferences("DocPrefs", Context.MODE_PRIVATE) }

    val isGalleryBusy by galleryViewModel.isBusy.collectAsState()
    val isDocsBusy by documentViewModel.isListLoading.collectAsState()
    val isScanning = isGalleryBusy || isDocsBusy

    val mediaList by galleryViewModel.media.collectAsState()
    val docList by documentViewModel.documents.collectAsState()
    val allSongs by musicViewModel.allAudioTracks.collectAsState()

    val photoCount = remember(mediaList) {
        mediaList.count { !it.isVideo }
    }

    val videoCount = remember(mediaList) {
        mediaList.count { it.isVideo }
    }

    val docCount = remember(docList) {
        docList.size
    }

    val songCount = remember(allSongs) {
        allSongs.size
    }

    val totalSize = remember(mediaList, docList) {
        Formatter.formatShortFileSize(
            context,
            mediaList.sumOf { it.size } + docList.sumOf { it.size }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Library Scanner",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onLockApp) {
                        Icon(
                            imageVector = Icons.Outlined.Lock,
                            contentDescription = "Lock"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            ScannerVisual(isScanning = isScanning)

            Spacer(modifier = Modifier.height(40.dp))

            ScannerStatusText(
                isScanning = isScanning,
                totalSize = totalSize
            )

            Spacer(modifier = Modifier.height(48.dp))

            LibraryStatsGrid(
                photos = photoCount,
                videos = videoCount,
                audio = songCount,
                docs = docCount
            )

            Spacer(modifier = Modifier.weight(1f))

            RefreshButton(isScanning = isScanning) {
                coroutineScope.launch {
                    galleryViewModel.forceSync()
                }

                // Fetch URI and pass it to the updated document scanner
                val savedUriStr = prefs.getString("document_tree_uri", null)
                if (savedUriStr != null) {
                    documentViewModel.loadAllDocuments(Uri.parse(savedUriStr))
                }

                musicViewModel.loadAllAudioTracks()
            }
        }
    }
}

@Composable
fun ScannerVisual(isScanning: Boolean) {
    val rotationAnim = remember { Animatable(0f) }
    val scaleAnim = remember { Animatable(0.8f) }
    val alphaAnim = remember { Animatable(0f) }

    LaunchedEffect(isScanning) {
        if (isScanning) {
            launch {
                while (isActive) {
                    rotationAnim.animateTo(
                        targetValue = rotationAnim.value + 360f,
                        animationSpec = tween(2000, easing = LinearEasing)
                    )
                }
            }
            launch {
                scaleAnim.animateTo(
                    targetValue = 1.4f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1500, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    )
                )
            }
            launch {
                alphaAnim.animateTo(
                    targetValue = 0.6f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1500, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    )
                )
            }
        } else {
            launch {
                scaleAnim.animateTo(0.8f, tween(300))
            }
            launch {
                alphaAnim.animateTo(0f, tween(300))
            }
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(200.dp)
    ) {
        Box(
            modifier = Modifier
                .size(140.dp)
                .scale(scaleAnim.value)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = alphaAnim.value))
        )

        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isScanning) Icons.Default.Refresh else Icons.Rounded.Storage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(56.dp)
                    .rotate(rotationAnim.value)
            )
        }
    }
}

@Composable
fun ScannerStatusText(isScanning: Boolean, totalSize: String) {
    Crossfade(
        targetState = isScanning,
        animationSpec = tween(300),
        label = "StatusCrossfade"
    ) { scanning ->
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (scanning) "Scanning Photos, Videos and Documents..." else "Library Up to Date",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (scanning) "Searching for new files." else "All files are indexed.\nTotal Vault Size: $totalSize",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun LibraryStatsGrid(photos: Int, videos: Int, audio: Int, docs: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            StatCard(
                modifier = Modifier.weight(1f),
                count = photos.toString(),
                label = "Photos",
                icon = Icons.Rounded.Photo
            )
            StatCard(
                modifier = Modifier.weight(1f),
                count = videos.toString(),
                label = "Videos",
                icon = Icons.Rounded.Videocam
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            StatCard(
                modifier = Modifier.weight(1f),
                count = audio.toString(),
                label = "Audio",
                icon = Icons.Rounded.MusicNote
            )
            StatCard(
                modifier = Modifier.weight(1f),
                count = docs.toString(),
                label = "Docs",
                icon = Icons.Rounded.Description
            )
        }
    }
}

@Composable
fun RefreshButton(isScanning: Boolean, onClick: () -> Unit) {
    Button(
        onClick = {
            if (!isScanning) {
                onClick()
            }
        },
        enabled = !isScanning,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(bottom = 24.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        AnimatedContent(
            targetState = isScanning,
            label = "RefreshButtonContent"
        ) { scanning ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (scanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = if (scanning) "Scanning..." else "Refresh Library",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun StatCard(modifier: Modifier = Modifier, count: String, label: String, icon: ImageVector) {
    val upperLabel = remember(label) {
        label.uppercase()
    }

    Surface(
        modifier = modifier.height(100.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = count,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = upperLabel,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.sp
            )
        }
    }
}