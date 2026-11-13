package com.arena.feature.arena.session

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.arena.core.webview.ArenaNavEvent
import com.arena.core.webview.ArenaWebViewClient
import com.arena.core.webview.applyArenaSettings

/**
 * Hidden host for the authenticated arena.ai document.
 *
 * Why a hidden WebView at all? The native screens (Battle, Direct, …) drive
 * the real SPA through the typed bridge — votes, model switches, prompts and
 * stream events all need a live, cookie-bearing session. Rather than
 * duplicating arena.ai's backend protocol (auth, reCAPTCHA, token pools), we
 * keep **one** official WebView session and talk to it with typed messages.
 *
 * Lifecycle policy (memory-conscious):
 *  - Composed only while a mode screen is on the back stack top
 *    (see `ArenaNavHost`), NOT at app launch — this is deliberately weaker
 *    than tier-3 prerender: no speculative renderer while the user idles on
 *    Home.
 *  - 1×1 dp surface: the document still runs its JS pipeline (streaming,
 *    bridge) without any visible raster cost.
 *  - Destroyed with the composition so back-stack pops free the renderer.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SessionWebViewHost(
    session: ArenaSessionRepository,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val webView = remember {
        WebView(context).apply {
            applyArenaSettings(this)
            // Navigation events are handled natively by feature screens; the
            // hidden host only cares about keeping the session alive.
            webViewClient = ArenaWebViewClient(onEvent = { event ->
                if (event is ArenaNavEvent.RenderProcessGone) {
                    session.detach()
                }
            })
            session.attach(this)
            loadUrl(SESSION_URL)
        }
    }

    DisposableEffect(session) {
        onDispose {
            session.detach()
            runCatching { webView.destroy() }
        }
    }

    AndroidView(
        factory = { webView },
        modifier = modifier.size(1.dp),
    )
}

private const val SESSION_URL = "https://arena.ai/chat"
