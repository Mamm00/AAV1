package com.arena.core.webview

import android.content.Context
import android.os.SystemClock
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.ArrayDeque

/**
 * On-disk cache for speculatively prefetched Arena pages (tier 2).
 *
 * Mirrors the WebKit `PrefetchCache` semantics requested by the spec:
 *  - [setMaxPrefetches] caps how many full pages are retained (memory/network
 *    trade-off; default 2: `/chat` and `/arena`).
 *  - [setPrefetchTtlSeconds] bounds staleness (default 15 minutes) so a
 *    prefetched document never serves an auth-stale shell for long.
 *
 * Entries are plain HTML shells keyed by absolute URL, LRU-evicted. Auth
 * cookies are NEVER written here — the live WebView session owns those; the
 * cache only warms parse/compile of the static shell and speeds first paint.
 */
class PrefetchCache(
    private val rootDir: File,
    private var maxPrefetches: Int = 2,
    private var prefetchTtlSeconds: Long = 900L,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {

    init {
        require(maxPrefetches >= 1) { "maxPrefetches must be >= 1" }
        rootDir.mkdirs()
    }

    fun setMaxPrefetches(max: Int) {
        require(max >= 1)
        maxPrefetches = max
        evictIfNeeded()
    }

    fun setPrefetchTtlSeconds(ttl: Long) {
        require(ttl >= 0)
        prefetchTtlSeconds = ttl
    }

    fun put(url: String, body: ByteArray) {
        val file = fileFor(url) ?: return
        runCatching {
            file.writeBytes(body)
            // Touch for LRU ordering.
            file.setLastModified(clock())
        }
        evictIfNeeded()
    }

    fun get(url: String): CachedPage? {
        val file = fileFor(url) ?: return null
        if (!file.exists()) return null
        val ageSeconds = (clock() - file.lastModified()) / 1000
        if (ageSeconds > prefetchTtlSeconds) {
            file.delete()
            return null
        }
        file.setLastModified(clock()) // refresh LRU position on hit
        return runCatching { CachedPage(url, file.readBytes(), ageSeconds) }.getOrNull()
    }

    fun clear() {
        rootDir.listFiles()?.forEach { it.delete() }
    }

    fun size(): Int = rootDir.listFiles()?.count { it.isFile } ?: 0

    data class CachedPage(val url: String, val body: ByteArray, val ageSeconds: Long) {
        override fun equals(other: Any?): Boolean =
            other is CachedPage && other.url == url && other.body.contentEquals(body)
        override fun hashCode(): Int = url.hashCode() * 31 + body.contentHashCode()
    }

    private fun fileFor(url: String): File? {
        // Stable, filesystem-safe key: hash of the URL.
        val name = Integer.toHexString(url.hashCode())
        return File(rootDir, "$name.html")
    }

    private fun evictIfNeeded() {
        val files = rootDir.listFiles { f -> f.isFile && f.name.endsWith(".html") }
            ?.sortedBy { it.lastModified() }
            ?: return
        val excess = files.size - maxPrefetches
        if (excess <= 0) return
        files.take(excess).forEach { it.delete() }
    }
}

/**
 * Three-tier speculative loading strategy for arena.ai:
 *
 * | Tier | Trigger                                   | Mechanism                          |
 * |------|-------------------------------------------|------------------------------------|
 * | 1    | App cold start (before first UI frame)    | [preconnect] TCP/TLS/QUIC warm-up  |
 * | 2    | User authenticates / navigation predicted | [prefetch] `/chat` + `/arena` shells|
 * | 3    | User taps New Chat (certainty is high)    | [prerender] preloaded WebView      |
 *
 * Tier 3 is deliberately NEVER triggered from app launch: prerendering is
 * high-resource (full renderer + JS heap) and would regress cold start and
 * memory on low-end devices — exactly what the spec forbids.
 */
class SpeculativeLoader(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val prefetchCache: PrefetchCache,
) {

    private var preconnected = false
    private var prefetched = false
    private var prerenderWebView: WebView? = null

    /**
     * Tier 1 — preconnect to https://arena.ai during cold start.
     *
     * Opens a socket early so DNS + TCP + TLS (and QUIC where the OkHttp
     * client negotiated it via `addQuicHints`) are warm before the WebView
     * requests the document. Idempotent; safe to call from any thread.
     */
    fun preconnect() {
        if (preconnected) return
        preconnected = true
        Thread(
            {
                runCatching {
                    val request = Request.Builder()
                        .url("$ARENA_ORIGIN/")
                        .head()
                        .build()
                    okHttpClient.newBuilder()
                        .connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                        .newCall(request)
                        .execute()
                        .close()
                }
            },
            "arena-preconnect",
        ).start()
    }

    /**
     * Tier 2 — prefetch the chat and arena shells once the user is
     * authenticated (or navigation intent is predicted). Responses are stored
     * in [prefetchCache]; the WebView can serve them instantly (network
     * response cache / offline fallback) while the live session revalidates.
     */
    suspend fun prefetch(): PrefetchResult = withContext(Dispatchers.IO) {
        if (prefetched) return@withContext PrefetchResult(alreadyDone = true)
        prefetched = true

        val urls = listOf("$ARENA_ORIGIN/chat", "$ARENA_ORIGIN/arena")
        var hits = 0
        var misses = 0
        for (url in urls) {
            val cached = prefetchCache.get(url)
            if (cached != null) {
                hits++
                continue
            }
            runCatching {
                val request = Request.Builder().url(url).get().build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.bytes() ?: ByteArray(0)
                        prefetchCache.put(url, body)
                        hits++
                    } else {
                        misses++
                    }
                }
            }.onFailure { misses++ }
        }
        PrefetchResult(hits = hits, misses = misses, alreadyDone = false)
    }

    /**
     * Tier 3 — prerender the primary chat view. Call ONLY when navigation
     * certainty is high (e.g. the user tapped "New Chat" but the route has
     * not resolved yet). The caller owns the lifecycle: release via
     * [releasePrerender] when the real navigation completes or 30s elapse.
     *
     * @param provideWebView factory that returns a configured, hidden WebView
     * (settings/back-forward cache/bridge already applied by the wrapper).
     */
    fun prerender(provideWebView: () -> WebView) {
        if (prerenderWebView != null) return
        val webView = provideWebView()
        prerenderWebView = webView
        // Loading the URL off-screen warms the renderer, JIT, and caches.
        webView.loadUrl("$ARENA_ORIGIN/chat")
        // Hard safety valve: never hold a prerendered renderer indefinitely.
        webView.postDelayed({ releasePrerender() }, PRERENDER_TTL_MS)
    }

    /** Drop the tier-3 WebView (called after real navigation or timeout). */
    fun releasePrerender() {
        val webView = prerenderWebView ?: return
        prerenderWebView = null
        runCatching { webView.stopLoading() }
        runCatching { webView.destroy() }
    }

    /** Teardown on app exit. */
    fun shutdown() {
        releasePrerender()
        preconnected = false
        prefetched = false
    }

    data class PrefetchResult(
        val hits: Int = 0,
        val misses: Int = 0,
        val alreadyDone: Boolean = false,
    ) {
        val hitRate: Double
            get() = if (hits + misses == 0) 0.0 else hits.toDouble() / (hits + misses)
    }

    companion object {
        const val ARENA_ORIGIN = "https://arena.ai"
        private const val PRERENDER_TTL_MS = 30_000L

        /** Cache dir used by [PrefetchCache] under the app cache directory. */
        fun defaultCacheDir(context: Context): File =
            File(context.cacheDir, "speculative").apply { mkdirs() }
    }
}
