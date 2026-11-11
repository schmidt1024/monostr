package com.monostr.app.ui

import com.monostr.app.ui.settings.OVERVIEW_PAGES
import com.monostr.app.ui.settings.SETTINGS_INDEX
import com.monostr.app.ui.settings.SettingsPage
import com.monostr.app.ui.settings.SettingsSection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SettingsPagesTest {
    @Test fun sectionIdsMapToPages() {
        assertEquals(SettingsPage.PRIVACY, SettingsPage.of(SettingsSection.PRIVACY))
        assertEquals(SettingsPage.TRANSLATION, SettingsPage.of(SettingsSection.TRANSLATION))
        assertNull(SettingsPage.of(null)); assertNull(SettingsPage.of("gone"))
    }

    @Test fun everyIndexKeyIsUniqueAndEveryPageHasAnEntry() {
        assertEquals(SETTINGS_INDEX.size, SETTINGS_INDEX.map { it.key }.toSet().size)
        assertEquals(SettingsPage.entries.toSet(), SETTINGS_INDEX.map { it.page }.toSet())
    }

    @Test fun overviewOrderIsPrivacyFirst() = assertEquals(
        listOf("privacy", "relays", "monero", "notifications", "content", "translation", "appearance", "help"), OVERVIEW_PAGES.map { it.id })
}
