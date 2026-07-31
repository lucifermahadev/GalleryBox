@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package com.gallerybox.viewmodel

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "gallery_master_settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

@HiltViewModel
class SettingViewModel @Inject constructor(
    application: Application
) : AndroidViewModel(application) {

    private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
    private val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
    private val KEY_PURE_BLACK = booleanPreferencesKey("pure_black_dark")
    private val KEY_GRID_COLUMNS = intPreferencesKey("gallery_grid_columns")

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

    val themeMode: Flow<ThemeMode> = application.dataStore.data.map {
        try { ThemeMode.valueOf(it[KEY_THEME_MODE] ?: "SYSTEM") } catch (e: Exception) { ThemeMode.SYSTEM }
    }
    val dynamicColor: Flow<Boolean> = application.dataStore.data.map { it[KEY_DYNAMIC_COLOR] ?: true }
    val pureBlackDark: Flow<Boolean> = application.dataStore.data.map { it[KEY_PURE_BLACK] ?: false }
    val gridColumns: Flow<Int> = application.dataStore.data.map { it[KEY_GRID_COLUMNS] ?: 4 }

    val gridAutoPlay: Flow<Boolean> = application.dataStore.data.map { it[KEY_GRID_AUTO_PLAY] ?: true }
    val crossfadeDuration: Flow<Int> = application.dataStore.data.map { it[KEY_CROSSFADE_DURATION] ?: 2 }
    val gaplessPlayback: Flow<Boolean> = application.dataStore.data.map { it[KEY_GAPLESS_PLAYBACK] ?: true }
    val pauseOnUnplug: Flow<Boolean> = application.dataStore.data.map { it[KEY_PAUSE_UNPLUG] ?: true }
    val resumeOnPlug: Flow<Boolean> = application.dataStore.data.map { it[KEY_RESUME_PLUG] ?: false }
    val hwAccelEnabled: Flow<Boolean> = application.dataStore.data.map { it[KEY_HW_ACCEL] ?: true }
    val bgPlayEnabled: Flow<Boolean> = application.dataStore.data.map { it[KEY_BG_PLAY] ?: false }
    val autoPipEnabled: Flow<Boolean> = application.dataStore.data.map { it[KEY_AUTO_PIP] ?: true }
    val videoGesturesEnabled: Flow<Boolean> = application.dataStore.data.map { it[KEY_VIDEO_GESTURES] ?: true }
    val vaultHideIcon: Flow<Boolean> = application.dataStore.data.map { it[KEY_VAULT_HIDE_ICON] ?: false }
    val trashAutoEmptyDays: Flow<Int> = application.dataStore.data.map { it[KEY_TRASH_DAYS] ?: 30 }
    val showHiddenFolders: Flow<Boolean> = application.dataStore.data.map { it[KEY_SHOW_HIDDEN] ?: false }
    val editorSaveCopy: Flow<Boolean> = application.dataStore.data.map { it[KEY_EDITOR_SAVE_COPY] ?: false }

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { getApplication<Application>().dataStore.edit { it[KEY_THEME_MODE] = mode.name } }
    fun toggleDynamicColor(enabled: Boolean) = viewModelScope.launch { getApplication<Application>().dataStore.edit { it[KEY_DYNAMIC_COLOR] = enabled } }
    fun togglePureBlackDark(enabled: Boolean) = viewModelScope.launch { getApplication<Application>().dataStore.edit { it[KEY_PURE_BLACK] = enabled } }
    fun setGridColumns(columns: Int) = viewModelScope.launch { getApplication<Application>().dataStore.edit { it[KEY_GRID_COLUMNS] = columns } }

    fun toggleGridAutoPlay(enabled: Boolean) = viewModelScope.launch { getApplication<Application>().dataStore.edit { it[KEY_GRID_AUTO_PLAY] = enabled } }
    fun setCrossfadeDuration(seconds: Int) = viewModelScope.launch { getApplication<Application>().dataStore.edit { it[KEY_CROSSFADE_DURATION] = seconds } }
    fun toggleGaplessPlayback(enabled: Boolean) = viewModelScope.launch { getApplication<Application>().dataStore.edit { it[KEY_GAPLESS_PLAYBACK] = enabled } }
    fun togglePauseOnUnplug(enabled: Boolean) = viewModelScope.launch { getApplication<Application>().dataStore.edit { it[KEY_PAUSE_UNPLUG] = enabled } }
    fun toggleResumeOnPlug(enabled: Boolean) = viewModelScope.launch { getApplication<Application>().dataStore.edit { it[KEY_RESUME_PLUG] = enabled } }
    fun toggleHwAccel(enabled: Boolean) = viewModelScope.launch { getApplication<Application>().dataStore.edit { it[KEY_HW_ACCEL] = enabled } }
    fun toggleBgPlay(enabled: Boolean) = viewModelScope.launch { getApplication<Application>().dataStore.edit { it[KEY_BG_PLAY] = enabled } }
    fun toggleAutoPip(enabled: Boolean) = viewModelScope.launch { getApplication<Application>().dataStore.edit { it[KEY_AUTO_PIP] = enabled } }
    fun toggleVideoGestures(enabled: Boolean) = viewModelScope.launch { getApplication<Application>().dataStore.edit { it[KEY_VIDEO_GESTURES] = enabled } }
    fun toggleVaultHideIcon(enabled: Boolean) = viewModelScope.launch { getApplication<Application>().dataStore.edit { it[KEY_VAULT_HIDE_ICON] = enabled } }
    fun updateTrashDays(days: Int) = viewModelScope.launch { getApplication<Application>().dataStore.edit { it[KEY_TRASH_DAYS] = days } }
    fun toggleShowHidden(enabled: Boolean) = viewModelScope.launch { getApplication<Application>().dataStore.edit { it[KEY_SHOW_HIDDEN] = enabled } }
    fun toggleEditorSaveCopy(enabled: Boolean) = viewModelScope.launch { getApplication<Application>().dataStore.edit { it[KEY_EDITOR_SAVE_COPY] = enabled } }

    fun resetAllSettings() {
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit {
                it[KEY_THEME_MODE] = ThemeMode.SYSTEM.name
                it[KEY_DYNAMIC_COLOR] = true
                it[KEY_PURE_BLACK] = false
                it[KEY_GRID_COLUMNS] = 4
                it[KEY_GRID_AUTO_PLAY] = true
                it[KEY_CROSSFADE_DURATION] = 2
                it[KEY_GAPLESS_PLAYBACK] = true
                it[KEY_PAUSE_UNPLUG] = true
                it[KEY_RESUME_PLUG] = false
                it[KEY_HW_ACCEL] = true
                it[KEY_BG_PLAY] = false
                it[KEY_AUTO_PIP] = true
                it[KEY_VIDEO_GESTURES] = true
                it[KEY_VAULT_HIDE_ICON] = false
                it[KEY_TRASH_DAYS] = 30
                it[KEY_SHOW_HIDDEN] = false
                it[KEY_EDITOR_SAVE_COPY] = false
            }
        }
    }
}