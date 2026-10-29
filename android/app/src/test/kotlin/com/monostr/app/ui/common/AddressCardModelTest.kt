package com.monostr.app.ui.common

import com.monostr.app.R
import com.monostr.nostr.model.AddressableEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Spec 2026-10-29 naddr: what an address card shows, decided without any UI. */
class AddressCardModelTest {
    private val author = "a".repeat(64)
    private val host = "b".repeat(64)
    private val now = 1_000_000L

    private fun event(kind: Int, tags: List<List<String>>, d: String = "slug", at: Long = now - 10, content: String = "") =
        AddressableEvent("e".repeat(64), kind, author, d, at, tags, content)

    @Test fun articleShowsTitleSummaryImage() {
        val m = AddressCardModel.from(
            event(30023, listOf(listOf("title", "Hello"), listOf("summary", "Short"), listOf("image", "https://cdn.example.com/a.jpg"))), now,
        )
        assertEquals(R.string.address_type_article, m.typeLabel)
        assertEquals("Hello", m.title)
        assertEquals("Short", m.summary)
        assertEquals("https://cdn.example.com/a.jpg", m.image)
        assertEquals(author, m.personPubkey)
        assertNull(m.badge)
        assertFalse(m.covered)
    }

    @Test fun insecureOrLanPictureIsDropped() {
        assertNull(AddressCardModel.from(event(30023, listOf(listOf("image", "http://cdn.example.com/a.jpg"))), now).image)
        assertNull(AddressCardModel.from(event(30023, listOf(listOf("image", "https://192.168.1.5/a.jpg"))), now).image)
    }

    @Test fun freshLiveStreamIsLiveWithParticipantsAndHost() {
        val m = AddressCardModel.from(
            event(30311, listOf(listOf("title", "Show"), listOf("status", "live"), listOf("current_participants", "42"), listOf("p", host, "", "Host"))), now,
        )
        assertEquals(R.string.address_type_live, m.typeLabel)
        assertEquals(AddressCardModel.Badge.LIVE, m.badge)
        assertEquals(42, m.participants)
        assertEquals(host, m.personPubkey)
    }

    @Test fun staleLiveStreamIsEnded() {
        val m = AddressCardModel.from(event(30311, listOf(listOf("status", "live")), at = now - 3601), now)
        assertEquals(AddressCardModel.Badge.ENDED, m.badge)
    }

    @Test fun plannedStreamHasStart() {
        val m = AddressCardModel.from(event(30311, listOf(listOf("status", "planned"), listOf("starts", "1234"))), now)
        assertEquals(AddressCardModel.Badge.PLANNED, m.badge)
        assertEquals(1234L, m.startsAt)
    }

    @Test fun nsfwInTitleTagOrContentWarningCovers() {
        assertTrue(AddressCardModel.from(event(30023, listOf(listOf("title", "x #NSFW"))), now).covered)
        assertTrue(AddressCardModel.from(event(30023, listOf(listOf("t", "NSFW"))), now).covered)
        assertTrue(AddressCardModel.from(event(30023, listOf(listOf("content-warning", ""))), now).covered)
        assertFalse(AddressCardModel.from(event(30023, listOf(listOf("t", "nostr"))), now).covered)
    }

    @Test fun kindsMapToTypeLabels() {
        assertEquals(R.string.address_type_listing, AddressCardModel.from(event(30402, emptyList()), now).typeLabel)
        assertEquals(R.string.address_type_list, AddressCardModel.from(event(30001, emptyList()), now).typeLabel)
        assertEquals(R.string.address_type_calendar, AddressCardModel.from(event(31922, emptyList()), now).typeLabel)
        assertEquals(R.string.address_type_event, AddressCardModel.from(event(34550, emptyList()), now).typeLabel)
    }

    @Test fun titleFallsBackToNameThenShortIdentifier() {
        assertEquals("N", AddressCardModel.from(event(34550, listOf(listOf("name", "N"))), now).title)
        assertEquals("abcdefghijkl…", AddressCardModel.from(event(34550, emptyList(), d = "abcdefghijklmnop"), now).title)
        assertEquals("short", AddressCardModel.from(event(34550, emptyList(), d = "short"), now).title)
    }

    @Test fun plannedWithoutStartHasNoStartTime() {
        val m = AddressCardModel.from(event(30311, listOf(listOf("status", "planned"))), now)
        assertEquals(AddressCardModel.Badge.PLANNED, m.badge)
        assertNull(m.startsAt)
    }

    @Test fun thumbIsTheImageFallback() {
        val m = AddressCardModel.from(event(30311, listOf(listOf("thumb", "https://cdn.example.com/t.jpg"))), now)
        assertEquals("https://cdn.example.com/t.jpg", m.image)
    }

    @Test fun listKindsAreExactlyTheKnownOnes() {
        assertEquals(R.string.address_type_list, AddressCardModel.from(event(30030, emptyList()), now).typeLabel)
        assertEquals(R.string.address_type_event, AddressCardModel.from(event(30004, emptyList()), now).typeLabel)
    }

    @Test fun dmCardDropsRelayHints() {
        val ref = com.monostr.nostr.model.AddressRef(30023, author, "d", listOf("wss://relay.example.com"))
        assertTrue(dmAddressRef(ref).relays.isEmpty())
        assertEquals(ref.coordinate, dmAddressRef(ref).coordinate)
    }
}
