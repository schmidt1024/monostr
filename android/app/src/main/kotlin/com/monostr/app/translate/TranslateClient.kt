package com.monostr.app.translate

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** An open LibreTranslate server (no API key). */
data class TranslateServer(val url: String) {
    val host: String get() = runCatching { URI(url).host }.getOrNull() ?: url

    companion object {
        val MONOSTR = TranslateServer("https://translate.monostr.com")
    }
}

sealed interface TranslateResult {
    data class Ok(val texts: List<String>) : TranslateResult
    data object RateLimited : TranslateResult
    data object Failed : TranslateResult
}

/**
 * POST /translate of one LibreTranslate server. The call is asynchronous and cancellable: a cancelled caller (the
 * reader closed) cancels the HTTP call too (spec 2026-11-03). Redirects stay on their scheme: an https server can never
 * send the texts on over plain http (`followSslRedirects(false)`); http to http and https to https still follow.
 */
class TranslateClient(http: OkHttpClient, callTimeoutSeconds: Long = 15) {
    internal val http = http.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        // no read timeout: LibreTranslate sends nothing until the whole answer is ready, so the shared client's would cut
        // every long call; the call timeout (the client's, or the call's own) bounds each call instead
        .readTimeout(0, TimeUnit.SECONDS)
        .callTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(false)
        .build()

    /** [timeoutMs]: this call's whole-call timeout instead of the client's (an article block by its length); null: the client's. */
    suspend fun translate(
        server: TranslateServer,
        texts: List<String>,
        source: String,
        target: String,
        timeoutMs: Long? = null,
    ): TranslateResult {
        val req = try {
            val body = buildJsonObject {
                put("q", JsonArray(texts.map { JsonPrimitive(it) }))
                put("source", source)
                put("target", target)
                put("format", "html")
            }.toString().toRequestBody("application/json".toMediaType())
            Request.Builder().url("${server.url}/translate").post(body).build()
        } catch (e: IllegalArgumentException) { // bad URL
            return TranslateResult.Failed
        }
        val call = http.newCall(req)
        timeoutMs?.let { call.timeout().timeout(it, TimeUnit.MILLISECONDS) }
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = cont.resume(TranslateResult.Failed)

                // on OkHttp's thread: the body is read there, never on the caller's
                override fun onResponse(call: Call, response: Response) = cont.resume(response.use { parse(it, texts.size) })
            })
        }
    }

    private fun parse(resp: Response, count: Int): TranslateResult = try {
        when {
            resp.code == 429 -> TranslateResult.RateLimited
            !resp.isSuccessful -> TranslateResult.Failed
            else -> {
                val out = Json.parseToJsonElement(resp.body?.string().orEmpty())
                    .jsonObject["translatedText"]!!.jsonArray.map { it.jsonPrimitive.content }
                if (out.size == count) TranslateResult.Ok(out) else TranslateResult.Failed
            }
        }
    } catch (e: IOException) {
        TranslateResult.Failed
    } catch (e: IllegalArgumentException) { // malformed/unexpected JSON
        TranslateResult.Failed
    } catch (e: NullPointerException) { // translatedText missing
        TranslateResult.Failed
    }
}
