package com.arena.core.sse

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.Reader

/**
 * Non-blocking Server-Sent Events parser for Arena.ai streaming responses.
 *
 * Implements the WHATWG SSE state machine (field parsing, comment lines,
 * multi-line `data:` joining, event dispatch on blank line) and layers Arena's
 * custom data prefixes on top:
 *
 * | Prefix | Decodes to                          | Direction hint      |
 * |--------|-------------------------------------|---------------------|
 * | `a0:`  | [ArenaEvent.TextDelta] slot 0       | primary assistant   |
 * | `a1:`  | [ArenaEvent.ReasoningDelta] slot 0  | collapsible thinking|
 * | `a2:`  | [ArenaEvent.TextDelta] slot 1       | battle B / right pane|
 * | `ag:`  | [ArenaEvent.ModelAnnounce]          | post-vote reveal    |
 * | `ad:`  | [ArenaEvent.Done] (+usage)          | terminal marker     |
 * | `a3:`  | structured JSON (agent/bash/usage/error/reasoning) | Agent Mode |
 *
 * Guarantees:
 *  - Never throws on malformed input: protocol drift yields
 *    [ArenaEvent.ProtocolWarning] and parsing continues.
 *  - Never blocks the main thread: reader overloads run on [Dispatchers.IO].
 *  - Never logs payloads (they may contain prompts/responses/tokens).
 *
 * @see SseParserTest in `core/sse/src/test` for the behavioural contract.
 */
class SseParser {

    /**
     * Parse a live byte/text stream (e.g. `response.body.charStream()` from
     * OkHttp). Completes when [reader] reaches EOF; a trailing frame without a
     * terminating blank line is discarded per the SSE specification.
     */
    fun parse(reader: Reader): Flow<ArenaEvent> = flow {
        val buffer = FrameBuffer()
        reader.buffered().use { r ->
            while (true) {
                val line = r.readLine() ?: break
                buffer.feed(line).forEach { emit(it) }
            }
            // EOF: partial frame without blank line is dropped by spec.
            buffer.abort()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Parse a flow of already-split lines (no terminators). Used by tests and
     * by callers that own their own reading loop (e.g. a pre-buffered cache).
     */
    fun parseLines(lines: Flow<String>): Flow<ArenaEvent> = flow {
        val buffer = FrameBuffer()
        lines.collect { line ->
            buffer.feed(line).forEach { emit(it) }
        }
    }

    /**
     * Decode one fully-assembled SSE frame (its `event:` type and joined
     * `data:` payload) into zero or more typed events. Exposed for callers
     * that receive complete frames (e.g. tests, replay tooling).
     */
    fun decodeFrame(type: String?, data: String): List<ArenaEvent> = decode(type, data)

    // --------------------------------------------------------------------- //

    private class FrameBuffer {
        private val data = StringBuilder()
        private var event: String? = null
        private var hasData = false

        /** Feed one line (terminator already stripped). Returns dispatched events. */
        fun feed(rawLine: String): List<ArenaEvent> {
            if (rawLine.isEmpty()) return dispatch()
            if (rawLine.startsWith(COMMENT_PREFIX)) return emptyList() // comment / heartbeat

            val colonIndex = rawLine.indexOf(':')
            val field: String
            val value: String
            if (colonIndex == -1) {
                field = rawLine
                value = ""
            } else {
                field = rawLine.substring(0, colonIndex)
                val rawValue = rawLine.substring(colonIndex + 1)
                // SSE strips exactly one leading space after the colon.
                value = if (rawValue.startsWith(" ")) rawValue.substring(1) else rawValue
            }

            when (field) {
                FIELD_DATA -> {
                    if (hasData) data.append('\n')
                    data.append(value)
                    hasData = true
                }
                FIELD_EVENT -> event = value
                FIELD_ID -> { /* last-event-id not required for streaming chat */ }
                FIELD_RETRY -> { /* reconnect tuning handled by OkHttp caller */ }
                else -> { /* unknown fields ignored per spec */ }
            }
            return emptyList()
        }

        /** Drop any partially assembled frame (EOF / caller abort). */
        fun abort() {
            data.setLength(0)
            event = null
            hasData = false
        }

        private fun dispatch(): List<ArenaEvent> {
            if (!hasData) {
                event = null
                return emptyList()
            }
            val payload = data.toString()
            val type = event
            abort()
            return decode(type, payload)
        }
    }

    private fun decode(type: String?, data: String): List<ArenaEvent> = when {
        data.startsWith(PREFIX_TEXT_A0) -> listOf(ArenaEvent.TextDelta(data.substring(3), slot = 0))
        data.startsWith(PREFIX_REASONING_A1) -> listOf(ArenaEvent.ReasoningDelta(data.substring(3), slot = 0))
        data.startsWith(PREFIX_TEXT_A2) -> listOf(ArenaEvent.TextDelta(data.substring(3), slot = 1))
        data.startsWith(PREFIX_ANNOUNCE_AG) -> listOf(decodeAnnounce(data.substring(3)))
        data.startsWith(PREFIX_DONE_AD) -> listOf(decodeDone(data.substring(3)))
        data.startsWith(PREFIX_STRUCTURED_A3) -> decodeStructured(data.substring(3))
        else -> decodePlainEvent(type, data)
    }

    private fun decodeAnnounce(payload: String): ArenaEvent {
        val trimmed = payload.trim()
        if (trimmed.startsWith("{")) {
            return runCatching {
                val json = org.json.JSONObject(trimmed)
                ArenaEvent.ModelAnnounce(
                    slot = json.optInt("slot", 0),
                    modelId = json.getString("modelId"),
                    displayName = json.optString("displayName", json.getString("modelId")),
                    provider = json.optString("provider").ifBlank { null },
                    revealed = json.optBoolean("revealed", true),
                )
            }.getOrElse {
                ArenaEvent.ProtocolWarning("malformed-ag-payload")
            }
        }
        // Plain-text fallback: "modelId" or "modelId|Display Name".
        val parts = payload.split('|', limit = 2)
        return ArenaEvent.ModelAnnounce(
            slot = 0,
            modelId = parts[0],
            displayName = parts.getOrElse(1) { parts[0] },
        )
    }

    private fun decodeDone(payload: String): ArenaEvent {
        val trimmed = payload.trim()
        if (trimmed.isEmpty()) return ArenaEvent.Done()
        if (trimmed.startsWith("{")) {
            return runCatching {
                val json = org.json.JSONObject(trimmed)
                ArenaEvent.Done(
                    slot = json.optInt("slot", 0),
                    usage = json.optJSONObject("usage")?.let { usageFrom(it) },
                )
            }.getOrElse { ArenaEvent.ProtocolWarning("malformed-ad-payload") }
        }
        return ArenaEvent.Done()
    }

    private fun decodeStructured(payload: String): List<ArenaEvent> {
        val trimmed = payload.trim()
        if (!trimmed.startsWith("{")) {
            return listOf(ArenaEvent.ProtocolWarning("malformed-a3-payload"))
        }
        return runCatching {
            val json = org.json.JSONObject(trimmed)
            when (val kind = json.optString("kind")) {
                "agent" -> listOf(
                    ArenaEvent.AgentStep(
                        stepId = json.getString("stepId"),
                        kind = json.getString("type"),
                        title = json.optString("title", json.getString("type")),
                        status = parseStepStatus(json.optString("status", "RUNNING")),
                        detail = json.optString("detail").ifBlank { null },
                    ),
                )
                "bash" -> listOf(
                    ArenaEvent.BashOutput(
                        chunk = json.getString("chunk"),
                        stream = if (json.optString("stream") == "stderr") {
                            ArenaEvent.BashOutput.Stream.STDERR
                        } else {
                            ArenaEvent.BashOutput.Stream.STDOUT
                        },
                    ),
                )
                "usage" -> listOf(usageFrom(json)),
                "reasoning" -> listOf(
                    ArenaEvent.ReasoningDelta(
                        text = json.getString("text"),
                        slot = json.optInt("slot", 0),
                    ),
                )
                "error" -> listOf(
                    ArenaEvent.StreamError(
                        code = json.optString("code", "stream"),
                        message = json.optString("message", "stream error"),
                    ),
                )
                else -> listOf(ArenaEvent.ProtocolWarning("unknown-a3-kind:$kind"))
            }
        }.getOrElse { listOf(ArenaEvent.ProtocolWarning("malformed-a3-json")) }
    }

    private fun usageFrom(json: org.json.JSONObject): ArenaEvent.Usage = ArenaEvent.Usage(
        inputTokens = json.optInt("inputTokens", -1).takeIf { it >= 0 },
        outputTokens = json.optInt("outputTokens", -1).takeIf { it >= 0 },
        latencyMs = json.optLong("latencyMs", -1L).takeIf { it >= 0 },
    )

    private fun parseStepStatus(raw: String): ArenaEvent.AgentStep.Status =
        runCatching { ArenaEvent.AgentStep.Status.valueOf(raw.uppercase()) }
            .getOrDefault(ArenaEvent.AgentStep.Status.RUNNING)

    private fun decodePlainEvent(type: String?, data: String): List<ArenaEvent> = when (type) {
        null, "", "message", "update" -> listOf(ArenaEvent.TextDelta(data, slot = 0))
        "error" -> listOf(ArenaEvent.StreamError(code = "sse", message = data))
        "usage" -> runCatching { listOf(usageFrom(org.json.JSONObject(data))) }
            .getOrElse { listOf(ArenaEvent.ProtocolWarning("malformed-usage")) }
        else -> listOf(ArenaEvent.ProtocolWarning("unknown-event-type:$type"))
    }

    private companion object {
        const val COMMENT_PREFIX = ":"
        const val FIELD_DATA = "data"
        const val FIELD_EVENT = "event"
        const val FIELD_ID = "id"
        const val FIELD_RETRY = "retry"

        const val PREFIX_TEXT_A0 = "a0:"
        const val PREFIX_REASONING_A1 = "a1:"
        const val PREFIX_TEXT_A2 = "a2:"
        const val PREFIX_ANNOUNCE_AG = "ag:"
        const val PREFIX_DONE_AD = "ad:"
        const val PREFIX_STRUCTURED_A3 = "a3:"
    }
}
