@file:Suppress("unused")

package com.gallerybox.ui.util

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.gallerybox.viewmodel.GalleryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UtilitiesScreen(
    viewModel: GalleryViewModel = hiltViewModel(),
    onNavigateToTrash: () -> Unit,
    onNavigateToHidden: () -> Unit,
    onNavigateToFavorites: () -> Unit,
    onNavigateToDuplicates: () -> Unit,
    onNavigateToScanner: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme

    val allMedia by viewModel.media.collectAsState()

    var storageInfo by remember { mutableStateOf(Pair(0L, 0L)) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val path = Environment.getExternalStorageDirectory()
            val total = path.totalSpace
            val free = path.freeSpace
            storageInfo = Pair(total, free)
        }
    }

    val totalStorage = storageInfo.first
    val freeStorage = storageInfo.second
    val usedStorage = totalStorage - freeStorage
    val usedPercent = if (totalStorage > 0) (usedStorage.toFloat() / totalStorage.toFloat()) else 0f

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = { Text("Utilities", fontWeight = FontWeight.Bold, color = colors.onBackground) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = colors.onBackground)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                StorageCard(
                    usedBytes = usedStorage,
                    totalBytes = totalStorage,
                    percentage = usedPercent
                )
            }

            item { SectionTitle("Organize") }

            item {
                UtilityTile(
                    title = "Trash Bin",
                    subtitle = "Recover or delete files permanently",
                    icon = Icons.Rounded.DeleteSweep,
                    color = colors.error,
                    onClick = onNavigateToTrash
                )
            }

            item {
                UtilityTile(
                    title = "Hidden Cabinet",
                    subtitle = "Secure photos & videos",
                    icon = Icons.Rounded.Shield,
                    color = colors.primary,
                    onClick = onNavigateToHidden
                )
            }

            item {
                UtilityTile(
                    title = "Favorites",
                    subtitle = "Quick access to loved items",
                    icon = Icons.Rounded.FolderSpecial,
                    color = Color(0xFFFFB300),
                    onClick = onNavigateToFavorites
                )
            }

            item { SectionTitle("Maintenance") }

            item {
                UtilityTile(
                    title = "Scan Duplicates",
                    subtitle = "Find and remove visually identical copies",
                    icon = Icons.Rounded.CleaningServices,
                    color = colors.secondary,
                    onClick = onNavigateToDuplicates
                )
            }

            item {
                UtilityTile(
                    title = "Library Scanner",
                    subtitle = "Deep index storage for new files",
                    icon = Icons.Rounded.Search,
                    color = colors.tertiary,
                    onClick = onNavigateToScanner
                )
            }

            item {
                UtilityTile(
                    title = "Clean Cache",
                    subtitle = "Clear temporary thumbnails and logs",
                    icon = Icons.Rounded.AutoFixHigh,
                    color = Color(0xFF4CAF50),
                    onClick = {
                        scope.launch {
                            val bytesCleared = withContext(Dispatchers.IO) {
                                clearApplicationCache(context)
                            }
                            val detailedMessage = if (bytesCleared > 0) {
                                "Cleared ${Formatter.formatShortFileSize(context, bytesCleared)} of temporary storage"
                            } else {
                                "Cache is already pristine!"
                            }
                            Toast.makeText(context, detailedMessage, Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun StorageCard(usedBytes: Long, totalBytes: Long, percentage: Float) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme

    Card(
        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant.copy(alpha = 0.4f)),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Storage, null, tint = colors.primary)
                Spacer(Modifier.width(12.dp))
                Text("Device Storage", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = colors.onSurfaceVariant)
            }
            Spacer(Modifier.height(20.dp))

            LinearProgressIndicator(
                progress = { percentage },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(CircleShape),
                color = if (percentage > 0.9f) colors.error else colors.primary,
                trackColor = colors.onSurfaceVariant.copy(alpha = 0.2f),
            )

            Spacer(Modifier.height(14.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = "${(percentage * 100).toInt()}% Used",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (percentage > 0.9f) colors.error else colors.primary
                )
                Text(
                    text = "${Formatter.formatShortFileSize(context, usedBytes)} / ${Formatter.formatShortFileSize(context, totalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun UtilityTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
        color = colors.surfaceVariant.copy(alpha = 0.2f),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(color.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = color, modifier = Modifier.size(26.dp))
            }

            Spacer(Modifier.width(16.dp))

            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = colors.onSurface)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }

            Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, null, tint = colors.outline.copy(alpha = 0.6f), modifier = Modifier.size(12.dp))
        }
    }
}

@Composable
fun SectionTitle(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Black,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
        letterSpacing = 1.2.sp
    )
}

private fun clearApplicationCache(context: Context): Long {
    var totalDeletedBytesBytes = 0L

    val cacheDirs = listOfNotNull(
        context.cacheDir,
        context.externalCacheDir
    )

    cacheDirs.forEach { dir ->
        totalDeletedBytesBytes += getFolderSizeAndClean(dir)
    }
    return totalDeletedBytesBytes
}

private fun getFolderSizeAndClean(file: File): Long {
    var size = 0L
    if (file.isDirectory) {
        file.listFiles()?.forEach { child ->
            size += getFolderSizeAndClean(child)
        }
    }
    size += file.length()
    file.delete()
    return size
}

object FileOpener {
    fun openFile(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file
        )

        val mimeType = when (file.extension.lowercase()) {
            "pdf" -> "application/pdf"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "txt" -> "text/plain"
            "jpg", "jpeg", "png", "webp" -> "image/*"
            "mp4", "mkv", "webm" -> "video/*"
            "mp3", "wav", "ogg" -> "audio/*"
            else -> "*/*"
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(
                context,
                "No app found to open this file",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}

fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    val (height: Int, width: Int) = options.outHeight to options.outWidth
    var inSampleSize = 1
    if (height > reqHeight || width > reqWidth) {
        val halfHeight: Int = height / 2
        val halfWidth: Int = width / 2
        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}