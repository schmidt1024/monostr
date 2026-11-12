package com.monostr.app.ui.media

import coil3.network.HttpException
import coil3.network.NetworkResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.IOException

class MediaFailureTest {
    private fun http(code: Int) = HttpException(NetworkResponse(code = code))

    @Test fun `refusal codes with Tor say the site blocks Tor`() {
        for (code in listOf(403, 429, 451)) assertEquals(MediaFailure.TOR_BLOCKED, MediaFailures.of(http(code), torActive = true), "$code")
    }

    @Test fun `the same codes without Tor are just broken`() {
        for (code in listOf(403, 429, 451)) assertEquals(MediaFailure.BROKEN, MediaFailures.of(http(code), torActive = false), "$code")
    }

    @Test fun `other failures with Tor are broken`() {
        for (code in listOf(401, 404, 410, 500, 503)) assertEquals(MediaFailure.BROKEN, MediaFailures.of(http(code), torActive = true), "$code")
        assertEquals(MediaFailure.BROKEN, MediaFailures.of(IOException("timeout"), torActive = true))
        assertEquals(MediaFailure.BROKEN, MediaFailures.of(null, torActive = true))
    }
}
