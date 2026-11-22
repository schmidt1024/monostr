package com.monostr.app.translate

import javax.inject.Inject
import javax.inject.Singleton

/** Sends a translation to its backend: a server over [TranslateClient], the device over [DeviceTranslator] (never the network). */
@Singleton
class TranslateRouter @Inject constructor(
    private val client: TranslateClient,
    private val device: DeviceTranslator,
) {
    suspend fun translate(
        b: TranslateBackend,
        texts: List<String>,
        source: String,
        target: String,
        timeoutMs: Long? = null,
    ): TranslateResult = when (b) {
        is TranslateServer -> client.translate(b, texts, source, target, timeoutMs)
        DeviceBackend -> device.translate(texts, source, target, timeoutMs)
    }
}
