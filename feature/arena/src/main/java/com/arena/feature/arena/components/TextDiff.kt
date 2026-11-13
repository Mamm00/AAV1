package com.arena.feature.arena.components

/**
 * Word-level diff between two model outputs for the Side-by-Side diff toggle.
 *
 * Classic LCS dynamic programming over whitespace-delimited tokens.
 * Inputs are capped at [MAX_TOKENS] per side so a pathological output can
 * never hitch the UI thread (extra tokens are compared as a single tail
 * block).
 */
object TextDiff {

    const val MAX_TOKENS = 2_000

    sealed interface Chunk {
        val text: String

        data class Equal(override val text: String) : Chunk
        data class Added(override val text: String) : Chunk
        data class Removed(override val text: String) : Chunk
    }

    /** Tokenize preserving reconstructable spacing (single space between tokens). */
    fun tokenize(text: String): List<String> =
        text.split(Regex("\\s+")).filter { it.isNotEmpty() }

    fun diff(left: String, right: String): List<Chunk> {
        val a = tokenize(left).limit()
        val b = tokenize(right).limit()

        val n = a.size
        val m = b.size
        // LCS length table.
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (a[i] == b[j]) {
                    dp[i + 1][j + 1] + 1
                } else {
                    maxOf(dp[i + 1][j], dp[i][j + 1])
                }
            }
        }

        val chunks = ArrayList<Chunk>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> {
                    chunks += Chunk.Equal(a[i])
                    i++; j++
                }
                dp[i + 1][j] >= dp[i][j + 1] -> {
                    chunks += Chunk.Removed(a[i])
                    i++
                }
                else -> {
                    chunks += Chunk.Added(b[j])
                    j++
                }
            }
        }
        while (i < n) chunks += Chunk.Removed(a[i++])
        while (j < m) chunks += Chunk.Added(b[j++])
        return chunks
    }

    /** True when the two outputs are word-for-word identical. */
    fun identical(left: String, right: String): Boolean = left.trim() == right.trim()

    private fun List<String>.limit(): List<String> =
        if (size <= MAX_TOKENS) this else take(MAX_TOKENS)
}
