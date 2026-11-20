package com.flow.app.platform.run

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.os.Build
import com.flow.app.MainActivity
import com.flow.app.R
import com.flow.app.domain.run.NodeRunState
import com.flow.app.domain.run.PlanRunSnapshot
import com.flow.app.domain.run.RunBackgroundRunner
import com.flow.app.domain.run.RunHaptics
import com.flow.app.domain.run.RunNotificationSink
import com.flow.app.domain.run.RunSummary
import com.flow.app.domain.model.StepType
import com.flow.app.domain.run.formatCountdown
import com.flow.app.domain.run.formatNodeTarget
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Android notification implementation.
 *
 * - A low-importance ongoing notification is owned by the foreground service
 *   and mirrors the current run state.
 * - Reaching zero in the background posts a separate high-importance alert
 *   whose vibration pattern is the platform-approved background haptic.
 * - Pause/complete/exit update or cancel the notification exactly when the
 *   run state changes.
 */
class AndroidRunNotificationSink(private val context: Context) : RunNotificationSink {
    private val manager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val lock = Any()
    private var lastOngoingKey: String? = null
    private var expiredTimerRunId: Long? = null

    init {
        ensureChannels()
    }

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val progress = NotificationChannel(
            CHANNEL_PROGRESS,
            "Flow 运行状态",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "运行中的计划与剩余时间"
            setShowBadge(false)
            enableVibration(false)
        }
        val alert = NotificationChannel(
            CHANNEL_ALERT,
            "Flow 计时提醒",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "计时结束提醒"
            enableVibration(true)
            vibrationPattern = longArrayOf(0L, 180L, 100L, 180L)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .build()
            )
        }
        manager.createNotificationChannel(progress)
        manager.createNotificationChannel(alert)
    }

    fun buildOngoing(snapshot: PlanRunSnapshot): Notification {
        val text = progressText(snapshot)
        return Notification.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_flow)
            .setContentTitle(snapshot.plan.name)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_STOPWATCH)
            .setContentIntent(openAppIntent())
            .build()
    }

    override fun onRunStarted(snapshot: PlanRunSnapshot) {
        expiredTimerRunId = null
        lastOngoingKey = null
        postOngoing(snapshot, force = true)
    }

    override fun onProgress(snapshot: PlanRunSnapshot) {
        postOngoing(snapshot, force = false)
    }

    override fun onTimerStarted(snapshot: PlanRunSnapshot) {
        postOngoing(snapshot, force = true)
    }

    override fun onTimerPaused(snapshot: PlanRunSnapshot) {
        postOngoing(snapshot, force = true)
    }

    override fun onTimerResumed(snapshot: PlanRunSnapshot) {
        postOngoing(snapshot, force = true)
    }

    override fun onTimerCompleted(snapshot: PlanRunSnapshot) {
        cancelAlert()
        postOngoing(snapshot, force = true)
    }

    override fun onTimerExpired(snapshot: PlanRunSnapshot) {
        val timerId = snapshot.timerRunId ?: return
        synchronized(lock) {
            if (expiredTimerRunId == timerId) return
            expiredTimerRunId = timerId
        }
        postOngoing(snapshot, force = true)
        val nodeName = snapshot.node?.name ?: "当前节点"
        val alert = Notification.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_stat_flow)
            .setContentTitle("计时结束")
            .setContentText("“$nodeName”的计时已结束")
            .setStyle(
                Notification.BigTextStyle().bigText(
                    "“$nodeName”的计时已结束。回到 Flow 确认完成或继续下一项。"
                )
            )
            .setCategory(Notification.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        notifySafely(ALERT_ID, alert)
    }

    override fun onRunFinished(summary: RunSummary) {
        cancelAll()
    }

    override fun onRunExited(summary: RunSummary) {
        cancelAll()
    }

    override fun clearStaleNotifications() {
        cancelAll()
    }

    private fun postOngoing(snapshot: PlanRunSnapshot, force: Boolean) {
        if (snapshot.finalized) return
        val key = ongoingKey(snapshot)
        synchronized(lock) {
            if (!force && key == lastOngoingKey) return
            lastOngoingKey = key
        }
        notifySafely(ONGOING_ID, buildOngoing(snapshot))
    }

    private fun ongoingKey(snapshot: PlanRunSnapshot): String {
        val second = (snapshot.remainingMs ?: -1L) / 1_000L
        return "${snapshot.runId}:${snapshot.nodeIndex}:${snapshot.nodeState}:" +
            "${snapshot.completedNodes}:$second"
    }

    private fun cancelAlert() {
        notifyManagerSafely { it.cancel(ALERT_ID) }
    }

    private fun cancelAll() {
        synchronized(lock) {
            lastOngoingKey = null
            expiredTimerRunId = null
        }
        notifyManagerSafely {
            it.cancel(ONGOING_ID)
            it.cancel(ALERT_ID)
        }
    }

    private fun progressText(snapshot: PlanRunSnapshot): String {
        val node = snapshot.node ?: return "运行结束"
        val position = "节点 ${snapshot.nodeIndex + 1}/${snapshot.totalNodes}"
        val state = when (snapshot.nodeState) {
            NodeRunState.READY -> "待开始"
            NodeRunState.RUNNING -> "进行中"
            NodeRunState.PAUSED -> "已暂停"
            NodeRunState.EXPIRED -> "已到期"
        }
        val loop = if (snapshot.loopCurrentNode) " · 循环" else ""
        return if (node.type == StepType.TIMER) {
            val remaining = formatCountdown(snapshot.remainingMs)
            "$position · ${node.name}$loop · $state · 剩余 $remaining"
        } else {
            "$position · ${node.name}$loop · $state · ${formatNodeTarget(node)}"
        }
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun notifySafely(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // Foreground service notifications are still allowed; the OS simply
            // hides them. The in-app run remains fully functional.
            return
        }
        notifyManagerSafely { it.notify(id, notification) }
    }

    private inline fun notifyManagerSafely(block: (NotificationManager) -> Unit) {
        try {
            block(manager)
        } catch (_: SecurityException) {
            // Notification permission revoked while running; timer keeps working.
        } catch (_: RuntimeException) {
            // Never let notification plumbing crash a run.
        }
    }

    companion object {
        const val CHANNEL_PROGRESS = "flow_run_progress"
        const val CHANNEL_ALERT = "flow_timer_alerts"
        const val ONGOING_ID = 4101
        const val ALERT_ID = 4102
    }
}

class AndroidRunHaptics(private val context: Context) : RunHaptics {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
            ?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
    }

    override fun timerWarning() {
        vibrate(VibrationEffect.createOneShot(120L, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    override fun timerExpired() {
        vibrate(VibrationEffect.createWaveform(longArrayOf(0L, 180L, 100L, 180L), -1))
    }

    private fun vibrate(effect: VibrationEffect) {
        val target = vibrator ?: return
        if (!target.hasVibrator()) return
        try {
            target.vibrate(effect)
        } catch (_: RuntimeException) {
            // Vibration is best-effort; ignore hardware/permission failures.
        }
    }
}

/**
 * Starts exactly one foreground service per run and stops it on finish/exit.
 * The service is never restarted after process death (START_NOT_STICKY and no
 * persisted state).
 */
class AndroidRunBackgroundRunner(private val context: Context) : RunBackgroundRunner {
    private var started = false

    @Synchronized
    override fun start(snapshot: PlanRunSnapshot) {
        if (started) return
        val intent = Intent(context, RunForegroundService::class.java)
            .setAction(RunForegroundService.ACTION_START)
        try {
            context.startForegroundService(intent)
            started = true
        } catch (_: RuntimeException) {
            // Background start may be restricted; the in-app run still works.
            started = false
        }
    }

    @Synchronized
    override fun stop(runId: String) {
        if (!started) return
        try {
            context.stopService(Intent(context, RunForegroundService::class.java))
        } catch (_: RuntimeException) {
            // Ignore: the service checks the coordinator on its next tick.
        }
        started = false
    }

    @Synchronized
    override fun clearStale() {
        try {
            context.stopService(Intent(context, RunForegroundService::class.java))
        } catch (_: RuntimeException) {
            // No service to clean up.
        }
        started = false
    }
}
