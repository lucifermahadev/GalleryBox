@file:Suppress("unused", "OPT_IN_USAGE", "DEPRECATION")

package com.gallerybox.ui.screens.setting

import android.app.Application
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
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gallerybox.viewmodel.SecurityViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

// ==========================================
// 1. DATASTORE REPOSITORY
// ==========================================

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "gallery_master_settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val KEY_GRID_AUTO_PLAY = booleanPreferencesKey("grid_auto_play_video")
    private val KEY_CROSSFADE_DURATION = intPreferencesKey("music_crossfade_duration")
    private val KEY_GAPLESS_PLAYBACK = booleanPreferencesKey("music_gapless_playback")
    private val KEY_PAUSE_UNPLUG = booleanPreferencesKey("music_pause_unplug")
    private val KEY_RESUME_PLUG = booleanPreferencesKey("music_resume_plug")
    private val KEY_HW_ACCEL = booleanPreferencesKey("video_hw_accel")
    private val KEY_BG_PLAY = booleanPreferencesKey("video_bg_play")
    private val KEY_AUTO_PIP = booleanPreferencesKey("video_auto_pip")
    private val KEY_VIDEO_GESTURES = booleanPreferencesKey("video_gesture_controls")
    private val KEY_VAULT_HIDE_ICON = booleanPreferencesKey("vault_hide_icon")
    private val KEY_TRASH_DAYS = intPreferencesKey("trash_auto_empty_days")
    private val KEY_SHOW_HIDDEN = booleanPreferencesKey("show_hidden_folders")
    private val KEY_EDITOR_SAVE_COPY = booleanPreferencesKey("editor_save_copy")

    val gridAutoPlay: Flow<Boolean> = context.dataStore.data.map { it[KEY_GRID_AUTO_PLAY] ?: true }
    val crossfadeDuration: Flow<Int> = context.dataStore.data.map { it[KEY_CROSSFADE_DURATION] ?: 2 }
    val gaplessPlayback: Flow<Boolean> = context.dataStore.data.map { it[KEY_GAPLESS_PLAYBACK] ?: true }
    val pauseOnUnplug: Flow<Boolean> = context.dataStore.data.map { it[KEY_PAUSE_UNPLUG] ?: true }
    val resumeOnPlug: Flow<Boolean> = context.dataStore.data.map { it[KEY_RESUME_PLUG] ?: false }
    val hwAccelEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_HW_ACCEL] ?: true }
    val bgPlayEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_BG_PLAY] ?: false }
    val autoPipEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_AUTO_PIP] ?: true }
    val videoGesturesEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_VIDEO_GESTURES] ?: true }
    val vaultHideIcon: Flow<Boolean> = context.dataStore.data.map { it[KEY_VAULT_HIDE_ICON] ?: false }
    val trashAutoEmptyDays: Flow<Int> = context.dataStore.data.map { it[KEY_TRASH_DAYS] ?: 30 }
    val showHiddenFolders: Flow<Boolean> = context.dataStore.data.map { it[KEY_SHOW_HIDDEN] ?: false }
    val editorSaveCopy: Flow<Boolean> = context.dataStore.data.map { it[KEY_EDITOR_SAVE_COPY] ?: false }

    suspend fun setGridAutoPlay(enabled: Boolean) = context.dataStore.edit { it[KEY_GRID_AUTO_PLAY] = enabled }
    suspend fun setCrossfadeDuration(seconds: Int) = context.dataStore.edit { it[KEY_CROSSFADE_DURATION] = seconds }
    suspend fun setGaplessPlayback(enabled: Boolean) = context.dataStore.edit { it[KEY_GAPLESS_PLAYBACK] = enabled }
    suspend fun setPauseOnUnplug(enabled: Boolean) = context.dataStore.edit { it[KEY_PAUSE_UNPLUG] = enabled }
    suspend fun setResumeOnPlug(enabled: Boolean) = context.dataStore.edit { it[KEY_RESUME_PLUG] = enabled }
    suspend fun setHwAccel(enabled: Boolean) = context.dataStore.edit { it[KEY_HW_ACCEL] = enabled }
    suspend fun setBgPlay(enabled: Boolean) = context.dataStore.edit { it[KEY_BG_PLAY] = enabled }
    suspend fun setAutoPip(enabled: Boolean) = context.dataStore.edit { it[KEY_AUTO_PIP] = enabled }
    suspend fun setVideoGestures(enabled: Boolean) = context.dataStore.edit { it[KEY_VIDEO_GESTURES] = enabled }
    suspend fun setVaultHideIcon(enabled: Boolean) = context.dataStore.edit { it[KEY_VAULT_HIDE_ICON] = enabled }
    suspend fun setTrashAutoEmptyDays(days: Int) = context.dataStore.edit { it[KEY_TRASH_DAYS] = days }
    suspend fun setShowHiddenFolders(enabled: Boolean) = context.dataStore.edit { it[KEY_SHOW_HIDDEN] = enabled }
    suspend fun setEditorSaveCopy(enabled: Boolean) = context.dataStore.edit { it[KEY_EDITOR_SAVE_COPY] = enabled }
}

// ==========================================
// 2. VIEWMODEL
// ==========================================

enum class ThemeMode { SYSTEM, LIGHT, DARK }

@HiltViewModel
class SettingViewModel @Inject constructor(
    application: Application,
    private val repository: SettingsRepository
) : AndroidViewModel(application) {

    private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
    private val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
    private val KEY_PURE_BLACK = booleanPreferencesKey("pure_black_dark")
    private val KEY_GRID_COLUMNS = intPreferencesKey("gallery_grid_columns")

    val themeMode: Flow<ThemeMode> = application.dataStore.data.map {
        try { ThemeMode.valueOf(it[KEY_THEME_MODE] ?: "SYSTEM") } catch (e: Exception) { ThemeMode.SYSTEM }
    }
    val dynamicColor: Flow<Boolean> = application.dataStore.data.map { it[KEY_DYNAMIC_COLOR] ?: true }
    val pureBlackDark: Flow<Boolean> = application.dataStore.data.map { it[KEY_PURE_BLACK] ?: false }
    val gridColumns: Flow<Int> = application.dataStore.data.map { it[KEY_GRID_COLUMNS] ?: 4 }

    val gridAutoPlay = repository.gridAutoPlay
    val crossfadeDuration = repository.crossfadeDuration
    val gaplessPlayback = repository.gaplessPlayback
    val pauseOnUnplug = repository.pauseOnUnplug
    val resumeOnPlug = repository.resumeOnPlug
    val hwAccelEnabled = repository.hwAccelEnabled
    val bgPlayEnabled = repository.bgPlayEnabled
    val autoPipEnabled = repository.autoPipEnabled
    val videoGesturesEnabled = repository.videoGesturesEnabled
    val vaultHideIcon = repository.vaultHideIcon
    val trashAutoEmptyDays = repository.trashAutoEmptyDays
    val showHiddenFolders = repository.showHiddenFolders
    val editorSaveCopy = repository.editorSaveCopy

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { getApplication<Application>().dataStore.edit { it[KEY_THEME_MODE] = mode.name } }
    fun toggleDynamicColor(enabled: Boolean) = viewModelScope.launch { getApplication<Application>().dataStore.edit { it[KEY_DYNAMIC_COLOR] = enabled } }
    fun togglePureBlackDark(enabled: Boolean) = viewModelScope.launch { getApplication<Application>().dataStore.edit { it[KEY_PURE_BLACK] = enabled } }
    fun setGridColumns(columns: Int) = viewModelScope.launch { getApplication<Application>().dataStore.edit { it[KEY_GRID_COLUMNS] = columns } }

    fun toggleGridAutoPlay(enabled: Boolean) = viewModelScope.launch { repository.setGridAutoPlay(enabled) }
    fun setCrossfadeDuration(seconds: Int) = viewModelScope.launch { repository.setCrossfadeDuration(seconds) }
    fun toggleGaplessPlayback(enabled: Boolean) = viewModelScope.launch { repository.setGaplessPlayback(enabled) }
    fun togglePauseOnUnplug(enabled: Boolean) = viewModelScope.launch { repository.setPauseOnUnplug(enabled) }
    fun toggleResumeOnPlug(enabled: Boolean) = viewModelScope.launch { repository.setResumeOnPlug(enabled) }
    fun toggleHwAccel(enabled: Boolean) = viewModelScope.launch { repository.setHwAccel(enabled) }
    fun toggleBgPlay(enabled: Boolean) = viewModelScope.launch { repository.setBgPlay(enabled) }
    fun toggleAutoPip(enabled: Boolean) = viewModelScope.launch { repository.setAutoPip(enabled) }
    fun toggleVideoGestures(enabled: Boolean) = viewModelScope.launch { repository.setVideoGestures(enabled) }
    fun toggleVaultHideIcon(enabled: Boolean) = viewModelScope.launch { repository.setVaultHideIcon(enabled) }
    fun updateTrashDays(days: Int) = viewModelScope.launch { repository.setTrashAutoEmptyDays(days) }
    fun toggleShowHidden(enabled: Boolean) = viewModelScope.launch { repository.setShowHiddenFolders(enabled) }
    fun toggleEditorSaveCopy(enabled: Boolean) = viewModelScope.launch { repository.setEditorSaveCopy(enabled) }

    fun resetAllSettings() {
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit {
                it[KEY_THEME_MODE] = ThemeMode.SYSTEM.name
                it[KEY_DYNAMIC_COLOR] = true
                it[KEY_PURE_BLACK] = false
                it[KEY_GRID_COLUMNS] = 4
            }
            repository.setGridAutoPlay(true)
            repository.setCrossfadeDuration(2)
            repository.setGaplessPlayback(true)
            repository.setPauseOnUnplug(true)
            repository.setResumeOnPlug(false)
            repository.setHwAccel(true)
            repository.setBgPlay(false)
            repository.setAutoPip(true)
            repository.setVideoGestures(true)
            repository.setVaultHideIcon(false)
            repository.setTrashAutoEmptyDays(30)
            repository.setShowHiddenFolders(false)
            repository.setEditorSaveCopy(false)
        }
    }
}

// ==========================================
// 3. UI SCREEN
// ==========================================

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
            // ==========================================
            // APPEARANCE & GRID
            // ==========================================
            SettingsHeader(text = "Appearance & Grid", icon = Icons.Rounded.Palette)

            val themeMode by viewModel.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
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

            val dynamicColor by viewModel.dynamicColor.collectAsState(initial = true)
            SwitchSetting(
                title = "Dynamic Colors",
                subtitle = "Extract theme colors from wallpaper (Android 12+)",
                checked = dynamicColor
            ) { newValue ->
                viewModel.toggleDynamicColor(newValue)
            }

            val pureBlackDark by viewModel.pureBlackDark.collectAsState(initial = false)
            SwitchSetting(
                title = "Pure Black Dark Mode",
                subtitle = "Use pitch black for AMOLED displays",
                checked = pureBlackDark
            ) { newValue ->
                viewModel.togglePureBlackDark(newValue)
            }

            val gridColumns by viewModel.gridColumns.collectAsState(initial = 4)
            ListSelectionSetting(
                title = "Grid Columns",
                currentValue = "$gridColumns Columns",
                options = listOf("2 Columns", "3 Columns", "4 Columns", "5 Columns", "6 Columns", "8 Columns")
            ) { selection ->
                val columns = selection.substringBefore(" ").toIntOrNull() ?: 4
                viewModel.setGridColumns(columns)
            }

            val gridAutoPlay by viewModel.gridAutoPlay.collectAsState(initial = true)
            SwitchSetting(
                title = "Auto-play Videos in Grid",
                subtitle = "Play motion photos & videos while scrolling",
                checked = gridAutoPlay
            ) { newValue ->
                viewModel.toggleGridAutoPlay(newValue)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ==========================================
            // PRIVACY & VAULT
            // ==========================================
            SettingsHeader(text = "Privacy & Vault", icon = Icons.Rounded.Security)

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

            val autoLockTimeout by securityViewModel.autoLockTimeout.collectAsState(initial = 5)
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
                headlineContent = { Text(text = "Secure Vault", fontWeight = FontWeight.Medium) },
                supportingContent = { Text(text = "Vault is permanently secured by your native device lock", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                leadingContent = { Icon(imageVector = Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
            )

            val vaultHideIcon by viewModel.vaultHideIcon.collectAsState(initial = false)
            SwitchSetting(
                title = "Hide Vault Icon",
                subtitle = "Remove 'Secure Vault' from Albums list",
                checked = vaultHideIcon
            ) { newValue -> viewModel.toggleVaultHideIcon(newValue) }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ==========================================
            // MUSIC PLAYER
            // ==========================================
            SettingsHeader(text = "Music Player", icon = Icons.Rounded.MusicNote)

            val gaplessPlayback by viewModel.gaplessPlayback.collectAsState(initial = true)
            SwitchSetting(
                title = "Gapless Playback",
                subtitle = "Eliminate gaps between consecutive tracks",
                checked = gaplessPlayback
            ) { newValue -> viewModel.toggleGaplessPlayback(newValue) }

            val crossfadeDuration by viewModel.crossfadeDuration.collectAsState(initial = 2)
            ListSelectionSetting(
                title = "Crossfade Duration",
                currentValue = if (crossfadeDuration == 0) "Off" else "$crossfadeDuration Seconds",
                options = listOf("Off", "2 Seconds", "4 Seconds", "6 Seconds", "8 Seconds")
            ) { selection ->
                val seconds = if (selection == "Off") 0 else selection.substringBefore(" ").toIntOrNull() ?: 2
                viewModel.setCrossfadeDuration(seconds)
            }

            val pauseOnUnplug by viewModel.pauseOnUnplug.collectAsState(initial = true)
            SwitchSetting(
                title = "Pause on Unplug",
                subtitle = "Automatically pause when headphones disconnect",
                checked = pauseOnUnplug
            ) { newValue -> viewModel.togglePauseOnUnplug(newValue) }

            val resumeOnPlug by viewModel.resumeOnPlug.collectAsState(initial = false)
            SwitchSetting(
                title = "Resume Playback",
                subtitle = "Resume when headphones are plugged in",
                checked = resumeOnPlug
            ) { newValue -> viewModel.toggleResumeOnPlug(newValue) }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ==========================================
            // VIDEO PLAYER
            // ==========================================
            SettingsHeader(text = "Video Player", icon = Icons.Rounded.PlayCircle)

            val hwAccelEnabled by viewModel.hwAccelEnabled.collectAsState(initial = true)
            SwitchSetting(
                title = "Hardware Acceleration",
                subtitle = "Use device decoders for smoother playback",
                checked = hwAccelEnabled
            ) { newValue -> viewModel.toggleHwAccel(newValue) }

            val bgPlayEnabled by viewModel.bgPlayEnabled.collectAsState(initial = false)
            SwitchSetting(
                title = "Background Play",
                subtitle = "Continue playing video audio in background",
                checked = bgPlayEnabled
            ) { newValue -> viewModel.toggleBgPlay(newValue) }

            val autoPipEnabled by viewModel.autoPipEnabled.collectAsState(initial = true)
            SwitchSetting(
                title = "Auto Picture-in-Picture",
                subtitle = "Enter PiP when leaving app during playback",
                checked = autoPipEnabled
            ) { newValue -> viewModel.toggleAutoPip(newValue) }

            val videoGesturesEnabled by viewModel.videoGesturesEnabled.collectAsState(initial = true)
            SwitchSetting(
                title = "Video Gestures",
                subtitle = "Swipe to seek, adjust volume, and brightness",
                checked = videoGesturesEnabled
            ) { newValue -> viewModel.toggleVideoGestures(newValue) }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ==========================================
            // LIBRARY & EDITOR
            // ==========================================
            SettingsHeader(text = "Library & Editor", icon = Icons.Rounded.PhotoLibrary)

            val showHiddenFolders by viewModel.showHiddenFolders.collectAsState(initial = false)
            SwitchSetting(
                title = "Show Hidden Folders",
                subtitle = "Display system folders starting with a dot (.)",
                checked = showHiddenFolders
            ) { newValue -> viewModel.toggleShowHidden(newValue) }

            val trashDays by viewModel.trashAutoEmptyDays.collectAsState(initial = 30)
            ListSelectionSetting(
                title = "Auto-Empty Trash",
                currentValue = if (trashDays == -1) "Never" else "$trashDays Days",
                options = listOf("7 Days", "15 Days", "30 Days", "60 Days", "Never")
            ) { selection ->
                val parsedValue = if (selection == "Never") -1 else selection.replace(" Days", "").toInt()
                viewModel.updateTrashDays(parsedValue)
            }

            val editorSaveCopy by viewModel.editorSaveCopy.collectAsState(initial = false)
            SwitchSetting(
                title = "Save Edits as Copy",
                subtitle = "Keep original file unmodified",
                checked = editorSaveCopy
            ) { newValue -> viewModel.toggleEditorSaveCopy(newValue) }

            Spacer(modifier = Modifier.height(32.dp))

            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                OutlinedButton(
                    onClick = { viewModel.resetAllSettings() },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(text = "Reset to Defaults", fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}

@Composable
fun SettingsHeader(text: String, icon: ImageVector? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(12.dp))
        }
        Text(text = text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SwitchSetting(title: String, subtitle: String? = null, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(text = title, fontWeight = FontWeight.Medium) },
        supportingContent = subtitle?.let { text -> { Text(text = text, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onCheckedChange) },
        modifier = Modifier.clickable { onCheckedChange(!checked) }
    )
}

@Composable
fun ListSelectionSetting(title: String, currentValue: String, options: List<String>, onOptionSelected: (String) -> Unit) {
    var showDialog by remember { mutableStateOf(false) }

    ListItem(
        headlineContent = { Text(text = title, fontWeight = FontWeight.Medium) },
        trailingContent = { Text(text = currentValue, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) },
        modifier = Modifier.clickable { showDialog = true }
    )

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(text = "Select $title") },
            text = {
                Column(modifier = Modifier.selectableGroup()) {
                    options.forEach { option ->
                        Row(
                            modifier = Modifier.fillMaxWidth().height(48.dp).selectable(
                                selected = (option == currentValue),
                                onClick = { onOptionSelected(option); showDialog = false },
                                role = Role.RadioButton
                            ).padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = (option == currentValue), onClick = null)
                            Text(text = option, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showDialog = false }) { Text(text = "Cancel") } }
        )
    }
}