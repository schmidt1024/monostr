package com.monostr.nostr.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LanguageLabelTest {
    @Test
    fun `namespace and label give the code`() =
        assertEquals("fr", LanguageLabel.of(listOf(listOf("L", "ISO-639-1"), listOf("l", "fr", "ISO-639-1"))))

    @Test
    fun `code is lowercased`() = assertEquals("fr", LanguageLabel.of(listOf(listOf("l", "FR", "ISO-639-1"))))

    @Test
    fun `other namespace is ignored`() = assertNull(LanguageLabel.of(listOf(listOf("l", "fr", "other-ns"))))

    @Test
    fun `three letter code is ignored`() = assertNull(LanguageLabel.of(listOf(listOf("l", "fra", "ISO-639-1"))))

    @Test
    fun `no tags give null`() = assertNull(LanguageLabel.of(emptyList()))

    @Test
    fun `first label wins`() =
        assertEquals("de", LanguageLabel.of(listOf(listOf("l", "de", "ISO-639-1"), listOf("l", "fr", "ISO-639-1"))))

    @Test
    fun `article reads the label`() {
        val e = AddressableEvent("e".repeat(64), 30023, "a".repeat(64), "p", 1000, listOf(listOf("d", "p"), listOf("l", "es", "ISO-639-1")), "x")
        assertEquals("es", Article.parse(e)!!.language)
    }
}
