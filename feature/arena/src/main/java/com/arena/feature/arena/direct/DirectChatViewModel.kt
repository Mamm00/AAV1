package com.arena.feature.arena.direct

import androidx.lifecycle.viewModelScope
import com.arena.core.data.model.ArenaModel
import com.arena.core.data.repo.ConversationRepository
import com.arena.core.data.repo.ModelRepository
import com.arena.core.data.repo.UsageStatsRepository
import com.arena.core.sse.ArenaEvent
import com.arena.feature.arena.mvi.ArenaMviViewModel
import com.arena.feature.arena.session.ArenaSessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class ChatMessageUi(
    val id: String,
    val role: String, // user | assistant
    val content: String,
    val author: String,
    val reasoning: String? = null,
    val streaming: Boolean = false,
    val modelId: String? = null,
    val slot: Int = 0,
)

data class DirectState(
    val messages: List<ChatMessageUi> = emptyList(),
    val models: List<ArenaModel> = emptyList(),
    val selectedModel: ArenaModel? = null,
    val pickerOpen: Boolean = false,
    val input: String = "",
    val isStreaming: Boolean = false,
    val listening: Boolean = false,
    /** 0f..1f microphone level for the live waveform. */
    val voiceLevel: Float = 0f,
    val pageReady: Boolean = false,
    val notice: String? = null,
)

sealed interface DirectIntent {
    data class InputChanged(val text: String) : DirectIntent
    data object Send : DirectIntent
    data object TogglePicker : DirectIntent
    data class SelectModel(val model: ArenaModel) : DirectIntent
    data object StopStreaming : DirectIntent
    data class LongPressMessage(val id: String) : DirectIntent
    data class CopyMessage(val id: String) : DirectIntent
    data class Regenerate(val id: String) : DirectIntent
    data class AttachChosen(val name: String, val mime: String, val size: Long, val uri: String) : DirectIntent
    data class DictationLevel(val level: Float) : DirectIntent
    data class DictationFinal(val text: String) : DirectIntent
    data object DictationStart : DirectIntent
    data object DictationCancel : DirectIntent
    data class ConsumeHomePrompt(val text: String) : DirectIntent
    data object ClearNotice : DirectIntent
}

sealed interface DirectEffect {
    data class CopyToClipboard(val text: String) : DirectEffect
    data class ShowNotice(val message: String) : DirectEffect
    data object OpenFilePicker : DirectEffect
}

/**
 * Direct Mode view model: a first-class chat surface backed by the arena.ai
 * session. Streaming arrives as bridge `stream` messages, is decoded by the
 * shared [SseParser] pipeline inside ArenaSessionRepository, and appends to
 * response cards without blocking the UI thread.
 */
@HiltViewModel
class DirectChatViewModel @Inject constructor(
    private val session: ArenaSessionRepository,
    modelRepository: ModelRepository,
    private val conversationRepository: ConversationRepository,
    private val usageStats: UsageStatsRepository,
) : ArenaMviViewModel<DirectState, DirectIntent, DirectEffect>(DirectState()) {

    private var activeConversationId: String = UUID.randomUUID().toString()

    init {
        viewModelScope.launch {
            modelRepository.observeAll().collect { models ->
                setState {
                    copy(
                        models = models,
                        selectedModel = selectedModel ?: models.firstOrNull { it.modality.name == "TEXT" }
                            ?: models.firstOrNull(),
                    )
                }
            }
        }

        viewModelScope.launch {
            session.events.collect { event -> reduceArenaEvent(event) }
        }

        viewModelScope.launch {
            session.pageReady.collect { ready -> setState { copy(pageReady = ready) } }
        }
    }

    override suspend fun handleIntent(intent: DirectIntent) {
        when (intent) {
            is DirectIntent.InputChanged -> setState { copy(input = intent.text) }
            DirectIntent.Send -> send(currentState.input)
            DirectIntent.TogglePicker -> setState { copy(pickerOpen = !pickerOpen) }
            is DirectIntent.SelectModel -> {
                setState {
                    copy(
                        selectedModel = intent.model,
                        pickerOpen = false,
                        notice = "Switched to ${intent.model.displayName}",
                    )
                }
                session.switchModel(intent.model.id)
                // Record selection for local recommendations (5-interaction rule).
                usageStats.record(mode = "DIRECT", modelId = intent.model.id, taskType = "chat")
            }
            DirectIntent.StopStreaming -> {
                setState { copy(isStreaming = false) }
            }
            is DirectIntent.CopyMessage -> {
                val message = currentState.messages.find { it.id == intent.id } ?: return
                emitEffect(DirectEffect.CopyToClipboard(message.content))
            }
            is DirectIntent.Regenerate -> {
                val message = currentState.messages.find { it.id == intent.id } ?: return
                // Regenerate the user prompt that produced this response.
                val index = currentState.messages.indexOf(message)
                val prompt = currentState.messages.subList(0, index)
                    .lastOrNull { it.role == "user" }?.content ?: return
                send(prompt)
            }
            is DirectIntent.LongPressMessage -> Unit // menu handled in UI
            is DirectIntent.AttachChosen -> {
                session.attachFile(
                    name = intent.name,
                    mimeType = intent.mime,
                    sizeBytes = intent.size,
                    uri = intent.uri,
                )
                setState { copy(notice = "Attached ${intent.name}") }
            }
            is DirectIntent.DictationLevel -> setState { copy(voiceLevel = intent.level) }
            is DirectIntent.DictationFinal -> {
                val text = intent.text.trim()
                setState { copy(listening = false, voiceLevel = 0f) }
                if (text.isNotEmpty()) send(text, fromVoice = true)
            }
            DirectIntent.DictationStart -> setState { copy(listening = true, voiceLevel = 0.1f) }
            DirectIntent.DictationCancel -> setState { copy(listening = false, voiceLevel = 0f) }
            is DirectIntent.ConsumeHomePrompt -> {
                setState { copy(input = intent.text) }
                send(intent.text)
            }
            DirectIntent.ClearNotice -> setState { copy(notice = null) }
        }
    }

    private fun send(text: String, fromVoice: Boolean = false) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || currentState.isStreaming) return

        val model = currentState.selectedModel
        val userMessage = ChatMessageUi(
            id = UUID.randomUUID().toString(),
            role = "user",
            content = trimmed,
            author = "You",
            modelId = model?.id,
        )
        val assistantPlaceholder = ChatMessageUi(
            id = UUID.randomUUID().toString(),
            role = "assistant",
            content = "",
            author = model?.displayName ?: "Model",
            streaming = true,
            modelId = model?.id,
        )

        setState {
            copy(
                messages = messages + userMessage + assistantPlaceholder,
                input = "",
                isStreaming = true,
                notice = if (fromVoice) "Sent by voice" else null,
            )
        }

        // Persist for offline last-history fallback (content stays on-device).
        viewModelScope.launch {
            conversationRepository.record(
                mode = "DIRECT",
                conversationId = activeConversationId,
                title = trimmed.take(60),
                role = "user",
                content = trimmed,
                modelId = model?.id,
            )
            usageStats.record(mode = "DIRECT", modelId = model?.id, taskType = "chat")
        }

        // Push into the arena.ai session (queued until page ready).
        session.sendPrompt(trimmed, modelId = model?.id)
    }

    private fun reduceArenaEvent(event: ArenaEvent) {
        when (event) {
            is ArenaEvent.TextDelta -> appendToLastAssistant(event.slot, event.text)
            is ArenaEvent.ReasoningDelta -> appendReasoning(event.slot, event.text)
            is ArenaEvent.ModelAnnounce -> if (event.revealed) {
                setState {
                    copy(
                        messages = messages.mapIndexed { index, m ->
                            if (index == messages.lastIndex && m.role == "assistant" && m.slot == event.slot) {
                                m.copy(author = event.displayName, modelId = event.modelId)
                            } else m
                        },
                    )
                }
            }
            is ArenaEvent.Done -> {
                setState { copy(isStreaming = false) }
                val last = currentState.messages.lastOrNull() ?: return
                if (last.role == "assistant" && last.content.isNotBlank()) {
                    viewModelScope.launch {
                        conversationRepository.record(
                            mode = "DIRECT",
                            conversationId = activeConversationId,
                            title = last.content.take(60),
                            role = "assistant",
                            content = last.content,
                            modelId = last.modelId,
                            slot = last.slot,
                        )
                    }
                }
            }
            is ArenaEvent.StreamError -> setState {
                copy(
                    isStreaming = false,
                    notice = "Stream error: ${event.message}",
                )
            }
            else -> Unit // Usage/Agent/ProtocolWarning handled by their screens
        }
    }

    private fun appendToLastAssistant(slot: Int, delta: String) {
        setState {
            val updated = messages.toMutableList()
            val index = updated.indexOfLast { it.role == "assistant" && it.streaming && it.slot == slot }
                .takeIf { it >= 0 } ?: updated.indexOfLast { it.role == "assistant" && it.slot == slot }
            if (index >= 0) {
                val current = updated[index]
                updated[index] = current.copy(content = current.content + delta)
                copy(messages = updated)
            } else {
                this
            }
        }
    }

    private fun appendReasoning(slot: Int, delta: String) {
        setState {
            val updated = messages.toMutableList()
            val index = updated.indexOfLast { it.role == "assistant" && it.slot == slot }
            if (index >= 0) {
                val current = updated[index]
                updated[index] = current.copy(reasoning = current.reasoning.orEmpty() + delta)
                copy(messages = updated)
            } else this
        }
    }
}
