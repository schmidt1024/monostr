package com.monostr.app

import android.content.Intent
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.session.NostrSessionEntryPoint
import com.monostr.app.work.OpenToken
import com.monostr.nostr.LocalSigner
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import rust.nostr.sdk.Keys

/** A chat opened from a DM notification's tap leaves by its back arrow (owner 2026-11-26: the arrow did nothing). */
@RunWith(AndroidJUnit4::class)
class DmNotificationBackTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val entry get() = EntryPointAccessors.fromApplication(ctx, NostrSessionEntryPoint::class.java)
    private fun has(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private val me = Keys.generate()
    private val peer = Keys.generate().publicKey().toHex()

    @Before fun loggedIn() {
        runBlocking {
            runCatching { entry.session().logout() }
            entry.session().loginLocal(LocalSigner(me.secretKey().toHex()))
            entry.session().applyRelays(listOf("ws://127.0.0.1:9"))
        }
    }

    @After fun out() {
        runBlocking { runCatching { entry.session().logout() } }
    }

    @Test fun backArrowLeavesAChatOpenedFromTheNotification() {
        val intent = Intent(ctx, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_DM, peer)
            .putExtra(MainActivity.EXTRA_OPEN_TOKEN, OpenToken.get(ctx))
            .putExtra(MainActivity.EXTRA_ACCOUNT, me.publicKey().toHex())
            .setData(android.net.Uri.parse("monostr-dm:test/$peer"))
        ActivityScenario.launch<MainActivity>(intent).use {
            // a fresh test account first sees the Monero setup; the pending chat opens behind it
            rule.waitUntil(60_000) { has("chat-input") || has("setup-skip") }
            if (has("setup-skip")) rule.onNode(hasTestTag("setup-skip")).performClick()
            rule.waitUntil(30_000) { has("chat-input") }
            // the chat stays open a while, as on the Pixel (the arrow was tapped after ~15 s)
            Thread.sleep(3_000)
            rule.waitForIdle()
            rule.onNode(hasContentDescription("Back")).performClick()
            rule.waitUntil(15_000) { !has("chat-input") }
        }
    }
}
