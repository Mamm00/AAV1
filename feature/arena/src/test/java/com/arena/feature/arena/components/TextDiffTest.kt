package com.arena.feature.arena.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Side-by-side diff contract. */
class TextDiffTest {

    @Test
    fun `identical texts produce only equal chunks`() {
        val chunks = TextDiff.diff("hello brave world", "hello brave world")
        assertThat(chunks.all { it is TextDiff.Chunk.Equal }).isTrue()
        assertThat(chunks.joinToString(" ") { it.text }).isEqualTo("hello brave world")
    }

    @Test
    fun `single word change shows remove and add`() {
        val chunks = TextDiff.diff("the cat sat", "the dog sat")
        assertThat(chunks).contains(TextDiff.Chunk.Removed("cat"))
        assertThat(chunks).contains(TextDiff.Chunk.Added("dog"))
        assertThat(chunks.filterIsInstance<TextDiff.Chunk.Equal>().map { it.text })
            .containsExactly("the", "sat")
            .inOrder()
    }

    @Test
    fun `pure insertion produces only added chunks for the tail`() {
        val chunks = TextDiff.diff("one", "one two three")
        val added = chunks.filterIsInstance<TextDiff.Chunk.Added>().map { it.text }
        assertThat(added).containsExactly("two", "three").inOrder()
        assertThat(chunks.filterIsInstance<TextDiff.Chunk.Removed>()).isEmpty()
    }

    @Test
    fun `pure deletion produces only removed chunks`() {
        val chunks = TextDiff.diff("one two three", "one")
        val removed = chunks.filterIsInstance<TextDiff.Chunk.Removed>().map { it.text }
        assertThat(removed).containsExactly("two", "three").inOrder()
        assertThat(chunks.filterIsInstance<TextDiff.Chunk.Added>()).isEmpty()
    }

    @Test
    fun `identical helper trims whitespace`() {
        assertThat(TextDiff.identical("  a  ", "a")).isTrue()
        assertThat(TextDiff.identical("a", "b")).isFalse()
    }

    @Test
    fun `pathological input is capped without throwing`() {
        val huge = List(TextDiff.MAX_TOKENS + 500) { "t$it" }.joinToString(" ")
        val chunks = TextDiff.diff(huge, huge.reversed())
        // Never throws; output stays bounded by 2 * MAX_TOKENS chunks.
        assertThat(chunks.size).isAtMost(2 * TextDiff.MAX_TOKENS)
    }

    @Test
    fun `empty left side marks everything added`() {
        val chunks = TextDiff.diff("", "fresh start")
        assertThat(chunks.filterIsInstance<TextDiff.Chunk.Added>().map { it.text })
            .containsExactly("fresh", "start")
            .inOrder()
    }
}
