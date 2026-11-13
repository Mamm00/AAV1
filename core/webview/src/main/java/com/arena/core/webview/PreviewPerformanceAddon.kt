package com.arena.core.webview

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Performance addon for the Live Preview tab — makes arena.ai itself respond
 * as fast as possible inside the app ("super fast responding").
 *
 * Four levers, all safe on API 26+:
 *
 *  1. **Connection prewarm** — [prewarm] opens DNS + TCP + TLS to arena.ai
 *     right before the tab is shown. The cold-start warm-up (tier 1) may have
 *     expired by then, so each fresh tab entry gets its own warm socket
 *     (rate-limited by [PREWARM_COOLDOWN_MS] so repeated taps stay cheap).
 *  2. **Rendered-frame reuse** — the owning `LivePreviewController` keeps ONE
 *     WebView alive across tab entries; leaving and re-entering reattaches
 *     the same renderer: no reload, no re-bootstrap, scroll position and SPA
 *     state intact.
 *  3. **Instant shell serving** — [servingClient] answers matching main-frame
 *     navigations straight from the tier-2 [PrefetchCache] (byte-identical
 *     HTML, 15-minute TTL) so first paint does not wait on the network, while
 *     the SPA revalidates its own data over the wire.
 *  4. **Back-forward cache** — inherited from [applyArenaSettings] (the
 *     wrapper never calls `stopLoading()` from `onPageFinished`), so Back is a
 *     frame restore instead of a load.
 */
object PreviewPerformanceAddon {

    /** What the Live Preview tab loads: the arena.ai website itself. */
    const val PREVIEW_URL = "${SpeculativeLoader.ARENA_ORIGIN}/"

    /** Minimum spacing between two warm-ups (re-entry storms stay cheap). */
    const val PREWARM_COOLDOWN_MS = 60_000L

    /**
     * Forces the mobile viewport before layout: create the meta if the page
     * has none, otherwise overwrite it — device-width wins either way, so the
     * document lays out exactly like mobile Chrome instead of at whatever
     * fixed/desktop width the site declares (or omits).
     */
    internal const val ENFORCE_VIEWPORT_JS =
        "(function(){try{var h=document.head||document.documentElement;" +
            "var m=document.querySelector('meta[name=viewport]');" +
            "if(!m){m=document.createElement('meta');m.setAttribute('name','viewport');h.appendChild(m);}" +
            "m.setAttribute('content','width=device-width, initial-scale=1');}catch(e){}})();"

    private val warmed = AtomicBoolean(false)

    /**
     * Tuned settings layered on the shared arena profile: HTTP cache stays
     * fully enabled so Back/forward and tab re-entry resolve from disk, and
     * JS/DOM storage come from [applyArenaSettings] (SPA requires them).
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun tune(webView: WebView) {
        applyArenaSettings(webView)
        // Resolution/fit fix: honour the site's meta viewport, fit the WHOLE
        // page width on load (overview zoom-out for wide desktop layouts),
        // and let the system pick the initial scale (0 = fit, not 100%).
        webView.setInitialScale(0)
        webView.settings.apply {
            // Chrome-parity user agent: strip the WebView markers ("; wv;"
            // and "Version/4.0") so arena.ai serves EXACTLY the document it
            // serves mobile Chrome — same engine, same layout, same page.
            // (Existing arena.ai WebView clients all ship UA normalisation
            // for this reason.)
            userAgentString = WebSettings.getDefaultUserAgent(webView.context)
                .replace("; wv;", "; ")
                .replace("Version/4.0 ", "")
            useWideViewPort = true
            loadWithOverviewMode = true
            // Preview is a browsing surface: pinch in/out so dense pages stay
            // readable even after the fit-to-width overview.
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            // Preview is read-mostly browsing: keep platform HTTP caching on.
            cacheMode = WebSettings.LOAD_DEFAULT
            // Images already load by default; ensure the renderer may fetch
            // them even before they scroll into view for snappier paging.
            loadsImagesAutomatically = true
        }
    }

    /**
     * Fresh tier-1 style warm-up for this tab entry: a HEAD request opens the
     * socket path, then (if missing) the root HTML shell is fetched into the
     * prefetch cache so the NEXT entry can paint instantly from disk. After
     * [PREWARM_COOLDOWN_MS] the gate re-arms so later re-entries can warm
     * again (idempotent within the window; safe from any thread).
     */
    fun prewarm(cache: PrefetchCache?) {
        if (!warmed.compareAndSet(false, true)) return
        Thread(
            {
                val result = runCatching {
                    val client = OkHttpClient.Builder()
                        .connectTimeout(2, TimeUnit.SECONDS)
                        .readTimeout(4, TimeUnit.SECONDS)
                        .build()
                    // Socket warm-up: DNS + TCP + TLS to arena.ai.
                    client.newCall(
                        Request.Builder().url(PREVIEW_URL).head().build(),
                    ).execute().close()
                    // Refresh the shell the interceptor can serve instantly.
                    if (cache != null && cache.get(PREVIEW_URL) == null) {
                        client.newCall(
                            Request.Builder().url(PREVIEW_URL).get().build(),
                        ).execute().use { response ->
                            if (response.isSuccessful) {
                                cache.put(PREVIEW_URL, response.body?.bytes() ?: ByteArray(0))
                            }
                        }
                    }
                }
                // Re-arm the gate — on success after the cooldown, on failure
                // immediately so a bad network doesn't disable future warms.
                if (result.isSuccess) Thread.sleep(PREWARM_COOLDOWN_MS)
                warmed.set(false)
            },
            "preview-prewarm",
        ).apply {
            priority = Thread.NORM_PRIORITY - 1
            start()
        }
    }

    /**
     * Nav-event client with instant prefetch-cache serving for matching
     * main-frame documents (everything else goes to the network).
     */
    fun servingClient(
        cache: PrefetchCache?,
        onEvent: (ArenaNavEvent) -> Unit,
    ): ArenaWebViewClient = PrefetchServingClient(cache, onEvent)

    private class PrefetchServingClient(
        private val cache: PrefetchCache?,
        onEvent: (ArenaNavEvent) -> Unit,
    ) : ArenaWebViewClient(onEvent) {
        /** Enforce the mobile viewport as early as the document exists … */
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            view?.evaluateJavascript(ENFORCE_VIEWPORT_JS, null)
        }

        /** … and again once committed (overwrite any meta the parser added). */
        override fun onPageCommitVisible(view: WebView?, url: String?) {
            super.onPageCommitVisible(view, url)
            view?.evaluateJavascript(ENFORCE_VIEWPORT_JS, null)
        }

        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?,
        ): WebResourceResponse? {
            if (request?.isForMainFrame != true) return null
            val url = request.url?.toString() ?: return null
            val hit = cache?.get(url) ?: return null
            return runCatching {
                WebResourceResponse("text/html", "UTF-8", hit.body.inputStream())
            }.getOrNull()
        }
    }
}
