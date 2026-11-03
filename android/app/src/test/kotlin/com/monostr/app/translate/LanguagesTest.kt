package com.monostr.app.translate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Locale

class LanguagesTest {
    @Test fun `swiss german system locale reads German`() = assertEquals("de", Languages.target(listOf(Locale.forLanguageTag("de-CH"))))

    @Test fun `first supported locale wins`() =
        assertEquals("fr", Languages.target(listOf(Locale.forLanguageTag("ja-JP"), Locale.forLanguageTag("fr-FR"))))

    @Test fun `no supported locale falls back to English`() = assertEquals("en", Languages.target(listOf(Locale.forLanguageTag("ja-JP"))))

    @Test fun `no locale at all falls back to English`() = assertEquals("en", Languages.target(emptyList()))

    @Test fun `the eight app languages`() = assertEquals(setOf("en", "de", "es", "fr", "it", "pt", "ru", "tr"), Languages.SUPPORTED.toSet())
}
