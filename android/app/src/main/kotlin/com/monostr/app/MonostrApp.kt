package com.monostr.app

import android.app.Application
import com.monostr.app.work.DmCheckWorker
import com.monostr.app.work.TipCheckWorker
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MonostrApp : Application() {
    override fun onCreate() {
        super.onCreate()
        TipCheckWorker.schedule(this)
        DmCheckWorker.schedule(this)
    }
}
