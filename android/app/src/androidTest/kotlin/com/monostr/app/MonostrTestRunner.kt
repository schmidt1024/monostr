package com.monostr.app

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import com.monostr.app.net.TorRestartSeam

/** A switch to Tor after a direct relay route ends the process, which would end the suite too: here it records that and goes on in place. `-e realEnd true` keeps the real end, for the leak test. */
class MonostrTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        TorRestartSeam.inProcess = arguments?.getString("realEnd") != "true"
        super.onCreate(arguments)
    }
}
