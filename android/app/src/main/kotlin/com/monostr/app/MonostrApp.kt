package com.monostr.app

import android.app.Application
import android.os.Process
import android.os.SystemClock
import com.monostr.app.work.DmCheckWorker
import com.monostr.app.work.TipCheckWorker
import com.monostr.nostr.PerfTrace
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MonostrApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.PERF) {
            PerfTrace.sinceStart = { SystemClock.uptimeMillis() - Process.getStartUptimeMillis() }
            PerfTrace.enabled = true
        }
        TipCheckWorker.schedule(this)
        DmCheckWorker.schedule(this)
    }
}
