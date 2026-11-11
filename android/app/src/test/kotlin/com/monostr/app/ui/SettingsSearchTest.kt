package com.monostr.app.ui

import com.monostr.app.ui.settings.ResolvedEntry
import com.monostr.app.ui.settings.SETTINGS_INDEX
import com.monostr.app.ui.settings.SettingEntry
import com.monostr.app.ui.settings.SettingsAvailability
import com.monostr.app.ui.settings.SettingsPage
import com.monostr.app.ui.settings.availableEntries
import com.monostr.app.ui.settings.normalizeForSearch
import com.monostr.app.ui.settings.searchSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SettingsSearchTest {
    private fun r(key: String, title: String, hint: String = "", page: String = "P", syn: List<String> = emptyList()) =
        ResolvedEntry(SettingEntry(key, SettingsPage.PRIVACY, 0, null, syn), title, hint, page)

    @Test fun accentsAndCaseAreIgnored() {
        val e = listOf(r("settings-tor", "Über Tor verbinden"), r("translate", "Übersetzung"))
        assertEquals(listOf("settings-tor"), searchSettings("uber tor", e).map { it.entry.key })
        assertEquals(listOf("translate"), searchSettings("UBERSETZUNG", e).map { it.entry.key })
        assertEquals(listOf("translate"), searchSettings("übersetz", e).map { it.entry.key })
    }

    @Test fun blankQueryFindsNothing() = assertTrue(searchSettings("  ", listOf(r("a", "Tor"))).isEmpty())

    @Test fun synonymsAndHintsMatchAfterTitles() {
        val e = listOf(r("hint", "Connect", hint = "hides your IP"), r("syn", "Something", syn = listOf("ip")), r("title", "IP address"))
        assertEquals(listOf("title", "syn", "hint"), searchSettings("ip", e).map { it.entry.key })
    }

    @Test fun pathOnlyHitsDropWhenAnythingMatchesDirectly() {
        val e = listOf(r("links", "Ask before opening links", page = "Tor & privacy"), r("tor", "Connect through Tor", page = "Tor & privacy"))
        assertEquals(listOf("tor"), searchSettings("tor", e).map { it.entry.key })
        assertEquals(listOf("links", "tor"), searchSettings("privacy", e).map { it.entry.key })
    }

    @Test fun pageTitleMatches() = assertEquals(listOf("x"), searchSettings("relays", listOf(r("x", "Proof of work", page = "Relays"))).map { it.entry.key })

    @Test fun turkishDotlessIMatchesI() {
        assertEquals("ayar bulunamadi", normalizeForSearch("Ayar bulunamadı"))
        assertEquals(listOf("x"), searchSettings("gizlilik", listOf(r("x", "Gızlılık"))).map { it.entry.key })
        assertEquals(listOf("x"), searchSettings("gızlı", listOf(r("x", "Gizlilik"))).map { it.entry.key })
    }

    private val all = SETTINGS_INDEX.map { ResolvedEntry(it, it.key, "", "") }
    private fun keys(a: SettingsAvailability) = availableEntries(all, a).map { it.entry.key }.toSet()
    private val conditional = setOf(
        "settings-show-key", "settings-amber-renew", "settings-monero-setup", "settings-monero-watcher", "settings-monero-amounts",
        "settings-monero-payto", "settings-monero-disable", "settings-monero-delete-view-key", "settings-translate-server",
    )

    @Test fun keyLoginWithoutSetupOffersShowKeyAndSetUpOnly() {
        val k = keys(SettingsAvailability())
        assertEquals(setOf("settings-show-key", "settings-monero-setup"), conditional.intersect(k))
        assertEquals(SETTINGS_INDEX.size - conditional.size + 2, k.size)
    }

    @Test fun amberWithSetupViewKeyAndCustomServer() {
        val k = keys(SettingsAvailability(isAmber = true, hasSetup = true, hasViewKey = true, translateCustom = true))
        assertEquals(
            setOf("settings-amber-renew", "settings-monero-watcher", "settings-monero-amounts", "settings-monero-disable", "settings-monero-delete-view-key", "settings-translate-server"),
            conditional.intersect(k),
        )
    }

    @Test fun paytoOnlyOffersPaytoNotSetUp() {
        val k = keys(SettingsAvailability(paytoOnly = true))
        assertEquals(setOf("settings-show-key", "settings-monero-payto"), conditional.intersect(k))
    }

    @Test fun unconditionalEntriesStayInEveryState() {
        val states = listOf(SettingsAvailability(), SettingsAvailability(isAmber = true, hasSetup = true, paytoOnly = true, hasViewKey = true, translateCustom = true))
        states.forEach { a -> assertTrue(keys(a).containsAll(SETTINGS_INDEX.map { it.key }.toSet() - conditional)) }
    }
}
