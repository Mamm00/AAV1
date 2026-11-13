package com.arena.feature.arena.agent

import androidx.lifecycle.viewModelScope
import com.arena.core.sse.ArenaEvent
import com.arena.feature.arena.mvi.ArenaMviViewModel
import com.arena.feature.arena.session.ArenaSessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AgentStepUi(
    val stepId: String,
    val kind: String,
    val title: String,
    val status: ArenaEvent.AgentStep.Status,
    val detail: String? = null,
)

data class AgentState(
    val prompt: String = "",
    val webSearchEnabled: Boolean = true,
    val filesEnabled: Boolean = true,
    val imageGenEnabled: Boolean = false,
    val running: Boolean = false,
    val steps: List<AgentStepUi> = emptyList(),
    val bashOutput: String = "",
    val bashStderr: String = "",
    val finalAnswer: String = "",
    val notice: String? = null,
    val serviceRunning: Boolean = false,
)

sealed interface AgentIntent {
    data class PromptChanged(val text: String) : AgentIntent
    data class ToggleTool(val tool: String) : AgentIntent // web | files | image
    data object Run : AgentIntent
    data object Stop : AgentIntent
    data object ClearNotice : AgentIntent
    data class BashChunk(val chunk: String, val stderr: Boolean) : AgentIntent
}

sealed interface AgentEffect {
    data class ShowNotice(val message: String) : AgentEffect
    /** Long task started → promote to foreground dataSync service. */
    data object StartForegroundTask : AgentEffect
    /** Task finished → tear the foreground service down. */
    data object StopForegroundTask : AgentEffect
}

/**
 * Agent Mode: tool toggles (web search / files / image generation), a native
 * progress timeline fed by `a3:` agent-step events, and a monospace Bash
 * output viewer — rendered as a vertical stepper instead of a raw log.
 */
@HiltViewModel
class AgentModeViewModel @Inject constructor(
    private val session: ArenaSessionRepository,
) : ArenaMviViewModel<AgentState, AgentIntent, AgentEffect>(AgentState()) {

    init {
        viewModelScope.launch {
            session.events.collect { event -> reduce(event) }
        }
    }

    override suspend fun handleIntent(intent: AgentIntent) {
        when (intent) {
            is AgentIntent.PromptChanged -> setState { copy(prompt = intent.text) }
            is AgentIntent.ToggleTool -> setState {
                when (intent.tool) {
                    "web" -> copy(webSearchEnabled = !webSearchEnabled)
                    "files" -> copy(filesEnabled = !filesEnabled)
                    else -> copy(imageGenEnabled = !imageGenEnabled)
                }
            }
            AgentIntent.Run -> {
                val prompt = currentState.prompt.trim()
                if (prompt.isEmpty() || currentState.running) return
                val flags = buildList {
                    if (currentState.webSearchEnabled) add("web_search")
                    if (currentState.filesEnabled) add("files")
                    if (currentState.imageGenEnabled) add("image_gen")
                }
                setState {
                    copy(
                        prompt = "",
                        running = true,
                        steps = listOf(
                            AgentStepUi(
                                stepId = "queued",
                                kind = "queued",
                                title = "Queued (${flags.joinToString().ifEmpty { "no tools" }})",
                                status = ArenaEvent.AgentStep.Status.RUNNING,
                            ),
                        ),
                        bashOutput = "",
                        bashStderr = "",
                        finalAnswer = "",
                        serviceRunning = true,
                    )
                }
                // Tool flags travel with the prompt as structured text the
                // page's agent router understands; typed tool toggles also
                // sync through the bridge contract.
                session.sendPrompt("[agent:${flags.joinToString(",")}] $prompt")
                emitEffect(AgentEffect.StartForegroundTask)
            }
            AgentIntent.Stop -> {
                setState { copy(running = false, serviceRunning = false) }
                emitEffect(AgentEffect.StopForegroundTask)
            }
            AgentIntent.ClearNotice -> setState { copy(notice = null) }
            is AgentIntent.BashChunk -> {
                setState {
                    if (intent.stderr) copy(bashStderr = bashStderr + intent.chunk)
                    else copy(bashOutput = bashOutput + intent.chunk)
                }
            }
        }
    }

    private fun reduce(event: ArenaEvent) {
        when (event) {
            is ArenaEvent.AgentStep -> setState {
                val updated = steps.toMutableList()
                val index = updated.indexOfFirst { it.stepId == event.stepId }
                val ui = AgentStepUi(
                    stepId = event.stepId,
                    kind = event.kind,
                    title = event.title,
                    status = event.status,
                    detail = event.detail,
                )
                if (index >= 0) updated[index] = ui else updated.add(ui)
                copy(
                    steps = updated,
                    // First real step replaces the "queued" placeholder.
                    running = updated.any {
                        it.status == ArenaEvent.AgentStep.Status.RUNNING ||
                            it.status == ArenaEvent.AgentStep.Status.PENDING
                    },
                )
            }
            is ArenaEvent.BashOutput -> setState {
                if (event.stream == ArenaEvent.BashOutput.Stream.STDERR) {
                    copy(bashStderr = bashStderr + event.chunk)
                } else {
                    copy(bashOutput = bashOutput + event.chunk)
                }
            }
            is ArenaEvent.TextDelta -> setState {
                if (running) copy(finalAnswer = finalAnswer + event.text) else this
            }
            is ArenaEvent.Done -> {
                setState {
                    copy(
                        running = false,
                        serviceRunning = false,
                        steps = steps.map {
                            if (it.status == ArenaEvent.AgentStep.Status.RUNNING) {
                                it.copy(status = ArenaEvent.AgentStep.Status.DONE)
                            } else it
                        },
                    )
                }
                emitEffect(AgentEffect.StopForegroundTask)
            }
            is ArenaEvent.StreamError -> {
                setState { copy(running = false, serviceRunning = false, notice = event.message) }
                emitEffect(AgentEffect.StopForegroundTask)
            }
            else -> Unit
        }
    }
}
