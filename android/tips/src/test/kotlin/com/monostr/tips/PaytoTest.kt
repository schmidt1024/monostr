package com.monostr.tips

import com.monostr.tips.event.UnsignedEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PaytoTest {
    private val mainnet = "42nsXK8WbVGTNayQ6Kjw5UdgqbQY5KCCufdxdCgF7NgTfjC69Mna7DJSYyie77hZTQ8H92G2HwgFhgEUYnDzrnLnQdF28r3"
    private val sub = "84DbuRJ1PQ4LHj5awNDtBXFDJczqxhujvgpMxmF9uxGNKjChpAUd2YobG8zibKqSyDUUxnavkHLew9YscmTLGYGgJSVxDjL"
    private val stagenet = "52zucA3UF6NTNayQ6Kjw5UdgqbQY5KCCufdxdCgF7NgTfjC69Mna7DJSYyie77hZTQ8H92G2HwgFhgEUYnDzrnLnQf9eTdc"
    private val integrated = "4CVYY7x1CknTNayQ6Kjw5UdgqbQY5KCCufdxdCgF7NgTfjC69Mna7DJSYyie77hZTQ8H92G2HwgFhgEUYnDzrnLnbxc5xHroTU8U3vykdq"

    private val mixed = listOf(
        listOf("payto", "lightning", "a@b.c"),
        listOf("payto", "xmr", mainnet),
        listOf("x", "y", "z"),
    )

    @Test
    fun readsMonero() {
        assertEquals(mainnet, Payto.moneroAddress(listOf(listOf("payto", "monero", mainnet))))
    }

    @Test
    fun readsXmrCaseInsensitive() {
        assertEquals(mainnet, Payto.moneroAddress(listOf(listOf("payto", "XMR", " $mainnet "))))
    }

    @Test
    fun skipsInvalidThenTakesFirstValid() {
        val tags = listOf(listOf("payto", "monero", "kaputt"), listOf("payto", "monero", sub))
        assertEquals(sub, Payto.moneroAddress(tags))
    }

    @Test
    fun ignoresStagenetAndIntegrated() {
        val tags = listOf(listOf("payto", "monero", stagenet), listOf("payto", "xmr", integrated))
        assertNull(Payto.moneroAddress(tags))
    }

    @Test
    fun ignoresOtherTypes() {
        assertNull(Payto.moneroAddress(listOf(listOf("payto", "lightning", mainnet))))
    }

    @Test
    fun withMoneroKeepsForeignTagsVerbatim() {
        assertEquals(
            listOf(listOf("payto", "lightning", "a@b.c"), listOf("x", "y", "z"), listOf("payto", "monero", sub)),
            Payto.withMonero(mixed, sub),
        )
    }

    @Test
    fun withMoneroNullRemovesOnly() {
        assertEquals(listOf(listOf("payto", "lightning", "a@b.c"), listOf("x", "y", "z")), Payto.withMonero(mixed, null))
    }

    @Test
    fun buildIsKind10133() {
        val tags = listOf(listOf("payto", "monero", mainnet))
        assertEquals(UnsignedEvent(10133, "", tags, 5L), Payto.build(tags, 5L))
    }
}
