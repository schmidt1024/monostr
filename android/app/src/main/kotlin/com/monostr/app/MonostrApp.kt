package com.monostr.app

import coil3.serviceLoaderEnabled
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.SingletonImageLoader
import coil3.PlatformContext
import coil3.ImageLoader
import android.app.Application
import android.os.Process
import android.os.SystemClock
import com.monostr.app.work.BackgroundCheckWorker
import com.monostr.nostr.PerfTrace
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MonostrApp : Application(), SingletonImageLoader.Factory {
    /**
     * No service loader: coil-gif would register itself and every avatar, banner and thumbnail would animate without end.
     * Only a note's GIF cell and the viewer ask for animation, per request (see GifImage).
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context).serviceLoaderEnabled(false).components { add(OkHttpNetworkFetcherFactory()) }.build()

    override fun onCreate() {
        super.onCreate()
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
        BackgroundCheckWorker.schedule(this)
    }
}
