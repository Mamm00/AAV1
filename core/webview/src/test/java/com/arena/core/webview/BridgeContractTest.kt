package com.arena.core.webview

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** JVM tests for origin allow-listing and the JSON bridge codec. */
class BridgeContractTest {

    // ------------------------------------------------------------- origins //

    @Test
    fun `exact arena origin is allowed`() {
        assertThat(isAllowedArenaOrigin("https://arena.ai")).isTrue()
    }

    @Test
    fun `subdomains of arena are allowed`() {
        assertThat(isAllowedArenaOrigin("https://www.arena.ai")).isTrue()
        assertThat(isAllowedArenaOrigin("https://chat.arena.ai")).isTrue()
    }

    @Test
    fun `lookalike domains are rejected`() {
        assertThat(isAllowedArenaOrigin("https://arena.ai.evil.example")).isFalse()
        assertThat(isAllowedArenaOrigin("https://evilarena.ai")).isFalse()
        assertThat(isAllowedArenaOrigin("https://arena.ai.example.com")).isFalse()
        assertThat(isAllowedArenaOrigin("https://xarena.ai")).isFalse()
    }

    @Test
    fun `insecure and malformed origins are rejected`() {
        assertThat(isAllowedArenaOrigin("http://arena.ai")).isFalse()
        assertThat(isAllowedArenaOrigin("null")).isFalse()
        assertThat(isAllowedArenaOrigin("")).isFalse()
        assertThat(isAllowedArenaOrigin(null)).isFalse()
        assertThat(isAllowedArenaOrigin("https://arena.ai:8443")).isTrue() // port ok
        assertThat(isAllowedArenaOrigin("https://user@arena.ai")).isTrue() // creds stripped
        assertThat(isAllowedArenaOrigin("javascript:alert(1)")).isFalse()
    }

    // --------------------------------------------------------------- codec //

    @Test
    fun `outbound vote round-trips through encode`() {
        val json = BridgeCodec.encode(
            BridgeMessage.Vote(
                choice = BridgeMessage.Vote.VoteChoice.A,
                generationId = "gen-1",
                id = "cmd-9",
            ),
        )
        assertThat(json).contains("\"type\":\"vote\"")
        assertThat(json).contains("\"choice\":\"a\"")
        assertThat(json).contains("\"generationId\":\"gen-1\"")
        assertThat(json).contains("\"cmd-9\"")
        assertThat(json).contains("\"v\":1")
    }

    @Test
    fun `inbound stream event decodes`() {
        val raw = """{"v":1,"type":"stream","id":"s1","payload":{"raw":"a0:Hi","slot":0}}"""
        val msg = BridgeCodec.decode(raw)
        assertThat(msg).isInstanceOf(BridgeMessage.StreamEvent::class.java)
        msg as BridgeMessage.StreamEvent
        assertThat(msg.raw).isEqualTo("a0:Hi")
        assertThat(msg.slot).isEqualTo(0)
        assertThat(msg.id).isEqualTo("s1")
    }

    @Test
    fun `inbound agent task update decodes`() {
        val raw = """
            {"v":1,"type":"agentTaskUpdate","payload":{
              "taskId":"t1","stepId":"s2","kind":"code_execution",
              "title":"Running tests","status":"RUNNING","detail":"pytest -q"}}
        """.trimIndent()
        val msg = BridgeCodec.decode(raw) as BridgeMessage.AgentTaskUpdate
        assertThat(msg.taskId).isEqualTo("t1")
        assertThat(msg.kind).isEqualTo("code_execution")
        assertThat(msg.status).isEqualTo("RUNNING")
        assertThat(msg.detail).isEqualTo("pytest -q")
    }

    @Test
    fun `inbound ready marks readiness payload`() {
        val msg = BridgeCodec.decode("""{"v":1,"type":"ready","payload":{"route":"/chat"}}""")
        assertThat(msg).isEqualTo(BridgeMessage.Ready(route = "/chat", id = null))
    }

    @Test
    fun `unknown type degrades to Unknown instead of throwing`() {
        val msg = BridgeCodec.decode("""{"v":1,"type":"telepathy"}""")
        assertThat(msg).isEqualTo(BridgeMessage.Unknown(type = "telepathy", id = null))
    }

    @Test
    fun `garbage payload returns null`() {
        assertThat(BridgeCodec.decode("not json at all")).isNull()
        assertThat(BridgeCodec.decode("{}")).isNull()
        assertThat(BridgeCodec.decode("""{"type":""}""")).isNull()
    }

    @Test
    fun `attach file message encodes uri and metadata`() {
        val json = BridgeCodec.encode(
            BridgeMessage.AttachFile(
                name = "notes.pdf",
                mimeType = "application/pdf",
                sizeBytes = 2048,
                uri = "content://media/1",
                id = "f1",
            ),
        )
        assertThat(json).contains("\"type\":\"attachFile\"")
        assertThat(json).contains("notes.pdf")
        assertThat(json).contains("application/pdf")
        assertThat(json).contains("content://media/1")
    }
}
