package com.gallerybox.viewmodel

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.lifecycle.ViewModel
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class SecurityViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val securePrefs by lazy {
        EncryptedSharedPreferences.create(
            context,
            "bank_grade_secure_gallery_prefs",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked = _isUnlocked.asStateFlow()

    private val _lastUnlockTime = MutableStateFlow(0L)

    private val _autoLockTimeout = MutableStateFlow(5)
    val autoLockTimeout = _autoLockTimeout.asStateFlow()

    init {
        _autoLockTimeout.value = securePrefs.getInt("auto_lock_timeout", 5)
    }

    fun isAppLockEnabled(): Boolean = securePrefs.getBoolean("app_lock_enabled", false)

    fun setAppLockEnabled(enabled: Boolean) {
        securePrefs.edit().putBoolean("app_lock_enabled", enabled).apply()
    }

    fun setAutoLockTimeout(minutes: Int) {
        securePrefs.edit().putInt("auto_lock_timeout", minutes).apply()
        _autoLockTimeout.value = minutes
    }

    fun unlockReal() {
        _lastUnlockTime.value = System.currentTimeMillis()
        _isUnlocked.value = true
    }

    fun lock() {
        _isUnlocked.value = false
    }

    fun onAuthenticationSuccess() {
        unlockReal()
    }

    fun canUseSystemAuthentication(): Boolean {
        return BiometricManager.from(context).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun hasBiometricHardware(): Boolean {
        val result = BiometricManager.from(context).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.BIOMETRIC_WEAK
        )
        return result != BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE
    }

    fun isDeviceCredentialAvailable(): Boolean {
        return BiometricManager.from(context).canAuthenticate(
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun shouldRelock(timeoutMinutes: Int): Boolean {
        if (timeoutMinutes < 0) return false
        if (timeoutMinutes == 0) return true

        val diff = System.currentTimeMillis() - _lastUnlockTime.value
        return diff > (timeoutMinutes * 60_000L)
    }
}