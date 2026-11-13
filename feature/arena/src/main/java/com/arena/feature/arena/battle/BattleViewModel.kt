package com.arena.feature.arena.battle

import androidx.lifecycle.viewModelScope
import com.arena.core.data.repo.UsageStatsRepository
import com.arena.core.data.repo.VoteRepository
import com.arena.core.sse.ArenaEvent
import com.arena.feature.arena.mvi.ArenaMviViewModel
import com.arena.feature.arena.session.ArenaSessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class BattlePane(
    val content: String = "",
    val reasoning: String? = null,
    val streaming: Boolean = false,
    /** Only set after the vote reveals identities. */
    val revealedName: String? = null,
)

data class BattleState(
    val prompt: String = "",
    val paneA: BattlePane = BattlePane(),
    val paneB: BattlePane = BattlePane(),
    val battleId: String = "",
    val generationId: String? = null,
    val votedChoice: String? = null,
    val revealed: Boolean = false,
    val voteReady: Boolean = false,
    val notice: String? = null,
)

sealed interface BattleIntent {
    data class PromptChanged(val text: String) : BattleIntent
    data object Submit : BattleIntent
    data class Vote(val choice: String) : BattleIntent // A | B | TIE | BOTH_BAD
    data object NewBattle : BattleIntent
    data object ClearNotice : BattleIntent
}

sealed interface BattleEffect {
    data class VoteRecorded(val choice: String) : BattleEffect
    data class ShowNotice(val message: String) : BattleEffect
    /** Both responses finished → post heads-up vote notification. */
    data object OfferNotificationVote : BattleEffect
}

/**
 * Battle Mode: two anonymous responses stream in on slots 0/1 (a0/a2),
 * identities (ag) stay masked until the user votes, and the vote is cached
 * locally for offline leaderboard viewing. A heads-up notification with
 * Model A / Model B actions lets the user vote without opening the app.
 */
@HiltViewModel
class BattleViewModel @Inject constructor(
    private val session: ArenaSessionRepository,
    private val voteRepository: VoteRepository,
    private val usageStats: UsageStatsRepository,
) : ArenaMviViewModel<BattleState, BattleIntent, BattleEffect>(BattleState()) {

    init {
        newBattleInternal()

        viewModelScope.launch {
            session.events.collect { event -> reduce(event) }
        }
    }

    override suspend fun handleIntent(intent: BattleIntent) {
        when (intent) {
            is BattleIntent.PromptChanged -> setState { copy(prompt = intent.text) }
            BattleIntent.Submit -> {
                val prompt = currentState.prompt.trim()
                if (prompt.isEmpty() || currentState.paneA.streaming || currentState.paneB.streaming) return
                val battleId = currentState.battleId.ifEmpty { UUID.randomUUID().toString() }
                setState {
                    copy(
                        battleId = battleId,
                        paneA = BattlePane(streaming = true),
                        paneB = BattlePane(streaming = true),
                        voteReady = false,
                        revealed = false,
                        votedChoice = null,
                        prompt = "",
                    )
                }
                // One prompt drives both anonymous slots on the page.
                session.sendPrompt(prompt)
                usageStats.record(mode = "BATTLE", modelId = null, taskType = "chat")
            }
            is BattleIntent.Vote -> castVote(intent.choice)
            BattleIntent.NewBattle -> newBattleInternal()
            BattleIntent.ClearNotice -> setState { copy(notice = null) }
        }
    }

    private suspend fun castVote(choice: String) {
        val state = currentState
        if (state.votedChoice != null) return
        if (!state.voteReady && choice != "TIE" && choice != "BOTH_BAD") {
            setState { copy(notice = "Both models must finish before voting") }
            return
        }

        voteRepository.submitVote(
            battleId = state.battleId,
            choice = choice,
            modelAId = null, // revealed names are recorded on reveal below
            modelBId = null,
            generationId = state.generationId,
        )

        // Tell the page (reveals identities server-side via `ag:` events).
        session.vote(
            choice = when (choice) {
                "A" -> com.arena.core.webview.BridgeMessage.Vote.VoteChoice.A
                "B" -> com.arena.core.webview.BridgeMessage.Vote.VoteChoice.B
                "TIE" -> com.arena.core.webview.BridgeMessage.Vote.VoteChoice.TIE
                else -> com.arena.core.webview.BridgeMessage.Vote.VoteChoice.BOTH_BAD
            },
            generationId = state.generationId,
        )

        setState { copy(votedChoice = choice, notice = "Vote recorded") }
        usageStats.record(mode = "BATTLE", modelId = null, taskType = "vote")
        emitEffect(BattleEffect.VoteRecorded(choice))
    }

    private fun newBattleInternal() {
        setState {
            BattleState(battleId = UUID.randomUUID().toString())
        }
    }

    private fun reduce(event: ArenaEvent) {
        when (event) {
            is ArenaEvent.TextDelta -> {
                setState {
                    if (event.slot == 0) {
                        copy(paneA = paneA.copy(content = paneA.content + event.text))
                    } else {
                        copy(paneB = paneB.copy(content = paneB.content + event.text))
                    }
                }
            }
            is ArenaEvent.ReasoningDelta -> {
                setState {
                    if (event.slot == 0) {
                        copy(paneA = paneA.copy(reasoning = paneA.reasoning.orEmpty() + event.text))
                    } else {
                        copy(paneB = paneB.copy(reasoning = paneB.reasoning.orEmpty() + event.text))
                    }
                }
            }
            is ArenaEvent.ModelAnnounce -> {
                // Identities arrive only post-vote; never unmask early.
                if (currentState.votedChoice != null && event.revealed) {
                    setState {
                        if (event.slot == 0) {
                            copy(paneA = paneA.copy(revealedName = event.displayName), revealed = true)
                        } else {
                            copy(paneB = paneB.copy(revealedName = event.displayName), revealed = true)
                        }
                    }
                }
            }
            is ArenaEvent.GenerationStart -> setState { copy(generationId = event.generationId) }
            is ArenaEvent.Done -> {
                val slotDone = event.slot
                setState {
                    val pane = if (slotDone == 0) paneA.copy(streaming = false) else paneB.copy(streaming = false)
                    val next = if (slotDone == 0) copy(paneA = pane) else copy(paneB = pane)
                    next.copy(voteReady = !next.paneA.streaming && !next.paneB.streaming)
                }
                if (currentState.voteReady) emitEffect(BattleEffect.OfferNotificationVote)
            }
            is ArenaEvent.StreamError -> setState {
                copy(
                    paneA = paneA.copy(streaming = false),
                    paneB = paneB.copy(streaming = false),
                    voteReady = true,
                    notice = "Stream interrupted — you can still vote",
                )
            }
            else -> Unit
        }
    }
}
