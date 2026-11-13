package com.arena.feature.arena.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arena.feature.arena.components.rememberArenaHaptics

/**
 * AI-native adaptive home.
 *
 * - Mode cards are ordered by local usage frequency (see [HomeViewModel]).
 * - Clipboard-to-prompt: when the app resumes and the clipboard holds code or
 *   a long text block, a SuggestionChip offers to paste it as a prompt.
 * - Quick tools (image editor, code editor, leaderboard) sit in the thumb
 *   zone at the bottom; every tap target is ≥ 48dp.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeAdaptiveScreen(
    onOpenMode: (HomeMode) -> Unit,
    onOpenLeaderboard: () -> Unit,
    onOpenImageEditor: () -> Unit,
    onOpenCodeEditor: () -> Unit,
    onPromptFromClipboard: (String) -> Unit = { HomePromptBus.publish(it) },
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val haptics = rememberArenaHaptics()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    // Clipboard-to-prompt shortcut: re-check every time the app resumes.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                clipboard.getText()?.text?.let { text ->
                    if (text.isNotBlank()) viewModel.onClipboardDetected(text)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // One-shot effects → navigation callbacks.
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                HomeEffect.OpenLeaderboard -> onOpenLeaderboard()
                HomeEffect.OpenImageEditor -> onOpenImageEditor()
                HomeEffect.OpenCodeEditor -> onOpenCodeEditor()
                is HomeEffect.OpenMode -> {
                    haptics(HapticFeedbackType.TextHandleMove)
                    onOpenMode(effect.mode)
                }
                is HomeEffect.PromptFromClipboard -> onPromptFromClipboard(effect.text)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Arena",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                actions = {
                    IconButton(
                        onClick = {
                            haptics(HapticFeedbackType.TextHandleMove)
                            viewModel.onIntent(HomeIntent.OpenLeaderboard)
                        },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.Filled.Leaderboard, contentDescription = "Leaderboard")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Clipboard suggestion chip (dismissable, privacy-friendly: local only).
            state.clipboardSuggestion?.let { suggestion ->
                item(key = "clipboard") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SuggestionChip(
                            onClick = { viewModel.onIntent(HomeIntent.PasteClipboard(suggestion)) },
                            label = { Text("Paste clipboard as prompt") },
                        )
                        FilledTonalButton(
                            onClick = { viewModel.onIntent(HomeIntent.DismissClipboardSuggestion) },
                            modifier = Modifier
                                .height(48.dp)
                                .padding(horizontal = 4.dp),
                        ) {
                            Text("Dismiss")
                        }
                    }
                }
            }

            // Adaptive mode cards — order IS the adaptation.
            items(state.modes, key = { it.mode.name }) { mode ->
                ModeCard(
                    mode = mode,
                    onClick = { viewModel.onIntent(HomeIntent.OpenMode(mode.mode)) },
                )
            }

            // Thumb-zone quick tools.
            item(key = "tools") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        FilledTonalButton(
                            onClick = {
                                haptics(HapticFeedbackType.TextHandleMove)
                                viewModel.onIntent(HomeIntent.OpenImageEditor)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                        ) {
                            Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Editor")
                        }
                        FilledTonalButton(
                            onClick = {
                                haptics(HapticFeedbackType.TextHandleMove)
                                viewModel.onIntent(HomeIntent.OpenCodeEditor)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                        ) {
                            Icon(Icons.Filled.Code, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Code")
                        }
                        FilledTonalButton(
                            onClick = {
                                haptics(HapticFeedbackType.TextHandleMove)
                                viewModel.onIntent(HomeIntent.OpenLeaderboard)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                        ) {
                            Icon(Icons.Filled.EmojiEvents, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Ranks")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeCard(mode: HomeModeUi, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "${mode.title} mode" },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = mode.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = when (mode.usageRank) {
                        0 -> "★ Most used"
                        else -> ""
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = mode.body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Tiny hand-off so clipboard prompts can cross the navigation boundary
 * without leaking through saved instance state (privacy: prompt text is not
 * persisted to disk).
 */
object HomePromptBus {
    @Volatile
    private var pending: String? = null

    fun publish(text: String) {
        pending = text
    }

    fun consume(default: String? = null): String? = pending.also { pending = null } ?: default
}
