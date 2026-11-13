package com.arena.core.data.repo

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Local-only recommendation engine contract. */
class ModelRecommenderTest {

    private fun interactions(count: Int, model: String, task: String? = null) =
        List(count) { ModelRecommender.Interaction(modelId = model, taskType = task) }

    @Test
    fun `returns empty before five interactions for a task`() {
        val events = interactions(4, "gpt-5", task = "code")
        assertThat(ModelRecommender.recommend(events, taskType = "code")).isEmpty()
    }

    @Test
    fun `returns ranked models after five interactions for a task`() {
        val events = buildList {
            repeat(6) { add(ModelRecommender.Interaction("claude-opus-4.5", "code")) }
            repeat(3) { add(ModelRecommender.Interaction("gpt-5-codex", "code")) }
            repeat(9) { add(ModelRecommender.Interaction("flux-1.1-pro", "image")) } // other task
        }
        val recs = ModelRecommender.recommend(events, taskType = "code")
        assertThat(recs).containsExactly("claude-opus-4.5", "gpt-5-codex").inOrder()
    }

    @Test
    fun `image task does not leak into code recommendations`() {
        val events = buildList {
            repeat(10) { add(ModelRecommender.Interaction("sora-2", "video")) }
            repeat(5) { add(ModelRecommender.Interaction("codestral-3", "code")) }
        }
        val recs = ModelRecommender.recommend(events, taskType = "code")
        assertThat(recs).containsExactly("codestral-3")
    }

    @Test
    fun `null task aggregates across all tasks`() {
        val events = buildList {
            repeat(3) { add(ModelRecommender.Interaction("a")) }
            repeat(3) { add(ModelRecommender.Interaction("b")) }
        }
        val recs = ModelRecommender.recommend(events, taskType = null)
        assertThat(recs).containsExactly("a", "b").inOrder() // tie broken alphabetically
    }

    @Test
    fun `respects limit`() {
        val events = (1..20).flatMap { i -> interactions(1, "m$i") }
        val recs = ModelRecommender.recommend(events, taskType = null, limit = 3)
        assertThat(recs).hasSize(3)
    }

    @Test
    fun `adaptive order surfaces most used mode first`() {
        val order = ModelRecommender.adaptiveOrder(
            modeFrequency = mapOf("AGENT" to 12, "DIRECT" to 30, "BATTLE" to 4),
            defaultOrder = listOf("BATTLE", "DIRECT", "SIDE_BY_SIDE", "AGENT"),
            enabled = true,
        )
        assertThat(order.first()).isEqualTo("DIRECT")
        assertThat(order).containsExactly(
            "DIRECT", "AGENT", "BATTLE", "SIDE_BY_SIDE",
        ).inOrder()
    }

    @Test
    fun `adaptive order falls back to default when disabled or empty`() {
        val default = listOf("BATTLE", "DIRECT", "SIDE_BY_SIDE", "AGENT")
        assertThat(
            ModelRecommender.adaptiveOrder(emptyMap(), default, enabled = true),
        ).isEqualTo(default)
        assertThat(
            ModelRecommender.adaptiveOrder(mapOf("AGENT" to 5), default, enabled = false),
        ).isEqualTo(default)
    }

    @Test
    fun `adaptive order keeps unobserved modes in default sequence at the end`() {
        val default = listOf("BATTLE", "DIRECT", "SIDE_BY_SIDE", "AGENT")
        val order = ModelRecommender.adaptiveOrder(mapOf("BATTLE" to 2), default, enabled = true)
        assertThat(order).containsExactly(
            "BATTLE", "DIRECT", "SIDE_BY_SIDE", "AGENT",
        ).inOrder()
    }
}
