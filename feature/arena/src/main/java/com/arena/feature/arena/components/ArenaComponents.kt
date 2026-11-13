package com.arena.feature.arena.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp

/** Haptics helper: light tick on selections, medium on votes/model switch. */
@Composable
fun rememberArenaHaptics(): (HapticFeedbackType) -> Unit {
    val haptics = LocalHapticFeedback.current
    return { type -> haptics.performHapticFeedback(type) }
}

/** Arena rank badge ("#12") shown on model rows. */
@Composable
fun RankBadge(rankLabel: String?, modifier: Modifier = Modifier) {
    if (rankLabel == null) return
    Text(
        text = rankLabel,
        modifier = modifier
            .padding(end = 4.dp)
            .background(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * Interactive response card: collapsible reasoning header, long-press menu
 * slot, and content-description for screen readers. Replaces "plain text
 * walls" from the website.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ResponseCard(
    author: String,
    content: String,
    modifier: Modifier = Modifier,
    reasoning: String? = null,
    footer: (@Composable () -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
) {
    var reasoningOpen by remember(reasoning) { mutableStateOf(false) }
    var contentOpen by remember { mutableStateOf(true) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Model response from $author" }
            .combinedClickable(
                onClick = { contentOpen = !contentOpen },
                onLongClick = onLongPress,
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = author,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (reasoning != null) {
                    IconButton(
                        onClick = { reasoningOpen = !reasoningOpen },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = if (reasoningOpen) {
                                Icons.Filled.KeyboardArrowUp
                            } else {
                                Icons.Filled.KeyboardArrowDown
                            },
                            contentDescription = if (reasoningOpen) "Hide reasoning" else "Show reasoning",
                        )
                    }
                }
            }

            if (reasoning != null) {
                AnimatedVisibility(
                    visible = reasoningOpen,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Text(
                        text = reasoning,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp, bottom = 8.dp),
                    )
                }
            }

            AnimatedVisibility(visible = contentOpen) {
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            footer?.let {
                Spacer(Modifier.height(8.dp))
                it()
            }
        }
    }
}

/**
 * Monospace, scrollable terminal surface for Agent Bash output.
 * Combined with SelectionContainer at call sites so output can be copied.
 */
@Composable
fun BashOutputView(
    output: String,
    modifier: Modifier = Modifier,
    isStderr: Boolean = false,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isStderr) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Text(
            text = output.ifEmpty { "—" },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp, max = 240.dp)
                .padding(12.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = if (isStderr) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}
