package com.monostr.app

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.data.FileOwnListsCache
import com.monostr.app.data.PrefsRelayStore
import com.monostr.app.data.accountDir
import com.monostr.app.session.NostrSessionEntryPoint
import com.monostr.nostr.LocalSigner
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.repo.CachedTabs
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Tag

/** Spec perf 3.7 / 6: a pinned own list shows its title from the device while the only relay never answers; logout takes the summary along. */
@RunWith(AndroidJUnit4::class)
class ListTabOfflineTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val me = Keys.generate()
    private val secret = me.secretKey().toHex()
    private val pk = me.publicKey().toHex()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val entry get() = EntryPointAccessors.fromApplication(ctx, NostrSessionEntryPoint::class.java)
    private fun nodes(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes()
    private fun texts(text: String) = rule.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes()

    @After fun restore() {
        runBlocking {
            runCatching { entry.session().applyRelays(PrefsRelayStore.DEFAULT_RELAYS) }
            runCatching { entry.session().logout() }
        }
    }

    @Test
    fun aPinnedOwnListShowsItsTitleWhileTheRelaysNeverAnswer() {
        runBlocking { runCatching { entry.session().logout() } }
        rule.waitUntil(15_000) { nodes("login-secret").isNotEmpty() }
        val member = Keys.generate().publicKey().toHex()
        val list = EventBuilder(Kind(30000u), "")
            .tags(listOf(Tag.parse(listOf("d", "offline")), Tag.parse(listOf("title", "Offline tab")), Tag.parse(listOf("p", member))))
            .signWithKeys(me)
        LoopbackRelay(silent = true).use { relay ->
            runBlocking {
                // logged out: only the stored relay list changes
                entry.session().applyRelays(listOf(relay.url))
                // the account's database and pins as an earlier session on this device left them
                val db = NostrEngine.create(accountDir(ctx.filesDir, pk).apply { mkdirs() }.absolutePath, LocalSigner(secret), emptyList())
                db.save(list)
                db.close()
                entry.feedTabs().save(pk, CachedTabs(listOf("30000:$pk:offline"), 1))
            }
            rule.onNodeWithTag("login-secret").performTextInput(secret)
            rule.onNodeWithTag("login-secret-button").performClick()
            rule.waitUntil(30_000) { nodes("fab-compose").isNotEmpty() || nodes("setup-skip").isNotEmpty() }
            if (nodes("setup-skip").isNotEmpty()) {
                rule.onNodeWithTag("setup-skip").performClick()
                rule.waitUntil(15_000) { nodes("fab-compose").isNotEmpty() }
            }
            // before: the relays were asked first (6 s on this relay, then the default relays) and the tab said "Untitled list"
            rule.waitUntil(3_000) { texts("Offline tab").isNotEmpty() }
            rule.waitUntil(5_000) { runBlocking { FileOwnListsCache(ctx.noBackupFilesDir).load(pk) }?.any { it.title == "Offline tab" } == true }
            runBlocking { entry.session().logout() }
            // the summary leaves the device with the account (privacy, spec perf 3.7)
            assertNull(runBlocking { FileOwnListsCache(ctx.noBackupFilesDir).load(pk) })
        }
    }
}
