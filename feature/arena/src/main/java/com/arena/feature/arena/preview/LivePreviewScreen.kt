package com.arena.feature.arena.preview

import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arena.core.webview.OfflineFallbackSurface

/**
 * Live Preview tab — arena.ai rendered full-screen in a pooled WebView with
 * the [com.arena.core.webview.PreviewPerformanceAddon] fast-path:
 *
 *  - first open: prewarmed socket + (if cached) instant shell from disk;
 *  - re-entry: the SAME renderer reattaches — no reload, no re-bootstrap;
 *  - Back: predictive, in-page history first (frame restore via bfcache),
 *    then the tab pops;
 *  - offline: native fallback surface over the last rendered frame.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LivePreviewScreen(
    onBack: () -> Unit,
    viewModel: LivePreviewViewModel = hiltViewModel(),
) {
    val controller = viewModel.controller
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }

    // Browser-style Back: pop in-page history first, otherwise leave the tab.
    BackHandler(enabled = state.canGoBack) {
        webView?.let(controller::goBack)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Live Preview") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { webView?.let(controller::refresh) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Renderer death bumps the generation → fresh WebView here only.
            key(state.rendererGeneration) {
                AndroidView(
                    factory = {
                        controller.obtain(context).also { webView = it }
                    },
                    update = { controller.syncBackState(it) },
                    onRelease = { /* pooled on purpose: reuse on re-entry */ },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (state.loading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                )
            }

            if (state.offline) {
                OfflineFallbackSurface(
                    cachedModels = emptyList(),
                    lastConversationPreview = null,
                    onRetry = { webView?.let(controller::refresh) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
