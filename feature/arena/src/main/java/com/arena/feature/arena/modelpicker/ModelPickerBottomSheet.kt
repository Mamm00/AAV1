package com.arena.feature.arena.modelpicker

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arena.core.data.model.ArenaModel
import com.arena.core.data.model.Modality
import com.arena.feature.arena.components.RankBadge

/**
 * Thumb-friendly model switcher.
 *
 * - Grouped by modality: Text, Image, Code, Search, Video.
 * - Arena leaderboard rank badges on every row.
 * - "Recommended for you" row appears only after ≥5 local interactions for a
 *   task type (see ModelRecommender) — local and private, never uploaded.
 * - Search filters provider/name/id across the full 300+ catalog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerBottomSheet(
    models: List<ArenaModel>,
    recommended: List<String>,
    selectedModelId: String?,
    onModelSelected: (ArenaModel) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }

    val filtered = remember(models, query) {
        if (query.isBlank()) models
        else models.filter {
            it.displayName.contains(query, ignoreCase = true) ||
                it.provider.orEmpty().contains(query, ignoreCase = true) ||
                it.id.contains(query, ignoreCase = true)
        }
    }
    val recommendedModels = remember(filtered, recommended) {
        recommended.mapNotNull { id -> filtered.find { it.id == id } }
    }
    val recommendedIds = recommendedModels.map { it.id }.toSet()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Choose a model",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Close")
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                placeholder = { Text("Search 300+ models") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
            )

            Spacer(Modifier.height(12.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (recommendedModels.isNotEmpty()) {
                    item(key = "rec-header") {
                        SectionHeader("Recommended for you")
                    }
                    items(recommendedModels, key = { "rec-${it.id}" }) { model ->
                        ModelRow(
                            model = model,
                            selected = model.id == selectedModelId,
                            onClick = { onModelSelected(model) },
                        )
                    }
                    item(key = "rec-divider") { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                }

                Modality.entries.forEach { modality ->
                    val group = filtered.filter { it.modality == modality && it.id !in recommendedIds }
                    if (group.isEmpty()) return@forEach
                    item(key = "header-${modality.name}") {
                        SectionHeader(modality.label())
                    }
                    items(group, key = { "${modality.name}-${it.id}" }) { model ->
                        ModelRow(
                            model = model,
                            selected = model.id == selectedModelId,
                            onClick = { onModelSelected(model) },
                        )
                    }
                }

                if (filtered.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            text = "No models match “$query”",
                            modifier = Modifier.padding(24.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun ModelRow(
    model: ArenaModel,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = model.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                RankBadge(model.rankLabel)
            }
            Text(
                text = listOfNotNull(
                    model.provider,
                    model.modality.name.lowercase().replaceFirstChar { it.uppercase() },
                    model.votes?.let { "${it / 1000}k votes" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

private fun Modality.label(): String = when (this) {
    Modality.TEXT -> "Text"
    Modality.IMAGE -> "Image"
    Modality.CODE -> "Code"
    Modality.SEARCH -> "Search"
    Modality.VIDEO -> "Video"
}
