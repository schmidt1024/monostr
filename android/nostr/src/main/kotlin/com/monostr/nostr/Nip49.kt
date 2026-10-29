package com.monostr.nostr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rust.nostr.sdk.EncryptedSecretKey
import rust.nostr.sdk.KeySecurity
import rust.nostr.sdk.SecretKey

/**
 * NIP-49 password-encrypted secret keys (ncryptsec), spec 2026-10-29 ncryptsec.
 * scrypt is slow, so every rust call runs on [Dispatchers.Default], never on Main.
 * The password and the decrypted key are never logged.
 */
object Nip49 {
    private const val PREFIX = "ncryptsec1"
    private const val LOG_N: UByte = 16u
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

    /** Highest log_n we decrypt; scrypt memory is 128 * 2^log_n * 8 bytes, so 20 is 1 GiB. */
    const val MAX_LOG_N = 20

    /** True when [input] (trimmed, any case) starts with the ncryptsec prefix. */
    fun isNcryptsec(input: String): Boolean = input.trim().startsWith(PREFIX, ignoreCase = true)

    /**
     * log_n from the payload (byte 1, after the version byte), read from the first four bech32 chars.
     * Null when the string is too short or has characters outside the bech32 set. Checksum is not checked here.
     */
    fun logN(input: String): Int? {
        val data = input.trim().lowercase().removePrefix(PREFIX)
        if (data.length < 4) return null
        var acc = 0
        for (c in data.take(4)) {
            val v = CHARSET.indexOf(c)
            if (v < 0) return null
            acc = (acc shl 5) or v
        }
        return (acc shr 4) and 0xff // 20 bits: version byte, log_n byte, 4 spare bits
    }

    /** Encrypts a hex secret key with [password] (used unchanged) at log_n 16, key security unknown. */
    suspend fun encrypt(secretHex: String, password: String): String = withContext(Dispatchers.Default) {
        PerfTrace.mark("ncryptsec encrypt start")
        try {
            SecretKey.parse(secretHex).use { sk ->
                EncryptedSecretKey(sk, password, LOG_N, KeySecurity.UNKNOWN).use { it.toBech32() }
            }
        } finally {
            PerfTrace.mark("ncryptsec encrypt done")
        }
    }

    /** Decrypts an ncryptsec string. A failure after a successful parse reads as a wrong password. */
    suspend fun decrypt(input: String, password: String): Decrypted = withContext(Dispatchers.Default) {
        PerfTrace.mark("ncryptsec decrypt start")
        try {
            val enc = try {
                EncryptedSecretKey.fromBech32(input.trim())
            } catch (_: Exception) {
                return@withContext Decrypted.Invalid
            }
            val logN = logN(input)
            if (logN != null && logN > MAX_LOG_N) return@withContext Decrypted.TooStrong
            enc.use {
                try {
                    it.decrypt(password).use { sk -> Decrypted.Key(sk.toHex()) }
                } catch (_: Exception) {
                    Decrypted.WrongPassword
                }
            }
        } finally {
            PerfTrace.mark("ncryptsec decrypt done")
        }
    }

    sealed interface Decrypted {
        class Key(val secretHex: String) : Decrypted {
            override fun toString() = "Key(***)"
        }
        data object WrongPassword : Decrypted
        data object Invalid : Decrypted

        /** log_n above [MAX_LOG_N]: refused before scrypt runs, it could exhaust the phone's memory. */
        data object TooStrong : Decrypted
    }
}
