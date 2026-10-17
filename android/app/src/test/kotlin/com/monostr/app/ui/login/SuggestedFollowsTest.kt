package com.monostr.app.ui.login

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SuggestedFollowsTest {
    private val expected = listOf(
        "1601fcacdf227ddda10e33e71917930b49be436b43bdd030b799948a9f274ed3",
        "0d7ceca9e000e711e263bc14a2216ab74968a9e903eba214a714300adede5a20",
        "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d",
        "32e1827635450ebb3c5a7d12c1f8e7b2b514439ac10a67eef3d9fd9c5c68e245",
        "460c25e682fda7832b52d1f22d3d22b3176d972f60dcdc3212ed8c92ef85065c",
        "7579076d9aff0a4cfdefa7e2045f2486c7e5d8bc63bfc6b45397233e1bbfcb19",
        "266815e0c9210dfa324c6cba3573b14bee49da4209a9456f9484e5106cd408a5",
        "3f770d65d3a764a9c5cb503ae123e62ec7598ad035d836e2a810f3877a745b24",
        "04c915daefee38317fa734444acee390a8269fe5810b2241e5e6dd343dfbecc9",
        "82341f882b6eabcd2ba7f1ef90aad961cf074af15b9ef44a09f9d2a8fbfbe6a2",
        "78ce6faa72264387284e647ba6938995735ec8c7d5c5a65737e55130f026307d",
        "0f22c06eac1002684efcc68f568540e8342d1609d508bcd4312c038e6194f8b6",
        "dbe791bf6b9221eaf517e43c030cdff6c0c7e9b561c6228773e194d08eb8ddf7",
        "a6038971d542950c13c4da295bcaa37c7985195b12b0fa95f6886fe608050bb9",
    )

    @Test
    fun `the list is the 14 approved accounts in order`() {
        assertEquals(14, SuggestedFollows.all.size)
        assertEquals(expected, SuggestedFollows.all.map { it.pubkey })
        assertEquals("monostr", SuggestedFollows.all[0].name)
        assertEquals("Schmidt", SuggestedFollows.all[1].name)
    }

    @Test
    fun `every key is 64 lowercase hex and unique`() {
        val hex = Regex("[0-9a-f]{64}")
        assertTrue(SuggestedFollows.all.all { hex.matches(it.pubkey) })
        assertEquals(14, SuggestedFollows.all.map { it.pubkey }.toSet().size)
    }
}
