package com.monostr.app.translate

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LanguageDetectorTest {
    private val detector = LanguageDetector()

    @Test fun french() = assertEquals(
        "fr",
        detector.detect("Je suis allé au marché ce matin et j'ai acheté du pain frais pour toute la famille.", null),
    )

    @Test fun german() = assertEquals(
        "de",
        detector.detect("Heute Morgen bin ich mit dem Fahrrad zur Arbeit gefahren, weil die Sonne so schön schien.", null),
    )

    @Test fun spanish() = assertEquals(
        "es",
        detector.detect("Esta mañana fui al mercado y compré pan fresco para toda la familia, como cada domingo.", null),
    )

    @Test fun russian() = assertEquals(
        "ru",
        detector.detect("Сегодня утром я ходил на рынок и купил свежий хлеб для всей нашей семьи.", null),
    )

    @Test fun turkish() = assertEquals(
        "tr",
        detector.detect("Bu sabah pazara gittim ve bütün aile için taze ekmek aldım, her pazar yaptığım gibi.", null),
    )

    @Test fun italian() = assertEquals(
        "it",
        detector.detect("Stamattina ho finalmente configurato il mio relay, è stato più facile del previsto.", null),
    )

    @Test fun portuguese() = assertEquals(
        "pt",
        detector.detect("Hoje finalmente configurei o meu próprio relay, foi mais fácil do que eu pensava.", null),
    )

    @Test fun english() = assertEquals(
        "en",
        detector.detect("Just set up my own Monero node at home, syncing takes a while but it is worth it.", null),
    )

    @Test fun `links mentions and hashtags only are too short`() =
        assertNull(detector.detect("https://x.com/a nostr:npub1sg6plzptd64u62a878hep2kev88swjh3tw00gjsfl8f237lmu63q0uf63m #monero", null))

    @Test fun `a short reply with an emoji is too short`() = assertNull(detector.detect("ok 👍", null))

    @Test fun `tokens do not count as letters`() = assertNull(
        detector.detect("gm https://example.com/a/very/long/path/with/many/letters #goodmorningnostrfriends", null),
    )

    @Test fun `a supported label wins over the text`() = assertEquals(
        "it",
        detector.detect("Je suis allé au marché ce matin et j'ai acheté du pain frais pour toute la famille.", "it"),
    )

    @Test fun `an unsupported label falls back to detection`() = assertEquals(
        "fr",
        detector.detect("Je suis allé au marché ce matin et j'ai acheté du pain frais pour toute la famille.", "ja"),
    )

    @Test fun `a label is matched case-insensitively`() = assertEquals("de", detector.detect("ok", "DE"))

    @Test fun `the detected text ignores the link around it`() = assertEquals(
        "de",
        detector.detect("Schaut euch das an, das ist wirklich ein grossartiger Artikel über Monero https://example.com/en/article #monero", null),
    )

    // I3: a model that fails to load is not tried again per note

    @Test fun `a failing model load gives null once and is never retried`() {
        var loads = 0
        val broken = LanguageDetector { loads++; throw java.io.IOException("profiles") }
        val text = "Je suis allé au marché ce matin et j'ai acheté du pain frais pour toute la famille."
        assertNull(broken.detect(text, null))
        assertNull(broken.detect(text, null))
        assertNull(broken.detect(text, null))
        assertEquals(1, loads)
        // a supported label still answers without the model
        assertEquals("fr", broken.detect(text, "fr"))
    }

    // I2: detection runs on one dedicated thread, not on Dispatchers.Default

    @Test fun `the lane is one thread of its own`(): Unit = runBlocking {
        val names = (1..8).map { async(LanguageDetector.LANE) { Thread.currentThread().name.substringBefore(" @") } }.map { it.await() }
        assertEquals(setOf("monostr-langdetect"), names.toSet())
    }
}
