package com.monostr.app.ui

import com.monostr.app.ui.settings.Contributors
import com.monostr.app.ui.settings.SupportLinks
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SupportLinksTest {
    @Test
    fun `the monero uri carries the address without an amount`() {
        assertEquals("monero:8ADYabc", SupportLinks.moneroUri("8ADYabc"))
        assertEquals("", SupportLinks.moneroUri(" "))
    }

    @Test
    fun `issues hang off the repository, an empty repository has none`() {
        assertEquals("https://github.com/schmidt1024/monostr/issues", SupportLinks.issuesUrl("https://github.com/schmidt1024/monostr"))
        assertEquals("https://github.com/schmidt1024/monostr/issues", SupportLinks.issuesUrl("https://github.com/schmidt1024/monostr/"))
        assertEquals("", SupportLinks.issuesUrl(""))
    }

    @Test
    fun `contributors are one trimmed name per line without blanks or repeats`() {
        assertEquals(listOf("Schmidt", "Alice"), Contributors.parse("Schmidt\n\n  Alice \nSchmidt\n"))
        assertEquals(emptyList<String>(), Contributors.parse("\n \n"))
    }
}
