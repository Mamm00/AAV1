package com.arena.feature.arena.leaderboard

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.arena.core.data.db.LeaderboardEntryEntity
import com.arena.core.data.repo.LeaderboardCategory
import com.arena.core.data.repo.LeaderboardRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LeaderboardUiState(
    val category: LeaderboardCategory = LeaderboardCategory.TEXT,
    val entries: List<LeaderboardEntryEntity> = emptyList(),
    val loading: Boolean = false,
    val offline: Boolean = false,
    val refreshFailed: Boolean = false,
    val lastUpdatedEpochMs: Long? = null,
)

sealed interface LeaderboardIntent {
    data class SelectCategory(val category: LeaderboardCategory) : LeaderboardIntent
    data object Refresh : LeaderboardIntent
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LeaderboardViewModel @Inject constructor(
    private val repository: LeaderboardRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(LeaderboardUiState())
    val state: StateFlow<LeaderboardUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // React to category switches: re-subscribe to that category's cache.
            _state.flatMapLatest { snapshot ->
                repository.observe(snapshot.category).map { rows -> rows }
            }.collect { rows ->
                _state.value = _state.value.copy(
                    entries = rows,
                    offline = _state.value.refreshFailed,
                )
            }
        }
        onIntent(LeaderboardIntent.Refresh)
    }

    fun onIntent(intent: LeaderboardIntent) {
        when (intent) {
            is LeaderboardIntent.SelectCategory -> {
                _state.value = _state.value.copy(category = intent.category, entries = emptyList())
                onIntent(LeaderboardIntent.Refresh)
            }
            LeaderboardIntent.Refresh -> refresh()
        }
    }

    private fun refresh() {
        val category = _state.value.category
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val result = repository.refresh(category)
            _state.value = _state.value.copy(
                loading = false,
                // Failure keeps cached rows and flips the offline banner.
                refreshFailed = result.isFailure,
                offline = result.isFailure,
                lastUpdatedEpochMs = repository.lastUpdated(category),
            )
        }
    }
}

/**
 * Offline Arena leaderboard: cached text/code/image rankings rendered in a
 * native LazyColumn, browsable with no connectivity. Refresh is best-effort —
 * a failed pull simply keeps the cache.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineLeaderboardScreen(
    onBack: () -> Unit,
    viewModel: LeaderboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Leaderboard", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.onIntent(LeaderboardIntent.Refresh) },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
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
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LeaderboardCategory.ALL.forEach { category ->
                    FilterChip(
                        selected = state.category.key == category.key,
                        onClick = { viewModel.onIntent(LeaderboardIntent.SelectCategory(category)) },
                        label = { Text(category.label) },
                    )
                }
            }

            if (state.loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            if (state.offline) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Filled.CloudOff,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Offline — showing cached rankings",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (state.entries.isEmpty() && !state.loading) {
                Text(
                    text = "No cached rankings yet. Connect once to fetch.",
                    modifier = Modifier.padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.entries, key = { it.key }) { entry ->
                        LeaderboardRow(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun LeaderboardRow(entry: LeaderboardEntryEntity) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "#${entry.rank}",
                modifier = Modifier.width(44.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (entry.rank <= 3) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Column(Modifier.weight(1f)) {
                Text(entry.displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    text = listOfNotNull(
                        entry.provider,
                        "${entry.votes} votes",
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = String.format("%.0f", entry.rating),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
