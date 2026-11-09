package com.monostr.app.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * `getDir("TorService")` is `app_TorService` in the `root` domain, and there are Tor's guards (`state`), its directory cache and the torrc:
 * copied to another phone they would link the two installs at the guard level. Read from the source tree, as the rules are not on the unit test classpath.
 */
class TorDataBackupRulesTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }

    private fun rules(name: String): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(root, "app/src/main/res/xml/$name")).documentElement

    private fun Element.children(): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>()

    private fun Element.excludesTor() =
        children().any { it.tagName == "exclude" && it.getAttribute("domain") == "root" && it.getAttribute("path") == "app_TorService/" }

    @Test
    fun `the full backup rules exclude Tor's directory`() {
        assertTrue(rules("backup_rules.xml").excludesTor())
    }

    @Test
    fun `every section of the data extraction rules excludes Tor's directory`() {
        val sections = rules("data_extraction_rules.xml").children()
        assertTrue(sections.map { it.tagName }.containsAll(listOf("cloud-backup", "device-transfer")), sections.map { it.tagName }.toString())
        assertEquals(emptyList<String>(), sections.filterNot { it.excludesTor() }.map { it.tagName })
    }
}
