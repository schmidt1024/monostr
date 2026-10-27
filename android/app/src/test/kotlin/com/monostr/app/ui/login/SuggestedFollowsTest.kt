package com.monostr.app.ui.login

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SuggestedFollowsTest {
    // 2026-10-27 (spec discover §7): monostr, Schmidt, then members of the Monero list in its order
    private val expected = listOf(
        "1601fcacdf227ddda10e33e71917930b49be436b43bdd030b799948a9f274ed3",
        "0d7ceca9e000e711e263bc14a2216ab74968a9e903eba214a714300adede5a20",
        "dbe791bf6b9221eaf517e43c030cdff6c0c7e9b561c6228773e194d08eb8ddf7",
        "a6038971d542950c13c4da295bcaa37c7985195b12b0fa95f6886fe608050bb9",
        "d54c85fa6483639e306db3efa0f8c6bf3f79ca61df1fc58bbc3e9c9d1e8504f5",
        "465eb13404d7219cd91c2b4a8a7e308ad4e09bc1298dc08c5eec852e9bf5da09",
        "7dc18ff0eec577704b3b8bdf8c85e30e3c9573939d2eafe0c331dc18b378181e",
        "5cde0d3cb27cc59bbc4132fffa5c6e74c75e7e64a5af592e1d5c6e0e8dcc9e46",
        "8027a1877f39e603dafc63279e004b4ed9df861d18ce81d9c43c7d7135da8f65",
        "ecbff9758417655ad8ba123d9e08d60d767045da22472ede86cfea8e9a2cd17c",
        "5e60b49b811061e2b2479e22075d6360e45c20884f04b85e27f4040b9a37ce5f",
        "2786c134148537aa3142222cccc1b6fcf4863643ca78a9625d91aa9748d0e478",
        "877308276be50ce9bafa7e5e374e4fcbf5e9859a21918f34baefd000746b7d35",
        "1222aa72b4100d9f822185cc5e5341dae474c345c93cf35d8497fb7d7b4f0211",
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
