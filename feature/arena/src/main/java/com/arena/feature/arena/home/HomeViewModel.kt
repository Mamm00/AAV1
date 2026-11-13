package com.arena.feature.arena.home

import androidx.lifecycle.viewModelScope
import com.arena.core.data.model.ArenaModel
import com.arena.core.data.prefs.UserPreferencesRepository
import com.arena.core.data.repo.ConversationRepository
import com.arena.core.data.repo.ModelRepository
import com.arena.core.data.repo.ModelRecommender
import com.arena.core.data.repo.UsageStatsRepository
import com.arena.feature.arena.mvi.ArenaMviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The principal modes, in canonical order. */
enum class HomeMode { BATTLE, DIRECT, SIDE_BY_SIDE, AGENT, PREVIEW }

data class HomeModeUi(
    val mode: HomeMode,
    val title: String,
    val body: String,
    /** 0 = most frequent; drives the AI-native adaptive reordering. */
    val usageRank: Int,
)

data class HomeState(
    val modes: List<HomeModeUi> = emptyList(),
    val recommendedModels: List<ArenaModel> = emptyList(),
    val clipboardSuggestion: String? = null,
    val isLoading: Boolean = true,
)

sealed interface HomeIntent {
    data class OpenMode(val mode: HomeMode) : HomeIntent
    data object OpenLeaderboard : HomeIntent
    data object OpenImageEditor : HomeIntent
    data object OpenCodeEditor : HomeIntent
    data class PasteClipboard(val text: String) : HomeIntent
    data object DismissClipboardSuggestion : HomeIntent
    data object RefreshCatalog : HomeIntent
}

sealed interface HomeEffect {
    data class OpenMode(val mode: HomeMode) : HomeEffect
    data object OpenLeaderboard : HomeEffect
    data object OpenImageEditor : HomeEffect
    data object OpenCodeEditor : HomeEffect
    data class PromptFromClipboard(val text: String) : HomeEffect
}

/**
 * Adaptive home: the card order restructures itself from local usage
 * frequency (never leaves the device). If the user mostly chats in Direct,
 * the model/direct card rises to the top; frequent Agent use elevates Agent.
 * The adaptation must feel invisible — hence no explicit "configure" UI.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    usageStats: UsageStatsRepository,
    userPreferences: UserPreferencesRepository,
    conversationRepository: ConversationRepository,
    private val modelRepository: ModelRepository,
) : ArenaMviViewModel<HomeState, HomeIntent, HomeEffect>(HomeState()) {

    private val canonical = listOf(
        HomeModeUi(HomeMode.BATTLE, "Battle", "Two anonymous models, you pick the winner.", 0),
        HomeModeUi(HomeMode.DIRECT, "Direct", "Pick any of 300+ models and chat.", 1),
        HomeModeUi(HomeMode.SIDE_BY_SIDE, "Side-by-Side", "Run two named models with one prompt.", 2),
        HomeModeUi(HomeMode.AGENT, "Agent", "Tools, files, search, and sandboxed code.", 3),
        HomeModeUi(
            HomeMode.PREVIEW,
            "Live Preview",
            "The arena.ai site itself — prewarmed, cached, instant.",
            4,
        ),
    )

    init {
        viewModelScope.launch {
            combine(
                usageStats.observeModeFrequency(),
                userPreferences.adaptiveHomeEnabled,
            ) { frequency, enabled -> frequency to enabled }
                .collect { (frequency, enabled) ->
                    val order = ModelRecommender.adaptiveOrder(
                        modeFrequency = frequency,
                        defaultOrder = canonical.map { it.mode.name },
                        enabled = enabled,
                    )
                    val usageRank = order.withIndex().associate { (index, name) -> name to index }
                    setState {
                        copy(
                            modes = canonical
                                .sortedBy { usageRank[it.mode.name] ?: Int.MAX_VALUE }
                                .mapIndexed { index, ui -> ui.copy(usageRank = index) },
                            isLoading = false,
                        )
                    }
                }
        }

        viewModelScope.launch {
            modelRepository.observeAll().collect { models ->
                setState { copy(recommendedModels = models.take(6)) }
            }
        }

        // Keep the offline snapshot warm so the fallback surface is instant.
        viewModelScope.launch {
            conversationRepository.observeLatest().collect { /* warm cache only */ }
        }
    }

    override suspend fun handleIntent(intent: HomeIntent) {
        when (intent) {
            HomeIntent.OpenLeaderboard -> emitEffect(HomeEffect.OpenLeaderboard)
            HomeIntent.OpenImageEditor -> emitEffect(HomeEffect.OpenImageEditor)
            HomeIntent.OpenCodeEditor -> emitEffect(HomeEffect.OpenCodeEditor)
            is HomeIntent.OpenMode -> emitEffect(HomeEffect.OpenMode(intent.mode))
            is HomeIntent.PasteClipboard -> {
                setState { copy(clipboardSuggestion = null) }
                emitEffect(HomeEffect.PromptFromClipboard(intent.text))
            }
            HomeIntent.DismissClipboardSuggestion -> setState { copy(clipboardSuggestion = null) }
            HomeIntent.RefreshCatalog -> modelRepository.refresh()
        }
    }

    fun onClipboardDetected(text: String) {
        // Only surface code blocks / long text — short clipboard noise is ignored.
        val looksUseful = text.length >= 60 || text.contains('\n') ||
            Regex("(fun |def |class |SELECT |const |import )").containsMatchIn(text)
        if (looksUseful && currentState.clipboardSuggestion == null) {
            setState { copy(clipboardSuggestion = text) }
        }
    }
}
