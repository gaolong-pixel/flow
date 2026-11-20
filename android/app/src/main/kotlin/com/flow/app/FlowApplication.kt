package com.flow.app

import android.app.Application
import com.flow.app.domain.run.AndroidRunMonotonicClock
import com.flow.app.domain.run.RunCoordinator
import com.flow.app.domain.run.SystemRunWallClock
import com.flow.app.platform.run.AndroidRunBackgroundRunner
import com.flow.app.platform.run.AndroidRunHaptics
import com.flow.app.platform.run.AndroidRunNotificationSink

/**
 * Owns the process-scoped run coordinator. There is exactly one instance for
 * the whole app (activities and the foreground service share it).
 */
class FlowApplication : Application() {
    lateinit var runNotificationSink: AndroidRunNotificationSink
        private set

    lateinit var runCoordinator: RunCoordinator
        private set

    override fun onCreate() {
        super.onCreate()
        runNotificationSink = AndroidRunNotificationSink(this)
        runCoordinator = RunCoordinator(
            monotonic = AndroidRunMonotonicClock,
            wallClock = SystemRunWallClock,
            notifications = runNotificationSink,
            background = AndroidRunBackgroundRunner(this),
            haptics = AndroidRunHaptics(this)
        )
    }
}
