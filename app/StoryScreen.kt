package com.gallerybox.ui.stories

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.gallerybox.viewmodel.GalleryViewModel
import com.gallerybox.viewmodel.MediaItem
import com.gallerybox.viewmodel.Story

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoryScreen(
    vm: GalleryViewModel,
    onOpenStory: (Story) -> Unit,
    onOpenItem: (List<MediaItem>, Int) -> Unit
) {
    val stories by vm.stories.collectAsState()
    val categories by vm.categoryGroups.collectAsState()
    val context = LocalContext.current

    // States
    var isScanning by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }

    // Story Management States
    var storyToEdit by remember { mutableStateOf<Story?>(null) }
    var showRenameDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text("Stories", fontWeight = FontWeight.ExtraBold) },
                actions = {
                    // AI SCAN BUTTON
                    IconButton(onClick = {
                        isScanning = true
                        vm.scanMediaForSmartTags(context)
                        Toast.makeText(context, "Analyzing photos...", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Rounded.AutoAwesome, "AI Scan", tint = MaterialTheme.colorScheme.primary)
                    }
                    // MENU
                    Box {
                        IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Create story") },
                                onClick = { showMenu = false; showCreateDialog = true },
                                leadingIcon = { Icon(Icons.Default.Add, null) }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {

            // 1. HIGHLIGHTS (STORIES)
            if (stories.isNotEmpty()) {
                item {
                    SectionHeader(title = "Highlights", icon = null)
                }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(stories) { story ->
                            StoryThumbnail(
                                story = story,
                                onClick = { onOpenStory(story) },
                                onLongClick = { storyToEdit = story; showRenameDialog = true }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }

            // 2. SMART COLLECTIONS (ML TAGS)
            if (categories.isNotEmpty()) {
                item {
                    SectionHeader(title = "Smart collections", icon = Icons.Rounded.AutoAwesome)
                }

                items(categories.toList()) { (categoryName, itemsList) ->
                    CategorySection(
                        title = categoryName,
                        items = itemsList,
                        onItemClick = { index ->
                            onOpenItem(itemsList, index)
                        }
                    )
                }
            } else {
                // EMPTY STATE
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(0.5f))
                            .clickable {
                                isScanning = true
                                vm.scanMediaForSmartTags(context)
                            }
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Rounded.AutoAwesome, null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(16.dp))
                            Text("Rediscover your memories", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Text("Tap to scan your photos for people, places, and things.", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                        }
                    }
                }
            }
        }
    }

    // --- DIALOGS ---

    // Create Story Dialog
    if (showCreateDialog) {
        TextInputDialog(
            title = "Create Story",
            label = "Story Name",
            onDismiss = { showCreateDialog = false },
            onConfirm = { name -> vm.createStory(name, emptyList()); showCreateDialog = false }
        )
    }

    // Rename/Delete Dialog
    if (showRenameDialog && storyToEdit != null) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false; storyToEdit = null },
            title = { Text("Manage Story") },
            text = { Text("What would you like to do with '${storyToEdit!!.title}'?") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteStory(storyToEdit!!)
                    showRenameDialog = false
                    storyToEdit = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false; storyToEdit = null }) { Text("Cancel") }
            }
        )
    }
}

// --- SUB COMPONENTS ---

@Composable
fun SectionHeader(title: String, icon: ImageVector?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun CategorySection(
    title: String,
    items: List<MediaItem>,
    onItemClick: (Int) -> Unit
) {
    Column {
        // Header
        Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text = title.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text(text = "${items.size}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Spacer(Modifier.weight(1f))
            Icon(Icons.Default.MoreVert, null, tint = Color.Gray, modifier = Modifier.size(16.dp))
        }

        // Horizontal Strip
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(items) { index, item ->
                AsyncImage(
                    model = item.uri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(110.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onItemClick(index) }
                )
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class) // FIX: This is required for combinedClickable
@Composable
fun StoryThumbnail(
    story: Story,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .width(160.dp)
            .height(240.dp)
            .clip(RoundedCornerShape(20.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        // Cover Image
        AsyncImage(
            model = story.coverUri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Gradient Overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)),
                        startY = 300f
                    )
                )
        )

        // Text & Icon
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.PlayCircle,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "${story.items.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = story.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "Auto-created",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(0.7f)
            )
        }
    }
}

@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initialValue: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = { Button(onClick = { if (text.isNotBlank()) onConfirm(text) }) { Text("Confirm") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}