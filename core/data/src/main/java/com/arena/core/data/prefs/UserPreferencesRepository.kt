package com.arena.core.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "arena_prefs")

/**
 * Non-sensitive user preferences (DataStore). Auth material lives in
 * [com.arena.core.data.security.SecureSessionStore] instead.
 */
@Singleton
class UserPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode") // SYSTEM | LIGHT | DARK
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val ADAPTIVE_HOME = booleanPreferencesKey("adaptive_home")
        val HAPTICS_ENABLED = booleanPreferencesKey("haptics_enabled")
        val VOICE_AUTO_SEND = booleanPreferencesKey("voice_auto_send")
    }

    /** "SYSTEM" | "LIGHT" | "DARK"; default DARK (dark mode default spec). */
    val themeMode: Flow<String> = context.dataStore.data.map { it[Keys.THEME_MODE] ?: "DARK" }

    val notificationsEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.NOTIFICATIONS_ENABLED] ?: false }

    /** AI-native adaptive home reordering toggle. */
    val adaptiveHomeEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.ADAPTIVE_HOME] ?: true }

    val hapticsEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.HAPTICS_ENABLED] ?: true }

    /** Voice-first: auto-send after SpeechRecognizer finalizes a dictation. */
    val voiceAutoSend: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.VOICE_AUTO_SEND] ?: true }

    suspend fun setThemeMode(mode: String) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.NOTIFICATIONS_ENABLED] = enabled }
    }

    suspend fun setAdaptiveHomeEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.ADAPTIVE_HOME] = enabled }
    }

    suspend fun setHapticsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.HAPTICS_ENABLED] = enabled }
    }

    suspend fun setVoiceAutoSend(enabled: Boolean) {
        context.dataStore.edit { it[Keys.VOICE_AUTO_SEND] = enabled }
    }
}
