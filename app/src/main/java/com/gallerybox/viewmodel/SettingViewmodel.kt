@file:Suppress("UnsafeOptInUsageError", "UnstableApiUsage", "OPT_IN_USAGE", "unused", "DEPRECATION", "BlockingMethodInNonBlockingContext", "MemberVisibilityCanBePrivate", "OVERRIDE_DEPRECATION")
@file:SuppressLint("UnsafeOptInUsageError")
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.gallerybox.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

enum class ThemeMode { SYSTEM, LIGHT, DARK }

@HiltViewModel
class SettingViewModel @Inject constructor(
    application: Application
) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("gallerybox_settings", Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(ThemeMode.valueOf(prefs.getString("theme_mode", ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name))
    val themeMode = _themeMode.asStateFlow()

    private val _dynamicColor = MutableStateFlow(prefs.getBoolean("dynamic_color", true))
    val dynamicColor = _dynamicColor.asStateFlow()

    private val _pureBlackDark = MutableStateFlow(prefs.getBoolean("pure_black_dark", false))
    val pureBlackDark = _pureBlackDark.asStateFlow()

    private val _showHiddenFiles = MutableStateFlow(prefs.getBoolean("show_hidden_files", false))
    val showHiddenFiles = _showHiddenFiles.asStateFlow()

    private val _autoPlayVideos = MutableStateFlow(prefs.getBoolean("auto_play_videos", true))
    val autoPlayVideos = _autoPlayVideos.asStateFlow()

    private val _loopVideos = MutableStateFlow(prefs.getBoolean("loop_videos", false))
    val loopVideos = _loopVideos.asStateFlow()

    private val _muteVideosDefault = MutableStateFlow(prefs.getBoolean("mute_videos_default", true))
    val muteVideosDefault = _muteVideosDefault.asStateFlow()

    private val _gridColumns = MutableStateFlow(prefs.getInt("gallery_grid_columns", 4))
    val gridColumns = _gridColumns.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        prefs.edit().putString("theme_mode", mode.name).apply()
    }

    fun toggleDynamicColor(enabled: Boolean) {
        _dynamicColor.value = enabled
        prefs.edit().putBoolean("dynamic_color", enabled).apply()
    }

    fun togglePureBlackDark(enabled: Boolean) {
        _pureBlackDark.value = enabled
        prefs.edit().putBoolean("pure_black_dark", enabled).apply()
    }

    fun toggleShowHiddenFiles(show: Boolean) {
        _showHiddenFiles.value = show
        prefs.edit().putBoolean("show_hidden_files", show).apply()
    }

    fun toggleAutoPlayVideos(autoPlay: Boolean) {
        _autoPlayVideos.value = autoPlay
        prefs.edit().putBoolean("auto_play_videos", autoPlay).apply()
    }

    fun toggleLoopVideos(loop: Boolean) {
        _loopVideos.value = loop
        prefs.edit().putBoolean("loop_videos", loop).apply()
    }

    fun toggleMuteVideosDefault(mute: Boolean) {
        _muteVideosDefault.value = mute
        prefs.edit().putBoolean("mute_videos_default", mute).apply()
    }

    fun setGridColumns(columns: Int) {
        if (columns in 2..8) {
            _gridColumns.value = columns
            prefs.edit().putInt("gallery_grid_columns", columns).apply()
        }
    }
}