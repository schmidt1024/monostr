package com.monostr.app

import android.os.Process
import android.os.SystemClock
import org.torproject.jni.TorService
import kotlin.concurrent.thread

/**
 * One tor per process: a second start in the same process aborts it (docs/testing/tor-spike.md). So this service runs in
 * `:tor`, and stopping it ends the process; the wait lets tor finish its shutdown first.
 */
class TorProcessService : TorService() {
    override fun onDestroy() {
        super.onDestroy()
        thread(isDaemon = true) {
            SystemClock.sleep(300)
            Process.killProcess(Process.myPid())
        }
    }
}
