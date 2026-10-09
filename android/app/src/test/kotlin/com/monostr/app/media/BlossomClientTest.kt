package com.monostr.app.media

import com.monostr.nostr.SigningRejectedException
import com.monostr.nostr.SilentSignUnavailable
import com.monostr.tips.event.Event
import com.monostr.tips.event.EventJson
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit

class BlossomClientTest {
    private val server = MockWebServer()
    private val pubkey = "c".repeat(64)
    private val signer = FakeSigner(pubkey)
    private val bytes = ByteArray(200_000) { (it % 251).toByte() }
    private val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private lateinit var base: String
    private lateinit var client: BlossomClient

    @BeforeEach
    fun start() {
        server.start()
        base = server.url("/").toString().trimEnd('/')
        client = BlossomClient(OkHttpClient(), signer) { 1000L }
    }

    @AfterEach
    fun stop() = runCatching { server.shutdown() }.let { }

    private fun descriptor(url: String = "$base/$hash.jpg", sha256: String = hash) =
        """{"url":"$url","sha256":"$sha256","size":${bytes.size},"type":"image/jpeg","uploaded":1000,"extra":true}"""

    private fun authOf(header: String): Event =
        EventJson.decode(String(Base64.getDecoder().decode(header.removePrefix("Nostr ")), Charsets.UTF_8))

    private suspend fun failure(block: suspend () -> Unit): Throwable {
        try {
            block()
        } catch (e: Throwable) {
            return e
        }
        return fail("expected a failure")
    }

    @Test
    fun `upload puts the raw bytes with authorization, type and hash, and reports progress`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody(descriptor()))
        val progress = ArrayList<Float>()
        val d = client.upload("$base/", bytes, "image/jpeg", hash) { progress += it }

        assertEquals(BlobDescriptor("$base/$hash.jpg", hash, bytes.size.toLong(), "image/jpeg"), d)
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/upload", request.path)
        assertEquals("image/jpeg", request.getHeader("Content-Type"))
        assertEquals(hash, request.getHeader("X-SHA-256"))
        assertEquals(bytes.size.toString(), request.getHeader("Content-Length"))
        assertArrayEquals(bytes, request.body.readByteArray())
        val auth = authOf(request.getHeader("Authorization")!!)
        assertEquals(24242, auth.kind)
        assertEquals("upload", auth.firstTagValue("t"))
        assertEquals(hash, auth.firstTagValue("x"))
        assertEquals("1300", auth.firstTagValue("expiration"))
        assertEquals(server.hostName.lowercase(), auth.firstTagValue("server"))
        assertTrue(progress.isNotEmpty() && progress.last() == 1f && progress == progress.sorted(), progress.toString())
    }

    @Test
    fun `a duplicate answered with 200 is a success too`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(descriptor()))
        assertEquals(hash, client.upload(base, bytes, "image/jpeg", hash).sha256)
    }

    @Test
    fun `the reason code of the server decides the kind of failure`() = runTest {
        val cases = mapOf(
            "nsfw" to UploadException.Nsfw::class.java, "too-large" to UploadException.TooLarge::class.java,
            "quota" to UploadException.Quota::class.java, "rate" to UploadException.Rate::class.java,
            "type" to UploadException.Type::class.java, "banned" to UploadException.Banned::class.java,
            "banned-content" to UploadException.Banned::class.java, "auth" to UploadException.Auth::class.java,
            "unavailable" to UploadException.Unavailable::class.java,
        )
        for ((code, type) in cases) {
            // the status is deliberately unhelpful: the code wins
            server.enqueue(MockResponse().setResponseCode(403).setHeader("X-Monostr-Reason", code).setHeader("X-Reason", "text for people").setBody("""{"message":"x"}"""))
            assertInstanceOf(type, failure { client.upload(base, bytes, "image/jpeg", hash) }, code)
        }
    }

    @Test
    fun `a server without reason codes is read by status`() = runTest {
        val cases = mapOf(
            401 to UploadException.Auth::class.java, 413 to UploadException.TooLarge::class.java,
            415 to UploadException.Type::class.java, 429 to UploadException.Rate::class.java,
            500 to UploadException.Unavailable::class.java, 503 to UploadException.Unavailable::class.java,
            400 to UploadException.Rejected::class.java, 402 to UploadException.Rejected::class.java,
            403 to UploadException.Rejected::class.java, 409 to UploadException.Rejected::class.java,
        )
        for ((status, type) in cases) {
            server.enqueue(MockResponse().setResponseCode(status).setBody("nope"))
            val e = failure { client.upload(base, bytes, "image/jpeg", hash) }
            assertInstanceOf(type, e, status.toString())
            if (e is UploadException.Rejected) assertEquals(status, e.status)
        }
        // an unknown code falls back to the status as well
        server.enqueue(MockResponse().setResponseCode(429).setHeader("X-Monostr-Reason", "something-new"))
        assertInstanceOf(UploadException.Rate::class.java, failure { client.upload(base, bytes, "image/jpeg", hash) })
    }

    @Test
    fun `an answer that does not describe the uploaded bytes is refused`() = runTest {
        for (body in listOf(
            descriptor(sha256 = "f".repeat(64)), // another blob
            descriptor(url = "ftp://files.example/$hash.jpg"), // not a web address
            descriptor(url = ""), // no address
            descriptor(url = "https://files.example/a.jpg\\nnostr:npub1evil"), // a line break smuggles text into the note
            descriptor(url = "https://files.example/a b.jpg"), // a space does too
            descriptor(url = "https://files.example/a​b.jpg"), // zero-width space
            descriptor(url = "https://files.example/a‮b.jpg"), // right-to-left override
            descriptor(url = "https://"), // no host
            descriptor(url = "https:///nohost.jpg"), // no host either
            "<html>welcome</html>", // not JSON
            "", // nothing
        )) {
            server.enqueue(MockResponse().setResponseCode(201).setBody(body))
            assertInstanceOf(UploadException.Rejected::class.java, failure { client.upload(base, bytes, "image/jpeg", hash) }, body)
        }
    }

    @Test
    fun `an answer larger than a descriptor can be is refused`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody(descriptor() + " ".repeat(100_000)))
        assertInstanceOf(UploadException.Rejected::class.java, failure { client.upload(base, bytes, "image/jpeg", hash) })
    }

    @Test
    fun `an error status is decided without reading its body`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(403).setHeader("X-Monostr-Reason", "nsfw")
                .setBody("x".repeat(1_000_000)).setBodyDelay(15, TimeUnit.SECONDS), // the body would take its time
        )
        val started = System.nanoTime()
        assertInstanceOf(UploadException.Nsfw::class.java, failure { client.upload(base, bytes, "image/jpeg", hash) })
        val millis = (System.nanoTime() - started) / 1_000_000
        assertTrue(millis < 3000, "took $millis ms")
    }

    @Test
    fun `an unreachable server is a network failure`() = runTest {
        server.shutdown()
        assertInstanceOf(UploadException.Network::class.java, failure { client.upload(base, bytes, "image/jpeg", hash) })
        assertInstanceOf(UploadException.Network::class.java, failure { client.delete(base, hash) })
    }

    @Test
    fun `a refused signature surfaces as SigningRejectedException and sends nothing`() = runTest {
        val refusing = BlossomClient(OkHttpClient(), FakeSigner(onSign = { throw SigningRejectedException() })) { 1000L }
        assertInstanceOf(SigningRejectedException::class.java, failure { refusing.upload(base, bytes, "image/jpeg", hash) })
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `delete names the blob in the path and in a delete authorization`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        client.delete("$base/", hash)
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/$hash", request.path)
        val auth = authOf(request.getHeader("Authorization")!!)
        assertEquals("delete", auth.firstTagValue("t"))
        assertEquals(hash, auth.firstTagValue("x"))
    }

    @Test
    fun `a delete is authorized without asking the user`() = runTest {
        val silentOnly = BlossomClient(OkHttpClient(), FakeSigner(pubkey, onSign = { fail("a delete must not open a signer prompt") }, onSilent = { FakeSigner.signed(it, pubkey) })) { 1000L }
        server.enqueue(MockResponse().setResponseCode(204))
        silentOnly.delete(base, hash)
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("DELETE", request.method)
        assertEquals("delete", authOf(request.getHeader("Authorization")!!).firstTagValue("t"))
    }

    @Test
    fun `a delete that would need the user throws and sends nothing`() = runTest {
        val needsUser = BlossomClient(OkHttpClient(), FakeSigner(pubkey, onSign = { fail("a delete must not open a signer prompt") }, onSilent = { throw SilentSignUnavailable() })) { 1000L }
        assertInstanceOf(SilentSignUnavailable::class.java, failure { needsUser.delete(base, hash) })
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a delete the user asked for is signed normally`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        val needsUser = BlossomClient(OkHttpClient(), FakeSigner(pubkey, onSign = { FakeSigner.signed(it, pubkey) }, onSilent = { throw SilentSignUnavailable() })) { 1000L }
        needsUser.delete(base, hash, silent = false)
        assertEquals("DELETE", server.takeRequest().method)
    }

    @Test
    fun `deleting what is already gone is fine, any other refusal is not`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setHeader("X-Monostr-Reason", "not-found"))
        client.delete(base, hash)
        server.enqueue(MockResponse().setResponseCode(401).setHeader("X-Monostr-Reason", "auth"))
        assertInstanceOf(UploadException.Auth::class.java, failure { client.delete(base, hash) })
    }

    @Test
    fun `absent is true only for a 404, by a HEAD without authorization`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        assertTrue(client.absent(base, hash))
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("HEAD", request.method)
        assertEquals("/$hash", request.path)
        assertEquals(null, request.getHeader("Authorization"))

        server.enqueue(MockResponse().setResponseCode(200))
        assertEquals(false, client.absent(base, hash))
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(false, client.absent(base, hash))
        server.enqueue(MockResponse().setResponseCode(403))
        assertEquals(false, client.absent(base, hash))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "$base/elsewhere"))
        assertEquals(false, client.absent(base, hash))
    }

    @Test
    fun `absent is false when the server cannot be reached or the address is bad`() = runTest {
        assertEquals(false, client.absent("not a url", hash))
        server.shutdown()
        assertEquals(false, client.absent(base, hash))
    }
}
