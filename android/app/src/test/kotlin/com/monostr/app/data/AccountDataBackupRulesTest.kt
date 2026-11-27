package com.monostr.app.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Final review I1: every account's settings (`datastore/monostr-<pk16>.preferences_pb`, also the global registry file) and its engine
 * database (`filesDir/nostr-<pk16>/`) never leave the device, neither by backup nor by a device transfer. The rules have no
 * wildcards, so the engine folders go with the whole `file` domain. Read from the source tree, as the rules are not on the unit test
 * classpath.
 */
class AccountDataBackupRulesTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }

    private fun rules(name: String): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(root, "app/src/main/res/xml/$name")).documentElement

    private fun Element.children(): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>()

    private fun Element.excludes(path: String) =
        children().any { it.tagName == "exclude" && it.getAttribute("domain") == "file" && it.getAttribute("path") == path }

    /** The settings folder and the whole `file` domain (engine databases `nostr-<pk16>/`). */
    private fun Element.missing(): List<String> = listOf("datastore/", ".").filterNot { excludes(it) }

    @Test
    fun `the full backup rules exclude every settings file and engine database`() {
        assertEquals(emptyList<String>(), rules("backup_rules.xml").missing())
    }

    @Test
    fun `every section of the data extraction rules excludes every settings file and engine database`() {
        val sections = rules("data_extraction_rules.xml").children()
        assertTrue(sections.map { it.tagName }.containsAll(listOf("cloud-backup", "device-transfer")), sections.map { it.tagName }.toString())
        sections.forEach { assertEquals(emptyList<String>(), it.missing(), it.tagName) }
    }
}
