package com.flow.app.platform.run

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.flow.app.FlowApplication

/**
 * Foreground service that keeps the run alive while Flow is in background.
 *
 * It never owns run state: it reads the process-scoped [RunCoordinator] and
 * asks it to refresh, so the foreground UI and the service always use the same
 * instance. It is START_NOT_STICKY and there is no persisted session, so a
 * killed process never revives an old run.
 */
class RunForegroundService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var ticking = false

    private val tick = object : Runnable {
        override fun run() {
            val app = application as? FlowApplication
            if (app == null) {
                stopSelf()
                return
            }
            val snapshot = app.runCoordinator.currentSnapshot()
            if (snapshot == null || snapshot.finalized) {
                stopSelf()
                return
            }
            app.runCoordinator.refreshFromBackground()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as? FlowApplication
        if (app == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val snapshot = app.runCoordinator.currentSnapshot()
        if (snapshot == null || snapshot.finalized) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForeground(
                AndroidRunNotificationSink.ONGOING_ID,
                app.runNotificationSink.buildOngoing(snapshot)
            )
        } catch (_: RuntimeException) {
            // The OS refused the foreground start; do not crash the app.
            stopSelf()
            return START_NOT_STICKY
        }
        if (!ticking) {
            ticking = true
            handler.postDelayed(tick, TICK_MS)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        ticking = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.flow.app.action.RUN_START"
        private const val TICK_MS = 500L
    }
}
