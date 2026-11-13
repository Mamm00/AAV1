package com.arena.feature.arena.battle

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.arena.core.data.repo.VoteRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Vote-from-notification: a heads-up notification with Model A / Model B
 * actions lets the user vote on Battle results without opening the app.
 *
 * POST_NOTIFICATIONS is requested only when the user enables notifications
 * (PermissionUseCase.POST_NOTIFICATIONS); without it we silently skip
 * posting — the in-app vote flow still works.
 */
object BattleVoteNotifications {

    const val CHANNEL_ID = "battle_votes"
    const val ACTION_VOTE_A = "com.arena.feature.arena.battle.VOTE_A"
    const val ACTION_VOTE_B = "com.arena.feature.arena.battle.VOTE_B"
    const val EXTRA_BATTLE_ID = "battle_id"
    const val EXTRA_GENERATION_ID = "generation_id"
    private const val NOTIFICATION_ID = 4210

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Battle votes",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Vote on Battle results without opening the app"
                setShowBadge(false)
            },
        )
    }

    /** Post the two-action heads-up notification. No-op without permission. */
    fun postVoteActions(
        context: Context,
        battleId: String,
        generationId: String?,
        summary: String,
    ) {
        ensureChannel(context)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return // notifications disabled — in-app voting remains available
        }

        val voteA = pendingVote(context, ACTION_VOTE_A, battleId, generationId, 1)
        val voteB = pendingVote(context, ACTION_VOTE_B, battleId, generationId, 2)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentTitle("Which model won?")
            .setContentText(summary.take(120))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .addAction(0, "Model A", voteA)
            .addAction(0, "Model B", voteB)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun pendingVote(
        context: Context,
        action: String,
        battleId: String,
        generationId: String?,
        requestCode: Int,
    ): PendingIntent {
        val intent = Intent(context, BattleVoteReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_BATTLE_ID, battleId)
            putExtra(EXTRA_GENERATION_ID, generationId)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/**
 * Receives notification action votes, persists them through the same
 * [VoteRepository] as in-app votes, then dismisses the notification.
 */
@AndroidEntryPoint
class BattleVoteReceiver : BroadcastReceiver() {

    @Inject
    lateinit var voteRepository: VoteRepository

    override fun onReceive(context: Context, intent: Intent) {
        val choice = when (intent.action) {
            BattleVoteNotifications.ACTION_VOTE_A -> "A"
            BattleVoteNotifications.ACTION_VOTE_B -> "B"
            else -> return
        }
        val battleId = intent.getStringExtra(BattleVoteNotifications.EXTRA_BATTLE_ID) ?: return
        val generationId = intent.getStringExtra(BattleVoteNotifications.EXTRA_GENERATION_ID)

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                voteRepository.submitVote(
                    battleId = battleId,
                    choice = choice,
                    modelAId = null,
                    modelBId = null,
                    generationId = generationId,
                )
            } finally {
                BattleVoteNotifications.cancel(context)
                pending.finish()
            }
        }
    }
}
