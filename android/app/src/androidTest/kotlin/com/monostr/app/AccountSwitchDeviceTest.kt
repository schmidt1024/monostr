package com.monostr.app

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.net.FreshRoute
import com.monostr.app.session.NostrSessionEntryPoint
import com.monostr.app.session.SessionState
import com.monostr.app.ui.accounts.SwitchNotice
import com.monostr.nostr.LocalSigner
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.assertIsDisplayed
import org.junit.Assert.assertNotSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import rust.nostr.sdk.Keys

/**
 * Spec «Switcher UI», «Adding an account», «Removing an account» (Task 10): a second account added by key from the switcher, a
 * switch back by its row, removal of the active account (the other one takes over) and of the last one (login screen).
 */
@RunWith(AndroidJUnit4::class)
class AccountSwitchDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val entry get() = EntryPointAccessors.fromApplication(ctx, NostrSessionEntryPoint::class.java)
    private fun has(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun active(): String? = (entry.session().state.value as? SessionState.Active)?.ready?.pubkey

    private val keyA = Keys.generate()
    private val keyB = Keys.generate()
    private val pkA get() = keyA.publicKey().toHex()
    private val pkB get() = keyB.publicKey().toHex()

    @Before fun loggedInAsA() {
        runBlocking {
            runCatching { entry.session().logout() }
            entry.session().loginLocal(LocalSigner(keyA.secretKey().toHex()))
            entry.session().applyRelays(listOf("ws://127.0.0.1:9"))
        }
    }

    @After fun out() {
        runBlocking { runCatching { entry.session().logout() } }
    }

    private fun waitForFeed() {
        rule.waitUntil(60_000) { has("fab-compose") || has("setup-skip") }
        if (has("setup-skip")) {
            rule.onNodeWithTag("setup-skip").performClick()
            rule.waitUntil(15_000) { has("fab-compose") }
        }
    }

    private fun resumed(): android.app.Activity {
        var found: android.app.Activity? = null
        rule.waitUntil(10_000) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                found = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).firstOrNull()
            }
            found != null
        }
        return found!!
    }

    private fun openSwitcher() {
        rule.waitUntil(15_000) { has("feed-profile") }
        rule.onNodeWithTag("feed-profile").performTouchInput { longClick() }
        rule.waitUntil(10_000) { has("account-switcher") }
    }

    /**
     * Final review M3: a restore (every app start, also the first after the update) refreshes the switcher's name of the account
     * from its own profile, not only a switch to it.
     */
    @Test
    fun restoreRefreshesTheSwitcherName() {
        val registry = entry.sessions() as com.monostr.app.data.AccountsStore
        fun storedName() = runBlocking { registry.accounts.first().firstOrNull { it.pubkey == pkA }?.name }
        val name = "monostr-test-" + System.currentTimeMillis()
        runBlocking {
            val ready = entry.session().requireReady()
            ready.engine.save(rust.nostr.sdk.EventBuilder(rust.nostr.sdk.Kind(0u), """{"name":"$name"}""").signWithKeys(keyA))
            ready.profiles.invalidate(pkA)
        }
        rule.waitUntil(30_000) { storedName() == name }
        // as a registry of an older version: no name yet; the running session told it already, so only a new start can
        runBlocking {
            registry.updateDisplay(pkA, null, null)
            entry.session().release()
            entry.session().restore()
        }
        assertEquals(pkA, active())
        rule.waitUntil(30_000) { storedName() == name }
    }

    /** Owner 2026-11-26: Settings › Accounts is one page, the active account above, the others below with «Add account». */
    @Test
    fun settingsAccountPageListsTheOtherAccountsAndSwitches() {
        runBlocking {
            entry.session().loginLocal(LocalSigner(keyB.secretKey().toHex()))
            assertEquals(com.monostr.app.session.SwitchResult.Done, entry.session().switchTo(pkA))
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForFeed()
            rule.onNodeWithTag("feed-settings").performClick()
            rule.waitUntil(15_000) { has("settings-account") }
            assertFalse(has("settings-accounts")) // no second row any more
            rule.onNodeWithTag("settings-account").performClick()
            rule.waitUntil(15_000) { has("account-row-" + pkB.take(8)) }
            assertFalse(has("account-row-" + pkA.take(8))) // the active one is the summary above, not a row
            assertTrue(has("account-add"))
            assertTrue(has("account-remove")) // sign-out of the active account is here too
            rule.onNodeWithTag("account-row-" + pkB.take(8)).performClick()
            rule.waitUntil(30_000) { active() == pkB }
        }
    }

    /** B is kept, but its key is gone for good (as Ruling 6 leaves it): it can never run again until signed in. */
    private fun bKeptWithoutKey(switchBackToA: Boolean) = runBlocking {
        entry.session().loginLocal(LocalSigner(keyB.secretKey().toHex()))
        if (switchBackToA) assertEquals(com.monostr.app.session.SwitchResult.Done, entry.session().switchTo(pkA))
        com.monostr.app.data.KeystoreSecretStore(ctx).remove(com.monostr.app.data.SecretNames.nsec(pkB))
    }

    private fun settingsFileOf(pk: String) = java.io.File(ctx.filesDir, "datastore/monostr-${pk.take(16)}.preferences_pb")

    private fun removeFromSwitcher(pk: String) {
        rule.onNodeWithTag("account-menu-" + pk.take(8)).performClick()
        rule.waitUntil(10_000) { has("account-remove-" + pk.take(8)) }
        rule.onNodeWithTag("account-remove-" + pk.take(8)).performClick()
        rule.waitUntil(10_000) { has("account-remove-confirm") }
        rule.onNodeWithTag("account-remove-confirm").performClick()
    }

    /**
     * Final review I2 (a) and M8: an inactive account whose key is gone goes from the switcher's row menu, after a confirmation;
     * then the running account's own key entered on the add screen closes it with «Already using …».
     */
    @Test
    fun removeAStaleAccountFromTheSwitcherAndAddTheRunningOne() {
        bKeptWithoutKey(switchBackToA = true)
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForFeed()
            openSwitcher()
            rule.waitUntil(10_000) { rule.onAllNodes(hasText(ctx.getString(R.string.accounts_sign_in_again))).fetchSemanticsNodes().isNotEmpty() }
            assertFalse("the shown account has no remove action here", has("account-menu-" + pkA.take(8)))
            removeFromSwitcher(pkB)
            rule.waitUntil(15_000) { runBlocking { entry.session().accounts.first().map { it.pubkey } } == listOf(pkA) }
            assertEquals(pkA, active())
            rule.waitUntil(10_000) { !settingsFileOf(pkB).exists() }
            rule.waitUntil(10_000) { !has("account-row-" + pkB.take(8)) }

            // the running account's own key: no error, the add screen closes and says which account is in use
            rule.onNodeWithTag("account-add").performClick()
            rule.waitUntil(10_000) { has("add-account-key") }
            rule.onNodeWithTag("add-account-key").performClick()
            rule.waitUntil(10_000) { has("login-secret") }
            rule.onNodeWithTag("login-secret").performTextInput(keyA.secretKey().toBech32())
            rule.onNodeWithTag("login-secret-button").performClick()
            rule.waitUntil(15_000) { !has("login-secret") && has("fab-compose") }
            rule.waitUntil(10_000) { has("account-notice") }
            assertEquals(pkA, active())
        }
    }

    /**
     * Final review I2 (b): the active account cannot start (its key is gone), another one is kept. The login screen offers the
     * switcher: a switch to the working account, then the stale one goes from the switcher.
     */
    @Test
    fun aFailedRestoreOffersTheKeptAccounts() {
        bKeptWithoutKey(switchBackToA = false)
        runBlocking { entry.session().release() }
        ActivityScenario.launch(MainActivity::class.java).use {
            rule.waitUntil(30_000) { has("login-accounts") }
            rule.onNodeWithTag("login-accounts").performClick()
            rule.waitUntil(10_000) { has("account-switcher") }
            rule.onNodeWithTag("account-row-" + pkA.take(8)).performClick()
            rule.waitUntil(30_000) { active() == pkA }
            waitForFeed()
            openSwitcher()
            removeFromSwitcher(pkB)
            rule.waitUntil(15_000) { runBlocking { entry.session().accounts.first().map { it.pubkey } } == listOf(pkA) }
            assertEquals(pkA, active())
        }
    }

    /** The bottom edge of [activity]'s screen above the navigation bar, in screen pixels. */
    private fun usableBottom(activity: android.app.Activity): Float {
        val decor = activity.window.decorView
        val nav = androidx.core.view.ViewCompat.getRootWindowInsets(decor)!!
            .getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom
        val at = IntArray(2).also(decor::getLocationOnScreen)
        return (at[1] + decor.height - nav).toFloat()
    }

    private fun bottomOf(tag: String): Float = rule.onNodeWithTag(tag).fetchSemanticsNode().let { it.positionOnScreen.y + it.size.height }

    /**
     * Pixel finding 2026-11-25: the switcher opened right after a switch (a fresh activity, its rows still loading) sat too low,
     * its last row behind the navigation bar and «Add account» off screen. Three accounts, opened as soon as the feed shows.
     */
    @Test
    fun switcherRightAfterASwitchShowsEveryRowAboveTheNavigationBar() {
        val keyC = Keys.generate()
        val pkC = keyC.publicKey().toHex()
        runBlocking {
            entry.session().loginLocal(LocalSigner(keyB.secretKey().toHex()))
            entry.session().loginLocal(LocalSigner(keyC.secretKey().toHex()))
            assertEquals(com.monostr.app.session.SwitchResult.Done, entry.session().switchTo(pkA))
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForFeed()
            openSwitcher()
            rule.onNodeWithTag("account-row-" + pkC.take(8)).performClick()
            rule.waitUntil(30_000) { active() == pkC }
            // the fresh activity: open as soon as the feed shows, as on the Pixel
            waitForFeed()
            openSwitcher()
            rule.waitUntil(15_000) { listOf(pkA, pkB, pkC).all { has("account-row-" + it.take(8)) } }
            rule.waitForIdle()
            Thread.sleep(1_500) // the opening animation and any late row change settle
            rule.waitForIdle()
            val usable = usableBottom(resumed())
            val last = listOf(pkA, pkB, pkC).maxOf { bottomOf("account-row-" + it.take(8)) }
            assertTrue("last row ends at $last, usable bottom $usable", last <= usable + 1f)
            assertTrue("«Add account» ends at ${bottomOf("account-add")}, usable bottom $usable", bottomOf("account-add") <= usable + 1f)
            rule.onNodeWithTag("account-add").assertIsDisplayed()
        }
    }

    @Test
    fun addSwitchAndRemove() {
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForFeed()
            val accentA = runBlocking { entry.uiSettings().accent.first() }

            // add B by key from the switcher
            openSwitcher()
            rule.onNodeWithTag("account-add").performClick()
            rule.waitUntil(10_000) { has("add-account-key") }
            rule.onNodeWithTag("add-account-key").performClick()
            rule.waitUntil(10_000) { has("login-secret") }
            rule.onNodeWithTag("login-secret").performTextInput(keyB.secretKey().toBech32())
            rule.onNodeWithTag("login-secret-button").performClick()
            rule.waitUntil(30_000) { active() == pkB }
            waitForFeed()
            val accentB = runBlocking { entry.uiSettings().accent.first() }
            assertNotEquals("a new account gets another accent", accentA, accentB)
            // the notice after the recreate names the account (no name yet: its short npub)
            rule.waitUntil(10_000) { rule.onAllNodes(hasText(ctx.getString(R.string.accounts_now_using, ""), substring = true)).fetchSemanticsNodes().isNotEmpty() }

            // back to A by its row: a fresh activity, the old one (with its view models and nav entries' stores) is gone
            openSwitcher()
            assertEquals(true, has("account-row-" + pkB.take(8)))
            val before = resumed()
            rule.onNodeWithTag("account-row-" + pkA.take(8)).performClick()
            rule.waitUntil(30_000) { active() == pkA }
            waitForFeed()
            rule.waitUntil(10_000) { before.isDestroyed }
            assertFalse("finished, not a config change: its ViewModelStore is cleared", before.isChangingConfigurations)
            assertNotSame(before, resumed())
            assertEquals(accentA, runBlocking { entry.uiSettings().accent.first() })

            // a notification target of B tapped while A runs: switch to B, then its chat opens in the new activity
            val peer = Keys.generate().publicKey().toHex()
            MainActivity.pendingOpen.value = com.monostr.app.work.PendingOpen(pkB, peer, messages = false)
            rule.waitUntil(30_000) { active() == pkB }
            rule.waitUntil(30_000) { has("chat-input") }
            androidx.test.espresso.Espresso.pressBack()
            waitForFeed()

            // remove the active account (B): A takes over
            rule.openSettings(ctx)
            rule.onNodeWithTag("settings-account").performClick()
            rule.waitUntil(10_000) { has("account-remove") }
            rule.onNodeWithTag("account-remove").performScrollTo().performClick()
            rule.waitUntil(10_000) { has("settings-logout-confirm") }
            rule.onNodeWithTag("settings-logout-confirm").performClick()
            rule.waitUntil(30_000) { active() == pkA }
            // the registry entry goes last, once A runs
            rule.waitUntil(15_000) { runBlocking { entry.session().accounts.first().map { it.pubkey } } == listOf(pkA) }
            waitForFeed()

            // remove the last one: the login screen
            rule.openSettings(ctx)
            rule.onNodeWithTag("settings-account").performClick()
            rule.waitUntil(10_000) { has("account-remove") }
            rule.onNodeWithTag("account-remove").performScrollTo().performClick()
            rule.waitUntil(10_000) { has("settings-logout-confirm") }
            rule.onNodeWithTag("settings-logout-confirm").performClick()
            rule.waitUntil(30_000) { has("login-secret") }
            rule.waitUntil(15_000) { runBlocking { entry.session().accounts.first() }.isEmpty() }
            // carried item: the login screen still works logged out (Tor off here): its key field takes input
            rule.onNodeWithTag("login-secret").performTextInput("x")
        }
    }
}

/** Spec «Tor wait»: the notice after a switch follows the fresh Tor route (a fake route here, no Tor needed). */
@RunWith(AndroidJUnit4::class)
class SwitchNoticeDeviceTest {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun countsDownTheTorWindowThenStalls() {
        val route = MutableStateFlow<FreshRoute>(FreshRoute.Waiting(3))
        rule.setContent { SwitchNotice(name = "Bob", freshRoute = route, onDone = {}) }
        rule.waitUntil(5_000) { rule.onAllNodes(hasText(ctx.getString(R.string.accounts_now_using_tor, "Bob", 3))).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("switch-notice").assertExists()
        route.value = FreshRoute.Stalled
        rule.waitUntil(5_000) { rule.onAllNodes(hasText(ctx.getString(R.string.accounts_tor_stalled))).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Final review M5/M8: a failed switch and «Already using …» are told over the app for a while, then go. */
    @Test
    fun accountNoticesShowThenGo() {
        rule.setContent { com.monostr.app.ui.accounts.AccountNoticeHost(nameOf = { "Bob" }) }
        com.monostr.app.ui.accounts.AccountNotices.post(com.monostr.app.ui.accounts.AccountNotice.SwitchFailed)
        rule.waitUntil(5_000) { rule.onAllNodes(hasText(ctx.getString(R.string.accounts_switch_failed))).fetchSemanticsNodes().isNotEmpty() }
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag("account-notice")).fetchSemanticsNodes().isEmpty() }
        com.monostr.app.ui.accounts.AccountNotices.post(com.monostr.app.ui.accounts.AccountNotice.AlreadyUsing("b".repeat(64)))
        rule.waitUntil(5_000) { rule.onAllNodes(hasText(ctx.getString(R.string.accounts_already_using, "Bob"))).fetchSemanticsNodes().isNotEmpty() }
        rule.waitUntil(10_000) { com.monostr.app.ui.accounts.AccountNotices.current.value == null }
    }

    @Test
    fun readyFromTheStartShowsTheNameAndEnds() {
        var done = false
        rule.setContent { SwitchNotice(name = "Bob", freshRoute = MutableStateFlow(FreshRoute.Ready), onDone = { done = true }) }
        rule.waitUntil(5_000) { rule.onAllNodes(hasText(ctx.getString(R.string.accounts_now_using, "Bob"))).fetchSemanticsNodes().isNotEmpty() }
        rule.waitUntil(5_000) { done }
    }
}
