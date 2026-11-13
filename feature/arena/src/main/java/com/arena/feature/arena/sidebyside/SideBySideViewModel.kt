package com.arena.feature.arena.sidebyside

import androidx.lifecycle.viewModelScope
import com.arena.core.data.model.ArenaModel
import com.arena.core.data.repo.ModelRepository
import com.arena.core.data.repo.UsageStatsRepository
import com.arena.core.sse.ArenaEvent
import com.arena.feature.arena.mvi.ArenaMviViewModel
import com.arena.feature.arena.session.ArenaSessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SbsPaneState(
    val model: ArenaModel? = null,
    val content: String = "",
    val streaming: Boolean = false,
)

data class SideBySideState(
    val prompt: String = "",
    val left: SbsPaneState = SbsPaneState(),
    val right: SbsPaneState = SbsPaneState(),
    val models: List<ArenaModel> = emptyList(),
    val pickerSlot: Int? = null, // 0 = left, 1 = right; null = closed
    val diffEnabled: Boolean = false,
    val notice: String? = null,
)

sealed interface SbsIntent {
    data class PromptChanged(val text: String) : SbsIntent
    data object Submit : SbsIntent
    data class OpenPicker(val slot: Int) : SbsIntent
    data class SelectModel(val slot: Int, val model: ArenaModel) : SbsIntent
    data object ToggleDiff : SbsIntent
    data object ClearNotice : SbsIntent
}

sealed interface SbsEffect {
    data class ShowNotice(val message: String) : SbsEffect
}

/**
 * Side-by-Side Mode: one synchronized prompt drives two named models.
 * The diff toggle highlights textual differences between outputs using a
 * word-level LCS computed on a background-safe input size cap.
 */
@HiltViewModel
class SideBySideViewModel @Inject constructor(
    private val session: ArenaSessionRepository,
    modelRepository: ModelRepository,
    private val usageStats: UsageStatsRepository,
) : ArenaMviViewModel<SideBySideState, SbsIntent, SbsEffect>(SideBySideState()) {

    init {
        viewModelScope.launch {
            modelRepository.observeAll().collect { models ->
                setState {
                    copy(
                        models = models,
                        left = if (left.model == null) {
                            left.copy(model = models.getOrNull(0))
                        } else left,
                        right = if (right.model == null) {
                            right.copy(model = models.getOrNull(1) ?: models.getOrNull(0))
                        } else right,
                    )
                }
            }
        }
        viewModelScope.launch {
            session.events.collect { event ->
                when (event) {
                    is ArenaEvent.TextDelta -> setState {
                        if (event.slot == 0) {
                            copy(left = left.copy(content = left.content + event.text))
                        } else {
                            copy(right = right.copy(content = right.content + event.text))
                        }
                    }
                    is ArenaEvent.Done -> setState {
                        if (event.slot == 0) {
                            copy(left = left.copy(streaming = false))
                        } else {
                            copy(right = right.copy(streaming = false))
                        }
                    }
                    is ArenaEvent.StreamError -> setState {
                        copy(
                            left = left.copy(streaming = false),
                            right = right.copy(streaming = false),
                            notice = "Stream interrupted",
                        )
                    }
                    else -> Unit
                }
            }
        }
    }

    override suspend fun handleIntent(intent: SbsIntent) {
        when (intent) {
            is SbsIntent.PromptChanged -> setState { copy(prompt = intent.text) }
            SbsIntent.Submit -> {
                val prompt = currentState.prompt.trim()
                if (prompt.isEmpty()) return
                // Synchronized input: pin both models, then fire once.
                currentState.left.model?.let { session.switchModel(it.id, slot = 0) }
                currentState.right.model?.let { session.switchModel(it.id, slot = 1) }
                setState {
                    copy(
                        prompt = "",
                        left = left.copy(content = "", streaming = true),
                        right = right.copy(content = "", streaming = true),
                    )
                }
                session.sendPrompt(prompt)
                usageStats.record(mode = "SIDE_BY_SIDE", modelId = currentState.left.model?.id, taskType = "chat")
            }
            is SbsIntent.OpenPicker -> setState {
                copy(pickerSlot = intent.slot.takeIf { slot -> slot in 0..1 })
            }
            is SbsIntent.SelectModel -> {
                val model = intent.model
                setState {
                    when (intent.slot) {
                        0 -> copy(
                            left = left.copy(model = model),
                            pickerSlot = null,
                            notice = "Left: ${model.displayName}",
                        )
                        else -> copy(
                            right = right.copy(model = model),
                            pickerSlot = null,
                            notice = "Right: ${model.displayName}",
                        )
                    }
                }
                session.switchModel(model.id, slot = intent.slot)
                usageStats.record(mode = "SIDE_BY_SIDE", modelId = model.id, taskType = "chat")
            }
            SbsIntent.ToggleDiff -> setState { copy(diffEnabled = !diffEnabled) }
            SbsIntent.ClearNotice -> setState { copy(notice = null) }
        }
    }
}
