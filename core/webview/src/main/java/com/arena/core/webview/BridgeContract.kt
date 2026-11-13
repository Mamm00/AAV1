package com.arena.core.webview

import org.json.JSONObject

/**
 * Typed JS ↔ Kotlin bridge contract.
 *
 * Wire format: a single JSON envelope posted in both directions.
 *
 * ```json
 * { "v": 1, "type": "sendPrompt", "id": "optional-correlation-id", "payload": { … } }
 * ```
 *
 * Directions:
 * - **Kotlin → JS** (native surfaces drive the page):
 *   [BridgeMessage.SendPrompt], [BridgeMessage.Vote],
 *   [BridgeMessage.SwitchModel], [BridgeMessage.AttachFile].
 * - **JS → Kotlin** (the page reports streaming/agent state to native UI):
 *   [BridgeMessage.StreamEvent], [BridgeMessage.AgentTaskUpdate],
 *   [BridgeMessage.Ready], [BridgeMessage.Ack].
 *
 * The TypeScript mirror of this contract lives in `contracts/arena-bridge.ts`
 * and MUST be updated together with this file (see README → Bridge contract).
 *
 * Security: every inbound message is verified against
 * [isAllowedArenaOrigin] both by the WebMessageListener origin rules and a
 * redundant in-Kotlin check (defence in depth). Never log payloads — they can
 * contain prompts, responses, and tokens.
 */
sealed interface BridgeMessage {

    /** correlation id echoed in [Ack]; null for fire-and-forget messages. */
    val id: String?

    // ------------------------------------------------------ Kotlin → JS -- //

    /** Send a prompt into the active conversation (voice, clipboard, native UI). */
    data class SendPrompt(
        val text: String,
        val modelId: String? = null,
        override val id: String? = null,
    ) : BridgeMessage

    /** Cast a Battle vote for side A / B / tie / both-bad. */
    data class Vote(
        val choice: VoteChoice,
        val generationId: String? = null,
        override val id: String? = null,
    ) : BridgeMessage {
        enum class VoteChoice { A, B, TIE, BOTH_BAD }
    }

    /** Switch the active model from the native ModelPickerBottomSheet. */
    data class SwitchModel(
        val modelId: String,
        val slot: Int = 0,
        override val id: String? = null,
    ) : BridgeMessage

    /** Hand a locally-picked file to the page for upload. */
    data class AttachFile(
        val name: String,
        val mimeType: String,
        val sizeBytes: Long,
        val uri: String,
        override val id: String? = null,
    ) : BridgeMessage

    // -------------------------------------------------------- JS → Kotlin -- //

    /** Page finished bootstrapping and installed its side of the bridge. */
    data class Ready(
        val route: String? = null,
        override val id: String? = null,
    ) : BridgeMessage

    /**
     * One SSE chunk forwarded by the page so native UI (response cards,
     * vote-from-notification, agent timeline) can render without polling.
     * [raw] is the SSE `data:` payload including its `a0:`-style prefix.
     */
    data class StreamEvent(
        val raw: String,
        val slot: Int = 0,
        override val id: String? = null,
    ) : BridgeMessage

    /** Agent Mode progress: web search / file processing / code / report. */
    data class AgentTaskUpdate(
        val taskId: String,
        val stepId: String,
        val kind: String,
        val title: String,
        val status: String,
        val detail: String? = null,
        override val id: String? = null,
    ) : BridgeMessage

    /** Page acknowledged a Kotlin → JS command. */
    data class Ack(
        val ofId: String,
        val ok: Boolean,
        val error: String? = null,
        override val id: String? = null,
    ) : BridgeMessage

    /** Unknown/future message type — surfaced for protocol-drift telemetry. */
    data class Unknown(val type: String, override val id: String? = null) : BridgeMessage
}

/**
 * Origin allow-list for the JS bridge.
 *
 * Only `https://arena.ai` and its subdomains may talk to Kotlin. This mirrors
 * the `allowedOriginRules` passed to `WebViewCompat.addWebMessageListener`.
 * Deliberately rejects `*.arena.ai.evil.example`, credentials, ports, and
 * non-https schemes.
 */
fun isAllowedArenaOrigin(origin: String?): Boolean {
    if (origin.isNullOrBlank()) return false
    val trimmed = origin.trim().trimEnd('/')
    if (!trimmed.startsWith("https://")) return false
    val host = trimmed.removePrefix("https://")
        .substringBefore('/')
        .substringBefore(':')
        .substringBefore('@')
    if (host.isEmpty()) return false
    return host == "arena.ai" || host.endsWith(".arena.ai")
}

/** WebMessageListener origin rules matching [isAllowedArenaOrigin]. */
val ARENA_ORIGIN_RULES: List<String> = listOf(
    "https://arena.ai",
    "https://*.arena.ai",
)

object BridgeCodec {

    const val VERSION = 1
    const val JS_OBJECT_NAME = "ArenaBridge"

    /** Serialize a Kotlin → JS command. */
    fun encode(message: BridgeMessage): String {
        val envelope = JSONObject()
        envelope.put("v", VERSION)
        envelope.put("type", message.typeName())
        message.id?.let { envelope.put("id", it) }
        envelope.put("payload", payloadOf(message))
        return envelope.toString()
    }

    /** Parse a JS → Kotlin message. Never throws: unknown types degrade to [BridgeMessage.Unknown]. */
    fun decode(raw: String): BridgeMessage? {
        val envelope = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val type = envelope.optString("type")
        if (type.isEmpty()) return null
        val id = envelope.optString("id").ifBlank { null }
        val payload = envelope.optJSONObject("payload") ?: JSONObject()
        return runCatching {
            when (type) {
                "ready" -> BridgeMessage.Ready(route = payload.optString("route").ifBlank { null }, id = id)
                "stream" -> BridgeMessage.StreamEvent(
                    raw = payload.getString("raw"),
                    slot = payload.optInt("slot", 0),
                    id = id,
                )
                "agentTaskUpdate" -> BridgeMessage.AgentTaskUpdate(
                    taskId = payload.getString("taskId"),
                    stepId = payload.getString("stepId"),
                    kind = payload.getString("kind"),
                    title = payload.optString("title", payload.getString("kind")),
                    status = payload.optString("status", "RUNNING"),
                    detail = payload.optString("detail").ifBlank { null },
                    id = id,
                )
                "ack" -> BridgeMessage.Ack(
                    ofId = payload.getString("ofId"),
                    ok = payload.optBoolean("ok", false),
                    error = payload.optString("error").ifBlank { null },
                    id = id,
                )
                // Commands are Kotlin → JS; receiving one means the page echoed
                // a command back — accept for robustness but mark unknown.
                else -> BridgeMessage.Unknown(type = type, id = id)
            }
        }.getOrDefault(BridgeMessage.Unknown(type = type, id = id))
    }

    private fun BridgeMessage.typeName(): String = when (this) {
        is BridgeMessage.SendPrompt -> "sendPrompt"
        is BridgeMessage.Vote -> "vote"
        is BridgeMessage.SwitchModel -> "switchModel"
        is BridgeMessage.AttachFile -> "attachFile"
        is BridgeMessage.Ready -> "ready"
        is BridgeMessage.StreamEvent -> "stream"
        is BridgeMessage.AgentTaskUpdate -> "agentTaskUpdate"
        is BridgeMessage.Ack -> "ack"
        is BridgeMessage.Unknown -> "unknown"
    }

    private fun payloadOf(message: BridgeMessage): JSONObject = JSONObject().apply {
        when (message) {
            is BridgeMessage.SendPrompt -> {
                put("text", message.text)
                message.modelId?.let { put("modelId", it) }
            }
            is BridgeMessage.Vote -> {
                put("choice", message.choice.name.lowercase())
                message.generationId?.let { put("generationId", it) }
            }
            is BridgeMessage.SwitchModel -> {
                put("modelId", message.modelId)
                put("slot", message.slot)
            }
            is BridgeMessage.AttachFile -> {
                put("name", message.name)
                put("mimeType", message.mimeType)
                put("sizeBytes", message.sizeBytes)
                put("uri", message.uri)
            }
            is BridgeMessage.Ready -> message.route?.let { put("route", it) }
            is BridgeMessage.StreamEvent -> {
                put("raw", message.raw)
                put("slot", message.slot)
            }
            is BridgeMessage.AgentTaskUpdate -> {
                put("taskId", message.taskId)
                put("stepId", message.stepId)
                put("kind", message.kind)
                put("title", message.title)
                put("status", message.status)
                message.detail?.let { put("detail", it) }
            }
            is BridgeMessage.Ack -> {
                put("ofId", message.ofId)
                put("ok", message.ok)
                message.error?.let { put("error", it) }
            }
            is BridgeMessage.Unknown -> Unit
        }
    }
}
