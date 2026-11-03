package com.monostr.app

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.translate.LanguageDetector
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pixel numbers for the language detector (spec 2026-11-03 performance section): init time, heap
 * after init, detect time over 200 note-like texts. Skipped unless run with
 * `-Pandroid.testInstrumentationRunnerArguments.bench=true`; results under logcat tag `LangBench`.
 */
@RunWith(AndroidJUnit4::class)
class LanguageDetectorBench {
    @Test fun detectorNumbers() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("bench") == "true")
        val notes = notes()
        val rt = Runtime.getRuntime()
        fun used(): Long { repeat(3) { System.gc(); System.runFinalization(); Thread.sleep(100) }; return rt.totalMemory() - rt.freeMemory() }

        val before = used()
        val detector = LanguageDetector()
        val t0 = SystemClock.elapsedRealtimeNanos()
        detector.detect(notes[0], null) // loads the models
        val initMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
        val heapMb = (used() - before) / 1048576.0

        repeat(2) { notes.forEach { detector.detect(it, null) } } // warm-up (JIT)
        val us = notes.map { n ->
            val s = SystemClock.elapsedRealtimeNanos()
            detector.detect(n, null)
            (SystemClock.elapsedRealtimeNanos() - s) / 1e3
        }.sorted()
        val detected = notes.count { detector.detect(it, null) != null }
        val line = "init=%.0f ms heap=%.1f MB median=%.0f us p95=%.0f us max=%.0f us detected=%d/%d"
            .format(initMs, heapMb, us[us.size / 2], us[(us.size * 95) / 100], us.last(), detected, notes.size)
        Log.i("LangBench", line)
        println("LangBench $line")
    }

    private fun notes(): List<String> {
        val sentences = listOf(
            "Just set up my own Monero node at home, syncing takes a while but it is worth it",
            "gm nostr, coffee is ready and the sun is out",
            "Heute habe ich endlich meinen eigenen Relay aufgesetzt, das war einfacher als gedacht",
            "Wer kommt am Samstag zum Treffen in Zürich? Wir sind schon zehn Leute",
            "Hoy he configurado mi propio nodo de Monero y la sincronización ha tardado toda la noche",
            "¿Alguien sabe un buen cliente de nostr para Android con propinas en Monero?",
            "Ce matin j'ai enfin installé mon propre relais, c'était plus simple que prévu",
            "Qui vient à la rencontre de samedi à Lyon ? On est déjà une dizaine",
            "Stamattina ho finalmente configurato il mio relay, è stato più facile del previsto",
            "Chi viene all'incontro di sabato a Milano? Siamo già in dieci",
            "Hoje finalmente configurei o meu próprio relay, foi mais fácil do que pensava",
            "Alguém sabe um bom cliente nostr para Android com gorjetas em Monero?",
            "Сегодня наконец настроил свой собственный релей, это оказалось проще, чем я думал",
            "Кто придёт на встречу в субботу в Москве? Нас уже десять человек",
            "Bugün sonunda kendi relay sunucumu kurdum, düşündüğümden çok daha kolaydı",
            "Cumartesi İstanbul'daki buluşmaya kim geliyor? Şimdiden on kişiyiz",
        )
        val extras = listOf("", " https://monostr.com/a", " #monero #nostr", " 🚀🔥", " nostr:npub1sg6plzptd64u62a878hep2kev88swjh3tw00gjsfl8f237lmu63q0uf63m", " lol")
        return (0 until 200).map { i ->
            val a = sentences[i % sentences.size]
            val b = sentences[(i * 7 + 3) % sentences.size].takeIf { i % 3 == 0 && it.length % 2 == a.length % 2 }
            listOfNotNull(a, b).joinToString(". ") + extras[i % extras.size] + " ($i)"
        }
    }
}
