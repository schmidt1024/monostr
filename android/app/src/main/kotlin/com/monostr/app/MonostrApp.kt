package com.monostr.app

import coil3.serviceLoaderEnabled
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.SingletonImageLoader
import coil3.PlatformContext
import coil3.ImageLoader
import android.app.ActivityManager
import android.app.Application
import android.os.Process
import android.os.SystemClock
import com.monostr.app.work.BackgroundCheckWorker
import com.monostr.app.work.StartQueue
import com.monostr.nostr.PerfTrace
import com.monostr.app.net.TorSwitchStore
import com.monostr.app.net.currentProcessName
import com.monostr.app.net.isTorProcessName
import com.monostr.nostr.net.NetworkGate
import com.monostr.nostr.net.initFrom
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
class MonostrApp : Application(), SingletonImageLoader.Factory {
    @Inject lateinit var networkGate: NetworkGate
    @Inject lateinit var torSwitch: TorSwitchStore
    /** Lazy: building a client is not for the main thread at start; the loader asks for it with the first picture. */
    @Inject lateinit var http: dagger.Lazy<okhttp3.OkHttpClient>

    /**
     * No service loader: coil-gif would register itself and every avatar, banner and thumbnail would animate without end.
     * Only a note's GIF cell and the viewer ask for animation, per request (see GifImage).
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context).serviceLoaderEnabled(false).components { add(OkHttpNetworkFetcherFactory(callFactory = { http.get() })) }.build()

    override fun onCreate() {
        super.onCreate()
        // :tor runs nothing of the app. The rest would crash there: content providers do not run in that process, and
        // WorkManager needs them (docs/testing/tor-spike.md)
        if (isTorProcessName(currentProcessName())) return
        // a long-press on a link in a selectable note must not offer the system «Open» action: its intent skips the link prompt
        androidx.compose.foundation.ComposeFoundationFlags.isSmartSelectionEnabled = false
        if (BuildConfig.PERF) {
            PerfTrace.sinceStart = { SystemClock.uptimeMillis() - Process.getStartUptimeMillis() }
            PerfTrace.enabled = true
            // perf builds only: the marks also go to Android/data/<app>/files/perf.log, so background runs while the phone is
            // off the cable can be read later (adb pull); timestamps in wall time, the process id tells the runs apart
            val log = getExternalFilesDir(null)?.let { java.io.File(it, "perf.log") }
            val pid = Process.myPid()
            // one background writer: a mark on the main thread must not wait for the disk (it would skew what it measures)
            val writer = java.util.concurrent.Executors.newSingleThreadExecutor()
            PerfTrace.sink = { line ->
                println(line)
                if (log != null) {
                    val at = System.currentTimeMillis()
                    writer.execute { runCatching { log.appendText("${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.ROOT).format(java.util.Date(at))} [$pid] $line\n") } }
                }
            }
        }
        // the gate stays Unknown (everything blocked) until the setting is read; read off the main thread, no runBlocking
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            networkGate.initFrom { torSwitch.readAtStart() }
        }
        // the store read its marker when it was built; the same value changes nothing about a schedule. Last: a start in the
        // background waits for WorkManager's start-up cleanup, which must not cancel a job the system delivered before it ran
        // (see scheduleAtStart); a start for the screen does not wait, it would cost the first frame about 40 ms
        val queueWait = SystemClock.uptimeMillis()
        val importance = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance
        val forScreen = importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        val queue = BackgroundCheckWorker.scheduleAtStart(this, torSwitch.enabled.value, wait = !forScreen)
        if (PerfTrace.enabled) {
            val what = when (queue) {
                StartQueue.THROUGH -> "through"
                StartQueue.TIMED_OUT -> "timed out"
                StartQueue.NOT_WAITED -> "not waited for"
            }
            PerfTrace.mark("work queue importance $importance, $what after ${SystemClock.uptimeMillis() - queueWait} ms")
        }
    }
}
