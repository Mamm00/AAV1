package com.arena.mobile

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.StrictMode
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point.
 *
 * Startup is intentionally lean: the speculative preconnect (tier 1 of the
 * three-tier WebView strategy) is dispatched here on a background thread so the
 * TCP/TLS/QUIC handshake to https://arena.ai is already warm by the time the
 * first screen renders. No permissions are requested at startup.
 */
@HiltAndroidApp
class ArenaApp : Application() {

    override fun onCreate() {
        super.onCreate()
        if (isDebuggable()) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .build(),
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .penaltyLog()
                    .build(),
            )
        }
        // Warm the connection to Arena.ai before any UI renders (tier 1).
        StartupWarmup.schedule(this)
    }

    private fun isDebuggable(): Boolean =
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
}

/**
 * Tier-1 speculative loading: preconnect to https://arena.ai during cold start
 * before UI renders. Kept free of Hilt so it can run as early as onCreate()
 * without blocking first frame; the full SpeculativeLoader (tiers 2 and 3) is
 * provided by :core:webview through DI once the WebView surface exists.
 */
internal object StartupWarmup {
    fun schedule(context: Context) {
        val app = context.applicationContext
        Thread(
            {
                runCatching {
                    val url = java.net.URI("https://arena.ai/")
                    val socket = java.net.Socket()
                    try {
                        socket.connect(java.net.InetSocketAddress(url.host, 443), 2_500)
                        // Keep the socket open briefly so TLS session tickets are
                        // retained by the platform connection pool, then release.
                        socket.soTimeout = 1
                        socket.getInputStream().read()
                    } finally {
                        runCatching { socket.close() }
                    }
                }
            },
            "arena-preconnect",
        ).apply {
            priority = Thread.NORM_PRIORITY - 1
            start()
        }
        // Silence unused warning in release builds where app is only used above.
        checkNotNull(app)
    }
}
