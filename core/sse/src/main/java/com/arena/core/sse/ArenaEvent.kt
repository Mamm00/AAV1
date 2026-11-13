package com.arena.core.sse

/**
 * Typed stream events decoded from Arena.ai's Server-Sent Events.
 *
 * Arena multiplexes several logical channels over one SSE connection using
 * custom data prefixes (see [SseParser]). Each variant maps to exactly one
 * prefix so UI layers never parse raw protocol bytes.
 */
sealed interface ArenaEvent {

    /**
     * Incremental assistant text.
     *
     * @property slot 0 = primary model (battle side A / direct), 1 = secondary
     * model (battle side B / side-by-side right pane).
     */
    data class TextDelta(val text: String, val slot: Int = 0) : ArenaEvent

    /** Collapsible reasoning/thinking delta rendered inside a response card. */
    data class ReasoningDelta(val text: String, val slot: Int = 0) : ArenaEvent

    /**
     * Model identity announcement. In Battle Mode this arrives only after the
     * user votes; before the vote the UI must keep identities masked.
     */
    data class ModelAnnounce(
        val slot: Int,
        val modelId: String,
        val displayName: String,
        val provider: String? = null,
        val revealed: Boolean = true,
    ) : ArenaEvent

    /** Server-assigned generation id, useful for cancelling/regenerating. */
    data class GenerationStart(val generationId: String, val slot: Int = 0) : ArenaEvent

    /** Structured Agent Mode step (web search, file processing, …). */
    data class AgentStep(
        val stepId: String,
        val kind: String,
        val title: String,
        val status: Status,
        val detail: String? = null,
    ) : ArenaEvent {
        enum class Status { PENDING, RUNNING, DONE, FAILED }
    }

    /** Raw Bash/terminal output chunk from the agent sandbox. */
    data class BashOutput(val chunk: String, val stream: Stream = Stream.STDOUT) : ArenaEvent {
        enum class Stream { STDOUT, STDERR }
    }

    /** Token/latency usage reported with completion. */
    data class Usage(
        val inputTokens: Int? = null,
        val outputTokens: Int? = null,
        val latencyMs: Long? = null,
    ) : ArenaEvent

    /** Terminal success marker for one generation. */
    data class Done(val slot: Int = 0, val usage: Usage? = null) : ArenaEvent

    /** Recoverable stream error; the connection may continue afterwards. */
    data class StreamError(val code: String, val message: String) : ArenaEvent

    /**
     * Non-fatal protocol anomaly (unknown prefix, malformed JSON payload).
     * Surfaced so tests/telemetry can count drift without ever crashing a
     * live stream. Logging of payloads is forbidden (may contain prompts).
     */
    data class ProtocolWarning(val reason: String) : ArenaEvent
}
