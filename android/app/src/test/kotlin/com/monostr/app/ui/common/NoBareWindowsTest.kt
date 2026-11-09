package com.monostr.app.ui.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Spec 2026-11-06 section 6: every web link asks first. A sheet, dialog, popup or menu has a window of its own whose ComposeView
 * provides the platform LocalUriHandler again, and a link in it would open the browser without the prompt. Only the Guarded*
 * wrappers in GuardedWindows.kt open such a window; a new kind of window gets a wrapper there that runs its content through KeepLinkGuard.
 */
class NoBareWindowsTest {
    private val wrappers = "app/src/main/kotlin/com/monostr/app/ui/common/GuardedWindows.kt"

    private val window = Regex(
        """(?<![\w])(ModalBottomSheet|AlertDialog|BasicAlertDialog|Dialog|Popup|DropdownMenu|ExposedDropdownMenu|DropdownMenuPopup|""" +
            """DatePickerDialog|TimePickerDialog|TooltipBox|ExpandedFullScreenSearchBar|ExpandedDockedSearchBar|ComposeView)\(""",
    )

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }

    private fun isComment(line: String) = line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

    private fun violations(path: String, lines: List<String>) =
        if (path == wrappers) emptyList()
        else lines.withIndex().filter { (_, l) -> !isComment(l) && window.containsMatchIn(l) }.map { (i, l) -> "$path:${i + 1}: ${l.trim()}" }

    @Test
    fun `every window keeps the link prompt`() {
        val all = File(root, "app/src/main").walkTopDown().filter { it.extension == "kt" || it.extension == "java" }.toList()
        assertTrue(all.size > 100, "found only ${all.size} sources under $root")
        assertTrue(File(root, wrappers).isFile, wrappers)
        assertEquals(emptyList<String>(), all.flatMap { violations(it.relativeTo(root).path, it.readLines()) })
    }

    @Test
    fun `the rule catches a bare window and leaves the rest`() {
        listOf(
            "ModalBottomSheet(onDismissRequest = onDismiss) {",
            "    AlertDialog(",
            "DropdownMenu(expanded = open, onDismissRequest = { open = false }) {",
            "Dialog(onDismissRequest = {}) { Text(\"x\") }",
            "Popup(alignment = Alignment.TopCenter) {",
            "androidx.compose.material3.ModalBottomSheet(onDismissRequest = {}) {}",
            "BasicAlertDialog(onDismissRequest = {}) {}",
            "val v = ComposeView(context)",
        ).forEach { assertTrue(violations("Some.kt", listOf(it)).isNotEmpty(), it) }
        listOf(
            "GuardedModalBottomSheet(onDismissRequest = onDismiss) {",
            "GuardedAlertDialog(",
            "GuardedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {",
            "DropdownMenuItem(text = { Text(\"x\") }, onClick = {})",
            "properties = DialogProperties(dismissOnClickOutside = false),",
            "import androidx.compose.material3.ModalBottomSheet",
            "// a ModalBottomSheet( in a comment",
            "fun TippersDialog(tippers: List<Tipper>) {",
        ).forEach { assertTrue(violations("Some.kt", listOf(it)).isEmpty(), it) }
        assertTrue(violations(wrappers, listOf("ModalBottomSheet(onDismissRequest = onDismissRequest) {")).isEmpty())
    }
}
