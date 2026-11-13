package com.arena.core.sse

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.StringReader

/**
 * Behavioural contract for [SseParser]. These tests pin the wire format so
 * protocol drift in Arena.ai surfaces as a failing test instead of a blank
 * chat bubble in production.
 */
class SseParserTest {

    private val parser = SseParser()

    private suspend fun parseLines(vararg lines: String): List<ArenaEvent> =
        parser.parseLines(flowOf(*lines)).toList()

    // -------------------------------------------------------------- frames //

    @Test
    fun `dispatches data on blank line`() = runTest {
        val events = parseLines("data: hello world", "")
        assertThat(events).containsExactly(ArenaEvent.TextDelta("hello world", 0))
    }

    @Test
    fun `joins multiple data lines with newline`() = runTest {
        val events = parseLines("data: line one", "data: line two", "")
        assertThat(events).containsExactly(ArenaEvent.TextDelta("line one\nline two", 0))
    }

    @Test
    fun `ignores comment lines`() = runTest {
        val events = parseLines(": heartbeat", "data: after comment", "")
        assertThat(events).containsExactly(ArenaEvent.TextDelta("after comment", 0))
    }

    @Test
    fun `does not dispatch until blank line`() = runTest {
        val events = parseLines("data: pending")
        assertThat(events).isEmpty()
    }

    @Test
    fun `parses event field with plain payload`() = runTest {
        val events = parseLines("event: error", "data: boom", "")
        assertThat(events).containsExactly(ArenaEvent.StreamError("sse", "boom"))
    }

    @Test
    fun `field without colon sets empty value and does not crash`() = runTest {
        val events = parseLines("data", "")
        assertThat(events).containsExactly(ArenaEvent.TextDelta("", 0))
    }

    @Test
    fun `strips single leading space after colon only`() = runTest {
        val events = parseLines("data:  double space", "")
        // One space is stripped by SSE; the second is content.
        assertThat(events).containsExactly(ArenaEvent.TextDelta(" double space", 0))
    }

    @Test
    fun `unknown fields are ignored`() = runTest {
        val events = parseLines("retry: 3000", "id: abc", "data: ok", "")
        assertThat(events).containsExactly(ArenaEvent.TextDelta("ok", 0))
    }

    @Test
    fun `consecutive blank lines dispatch only once`() = runTest {
        val events = parseLines("data: once", "", "", "data: twice", "")
        assertThat(events).containsExactly(
            ArenaEvent.TextDelta("once", 0),
            ArenaEvent.TextDelta("twice", 0),
        )
    }

    @Test
    fun `crlf reader input parses end to end`() = runTest {
        val reader = StringReader("data: a0:Hi\r\n\r\ndata: a2:Yo\r\n\r\n")
        val events = parser.parse(reader).toList()
        assertThat(events).containsExactly(
            ArenaEvent.TextDelta("Hi", 0),
            ArenaEvent.TextDelta("Yo", 1),
        ).inOrder()
    }

    // ------------------------------------------------------------ prefixes //

    @Test
    fun `a0 prefix decodes to slot 0 text delta`() = runTest {
        val events = parseLines("data: a0:Hello", "")
        assertThat(events).containsExactly(ArenaEvent.TextDelta("Hello", 0))
    }

    @Test
    fun `a2 prefix decodes to slot 1 text delta`() = runTest {
        val events = parseLines("data: a2:Hello B", "")
        assertThat(events).containsExactly(ArenaEvent.TextDelta("Hello B", 1))
    }

    @Test
    fun `a1 prefix decodes to reasoning delta`() = runTest {
        val events = parseLines("data: a1:thinking…", "")
        assertThat(events).containsExactly(ArenaEvent.ReasoningDelta("thinking…", 0))
    }

    @Test
    fun `ag prefix decodes json announce`() = runTest {
        val payload =
            """ag:{"slot":1,"modelId":"m-b","displayName":"Model B","provider":"acme","revealed":true}"""
        val events = parseLines("data: $payload", "")
        assertThat(events).containsExactly(
            ArenaEvent.ModelAnnounce(
                slot = 1,
                modelId = "m-b",
                displayName = "Model B",
                provider = "acme",
                revealed = true,
            ),
        )
    }

    @Test
    fun `ag prefix falls back to plain model id`() = runTest {
        val events = parseLines("data: ag:gpt-x|GPT X", "")
        assertThat(events).containsExactly(
            ArenaEvent.ModelAnnounce(slot = 0, modelId = "gpt-x", displayName = "GPT X"),
        )
    }

    @Test
    fun `ag malformed json yields protocol warning not crash`() = runTest {
        val events = parseLines("data: ag:{not-json", "")
        assertThat(events).containsExactly(ArenaEvent.ProtocolWarning("malformed-ag-payload"))
    }

    @Test
    fun `ad prefix with no payload is bare done`() = runTest {
        val events = parseLines("data: ad:", "")
        assertThat(events).containsExactly(ArenaEvent.Done(slot = 0, usage = null))
    }

    @Test
    fun `ad prefix carries usage`() = runTest {
        val payload = """ad:{"slot":0,"usage":{"inputTokens":12,"outputTokens":34,"latencyMs":900}}"""
        val events = parseLines("data: $payload", "")
        assertThat(events).containsExactly(
            ArenaEvent.Done(
                slot = 0,
                usage = ArenaEvent.Usage(inputTokens = 12, outputTokens = 34, latencyMs = 900),
            ),
        )
    }

    @Test
    fun `a3 agent step decodes`() = runTest {
        val payload =
            """a3:{"kind":"agent","stepId":"s1","type":"web_search","title":"Searching","status":"RUNNING"}"""
        val events = parseLines("data: $payload", "")
        assertThat(events).containsExactly(
            ArenaEvent.AgentStep(
                stepId = "s1",
                kind = "web_search",
                title = "Searching",
                status = ArenaEvent.AgentStep.Status.RUNNING,
                detail = null,
            ),
        )
    }

    @Test
    fun `a3 bash output decodes with stderr stream`() = runTest {
        val payload = """a3:{"kind":"bash","chunk":"permission denied","stream":"stderr"}"""
        val events = parseLines("data: $payload", "")
        assertThat(events).containsExactly(
            ArenaEvent.BashOutput("permission denied", ArenaEvent.BashOutput.Stream.STDERR),
        )
    }

    @Test
    fun `a3 unknown kind yields warning`() = runTest {
        val events = parseLines("""data: a3:{"kind":"telepathy"}""", "")
        assertThat(events).containsExactly(ArenaEvent.ProtocolWarning("unknown-a3-kind:telepathy"))
    }

    @Test
    fun `a3 non-json payload yields warning`() = runTest {
        val events = parseLines("data: a3:raw text", "")
        assertThat(events).containsExactly(ArenaEvent.ProtocolWarning("malformed-a3-payload"))
    }

    @Test
    fun `a3 reasoning kind decodes`() = runTest {
        val payload = """a3:{"kind":"reasoning","text":"step by step","slot":1}"""
        val events = parseLines("data: $payload", "")
        assertThat(events).containsExactly(ArenaEvent.ReasoningDelta("step by step", 1))
    }

    @Test
    fun `a3 usage kind decodes`() = runTest {
        val payload = """a3:{"kind":"usage","outputTokens":42}"""
        val events = parseLines("data: $payload", "")
        assertThat(events).containsExactly(
            ArenaEvent.Usage(inputTokens = null, outputTokens = 42, latencyMs = null),
        )
    }

    // ------------------------------------------------------- robustness --- //

    @Test
    fun `unknown event type yields warning not crash`() = runTest {
        val events = parseLines("event: custom", "data: hi", "")
        assertThat(events).containsExactly(ArenaEvent.ProtocolWarning("unknown-event-type:custom"))
    }

    @Test
    fun `trailing frame without blank line is discarded`() = runTest {
        val events = parseLines("data: unfinished")
        assertThat(events).isEmpty()
    }

    @Test
    fun `decodeFrame is pure and repeatable`() {
        val a = parser.decodeFrame(null, "a0:x")
        val b = parser.decodeFrame(null, "a0:x")
        assertThat(a).isEqualTo(b)
        assertThat(a).containsExactly(ArenaEvent.TextDelta("x", 0))
    }

    @Test
    fun `stream of many frames preserves order`() = runTest {
        val lines = buildList {
            repeat(50) { i ->
                add("data: a0:chunk-$i")
                add("")
            }
        }
        val events = parseLines(*lines.toTypedArray())
        assertThat(events).hasSize(50)
        assertThat(events.first()).isEqualTo(ArenaEvent.TextDelta("chunk-0", 0))
        assertThat(events.last()).isEqualTo(ArenaEvent.TextDelta("chunk-49", 0))
    }
}
