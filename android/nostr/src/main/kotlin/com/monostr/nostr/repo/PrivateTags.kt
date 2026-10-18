package com.monostr.nostr.repo

import com.monostr.nostr.Signer
import com.monostr.nostr.SigningRejectedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** How reading an encrypted own part went (NIP-51 private items, the NIP-78 tabs event). */
sealed interface Decrypted<out T> {
    data class Ok<T>(val value: T) : Decrypted<T>
    /** The signer would have to ask the user (a silent read of an external signer). */
    data object NoSilentAnswer : Decrypted<Nothing>
    /** The user declined the signer prompt. */
    data object Rejected : Decrypted<Nothing>
    /** Not decryptable, or not the expected JSON. */
    data object Unreadable : Decrypted<Nothing>
}

/**
 * NIP-44 to self through [signer] (local key or Amber): the private items of NIP-51 lists, a JSON array of
 * tags, and other own encrypted content (spec 2026-10-18 §4.1). NIP-04 payloads of older clients
 * ("…?iv=…") are read, never written. Extracted unchanged from [NostrPrivateList].
 */
internal class PrivateTags(private val signer: Signer) {
    suspend fun decryptText(content: String, interactive: Boolean): Decrypted<String> = try {
        val text = withContext(Dispatchers.Default) {
            val legacy = NIP04.containsMatchIn(content)
            when {
                legacy && interactive -> signer.nip04Decrypt(signer.pubkey, content)
                legacy -> signer.nip04DecryptSilent(signer.pubkey, content)
                interactive -> signer.nip44Decrypt(signer.pubkey, content)
                else -> signer.nip44DecryptSilent(signer.pubkey, content)
            }
        }
        if (text == null) Decrypted.NoSilentAnswer else Decrypted.Ok(text)
    } catch (e: CancellationException) {
        throw e
    } catch (e: SigningRejectedException) {
        Decrypted.Rejected
    } catch (e: Exception) {
        Decrypted.Unreadable
    }

    /** The tags of an encrypted private part; blank content is an empty one. */
    suspend fun decrypt(content: String, interactive: Boolean): Decrypted<List<List<String>>> {
        if (content.isBlank()) return Decrypted.Ok(emptyList())
        return when (val r = decryptText(content, interactive)) {
            is Decrypted.Ok -> try {
                Decrypted.Ok(parse(r.value))
            } catch (e: Exception) {
                Decrypted.Unreadable
            }
            Decrypted.NoSilentAnswer -> Decrypted.NoSilentAnswer
            Decrypted.Rejected -> Decrypted.Rejected
            Decrypted.Unreadable -> Decrypted.Unreadable
        }
    }

    /** Throws what the signer throws ([SigningRejectedException], `Nip44UnsupportedException`, …). */
    suspend fun encryptText(text: String): String = withContext(Dispatchers.Default) { signer.nip44Encrypt(signer.pubkey, text) }

    suspend fun encrypt(tags: List<List<String>>): String = encryptText(encode(tags))

    companion object {
        fun parse(text: String): List<List<String>> =
            Json.parseToJsonElement(text).jsonArray.map { t -> t.jsonArray.map { it.jsonPrimitive.content } }

        fun encode(tags: List<List<String>>): String =
            JsonArray(tags.map { t -> JsonArray(t.map { JsonPrimitive(it) }) }).toString()

        /** A NIP-04 payload: base64 ciphertext, "?iv=", base64 IV. NIP-44 payloads are plain base64. */
        private val NIP04 = Regex("\\?iv=[A-Za-z0-9+/=]+$") // regex
    }
}
