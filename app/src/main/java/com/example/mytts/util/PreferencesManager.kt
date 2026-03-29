package com.example.mytts.util

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists user settings and playback state via SharedPreferences.
 * Restores everything on next startup so the user can continue where they left off.
 */
class PreferencesManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "mytts_prefs"
        private const val KEY_TEXT = "text"
        private const val KEY_CURSOR_POSITION = "cursor_position"
        private const val KEY_VOICE = "voice"
        private const val KEY_SLOW_MODE = "slow_mode"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Expose saved values as StateFlows for Compose observation
    private val _text = MutableStateFlow(prefs.getString(KEY_TEXT, "") ?: "")
    val text: StateFlow<String> = _text.asStateFlow()

    private val _cursorPosition = MutableStateFlow(prefs.getInt(KEY_CURSOR_POSITION, 0))
    val cursorPosition: StateFlow<Int> = _cursorPosition.asStateFlow()

    private val _voice = MutableStateFlow(prefs.getString(KEY_VOICE, "") ?: "")
    val voice: StateFlow<String> = _voice.asStateFlow()

    private val _slowMode = MutableStateFlow(prefs.getBoolean(KEY_SLOW_MODE, false))
    val slowMode: StateFlow<Boolean> = _slowMode.asStateFlow()

    fun saveText(value: String) {
        _text.value = value
        prefs.edit().putString(KEY_TEXT, value).apply()
    }

    fun saveCursorPosition(value: Int) {
        _cursorPosition.value = value
        prefs.edit().putInt(KEY_CURSOR_POSITION, value).apply()
    }

    fun saveVoice(value: String) {
        _voice.value = value
        prefs.edit().putString(KEY_VOICE, value).apply()
    }

    fun saveSlowMode(value: Boolean) {
        _slowMode.value = value
        prefs.edit().putBoolean(KEY_SLOW_MODE, value).apply()
    }
}
