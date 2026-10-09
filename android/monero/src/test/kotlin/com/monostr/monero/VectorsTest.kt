package com.monostr.monero

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/** Differential tests against vectors produced by monero-python (scripts/gen_monero_vectors.py). */
class VectorsTest {
    @Serializable
    data class Integrated(val pid: String, val mainnet: String, val stagenet: String)

    @Serializable
    data class Vector(
        val spend_secret: String,
        val view_secret: String,
        val spend_public: String,
        val view_public: String,
        val mainnet: String,
        val stagenet: String,
        val mnemonic: List<String>,
        val integrated: List<Integrated>,
    )

    @Serializable
    data class File(val generator: String, val vectors: List<Vector>)

    private val vectors: List<Vector> by lazy {
        val text = javaClass.getResource("/vectors.json")!!.readText()
        Json { ignoreUnknownKeys = true }.decodeFromString<File>(text).vectors
    }

    @TestFactory
    fun `keys and addresses match monero-python`() = vectors.mapIndexed { i, v ->
        DynamicTest.dynamicTest("vector $i keys") {
            val k = MoneroKeys.fromSpendSecret(v.spend_secret.hexToBytes())
            assertEquals(v.view_secret, k.viewSecret.toHex())
            assertEquals(v.spend_public, k.spendPublic.toHex())
            assertEquals(v.view_public, k.viewPublic.toHex())
            assertEquals(v.mainnet, k.address(Network.MAINNET).encode())
            assertEquals(v.stagenet, k.address(Network.STAGENET).encode())
            assertTrue(MoneroKeys.viewKeyMatches(Address.parse(v.mainnet), v.view_secret.hexToBytes()))
        }
    }

    @TestFactory
    fun `mnemonic matches monero-python`() = vectors.mapIndexed { i, v ->
        DynamicTest.dynamicTest("vector $i mnemonic") {
            assertEquals(v.mnemonic, Mnemonic.encode(v.spend_secret.hexToBytes()))
            assertEquals(v.spend_secret, Mnemonic.decode(v.mnemonic).toHex())
        }
    }

    @TestFactory
    fun `integrated addresses match monero-python`() = vectors.flatMapIndexed { i, v ->
        v.integrated.map { integ ->
            DynamicTest.dynamicTest("vector $i pid ${integ.pid}") {
                val pid = integ.pid.hexToBytes()
                assertEquals(integ.mainnet, Address.parse(v.mainnet).integrated(pid))
                assertEquals(integ.stagenet, Address.parse(v.stagenet).integrated(pid))
            }
        }
    }
}
