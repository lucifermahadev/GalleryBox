@file:Suppress("unused", "OPT_IN_USAGE", "DEPRECATION")

package com.gallerybox.ui.screens.setting

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gallerybox.viewmodel.SecurityViewModel
import com.gallerybox.viewmodel.SettingViewModel
import com.gallerybox.viewmodel.ThemeMode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingScreen(
    onBack: () -> Unit,
    viewModel: SettingViewModel = hiltViewModel(),
    securityViewModel: SecurityViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isAppLockEnabled by remember { mutableStateOf(securityViewModel.isAppLockEnabled()) }
    val canAuth = remember { securityViewModel.canUseSystemAuthentication() }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings",
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
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            SettingsHeader(
                text = "Appearance & Grid",
                icon = Icons.Rounded.Palette
            )

            val themeMode by viewModel.themeMode.collectAsState()
            ListSelectionSetting(
                title = "App Theme",
                currentValue = when(themeMode) {
                    ThemeMode.SYSTEM -> "System Default"
                    ThemeMode.LIGHT -> "Light"
                    ThemeMode.DARK -> "Dark"
                },
                options = listOf("System Default", "Light", "Dark")
            ) { selection ->
                val newMode = when (selection) {
                    "System Default" -> ThemeMode.SYSTEM
                    "Light" -> ThemeMode.LIGHT
                    "Dark" -> ThemeMode.DARK
                    else -> ThemeMode.SYSTEM
                }
                viewModel.setThemeMode(newMode)
            }

            val dynamicColor by viewModel.dynamicColor.collectAsState()
            SwitchSetting(
                title = "Dynamic Colors",
                subtitle = "Extract theme colors from wallpaper (Android 12+)",
                checked = dynamicColor
            ) { newValue ->
                viewModel.toggleDynamicColor(newValue)
            }

            val pureBlackDark by viewModel.pureBlackDark.collectAsState()
            SwitchSetting(
                title = "Pure Black Dark Mode",
                subtitle = "Use pitch black for AMOLED displays",
                checked = pureBlackDark
            ) { newValue ->
                viewModel.togglePureBlackDark(newValue)
            }

            val gridColumns by viewModel.gridColumns.collectAsState()
            ListSelectionSetting(
                title = "Grid Columns",
                currentValue = "$gridColumns Columns",
                options = listOf("2 Columns", "3 Columns", "4 Columns", "5 Columns", "6 Columns", "8 Columns")
            ) { selection ->
                val columns = selection.substringBefore(" ").toIntOrNull() ?: 4
                viewModel.setGridColumns(columns)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SettingsHeader(
                text = "Library & Media",
                icon = Icons.Rounded.PhotoLibrary
            )

            val showHiddenFiles by viewModel.showHiddenFiles.collectAsState()
            SwitchSetting(
                title = "Show Hidden Files",
                subtitle = "Display files that begin with a dot (.)",
                checked = showHiddenFiles
            ) { newValue ->
                viewModel.toggleShowHiddenFiles(newValue)
            }

            val autoPlayVideos by viewModel.autoPlayVideos.collectAsState()
            SwitchSetting(
                title = "Auto-play Videos",
                subtitle = "Automatically play videos in the viewer",
                checked = autoPlayVideos
            ) { newValue ->
                viewModel.toggleAutoPlayVideos(newValue)
            }

            val loopVideos by viewModel.loopVideos.collectAsState()
            SwitchSetting(
                title = "Loop Videos",
                subtitle = "Continuously repeat videos",
                checked = loopVideos
            ) { newValue ->
                viewModel.toggleLoopVideos(newValue)
            }

            val muteVideosDefault by viewModel.muteVideosDefault.collectAsState()
            SwitchSetting(
                title = "Mute Videos by Default",
                subtitle = "Start videos with sound muted",
                checked = muteVideosDefault
            ) { newValue ->
                viewModel.toggleMuteVideosDefault(newValue)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SettingsHeader(
                text = "Privacy & Security",
                icon = Icons.Rounded.Security
            )

            SwitchSetting(
                title = "Global App Lock",
                subtitle = if (canAuth) "Require device lock (PIN/Fingerprint) to open app" else "Device screen lock not configured in Android Settings",
                checked = isAppLockEnabled,
                onCheckedChange = { enable ->
                    if (enable && !canAuth) {
                        Toast.makeText(context, "Please configure a device screen lock in Android Settings first", Toast.LENGTH_LONG).show()
                    } else {
                        securityViewModel.setAppLockEnabled(enable)
                        isAppLockEnabled = enable
                    }
                }
            )

            val autoLockTimeout by securityViewModel.autoLockTimeout.collectAsState()
            val autoLockText = when (autoLockTimeout) {
                0 -> "Immediately"
                -1 -> "Never"
                1 -> "1 Minute"
                else -> "$autoLockTimeout Minutes"
            }

            ListSelectionSetting(
                title = "Auto-Lock Timeout",
                currentValue = autoLockText,
                options = listOf("Immediately", "1 Minute", "5 Minutes", "10 Minutes", "30 Minutes", "Never")
            ) { selection ->
                val minutes = when (selection) {
                    "Immediately" -> 0
                    "Never" -> -1
                    "1 Minute" -> 1
                    else -> selection.substringBefore(" ").toIntOrNull() ?: 5
                }
                securityViewModel.setAutoLockTimeout(minutes)
            }

            ListItem(
                headlineContent = {
                    Text(
                        text = "Secure Vault",
                        fontWeight = FontWeight.Medium
                    )
                },
                supportingContent = {
                    Text(
                        text = "Vault is permanently secured by your native device lock",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            )

            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}

@Composable
fun SettingsHeader(
    text: String,
    icon: ImageVector? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun SwitchSetting(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = {
            Text(
                text = title,
                fontWeight = FontWeight.Medium
            )
        },
        supportingContent = subtitle?.let { text ->
            {
                Text(
                    text = text,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        },
        modifier = Modifier.clickable {
            onCheckedChange(!checked)
        }
    )
}

@Composable
fun ListSelectionSetting(
    title: String,
    currentValue: String,
    options: List<String>,
    onOptionSelected: (String) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }

    ListItem(
        headlineContent = {
            Text(
                text = title,
                fontWeight = FontWeight.Medium
            )
        },
        trailingContent = {
            Text(
                text = currentValue,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        },
        modifier = Modifier.clickable {
            showDialog = true
        }
    )

    if (showDialog) {
        AlertDialog(
            onDismissRequest = {
                showDialog = false
            },
            title = {
                Text(text = "Select $title")
            },
            text = {
                Column(modifier = Modifier.selectableGroup()) {
                    options.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .selectable(
                                    selected = (option == currentValue),
                                    onClick = {
                                        onOptionSelected(option)
                                        showDialog = false
                                    },
                                    role = Role.RadioButton
                                )
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (option == currentValue),
                                onClick = null
                            )
                            Text(
                                text = option,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 16.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDialog = false
                    }
                ) {
                    Text(text = "Cancel")
                }
            }
        )
    }
}