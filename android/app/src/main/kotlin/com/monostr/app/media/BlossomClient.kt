package com.monostr.app.media

import com.monostr.nostr.Signer
import com.monostr.nostr.blossom.BlossomAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** What a Blossom server answered to an upload (BUD-02). */
data class BlobDescriptor(val url: String, val sha256: String, val size: Long, val type: String)

/**
 * Uploads to and deletes from a Blossom server (spec 2 and 5.1). Every request is authorized by a
 * fresh kind 24242 event signed by [signer]; a signer that refuses throws its own exception
 * (`SigningRejectedException`, or `SilentSignUnavailable` for a delete), everything else is an [UploadException].
 */
class BlossomClient(
    http: OkHttpClient,
    private val signer: Signer,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    // several megabytes over a mobile link outlast the shared client's timeouts
    private val http: OkHttpClient = http.newBuilder().writeTimeout(120, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES).build() // no call lives forever, but 10 MB on a slow link fits
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Descriptor(val url: String = "", val sha256: String = "", val size: Long = 0, val type: String = "")

    suspend fun upload(server: String, bytes: ByteArray, type: String, sha256: String, onProgress: (Float) -> Unit = {}): BlobDescriptor {
        val base = server.trim().trimEnd('/')
        val request = Request.Builder().url("$base/upload")
            .header("Authorization", BlossomAuth.authorization(signer, BlossomAuth.UPLOAD, sha256, base, now()))
            .header("X-SHA-256", sha256)
            .put(ProgressBody(bytes, type.toMediaType(), onProgress))
            .build()
        return execute(request) { status, body ->
            val d = try {
                json.decodeFromString(Descriptor.serializer(), body)
            } catch (e: SerializationException) {
                throw UploadException.Rejected(status)
            }
            // spec 5.1: only an answer about the very bytes that were sent, and only a web address, ends up in a note
            if (!isSafeUrl(d.url, base) || d.sha256 != sha256) throw UploadException.Rejected(status)
            BlobDescriptor(d.url, d.sha256, if (d.size > 0) d.size else bytes.size.toLong(), d.type.ifBlank { type })
        }
    }

    /**
     * The address is pasted into the note's text and a tag, so text of the server's choosing must not
     * fit in it: printable ASCII only (no whitespace, control, zero-width or bidi characters), a real
     * web address with a host, https (http only towards an http server, which exists in tests only).
     */
    private fun isSafeUrl(url: String, base: String): Boolean {
        if (url.isEmpty() || url.any { it !in '!'..'~' }) return false
        // OkHttp's parser forgives extra slashes and backslashes after the scheme ("https:///x" would get the host "x")
        val authority = url.substringAfter("://", "")
        if (authority.isEmpty() || authority[0] == '/' || authority[0] == '\\') return false
        val parsed = url.toHttpUrlOrNull() ?: return false
        return parsed.host.isNotEmpty() && (parsed.isHttps || (base.startsWith("http://") && parsed.scheme == "http"))
    }

    /**
     * Asks the server to drop the user's ownership of a blob; a blob that is already gone counts as done.
     * [silent] (the default) is for deletes nobody asked for (spec 10e 5.2): signed silently, and when that
     * needs the user [Signer.signEventSilent] throws and nothing is sent. A delete the user asked for
     * (a deleted note, a removed profile picture) passes false and may open the signer.
     */
    suspend fun delete(server: String, sha256: String, silent: Boolean = true) {
        val base = server.trim().trimEnd('/')
        val unsigned = BlossomAuth.unsigned(BlossomAuth.DELETE, sha256, base, now())
        val auth = BlossomAuth.header(if (silent) signer.signEventSilent(unsigned) else signer.sign(unsigned))
        val request = Request.Builder().url("$base/$sha256")
            .header("Authorization", auth)
            .delete()
            .build()
        try {
            execute(request) { _, _ -> }
        } catch (e: UploadException.Rejected) {
            if (e.status != 404) throw e
        }
    }

    /**
     * True only when the server says it does not have the blob (404) to an unauthorized HEAD. Any other
     * answer and any failure is false: a blob that may exist must never be treated as ours to delete.
     */
    suspend fun absent(server: String, sha256: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("${server.trim().trimEnd('/')}/$sha256").head().build()
            http.newBuilder().followRedirects(false).build().newCall(request).await().use { it.code == 404 }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun <T> execute(request: Request, parse: (status: Int, body: String) -> T): T = withContext(Dispatchers.IO) {
        val response = try {
            http.newCall(request).await()
        } catch (e: IOException) {
            throw UploadException.Network(e)
        }
        response.use { r ->
            // the status decides before any body is read: an error body is never used
            if (r.code >= 400) throw failure(r.code, r.header(REASON_HEADER))
            val body = try {
                val source = r.body?.source()
                // a descriptor is a few hundred bytes; a larger answer is not one
                if (source != null && source.request(MAX_BODY + 1)) throw UploadException.Rejected(r.code)
                source?.readUtf8().orEmpty()
            } catch (e: IOException) {
                throw UploadException.Network(e)
            }
            parse(r.code, body)
        }
    }

    /** The server's reason code first (spec 4.4); a server without codes is read by its status. */
    private fun failure(status: Int, code: String?): UploadException = when (code) {
        "nsfw" -> UploadException.Nsfw()
        "too-large" -> UploadException.TooLarge()
        "quota" -> UploadException.Quota()
        "rate" -> UploadException.Rate()
        "type" -> UploadException.Type()
        "banned", "banned-content" -> UploadException.Banned()
        "auth" -> UploadException.Auth()
        "unavailable" -> UploadException.Unavailable()
        else -> when (status) {
            401 -> UploadException.Auth()
            413 -> UploadException.TooLarge()
            415 -> UploadException.Type()
            429 -> UploadException.Rate()
            in 500..599 -> UploadException.Unavailable()
            else -> UploadException.Rejected(status)
        }
    }

    private companion object {
        const val REASON_HEADER = "X-Monostr-Reason"
        const val MAX_BODY = 64L * 1024
    }
}

/** Runs the call without blocking a thread for its whole duration; cancelling the coroutine cancels the call. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
}

/** The picture bytes as a request body that reports how much has been written. */
private class ProgressBody(private val bytes: ByteArray, private val type: MediaType, private val onProgress: (Float) -> Unit) : RequestBody() {
    override fun contentType(): MediaType = type
    override fun contentLength(): Long = bytes.size.toLong()

    override fun writeTo(sink: BufferedSink) {
        var sent = 0
        while (sent < bytes.size) {
            val n = minOf(CHUNK, bytes.size - sent)
            sink.write(bytes, sent, n)
            sink.flush() // otherwise everything sits in the buffer and the progress jumps to the end at once
            sent += n
            onProgress(sent.toFloat() / bytes.size)
        }
    }

    private companion object {
        const val CHUNK = 64 * 1024
    }
}
