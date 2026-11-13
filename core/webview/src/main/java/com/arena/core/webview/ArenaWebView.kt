package com.arena.core.webview

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/**
 * Persistable WebView session state.
 *
 * `rememberSaveable` keeps this across configuration changes and process
 * death, which prevents the classic recomposition loop where a WebView is
 * re-created (and re-navigated) on every rotation.
 */
data class ArenaWebSession(
    val currentUrl: String = SpeculativeLoader.ARENA_ORIGIN,
    val isOffline: Boolean = false,
    val isLoading: Boolean = true,
    val canGoBack: Boolean = false,
) {
    companion object {
        val Saver: Saver<ArenaWebSession, Bundle> = Saver(
            save = { state ->
                Bundle().apply {
                    putString("url", state.currentUrl)
                    putBoolean("offline", state.isOffline)
                    putBoolean("loading", state.isLoading)
                    putBoolean("canGoBack", state.canGoBack)
                }
            },
            restore = { bundle ->
                ArenaWebSession(
                    currentUrl = bundle.getString("url") ?: SpeculativeLoader.ARENA_ORIGIN,
                    isOffline = bundle.getBoolean("offline"),
                    isLoading = bundle.getBoolean("loading", true),
                    canGoBack = bundle.getBoolean("canGoBack"),
                )
            },
        )
    }
}

/**
 * The WebView engine surface.
 *
 * Applies the performance checklist from the spec:
 *  - Back-forward cache via `WebSettingsCompat.setBackForwardCacheEnabled`
 *  - HTTP cache tuned to [WebSettings.LOAD_DEFAULT] so SSE/auth are untouched
 *  - Typed bridge via [ArenaBridge] (never `addJavascriptInterface`)
 *  - `rememberSaveable` session state across configuration changes
 *  - Main-frame errors surfaced as [ArenaNavEvent.Offline] for the native
 *    offline fallback (cached model list + last conversation)
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ArenaWebView(
    initialUrl: String,
    bridge: ArenaBridge,
    modifier: Modifier = Modifier,
    session: ArenaWebSession = ArenaWebSession(),
    onSessionChanged: (ArenaWebSession) -> Unit = {},
    onNavEvent: (ArenaNavEvent) -> Unit = {},
    onWebViewReady: (WebView) -> Unit = {},
) {
    var state by rememberSaveable(stateSaver = ArenaWebSession.Saver) { mutableStateOf(session) }

    // Latest callbacks without re-creating the WebView on every recomposition.
    val navCallback = rememberLatest(onNavEvent)
    val sessionCallback = rememberLatest(onSessionChanged)
    val readyCallback = rememberLatest(onWebViewReady)

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                applyArenaSettings(this)
                webViewClient = ArenaWebViewClient(
                    onEvent = { event ->
                        when (event) {
                            is ArenaNavEvent.Started ->
                                state = state.copy(isLoading = true, currentUrl = event.url)
                            is ArenaNavEvent.Finished ->
                                state = state.copy(isLoading = false, isOffline = false)
                            is ArenaNavEvent.Offline ->
                                state = state.copy(isOffline = true, isLoading = false)
                            is ArenaNavEvent.Online ->
                                state = state.copy(isOffline = false)
                            is ArenaNavEvent.RenderProcessGone ->
                                state = state.copy(isLoading = true)
                            else -> Unit
                        }
                        navCallback(event)
                        sessionCallback(state)
                    },
                )
                bridge.attach(this)
                loadUrl(initialUrl)
                readyCallback(this)
            }
        },
        update = { webView ->
            // Keep back-forward state fresh without touching load behaviour.
            val canGoBack = webView.canGoBack()
            if (canGoBack != state.canGoBack) {
                state = state.copy(canGoBack = canGoBack)
                sessionCallback(state)
            }
        },
        onRelease = { webView ->
            bridge.detach()
            runCatching { webView.destroy() }
        },
    )

    // Surface session changes to the caller whenever they mutate internally.
    LaunchedEffect(state) { sessionCallback(state) }
}

/** Apply the tuned settings required by the spec. */
@SuppressLint("SetJavaScriptEnabled")
fun applyArenaSettings(webView: WebView) {
    webView.settings.apply {
        javaScriptEnabled = true // the Next.js SPA requires it
        domStorageEnabled = true
        databaseEnabled = true
        // Auth + streaming safety: never allow mixed content.
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        // Cache policy: default HTTP cache so SSE and cookies are unaffected;
        // offline fallback is handled natively, not via LOAD_CACHE_ONLY.
        cacheMode = WebSettings.LOAD_DEFAULT
        mediaPlaybackRequiresUserGesture = true
        setSupportZoom(false)
        builtInZoomControls = false
        displayZoomControls = false
        // Accessibility scaling stays at default; system font scale is applied
        // by the Compose surfaces, not by reflowing the SPA.
        textZoom = 100
        // Scoped storage hardening for the document.
        allowFileAccess = false
        allowContentAccess = false
        // Back-forward cache (bfcache): critical for instant mode switches.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.BACK_FORWARD_CACHE)) {
            WebSettingsCompat.setBackForwardCacheEnabled(this, true)
        }
        // Safe browsing starts automatically on API 26+; no manual opt-in
        // needed (and the explicit callback API differs across WebView
        // versions, so we avoid binding to it here).
    }
}

/** Small helper to keep callbacks latest without recreating state holders. */
@Composable
private fun <T> rememberLatest(value: T): (T) -> Unit {
    var latest by remember { mutableStateOf(value) }
    latest = value
    return { latest(it) }
}

/**
 * Native offline fallback surface. Shown when the main frame errors; the
 * caller supplies cached model names and the last conversation so the screen
 * stays useful without connectivity.
 */
@Composable
fun OfflineFallbackSurface(
    cachedModels: List<String>,
    lastConversationPreview: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "You're offline",
    body: String = "Showing your cached model list and last conversation. Reconnect to stream new responses.",
    retryLabel: String = "Retry",
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineSmall)
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (cachedModels.isNotEmpty()) {
            Text(
                text = "Cached models (${cachedModels.size})",
                style = MaterialTheme.typography.titleMedium,
            )
            cachedModels.forEach { model ->
                Text(
                    text = model,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        lastConversationPreview?.let { preview ->
            Text(
                text = preview,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onRetry) { Text(retryLabel) }
    }
}
