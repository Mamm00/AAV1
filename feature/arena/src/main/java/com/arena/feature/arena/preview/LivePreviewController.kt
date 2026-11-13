package com.arena.feature.arena.preview

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import com.arena.core.webview.ArenaNavEvent
import com.arena.core.webview.PrefetchCache
import com.arena.core.webview.PreviewPerformanceAddon
import com.arena.core.webview.SpeculativeLoader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** UI state of the Live Preview tab (pure data — unit-testable). */
data class PreviewUiState(
    /** True while the main frame is loading. */
    val loading: Boolean = true,
    /** True when the main frame failed natively (offline fallback shown). */
    val offline: Boolean = false,
    /** Drives the predictive-back "browser style" Back handling. */
    val canGoBack: Boolean = false,
    /**
     * Bumped when the renderer died so the Compose side recreates the
     * AndroidView around a fresh WebView (rendered-frame reuse broken only
     * when the frame itself is gone).
     */
    val rendererGeneration: Int = 0,
)

/** Pure reducer: navigation events → preview UI state. */
internal fun PreviewUiState.reduce(event: ArenaNavEvent): PreviewUiState = when (event) {
    is ArenaNavEvent.Started -> copy(loading = true, offline = false)
    is ArenaNavEvent.Finished -> copy(loading = false, offline = false)
    is ArenaNavEvent.Offline -> copy(loading = false, offline = true)
    is ArenaNavEvent.Online -> copy(offline = false)
    is ArenaNavEvent.SslError -> copy(loading = false, offline = true)
    is ArenaNavEvent.RenderProcessGone -> copy(
        loading = true,
        offline = false,
        rendererGeneration = rendererGeneration + 1,
    )
    else -> this
}

/**
 * Owns the single pooled WebView behind the Live Preview tab.
 *
 * Rendered-frame reuse is the core of the "super fast" contract: the WebView
 * is created once (application context — survives rotation), kept alive when
 * the tab leaves composition, and re-attached on every re-entry, so the SPA
 * never re-bootstraps while the user hops between modes. The pooled instance
 * is destroyed only when its renderer dies ([ArenaNavEvent.RenderProcessGone]
 * path) or the process does.
 *
 * All WebView callbacks arrive on the main thread that created the instance,
 * which is the same thread [obtain] runs on — the pool needs no locking.
 */
@Singleton
class LivePreviewController @Inject constructor() {

    private var pooled: WebView? = null

    private val _state = MutableStateFlow(PreviewUiState())
    val state: StateFlow<PreviewUiState> = _state.asStateFlow()

    /**
     * Returns the pooled preview WebView, creating and loading the website
     * on first use. Also kicks the addon warm-up (rate-limited) so the socket
     * path to arena.ai is hot for whatever the frame does next.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun obtain(context: Context): WebView {
        val cache = cacheFor(context)
        PreviewPerformanceAddon.prewarm(cache)

        pooled?.let { existing ->
            // Re-entry: same renderer, same document — nothing to reload.
            _state.update { it.copy(loading = false, canGoBack = existing.canGoBack()) }
            return existing
        }

        // Activity context (not applicationContext): the renderer must
        // resolve layout against the real window metrics/density — app-context
        // WebViews can mis-scale on foldables/large screens. Safe to pool:
        // MainActivity is the single activity and is not recreated on
        // rotation (configChanges), so it lives as long as the process.
        val webView = WebView(context).apply {
            PreviewPerformanceAddon.tune(this)
            webViewClient = PreviewPerformanceAddon.servingClient(cache) { event ->
                _state.update { it.reduce(event) }
            }
            loadUrl(PreviewPerformanceAddon.PREVIEW_URL)
        }
        pooled = webView
        _state.update { it.copy(loading = true, offline = false) }
        return webView
    }

    /** Refresh the current document (offline-retry / manual reload). */
    fun refresh(webView: WebView) {
        webView.reload()
    }

    /** Browser-style Back: pop in-page history first, else leave the tab. */
    fun goBack(webView: WebView) {
        if (webView.canGoBack()) webView.goBack()
        syncBackState(webView)
    }

    /** Mirror `canGoBack` into UI state (called from AndroidView update). */
    fun syncBackState(webView: WebView) {
        _state.update {
            if (it.canGoBack == webView.canGoBack()) it else it.copy(canGoBack = webView.canGoBack())
        }
    }

    /** Drop the pooled frame (renderer death / explicit teardown). */
    fun evict() {
        pooled?.let { runCatching { it.destroy() } }
        pooled = null
    }

    private fun cacheFor(context: Context): PrefetchCache =
        PrefetchCache(SpeculativeLoader.defaultCacheDir(context))
}
