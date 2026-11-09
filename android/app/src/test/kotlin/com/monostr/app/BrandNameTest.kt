package com.monostr.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Spec 2026-11-07: where people read the name it is «Monostr»; technical names (hosts, URLs, e-mails, package, paths, file names,
 * volume names, code samples) stay lowercase. [BrandName.offences] finds every lowercase `monostr` that is not one of those.
 */
object BrandName {
    /** Each entry is a technical spelling that may stay lowercase. */
    private val allowed = listOf(
        // host, URL and e-mail domain, such as relay.monostr.com or alex@monostr.com
        """[A-Za-z0-9._/-]*monostr\.com""",
        // repository links, such as github.com/schmidt1024/monostr
        """github\.com/[^\s)"'<>]*monostr""",
        // package and applicationId, such as com.monostr.app
        """com\.monostr(?:\.\w+)*""",
        // e-mail local part, such as monostr@monostr.com or monostr@proton.me
        """monostr@[A-Za-z0-9-]+\.[A-Za-z]{2,}""",
        // folder in the user's gallery
        """Pictures/monostr""",
        // file and release names, such as monostr-0.34.0.apk, monostr-icon.png, monostr-tips.md (explicit extensions only)
        """monostr-(?:\d+(?:\.\d+)*|[A-Za-z0-9-]+)\.(?:apk|png|svg|jpg|jpeg|webp|ico|txt|md|zip|json|sig|asc)\b""",
        // the docker compose project and its volume, the only two names that exist
        """monostr-watcher_watcher-data""",
        """monostr-watcher\b""",
        // path inside the repository
        """monostr/watcher""",
        // Android resource key, a code identifier, such as <string name="settings_translate_monostr">
        """(?<=<string name=")[a-z0-9_]*monostr[a-z0-9_]*(?=")""",
    ).joinToString("|").toRegex()

    /** HTML code samples keep the technical spelling. Newlines stay so that line numbers still match. */
    private val code = Regex("""<(code|pre)\b[^>]*>.*?</\1>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    private fun blank(s: String) = s.map { if (it == '\n') '\n' else ' ' }.joinToString("")

    /** One entry per offence, as `line:text of the line`. */
    fun offences(text: String): List<String> {
        val cleaned = text.replace(code) { blank(it.value) }.replace(allowed) { blank(it.value) }
        val original = text.lines()
        return cleaned.lines().withIndex().filter { (_, l) -> l.contains("monostr") }.map { (i, _) -> "${i + 1}:${original[i].trim().take(160)}" }
    }
}

class BrandNameTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }.parentFile

    private fun scanned(): List<File> {
        fun glob(dir: String, name: (File) -> Boolean) = File(root, dir).walkTopDown().filter { it.isFile && name(it) }.toList()
        return glob("android/app/src/main/res") { it.name == "strings.xml" && it.parentFile.name.startsWith("values") } +
            glob("fastlane/metadata/android") { it.name == "title.txt" || it.name == "full_description.txt" } +
            glob("site") { it.extension == "html" } +
            // README*.md by glob: scripts/export-public.sh drops README.de.md from the public repo
            root.listFiles { f -> f.isFile && f.name.startsWith("README") && f.extension == "md" }.orEmpty().toList() +
            listOf("watcher/internal/api/home.html", "zapstore.yaml").map { File(root, it) }
    }

    @Test fun `hosts and paths pass`() {
        assertEquals(emptyList<String>(), BrandName.offences("Uses relay.monostr.com and monostr@monostr.com, Pictures/monostr, monostr-0.34.0.apk"))
        assertEquals(emptyList<String>(), BrandName.offences("github.com/schmidt1024/monostr, com.monostr.app, monostr/watcher, monostr-watcher_watcher-data, monostr-watcher"))
        assertEquals(emptyList<String>(), BrandName.offences("<p>Run <code>docker run monostr</code> now</p>\n<pre>\nmonostr up\n</pre>"))
        assertEquals(emptyList<String>(), BrandName.offences("""<string name="settings_translate_monostr">Monostr (translate.monostr.com)</string>"""))
    }

    @Test fun `the name lowercase is an offence`() {
        assertEquals(1, BrandName.offences("Support monostr").size)
        assertEquals(1, BrandName.offences("monostr'un Tor").size)
        assertEquals(1, BrandName.offences("ok relay.monostr.com\nbut monostr here").size)
        assertEquals(1, BrandName.offences("""<string name="create_who_monostr">Makes monostr</string>""").size)
        assertEquals(1, BrandName.offences("""<string name="monostr">x</string> name="monostr" """).size)
        listOf("monostr-Nutzer", "monostr_Nutzer", "monostr-Nutzer.Danke", "monostr-2 Nutzer", "monostr-watchers", "monostr@home").forEach {
            assertEquals(1, BrandName.offences(it).size, it)
        }
    }

    @Test fun `capital passes, with suffixes and compounds`() {
        assertEquals(emptyList<String>(), BrandName.offences("Monostr'un Tor, Monostr-Nutzer, via Monostr, relay.monostr.com, monostr-icon.png"))
    }

    @Test fun `displayed files spell Monostr`() {
        val files = scanned()
        assertTrue(files.size > 15, "found only ${files.size} files under $root")
        files.forEach { assertTrue(it.isFile, "missing $it") }
        val found = files.flatMap { f -> BrandName.offences(f.readText()).map { "${f.relativeTo(root).path}:$it" } }
        assertTrue(found.isEmpty(), found.joinToString("\n", prefix = "\n"))
    }
}
