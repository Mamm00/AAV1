package com.arena.feature.arena.sidebyside

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arena.feature.arena.components.ResponseCard
import com.arena.feature.arena.components.TextDiff
import com.arena.feature.arena.modelpicker.ModelPickerBottomSheet

/**
 * Side-by-Side Mode: synchronized prompt input, two named models, and a diff
 * toggle that highlights textual differences between the outputs.
 * Adapts from two columns (wide) to stacked panes (compact).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SideBySideScreen(
    onBack: () -> Unit,
    viewModel: SideBySideViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is SbsEffect.ShowNotice -> snackbar.showSnackbar(effect.message)
            }
        }
    }
    LaunchedEffect(state.notice) {
        state.notice?.let { snackbar.showSnackbar(it); viewModel.onIntent(SbsIntent.ClearNotice) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Side-by-Side", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    FilterChip(
                        selected = state.diffEnabled,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            viewModel.onIntent(SbsIntent.ToggleDiff)
                        },
                        label = { Text(if (state.diffEnabled) "Hide diff" else "Show diff") },
                        modifier = Modifier.padding(end = 12.dp),
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = state.prompt,
                    onValueChange = { viewModel.onIntent(SbsIntent.PromptChanged(it)) },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("One prompt, two models") },
                    maxLines = 3,
                )
                Button(
                    onClick = { viewModel.onIntent(SbsIntent.Submit) },
                    enabled = state.prompt.isNotBlank(),
                    modifier = Modifier.height(48.dp),
                ) { Text("Run") }
            }

            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                val wide = maxWidth >= 600.dp
                val diffChunks = remember(state.diffEnabled, state.left.content, state.right.content) {
                    if (state.diffEnabled) {
                        TextDiff.diff(state.left.content, state.right.content)
                    } else emptyList()
                }

                if (wide) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SbsPane(
                            title = state.left.model?.displayName ?: "Left",
                            subtitle = "Left",
                            content = state.left.content,
                            diffChunks = if (state.diffEnabled) diffChunks.filter {
                                it !is TextDiff.Chunk.Added
                            } else emptyList(),
                            streaming = state.left.streaming,
                            onPickModel = { viewModel.onIntent(SbsIntent.OpenPicker(0)) },
                            modifier = Modifier.weight(1f),
                        )
                        SbsPane(
                            title = state.right.model?.displayName ?: "Right",
                            subtitle = "Right",
                            content = state.right.content,
                            diffChunks = if (state.diffEnabled) diffChunks.filter {
                                it !is TextDiff.Chunk.Removed
                            } else emptyList(),
                            streaming = state.right.streaming,
                            onPickModel = { viewModel.onIntent(SbsIntent.OpenPicker(1)) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SbsPane(
                            title = state.left.model?.displayName ?: "Left",
                            subtitle = "Left",
                            content = state.left.content,
                            diffChunks = if (state.diffEnabled) diffChunks.filter {
                                it !is TextDiff.Chunk.Added
                            } else emptyList(),
                            streaming = state.left.streaming,
                            onPickModel = { viewModel.onIntent(SbsIntent.OpenPicker(0)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        SbsPane(
                            title = state.right.model?.displayName ?: "Right",
                            subtitle = "Right",
                            content = state.right.content,
                            diffChunks = if (state.diffEnabled) diffChunks.filter {
                                it !is TextDiff.Chunk.Removed
                            } else emptyList(),
                            streaming = state.right.streaming,
                            onPickModel = { viewModel.onIntent(SbsIntent.OpenPicker(1)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        state.pickerSlot?.let { slot ->
            ModelPickerBottomSheet(
                models = state.models,
                recommended = emptyList(),
                selectedModelId = if (slot == 0) state.left.model?.id else state.right.model?.id,
                onModelSelected = { viewModel.onIntent(SbsIntent.SelectModel(slot, it)) },
                onDismiss = { viewModel.onIntent(SbsIntent.OpenPicker(-1)) },
            )
        }
    }
}

@Composable
private fun SbsPane(
    title: String,
    subtitle: String,
    content: String,
    diffChunks: List<TextDiff.Chunk>,
    streaming: Boolean,
    onPickModel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$subtitle · $title",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            FilterChip(
                selected = false,
                onClick = onPickModel,
                label = { Text("Change") },
            )
        }
        Spacer(Modifier.height(6.dp))
        if (diffChunks.isNotEmpty()) {
            DiffView(diffChunks)
        } else {
            ResponseCard(
                author = title,
                content = if (streaming && content.isEmpty()) "…" else content,
            )
        }
    }
}

/** Renders LCS chunks with green/red/blue styling for add/remove/equal. */
@Composable
private fun DiffView(chunks: List<TextDiff.Chunk>) {
    val annotated = buildAnnotatedString {
        chunks.forEach { chunk ->
            when (chunk) {
                is TextDiff.Chunk.Equal -> append(chunk.text + " ")
                is TextDiff.Chunk.Added -> withStyle(
                    SpanStyle(
                        background = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.25f),
                        color = MaterialTheme.colorScheme.tertiary,
                    ),
                ) { append(chunk.text + " ") }
                is TextDiff.Chunk.Removed -> withStyle(
                    SpanStyle(
                        background = MaterialTheme.colorScheme.error.copy(alpha = 0.18f),
                        color = MaterialTheme.colorScheme.error,
                    ),
                ) { append(chunk.text + " ") }
            }
        }
    }
    Text(
        text = annotated,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            .padding(14.dp),
        style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
    )
}
