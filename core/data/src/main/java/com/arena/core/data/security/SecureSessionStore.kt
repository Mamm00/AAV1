package com.arena.core.data.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keystore-backed storage for Arena auth material.
 *
 * Security rules (spec → Security & Compliance):
 *  - Auth cookies are NEVER stored in plaintext SharedPreferences.
 *  - Values are encrypted with AES256-GCM under a MasterKey held in the
 *    Android Keystore (hardware-backed when available).
 *  - Nothing here is logged, not even key names alongside values.
 *  - Sharded/session cookies that only the official WebView session may
 *    touch are passed through, not decoded: this store keeps the *header*
 *    strings the WebView itself produced, so the app never parses tokens.
 */
@Singleton
class SecureSessionStore @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** Persist the cookie header the WebView session reports for [host]. */
    fun saveCookies(host: String, cookieHeader: String) {
        prefs.edit().putString(keyFor(host), cookieHeader).apply()
    }

    /** Returns the stored cookie header for [host], or null. */
    fun cookiesFor(host: String): String? = prefs.getString(keyFor(host), null)

    /** Remove all session material (logout / privacy wipe). */
    fun clear() {
        prefs.edit().clear().apply()
    }

    /** True if any session material exists (used to skip redundant work). */
    fun hasSession(): Boolean = prefs.all.isNotEmpty()

    private fun keyFor(host: String): String = "cookie::$host"

    private companion object {
        const val FILE_NAME = "arena_secure_session"
    }
}
