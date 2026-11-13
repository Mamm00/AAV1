package com.arena.feature.arena.preview

import com.arena.core.webview.ArenaNavEvent
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pure-JVM tests for the Live Preview reducer (no WebView required). */
class LivePreviewStateTest {

    @Test
    fun started_showsLoading_andClearsOffline() {
        val state = PreviewUiState(loading = false, offline = true)
            .reduce(ArenaNavEvent.Started("https://arena.ai/"))
        assertThat(state.loading).isTrue()
        assertThat(state.offline).isFalse()
    }

    @Test
    fun finished_clearsLoadingAndOffline() {
        val state = PreviewUiState(offline = true)
            .reduce(ArenaNavEvent.Finished("https://arena.ai/"))
        assertThat(state.loading).isFalse()
        assertThat(state.offline).isFalse()
    }

    @Test
    fun offline_mainFrameFailure_surfacesFallback() {
        val state = PreviewUiState().reduce(ArenaNavEvent.Offline("https://arena.ai/", -2))
        assertThat(state.offline).isTrue()
        assertThat(state.loading).isFalse()
    }

    @Test
    fun online_afterFallback_clearsOffline() {
        val state = PreviewUiState(offline = true)
            .reduce(ArenaNavEvent.Online("https://arena.ai/"))
        assertThat(state.offline).isFalse()
    }

    @Test
    fun sslError_neverRendersPage_showsFallbackInstead() {
        val state = PreviewUiState().reduce(ArenaNavEvent.SslError("https://arena.ai/"))
        assertThat(state.offline).isTrue()
        assertThat(state.loading).isFalse()
    }

    @Test
    fun renderProcessGone_bumpsGeneration_soTheHostRecreatesTheFrame() {
        val before = PreviewUiState(rendererGeneration = 3)
        val state = before.reduce(ArenaNavEvent.RenderProcessGone)
        assertThat(state.rendererGeneration).isEqualTo(4)
        assertThat(state.loading).isTrue()
        // Unrelated events must not churn the renderer.
        assertThat(before.reduce(ArenaNavEvent.External("https://example.com")).rendererGeneration)
            .isEqualTo(3)
    }
}
