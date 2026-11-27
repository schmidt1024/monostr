package com.monostr.app

import android.os.StrictMode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.session.NostrSessionEntryPoint
import com.monostr.app.session.SessionState
import com.monostr.app.session.SwitchResult
import com.monostr.nostr.LocalSigner
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import rust.nostr.sdk.Keys

/**
 * Pixel finding of 2026-11-25: the account steps run on the main thread (the ViewModels call them there), and the eviction of
 * the shared HTTP pool closed its idle TLS connections in place, which writes a close_notify: network on the main thread, so
 * every add, switch and removal failed on a real phone. Here the pool holds an idle TLS connection (a plain-HTTP loopback one
 * writes nothing on close, which hid it) and the steps are called on the main thread under a policy that throws on network.
 */
@RunWith(AndroidJUnit4::class)
class AccountSwitchMainThreadTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val entry get() = EntryPointAccessors.fromApplication(ctx, NostrSessionEntryPoint::class.java)
    private fun active(): String? = (entry.session().state.value as? SessionState.Active)?.ready?.pubkey

    private val keyA = Keys.generate()
    private val keyB = Keys.generate()
    private val pkA get() = keyA.publicKey().toHex()
    private val pkB get() = keyB.publicKey().toHex()

    private val server = MockWebServer()
    private var previousPolicy: StrictMode.ThreadPolicy? = null

    @Before fun loggedInAsA() {
        runBlocking {
            runCatching { entry.session().logout() }
            entry.session().loginLocal(LocalSigner(keyA.secretKey().toHex()))
            entry.session().applyRelays(listOf("ws://127.0.0.1:9"))
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            previousPolicy = StrictMode.getThreadPolicy()
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectNetwork().penaltyDeathOnNetwork().build())
        }
    }

    @After fun out() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { previousPolicy?.let(StrictMode::setThreadPolicy) }
        runCatching { server.shutdown() }
        runBlocking {
            runCatching { entry.session().removeAccount(pkB) }
            runCatching { entry.session().logout() }
        }
    }

    /** One idle TLS connection in the app's shared pool, opened by a client that shares that pool. */
    private fun poolHoldsIdleTls() {
        val held = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(held).build().sslSocketFactory(), false)
        server.enqueue(MockResponse().setBody("ok"))
        server.start()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(held.certificate).build()
        val pool = entry.http().connectionPool
        val client = OkHttpClient.Builder().connectionPool(pool).sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
        client.newCall(Request.Builder().url(server.url("/").newBuilder().host("localhost").build()).build()).execute().use { it.body!!.string() }
        assertEquals("the pool holds the idle TLS connection", 1, pool.idleConnectionCount())
    }

    @Test
    fun addAnAccountOnTheMainThread() {
        poolHoldsIdleTls()
        runBlocking { withContext(Dispatchers.Main) { entry.session().loginLocal(LocalSigner(keyB.secretKey().toHex())) } }
        assertEquals(pkB, active())
        assertEquals(0, entry.http().connectionPool.connectionCount())
    }

    @Test
    fun switchOnTheMainThread() {
        runBlocking { entry.session().loginLocal(LocalSigner(keyB.secretKey().toHex())) }
        poolHoldsIdleTls()
        val result = runBlocking { withContext(Dispatchers.Main) { entry.session().switchTo(pkA) } }
        assertEquals(SwitchResult.Done, result)
        assertEquals(pkA, active())
        assertEquals(0, entry.http().connectionPool.connectionCount())
    }

    @Test
    fun removeTheActiveAccountOnTheMainThread() {
        runBlocking { entry.session().loginLocal(LocalSigner(keyB.secretKey().toHex())) }
        poolHoldsIdleTls()
        runBlocking { withContext(Dispatchers.Main) { entry.session().removeAccount(pkB) } }
        assertEquals(pkA, active())
        assertEquals(0, entry.http().connectionPool.connectionCount())
    }
}
