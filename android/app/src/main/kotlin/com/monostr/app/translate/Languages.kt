package com.monostr.app.translate

import java.util.Locale

/** The eight app languages (spec 3.1) and the reader's target language for translations. */
object Languages {
    /** ISO 639-1 codes of the app languages, the only ones detected and translated. */
    val SUPPORTED: List<String> = listOf("en", "de", "es", "fr", "it", "pt", "ru", "tr")

    /**
     * The app's displayed language: the first system locale whose language is one of [SUPPORTED],
     * else English, which is what the UI falls back to as well.
     */
    fun target(locales: List<Locale>): String =
        locales.firstNotNullOfOrNull { locale -> locale.language.lowercase().takeIf { it in SUPPORTED } } ?: "en"
}
