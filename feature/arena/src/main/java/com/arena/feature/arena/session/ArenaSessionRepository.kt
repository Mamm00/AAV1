package com.arena.feature.arena.session

import android.webkit.WebView
import com.arena.core.sse.ArenaEvent
import com.arena.core.sse.SseParser
import com.arena.core.webview.ArenaBridge
import com.arena.core.webview.BridgeMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Single bridge between native feature surfaces and the arena.ai page.
 *
 * - Owns the [ArenaBridge] (typed WebMessageListener contract).
 * - Translates raw `stream` bridge messages into typed [ArenaEvent]s with
 *   [SseParser] so every screen consumes the same event model.
 * - Commands are queued until the page reports ready, then flushed — native
 *   UI never races the SPA bootstrap.
 */
class ArenaSessionRepository(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    private val parser: SseParser = SseParser(),
) {

    val bridge = ArenaBridge(scope)

    private val _events = MutableSharedFlow<ArenaEvent>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Typed stream events decoded from bridge `stream` messages. */
    val events: SharedFlow<ArenaEvent> = _events.asSharedFlow()

    private val _pageReady = MutableStateFlow(false)
    val pageReady: StateFlow<Boolean> = _pageReady.asStateFlow()

    private val pendingCommands = ArrayDeque<BridgeMessage>()

    init {
        scope.launch {
            bridge.messages.collect { message ->
                when (message) {
                    is BridgeMessage.Ready -> {
                        _pageReady.value = true
                        flushPending()
                    }
                    is BridgeMessage.StreamEvent -> {
                        // One bridge message may carry several prefixed payloads.
                        parser.decodeFrame(null, message.raw).forEach { event ->
                            _events.tryEmit(event)
                        }
                    }
                    is BridgeMessage.AgentTaskUpdate -> {
                        _events.tryEmit(
                            ArenaEvent.AgentStep(
                                stepId = message.stepId,
                                kind = message.kind,
                                title = message.title,
                                status = parseStatus(message.status),
                                detail = message.detail,
                            ),
                        )
                    }
                    else -> Unit
                }
            }
        }
    }

    /** Attach to the WebView that hosts arena.ai (call before loadUrl). */
    fun attach(webView: WebView) = bridge.attach(webView)

    fun detach() {
        bridge.detach()
        _pageReady.value = false
    }

    // ------------------------------------------------- Kotlin → JS commands //

    fun sendPrompt(text: String, modelId: String? = null) =
        enqueue(BridgeMessage.SendPrompt(text = text, modelId = modelId))

    fun vote(choice: BridgeMessage.Vote.VoteChoice, generationId: String? = null) =
        enqueue(BridgeMessage.Vote(choice = choice, generationId = generationId))

    fun switchModel(modelId: String, slot: Int = 0) =
        enqueue(BridgeMessage.SwitchModel(modelId = modelId, slot = slot))

    fun attachFile(name: String, mimeType: String, sizeBytes: Long, uri: String) =
        enqueue(
            BridgeMessage.AttachFile(
                name = name,
                mimeType = mimeType,
                sizeBytes = sizeBytes,
                uri = uri,
            ),
        )

    private fun enqueue(message: BridgeMessage) {
        if (_pageReady.value) {
            bridge.postToJs(message)
        } else {
            pendingCommands.addLast(message)
            // Bounded queue: a page that never boots must not leak memory.
            while (pendingCommands.size > MAX_PENDING) pendingCommands.removeFirst()
        }
    }

    private fun flushPending() {
        while (pendingCommands.isNotEmpty()) {
            bridge.postToJs(pendingCommands.removeFirst())
        }
    }

    private fun parseStatus(raw: String): ArenaEvent.AgentStep.Status =
        runCatching { ArenaEvent.AgentStep.Status.valueOf(raw.uppercase()) }
            .getOrDefault(ArenaEvent.AgentStep.Status.RUNNING)

    private companion object {
        const val MAX_PENDING = 32
    }
}
