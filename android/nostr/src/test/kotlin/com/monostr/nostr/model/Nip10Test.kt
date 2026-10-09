package com.monostr.nostr.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class Nip10Test {
    private val a = "a".repeat(64); private val b = "b".repeat(64); private val c = "c".repeat(64)

    @Test fun `no e tags is a root`() = assertEquals(ThreadRefs(null, null), Nip10.parse(listOf(listOf("p", a))))
    @Test fun `marked root and reply`() = assertEquals(ThreadRefs(a, b), Nip10.parse(listOf(listOf("e", a, "", "root"), listOf("e", b, "", "reply"))))
    @Test fun `marked root only means reply to root`() = assertEquals(ThreadRefs(a, a), Nip10.parse(listOf(listOf("e", a, "", "root"))))
    @Test fun `marked reply only`() = assertEquals(ThreadRefs(b, b), Nip10.parse(listOf(listOf("e", b, "wss://r", "reply"))))
    @Test fun `positional first root last reply`() = assertEquals(ThreadRefs(a, c), Nip10.parse(listOf(listOf("e", a), listOf("e", b), listOf("e", c))))
    @Test fun `single positional`() = assertEquals(ThreadRefs(a, a), Nip10.parse(listOf(listOf("e", a))))
    @Test fun `mentions are ignored, short ids dropped`() = assertEquals(ThreadRefs(a, a), Nip10.parse(listOf(listOf("e", "short"), listOf("e", a, "", "mention"), listOf("e", a, "", "root"))))
    @Test fun `profile shown name fallback`() {
        assertEquals("aaaaaaaa…aaaa", Profile.empty(a).shownName)
        assertEquals("Alice", Profile(a, "alice", "Alice", null, null, null).shownName)
        assertEquals("alice", Profile(a, "alice", " ", null, null, null).shownName)
    }
    @Test fun `mention-only e tag is not a thread reference`() = assertEquals(ThreadRefs(null, null), Nip10.parse(listOf(listOf("e", a, "", "mention"))))
    @Test fun `mention before positional tags is skipped`() = assertEquals(ThreadRefs(b, c), Nip10.parse(listOf(listOf("e", a, "", "mention"), listOf("e", b), listOf("e", c))))
}
