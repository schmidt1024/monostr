package com.monostr.app.ui.theme

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Spec 2026-11-10 §7: a tripwire against drift. Outside ui/theme, corners come from MaterialTheme.shapes
 * (or CircleShape / percent shapes) and spacing from [Spacing]; a numeric dp literal in either place fails.
 * Regex level on purpose: it is a tripwire, not a parser.
 */
class TokenGuardTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }

    private fun sources(): List<Pair<String, String>> =
        File(root, "app/src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && !it.invariantSeparatorsPath.contains("/ui/theme/") }
            .map { it.relativeTo(root).invariantSeparatorsPath to it.readText() }
            .toList()

    @Test
    fun `shape guard flags hand-built radii only`() {
        assertEquals(1, shapeViolations("a.kt", "val s = RoundedCornerShape(12.dp)").size)
        assertEquals(2, shapeViolations("a.kt", "RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)").size)
        assertTrue(shapeViolations("a.kt", "RoundedCornerShape(50)").isEmpty())
        assertTrue(shapeViolations("a.kt", "clip(CircleShape)").isEmpty())
        assertTrue(shapeViolations("a.kt", "clip(MaterialTheme.shapes.medium)").isEmpty())
    }

    @Test
    fun `spacing guard flags literals in spacing calls`() {
        assertEquals(1, spacingViolations("a.kt", "Modifier.padding(10.dp)").size)
        assertEquals(2, spacingViolations("a.kt", "Modifier.padding(horizontal = 6.dp, vertical = 2.dp)").size)
        assertEquals(1, spacingViolations("a.kt", "Modifier.padding(\n    top = Spacing.s,\n    start = 10.dp,\n)").size)
        assertEquals(1, spacingViolations("a.kt", "Spacer(Modifier.width(6.dp))").size)
        assertEquals(1, spacingViolations("a.kt", "Spacer(modifier = Modifier.height(6.dp))").size)
        assertEquals(1, spacingViolations("a.kt", "verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom)").size)
        assertEquals(1, spacingViolations("a.kt", "contentPadding = PaddingValues(horizontal = 18.dp)").size)
        assertEquals(1, spacingViolations("a.kt", "contentPadding = PaddingValues(8.dp)").size)
    }

    @Test
    fun `spacing guard ignores sizes, zero, tokens and size constants`() {
        assertTrue(spacingViolations("a.kt", "Modifier.padding(0.dp)").isEmpty())
        assertTrue(spacingViolations("a.kt", "Modifier.padding(Spacing.m)").isEmpty())
        assertTrue(spacingViolations("a.kt", "Modifier.size(10.dp)").isEmpty())
        assertTrue(spacingViolations("a.kt", "Box(Modifier.height(22.dp))").isEmpty())
        assertTrue(spacingViolations("a.kt", "drawLine(c, Offset(x, 10.dp.toPx()), Offset(x, 0f), w)").isEmpty())
        assertTrue(spacingViolations("a.kt", "Modifier.padding(start = Spacing.l + LIST_AVATAR + Spacing.m)").isEmpty())
    }

    @Test
    fun `spacer size and cut corners are guarded too`() {
        assertEquals(1, spacingViolations("a.kt", "Spacer(Modifier.size(8.dp))").size)
        assertEquals(1, shapeViolations("a.kt", "CutCornerShape(4.dp)").size)
    }

    @Test
    fun `comments and strings are not code`() {
        assertTrue(spacingViolations("a.kt", "// Modifier.padding(start = 10.dp)").isEmpty())
        assertTrue(spacingViolations("a.kt", "val s = \"padding(10.dp)\"").isEmpty())
        assertTrue(spacingViolations("a.kt", "/* padding(6.dp) */ val x = 1").isEmpty())
        // an unbalanced paren in a trailing comment must not stretch the scan over later code
        assertTrue(spacingViolations("a.kt", "Modifier.padding(Spacing.s) // (see below\nval y = Modifier.size(10.dp)").isEmpty())
        // a literal after a comment inside the call is still found
        assertEquals(listOf("a.kt:2: 10.dp"), spacingViolations("a.kt", "Modifier.padding( // why\n    start = 10.dp)"))
    }

    @Test
    fun `line numbers point at the literal`() {
        assertEquals(listOf("a.kt:3: 10.dp"), spacingViolations("a.kt", "val x = 1\nModifier.padding(\n    start = 10.dp,\n)"))
    }

    @Test
    fun `no hand-built corner radii`() {
        val hits = sources().flatMap { (p, s) -> shapeViolations(p, s) }
        assertTrue(hits.isEmpty(), "use MaterialTheme.shapes (spec 2026-11-10 §5):\n" + hits.joinToString("\n"))
    }

    @Test
    fun `no spacing literals`() {
        val hits = sources().flatMap { (p, s) -> spacingViolations(p, s) }
        assertTrue(hits.isEmpty(), "use Spacing.* (spec 2026-11-10 §4):\n" + hits.joinToString("\n"))
    }

    @Test
    fun `thread line stubs use the row padding token`() {
        // the line in Conversation.kt rows must reach the card edge: its stub length is the row's vertical padding (review 2026-11-10)
        val src = File(root, "app/src/main/kotlin/com/monostr/app/ui/common/Conversation.kt").readText()
        val hits = literalsInCalls("Conversation.kt", src, listOf(Regex("""\bdrawLine\(""")))
        assertTrue(hits.isEmpty(), "drawLine with a numeric dp literal:\n" + hits.joinToString("\n"))
    }

    companion object {
        private val DP_LITERAL = Regex("""(?<![\w.])(\d+(?:\.\d+)?)\.dp\b""")
        private val SHAPE_CALL = Regex("""\b(?:RoundedCornerShape|CutCornerShape)\(""")

        // the opening paren of each spacing call; the guard looks only inside its balanced argument list
        private val SPACING_CALLS = listOf(
            Regex("""(?<![\w])padding\("""),
            Regex("""\bPaddingValues\("""),
            Regex("""\bspacedBy\("""),
            Regex("""\bSpacer\(\s*(?:modifier\s*=\s*)?Modifier\s*\.\s*(?:width|height|size)\("""),
        )

        fun shapeViolations(path: String, source: String): List<String> = literalsInCalls(path, source, listOf(SHAPE_CALL))

        fun spacingViolations(path: String, source: String): List<String> = literalsInCalls(path, source, SPACING_CALLS)

        internal fun literalsInCalls(path: String, original: String, calls: List<Regex>): List<String> {
            val source = maskCommentsAndStrings(original)
            val hits = sortedMapOf<Int, String>()
            for (call in calls) for (m in call.findAll(source)) {
                val open = m.range.last
                val close = matchingParen(source, open) ?: continue
                for (lit in DP_LITERAL.findAll(source.substring(open + 1, close))) {
                    if (lit.groupValues[1].toDouble() == 0.0) continue
                    val at = open + 1 + lit.range.first
                    hits[at] = "$path:${source.lineNumberAt(at)}: ${lit.value}"
                }
            }
            return hits.values.toList()
        }

        /** Comments and string/char literals become spaces (newlines kept), so offsets and line numbers stay the same. */
        internal fun maskCommentsAndStrings(src: String): String {
            val out = StringBuilder(src)
            fun blank(from: Int, to: Int) { for (k in from until minOf(to, src.length)) if (src[k] != '\n') out.setCharAt(k, ' ') }
            var i = 0
            while (i < src.length) {
                when {
                    src.startsWith("//", i) -> { val end = src.indexOf('\n', i).let { if (it < 0) src.length else it }; blank(i, end); i = end }
                    src.startsWith("/*", i) -> { val end = src.indexOf("*/", i + 2).let { if (it < 0) src.length else it + 2 }; blank(i, end); i = end }
                    src.startsWith("\"\"\"", i) -> { val end = src.indexOf("\"\"\"", i + 3).let { if (it < 0) src.length else it + 3 }; blank(i, end); i = end }
                    src[i] == '"' || src[i] == '\'' -> {
                        val q = src[i]; var j = i + 1
                        while (j < src.length && src[j] != q && src[j] != '\n') j += if (src[j] == '\\') 2 else 1
                        blank(i, j + 1); i = j + 1
                    }
                    else -> i++
                }
            }
            return out.toString()
        }

        private fun matchingParen(s: String, open: Int): Int? {
            var depth = 0
            for (i in open until s.length) {
                when (s[i]) {
                    '(' -> depth++
                    ')' -> if (--depth == 0) return i
                }
            }
            return null
        }

        private fun String.lineNumberAt(index: Int) = 1 + (0 until index).count { this[it] == '\n' }
    }
}
