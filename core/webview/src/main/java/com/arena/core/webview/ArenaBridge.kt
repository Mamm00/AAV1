package com.arena.core.webview

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebMessage
import android.webkit.WebView
import androidx.webkit.WebMessageListener
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch

/**
 * Secure typed JS ↔ Kotlin bridge built on `WebViewCompat.addWebMessageListener`.
 *
 * Why not `addJavascriptInterface()`? That API exposes arbitrary Java methods
 * to any script on the page. [ArenaBridge] only accepts a single JSON string
 * through a message listener, restricted to arena.ai origins
 * ([ARENA_ORIGIN_RULES]), and every inbound message is re-verified in Kotlin
 * with [isAllowedArenaOrigin] before it is parsed (defence in depth).
 *
 * Threading: inbound messages are parsed on the WebView thread and delivered
 * as a hot [SharedFlow] with buffer 64 (DROP_OLDEST) so a burst of stream
 * chunks can never wedge the renderer. Outbound messages are marshalled to
 * the main thread automatically.
 */
class ArenaBridge(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) {

    private val _messages = MutableSharedFlow<BridgeMessage>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Inbound stream of verified bridge messages (JS → Kotlin). */
    val messages: SharedFlow<BridgeMessage> = _messages.asSharedFlow()

    /** Set once the page reports `ready`; native UI waits for this before sending commands. */
    @Volatile
    var pageReady: Boolean = false
        private set

    private var attachedWebView: WebView? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * The listener registered with [WebViewCompat.addWebMessageListener].
     * [sourceOrigin] is provided by WebKit from the document's real origin —
     * it cannot be spoofed by page script.
     */
    val listener = WebMessageListener { message, sourceOrigin, _ ->
        if (!isAllowedArenaOrigin(sourceOrigin)) return@WebMessageListener
        val payload = message?.data ?: return@WebMessageListener
        val decoded = BridgeCodec.decode(payload) ?: return@WebMessageListener
        if (decoded is BridgeMessage.Ready) pageReady = true
        _messages.tryEmit(decoded)
    }

    /**
     * Register the bridge on [webView]. Must be called before the page loads.
     * The injected JS object name is [BridgeCodec.JS_OBJECT_NAME]
     * (`window.ArenaBridge.postMessage(json)`).
     */
    @SuppressLint("RequiresFeature")
    fun attach(webView: WebView) {
        attachedWebView = webView
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(
                webView,
                BridgeCodec.JS_OBJECT_NAME,
                ARENA_ORIGIN_RULES,
                listener,
            )
        }
    }

    fun detach() {
        attachedWebView = null
        pageReady = false
    }

    /**
     * Send a typed command to the page (Kotlin → JS).
     *
     * Uses `WebView.postWebMessage` with target origin `https://arena.ai` so
     * the payload can never reach a different document; falls back to
     * `evaluateJavascript` on very old WebKit builds.
     */
    fun postToJs(message: BridgeMessage) {
        val json = BridgeCodec.encode(message)
        mainHandler.post {
            val webView = attachedWebView ?: return@post
            runCatching {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                    webView.postWebMessage(WebMessage(json), Uri.parse(ORIGIN_ARENA))
                } else {
                    webView.evaluateJavascript(
                        "window.${BridgeCodec.JS_OBJECT_NAME}?.onNativeMessage?.(${jsStringLiteral(json)});",
                        null,
                    )
                }
            }
        }
    }

    /** Ask the page to cancel an in-flight generation (native "stop" button). */
    fun cancelGeneration(generationId: String) {
        mainHandler.post {
            attachedWebView?.evaluateJavascript(
                "window.${BridgeCodec.JS_OBJECT_NAME}?.cancel?.(${jsStringLiteral(generationId)});",
                null,
            )
        }
    }

    /** Run [block] once the page has reported ready (or immediately if it has). */
    fun whenReady(block: () -> Unit) {
        scope.launch {
            if (pageReady) {
                block()
            } else {
                messages.takeWhile { it !is BridgeMessage.Ready }.collect { /* wait */ }
                block()
            }
        }
    }

    private fun jsStringLiteral(value: String): String = buildString(value.length + 2) {
        append('\'')
        value.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '\'' -> append("\\'")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\u2028' -> append("\\u2028")
                '\u2029' -> append("\\u2029")
                '<' -> append("\\u003c") // prevent breaking out of script context
                else -> append(c)
            }
        }
        append('\'')
    }

    private companion object {
        const val ORIGIN_ARENA = "https://arena.ai"
    }
}
