package com.arena.feature.arena.battle

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arena.feature.arena.components.ResponseCard

/**
 * Battle Mode — two anonymous responses side by side.
 *
 * - Identities stay masked ("Model A" / "Model B") until the vote.
 * - Vote buttons live in the thumb zone; haptic tick on vote.
 * - When both responses finish, a heads-up notification offers A/B voting
 *   without opening the app (subject to POST_NOTIFICATIONS).
 * - Adaptive layout: panes sit side-by-side on wide screens (tablets,
 *   foldables in unfolded state) and stack on compact phones.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BattleScreen(
    onBack: () -> Unit,
    viewModel: BattleViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is BattleEffect.VoteRecorded -> {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    snackbar.showSnackbar("Vote recorded — identities revealed")
                }
                is BattleEffect.ShowNotice -> snackbar.showSnackbar(effect.message)
                BattleEffect.OfferNotificationVote -> {
                    BattleVoteNotifications.postVoteActions(
                        context = context,
                        battleId = state.battleId,
                        generationId = state.generationId,
                        summary = state.prompt.ifBlank { "Two models answered your prompt" },
                    )
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Battle", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.onIntent(BattleIntent.NewBattle) },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "New battle")
                    }
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
            // Prompt entry (top — composing the battle is a two-hand action;
            // voting later happens in the thumb zone).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = state.prompt,
                    onValueChange = { viewModel.onIntent(BattleIntent.PromptChanged(it)) },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Prompt both models…") },
                    maxLines = 3,
                )
                Button(
                    onClick = { viewModel.onIntent(BattleIntent.Submit) },
                    enabled = state.prompt.isNotBlank() &&
                        !state.paneA.streaming &&
                        !state.paneB.streaming,
                    modifier = Modifier.height(48.dp),
                ) {
                    Text("Go")
                }
            }

            // Responses: side-by-side on wide, stacked on compact.
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                val wide = maxWidth >= 600.dp
                if (wide) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        BattlePaneView(
                            pane = state.paneA,
                            label = state.paneA.revealedName ?: "Model A",
                            modifier = Modifier.weight(1f),
                        )
                        BattlePaneView(
                            pane = state.paneB,
                            label = state.paneB.revealedName ?: "Model B",
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
                        BattlePaneView(
                            pane = state.paneA,
                            label = state.paneA.revealedName ?: "Model A",
                            modifier = Modifier.fillMaxWidth(),
                        )
                        BattlePaneView(
                            pane = state.paneB,
                            label = state.paneB.revealedName ?: "Model B",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            // ---- Thumb-zone vote bar (≥48dp targets) ----
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.revealed) {
                    Text(
                        text = "Revealed after your vote",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(
                        onClick = { viewModel.onIntent(BattleIntent.Vote("A")) },
                        enabled = state.votedChoice == null && (state.voteReady || state.paneA.content.isNotBlank()),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                    ) { Text("Vote A") }
                    FilledTonalButton(
                        onClick = { viewModel.onIntent(BattleIntent.Vote("B")) },
                        enabled = state.votedChoice == null && (state.voteReady || state.paneB.content.isNotBlank()),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                    ) { Text("Vote B") }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { viewModel.onIntent(BattleIntent.Vote("TIE")) },
                        enabled = state.votedChoice == null && state.voteReady,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                    ) { Text("Tie") }
                    OutlinedButton(
                        onClick = { viewModel.onIntent(BattleIntent.Vote("BOTH_BAD")) },
                        enabled = state.votedChoice == null && state.voteReady,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                    ) { Text("Both bad") }
                }
                state.votedChoice?.let { choice ->
                    Text(
                        text = "You voted: $choice",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun BattlePaneView(pane: BattlePane, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (pane.revealedName != null) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(Modifier.height(6.dp))
        ResponseCard(
            author = if (pane.streaming && pane.content.isEmpty()) "Thinking…" else label,
            content = if (pane.streaming && pane.content.isEmpty()) "…" else pane.content,
            reasoning = pane.reasoning,
        )
    }
}
