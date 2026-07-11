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
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
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
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private val KEY_GRID_AUTO_PLAY = booleanPreferencesKey("grid_auto_play_video")
    private val KEY_FACE_GROUPING = booleanPreferencesKey("ai_face_grouping")
    private val KEY_SMART_SEARCH = booleanPreferencesKey("ai_smart_search")
    private val KEY_NSFW_PROTECT = booleanPreferencesKey("ai_nsfw_protection")
    private val KEY_MAGIC_STORY = booleanPreferencesKey("ai_story_generation")
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
    val faceGroupingEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_FACE_GROUPING] ?: true }
    val smartSearchEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_SMART_SEARCH] ?: true }
    val nsfwProtectionEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_NSFW_PROTECT] ?: false }
    val storyGenerationEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_MAGIC_STORY] ?: true }
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
    suspend fun setFaceGrouping(enabled: Boolean) = context.dataStore.edit { it[KEY_FACE_GROUPING] = enabled }
    suspend fun setSmartSearch(enabled: Boolean) = context.dataStore.edit { it[KEY_SMART_SEARCH] = enabled }
    suspend fun setNsfwProtection(enabled: Boolean) = context.dataStore.edit { it[KEY_NSFW_PROTECT] = enabled }
    suspend fun setStoryGeneration(enabled: Boolean) = context.dataStore.edit { it[KEY_MAGIC_STORY] = enabled }
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

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository
) : ViewModel() {
    val gridAutoPlay = repository.gridAutoPlay
    val faceGroupingEnabled = repository.faceGroupingEnabled
    val smartSearchEnabled = repository.smartSearchEnabled
    val nsfwProtectionEnabled = repository.nsfwProtectionEnabled
    val storyGenerationEnabled = repository.storyGenerationEnabled
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

    fun toggleGridAutoPlay(enabled: Boolean) = viewModelScope.launch { repository.setGridAutoPlay(enabled) }
    fun toggleFaceGrouping(enabled: Boolean) = viewModelScope.launch { repository.setFaceGrouping(enabled) }
    fun toggleSmartSearch(enabled: Boolean) = viewModelScope.launch { repository.setSmartSearch(enabled) }
    fun toggleNsfwProtection(enabled: Boolean) = viewModelScope.launch { repository.setNsfwProtection(enabled) }
    fun toggleStoryGeneration(enabled: Boolean) = viewModelScope.launch { repository.setStoryGeneration(enabled) }
    fun updateCrossfadeDuration(seconds: Int) = viewModelScope.launch { repository.setCrossfadeDuration(seconds) }
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
            repository.setGridAutoPlay(true)
            repository.setFaceGrouping(true)
            repository.setSmartSearch(true)
            repository.setNsfwProtection(false)
            repository.setStoryGeneration(true)
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
// 3. MAIN SETTINGS SCREEN
// ==========================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
    securityViewModel: SecurityViewModel = hiltViewModel()
) {
    val context = LocalContext.current

    var isAppLockEnabled by remember { mutableStateOf(securityViewModel.isAppLockEnabled()) }
    val canAuth = remember { securityViewModel.canAuthenticate() }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
        ) {
            SettingsHeader("Appearance & Grid", Icons.Rounded.Palette)

            // Hardcoded to strictly sync with system theme per user command
            ListItem(
                headlineContent = { Text("App Theme", fontWeight = FontWeight.Medium) },
                supportingContent = { Text("Synchronized with phone system theme", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                trailingContent = { Icon(Icons.Rounded.Sync, contentDescription = "Synced with system", tint = MaterialTheme.colorScheme.primary) }
            )

            val gridAutoPlay by viewModel.gridAutoPlay.collectAsState(initial = true)
            SwitchSetting("Auto-play Videos in Grid", "Play motion photos & videos while scrolling", gridAutoPlay) { viewModel.toggleGridAutoPlay(it) }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SettingsHeader("Intelligence (AI)", Icons.Rounded.AutoAwesome)
            val faceEnabled by viewModel.faceGroupingEnabled.collectAsState(initial = true)
            SwitchSetting("Face Grouping", "Cluster photos by people (On-device ML)", faceEnabled) { viewModel.toggleFaceGrouping(it) }

            val searchEnabled by viewModel.smartSearchEnabled.collectAsState(initial = true)
            SwitchSetting("Smart Search Indexing", "Enable searching by content", searchEnabled) { viewModel.toggleSmartSearch(it) }

            val nsfwEnabled by viewModel.nsfwProtectionEnabled.collectAsState(initial = false)
            SwitchSetting("NSFW Blur", "Automatically blur sensitive content in grid", nsfwEnabled) { viewModel.toggleNsfwProtection(it) }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SettingsHeader("Privacy & Vault", Icons.Rounded.Security)

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

            // Read-only indicator to clarify how the Vault works natively now
            ListItem(
                headlineContent = { Text("Secure Vault", fontWeight = FontWeight.Medium) },
                supportingContent = { Text("Vault is permanently secured by your native device lock", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                leadingContent = { Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.primary) }
            )

            val vaultHideIcon by viewModel.vaultHideIcon.collectAsState(initial = false)
            SwitchSetting("Hide Vault Icon", "Remove 'Secure Vault' from Albums list", vaultHideIcon) { viewModel.toggleVaultHideIcon(it) }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SettingsHeader("Library & Editor", Icons.Rounded.CleaningServices)
            val trashDays by viewModel.trashAutoEmptyDays.collectAsState(initial = 30)
            ListSelectionSetting(
                title = "Auto-Empty Trash",
                currentValue = if (trashDays == -1) "Never" else "$trashDays Days",
                options = listOf("7 Days", "15 Days", "30 Days", "60 Days", "Never")
            ) { selection ->
                viewModel.updateTrashDays(if (selection == "Never") -1 else selection.replace(" Days", "").toInt())
            }

            val editorSaveCopy by viewModel.editorSaveCopy.collectAsState(initial = false)
            SwitchSetting("Save Edits as Copy", "Keep original file unmodified", editorSaveCopy) { viewModel.toggleEditorSaveCopy(it) }

            Spacer(Modifier.height(32.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                OutlinedButton(onClick = { viewModel.resetAllSettings() }, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text("Reset to Defaults", fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(48.dp))
        }
    }
}

// ==========================================
// 4. REUSABLE UI COMPONENTS
// ==========================================

@Composable
fun SettingsHeader(text: String, icon: ImageVector? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)) {
        if (icon != null) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(12.dp)) }
        Text(text = text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SwitchSetting(title: String, subtitle: String? = null, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
        supportingContent = subtitle?.let { { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onCheckedChange) },
        modifier = Modifier.clickable { onCheckedChange(!checked) }
    )
}

@Composable
fun ListSelectionSetting(title: String, currentValue: String, options: List<String>, onOptionSelected: (String) -> Unit) {
    var showDialog by remember { mutableStateOf(false) }

    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
        trailingContent = { Text(currentValue, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) },
        modifier = Modifier.clickable { showDialog = true }
    )

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Select $title") },
            text = {
                Column(Modifier.selectableGroup()) {
                    options.forEach { option ->
                        Row(
                            Modifier.fillMaxWidth().height(48.dp).selectable(selected = (option == currentValue), onClick = { onOptionSelected(option); showDialog = false }, role = Role.RadioButton).padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = (option == currentValue), onClick = null)
                            Text(text = option, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showDialog = false }) { Text("Cancel") } }
        )
    }
}