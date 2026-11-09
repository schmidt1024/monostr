package com.monostr.app.net

import android.app.Application
import android.os.Build
import java.io.File

/** Own file, without any reference to the service class: this runs on every app start, in every process. */
internal fun isTorProcessName(name: String): Boolean = name.endsWith(":tor")

/** [Application.getProcessName] needs API 28, minSdk is 26. */
internal fun currentProcessName(): String =
    if (Build.VERSION.SDK_INT >= 28) Application.getProcessName()
    else runCatching { File("/proc/self/cmdline").readText().substringBefore('\u0000') }.getOrDefault("")
