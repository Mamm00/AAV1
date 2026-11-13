package com.arena.core.webview

import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Navigation events surfaced to native UI.
 */
sealed interface ArenaNavEvent {
    /** Page started loading [url]. */
    data class Started(val url: String) : ArenaNavEvent

    /** Page finished loading [url]; safe to run FCP probes. */
    data class Finished(val url: String) : ArenaNavEvent

    /** Main-frame load failed; show native offline fallback. */
    data class Offline(val url: String, val errorCode: Int) : ArenaNavEvent

    /** Main-frame load recovered after a previous [Offline]. */
    data class Online(val url: String) : ArenaNavEvent

    /** Page tried to leave arena.ai; host app decides (custom tab / block). */
    data class External(val url: String) : ArenaNavEvent

    /** Renderer process died (crash/OOM); the host must reload the WebView. */
    data object RenderProcessGone : ArenaNavEvent

    /** SSL error on the main frame — never proceed, surface a native warning. */
    data class SslError(val url: String) : ArenaNavEvent
}

/**
 * WebViewClient for the Arena SPA.
 *
 * Responsibilities:
 *  - Emits [ArenaNavEvent]s so native UI can show offline fallbacks with
 *    cached model lists and last conversation history.
 *  - Keeps navigation inside `https://arena.ai` / `https://*.arena.ai`;
 *    external links are handed to the host (Custom Tab) instead of loading
 *    in-place, which also keeps the JS bridge origin lock tight.
 *  - Detects renderer crashes so the wrapper can rebuild the WebView rather
 *    than freeze on a blank surface.
 *  - Back-forward cache compatibility: never call `WebView.stopLoading()` or
 *    rebuild state in `onPageFinished` for bfcache restores (the wrapper
 *    enables it via `WebSettingsCompat.setBackForwardCacheEnabled`).
 */
class ArenaWebViewClient(
    private val onEvent: (ArenaNavEvent) -> Unit,
    private val allowExternalNavigation: (String) -> Boolean = { false },
) : WebViewClient() {

    /** Tracks the last good URL so we can flip back to [ArenaNavEvent.Online]. */
    private var lastLoadedUrl: String? = null
    private var wasOffline = false

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        url?.let { onEvent(ArenaNavEvent.Started(it)) }
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        if (url == null) return
        if (wasOffline) {
            wasOffline = false
            onEvent(ArenaNavEvent.Online(url))
        }
        lastLoadedUrl = url
        onEvent(ArenaNavEvent.Finished(url))
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?,
    ) {
        super.onReceivedError(view, request, error)
        if (request?.isForMainFrame != true) return
        val url = request.url?.toString() ?: return
        // Only subresource-independent main-frame failures trigger fallback.
        if (error?.errorCode == ERROR_HOST_LOOKUP ||
            error?.errorCode == ERROR_CONNECT ||
            error?.errorCode == ERROR_TIMEOUT ||
            error?.errorCode == ERROR_FAILED_SSL_HANDSHAKE ||
            error?.errorCode == ERROR_IO
        ) {
            wasOffline = true
            onEvent(ArenaNavEvent.Offline(url, error.errorCode))
        }
    }

    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
        // Never proceed past a TLS error: cancel and surface natively.
        handler?.cancel()
        onEvent(ArenaNavEvent.SslError(error?.url ?: lastLoadedUrl.orEmpty()))
    }

    override fun onRenderProcessGone(
        view: WebView?,
        detail: RenderProcessGoneDetail?,
    ): Boolean {
        // Returning true tells the framework we handled it; the wrapper
        // tears down and recreates the WebView surface.
        onEvent(ArenaNavEvent.RenderProcessGone)
        return true
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url?.toString() ?: return false
        if (isArenaUrl(url)) return false // load in-place
        // External URL: block in-WebView navigation, let host decide.
        onEvent(ArenaNavEvent.External(url))
        return !allowExternalNavigation(url)
    }

    companion object {
        fun isArenaUrl(url: String): Boolean = isAllowedArenaOrigin(url)

        // Mirror of WebResourceError codes without requiring API constants
        // in unit tests (values match android.net.http.WebResourceError).
        private const val ERROR_HOST_LOOKUP = -2
        private const val ERROR_CONNECT = -6
        private const val ERROR_TIMEOUT = -8
        private const val ERROR_IO = -9
        private const val ERROR_FAILED_SSL_HANDSHAKE = -11
    }
}
