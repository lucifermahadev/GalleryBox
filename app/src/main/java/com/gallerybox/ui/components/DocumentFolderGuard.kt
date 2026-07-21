package com.gallerybox.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun DocumentFolderGuard(
    content: @Composable (Uri) -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("DocPrefs", Context.MODE_PRIVATE) }
    var grantedUri by remember { mutableStateOf<Uri?>(null) }

    // Check for existing persisted permissions
    LaunchedEffect(Unit) {
        val savedUriStr = prefs.getString("document_tree_uri", null)
        if (savedUriStr != null) {
            val uri = Uri.parse(savedUriStr)
            val persistedPermissions = context.contentResolver.persistedUriPermissions
            val hasPermission = persistedPermissions.any { it.uri == uri && it.isReadPermission }

            if (hasPermission) {
                grantedUri = uri
            } else {
                prefs.edit().remove("document_tree_uri").apply()
            }
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            // Take persistable permission so we don't have to ask again on next app launch
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)

            // Save the URI
            prefs.edit().putString("document_tree_uri", uri.toString()).apply()
            grantedUri = uri
        }
    }

    if (grantedUri != null) {
        content(grantedUri!!)
    } else {
        // Full screen prompt explaining why we need folder access
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(32.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FolderOpen,
                        contentDescription = "Folder Access Required",
                        modifier = Modifier.size(60.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "Folder Access Required",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "To view your documents, please select a folder (like Documents or Downloads) where your files are stored. You only need to do this once.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    lineHeight = 22.sp
                )

                Spacer(modifier = Modifier.height(32.dp))

                Button(
                    onClick = { launcher.launch(null) },
                    modifier = Modifier.height(56.dp)
                ) {
                    Text(
                        text = "Select Folder",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
            }
        }
    }
}