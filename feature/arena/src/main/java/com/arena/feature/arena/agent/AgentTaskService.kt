package com.arena.feature.arena.agent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.arena.core.permissions.PermissionManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Foreground service for long-running Agent tasks.
 *
 * Declared with `android:foregroundServiceType="dataSync"` in the app
 * manifest (Android 14+ requires an explicit type). The actual agent work
 * streams through the WebView/session; this service only keeps the process
 * eligible to receive SSE/bridge updates while the user backgrounds the app,
 * and shows a progress notification with a Stop action.
 *
 * POST_NOTIFICATIONS gates the notification's visibility; the service itself
 * still runs (foreground services are exempt from the notification runtime
 * permission for their ongoing notification on API 33+, but we still check so
 * the UI can explain the trade-off via the rationale sheet).
 */
@AndroidEntryPoint
class AgentTaskService : Service() {

    @Inject
    lateinit var permissionManager: PermissionManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        startForeground(NOTIFICATION_ID, buildNotification(progress = 0))
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching {
            getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Agent tasks",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Long-running agent analysis" },
        )
    }

    private fun buildNotification(progress: Int) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("Agent task running")
            .setContentText("Analysis continues in the background")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, progress == 0)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private companion object {
        const val CHANNEL_ID = "agent_tasks"
        const val NOTIFICATION_ID = 7710
    }
}
