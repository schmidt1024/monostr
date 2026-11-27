package com.monostr.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.monostr.nostr.Signer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The account's settings could not be kept (the signer declined or did not answer): the account is not removed. */
class KeepSettingsFailedException(cause: Throwable) : Exception("settings not kept", cause)

/**
 * Owner 2026-11-27 (backlog 121): «Log out» removes an account with all its settings, and logging in again started from
 * nothing, the Monero setup included. With the switch in the dialog on, the settings stay on this device as one package
 * only the account's own key can open, and the next login of that account takes them back.
 *
 * The package: the settings file and the Monero view key as JSON, encrypted with a random AES-256-GCM key; that key alone
 * goes through the signer (NIP-44 to the account's own pubkey), so a key login and Amber work alike with one request each
 * way and the size of the settings does not matter (NIP-44 ends at 64 KiB). The nsec is never part of it.
 *
 * The file's name is a hash of the pubkey and a random secret of this device ([SALT]), so no pubkey can be read from the
 * folder: whoever holds the device's storage and already suspects a pubkey could tell that it was here, nothing more.
 * [dir] must not be backed up (`noBackupFilesDir`).
 */
class KeptSettings(
    private val dir: File,
    private val secrets: SecretStore,
    private val storeFor: (pubkey: String) -> DataStore<Preferences>,
    private val random: SecureRandom = SecureRandom(),
) {
    private fun salt(create: Boolean): String? = secrets.get(SALT) ?: if (!create) null else
        ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }.also { secrets.put(SALT, it) }

    private fun file(pubkey: String, create: Boolean): File? {
        val salt = salt(create) ?: return null
        val name = MessageDigest.getInstance("SHA-256").digest("$salt:$pubkey".toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
        return File(dir, name)
    }

    fun exists(pubkey: String): Boolean = runCatching { file(pubkey, create = false)?.isFile == true }.getOrDefault(false)

    /** Nothing of [pubkey] stays: a removal without the switch also takes an older package. */
    fun drop(pubkey: String) { runCatching { file(pubkey, create = false)?.delete() } }

    /** Writes [signer]'s account as a package; an older one is replaced. Throws [KeepSettingsFailedException]. */
    suspend fun keep(signer: Signer) {
        try {
            val pubkey = signer.pubkey
            val prefs = storeFor(pubkey).data.first()
            val plain = buildJsonObject {
                put("prefs", encode(prefs))
                secrets.get(SecretNames.viewKey(pubkey))?.let { put("viewKey", JsonPrimitive(it)) }
            }.toString().toByteArray()
            val key = ByteArray(32).also(random::nextBytes)
            val nonce = ByteArray(12).also(random::nextBytes)
            val sealed = aes(Cipher.ENCRYPT_MODE, key, nonce).doFinal(plain)
            val wrapped = signer.nip44Encrypt(pubkey, b64(key))
            val out = buildJsonObject {
                put("v", JsonPrimitive(1)); put("key", JsonPrimitive(wrapped)); put("nonce", JsonPrimitive(b64(nonce))); put("data", JsonPrimitive(b64(sealed)))
            }.toString()
            withContext(Dispatchers.IO) {
                val target = file(pubkey, create = true)!!
                dir.mkdirs()
                val tmp = File(dir, target.name + ".tmp")
                tmp.writeText(out)
                if (!tmp.renameTo(target)) { tmp.delete(); error("package not written") }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw KeepSettingsFailedException(e)
        }
    }

    /**
     * Puts the kept settings of [signer]'s account back: its settings file is replaced, its view key stored. False when there is
     * no package or it cannot be opened (the signer declined, another key, a damaged file); the package stays then, and also
     * after a success: [drop] it once the login went through.
     */
    suspend fun restore(signer: Signer): Boolean {
        val pubkey = signer.pubkey
        return try {
            val text = withContext(Dispatchers.IO) { file(pubkey, create = false)?.takeIf { it.isFile }?.readText() } ?: return false
            val box = Json.parseToJsonElement(text).jsonObject
            if (box["v"]?.jsonPrimitive?.content != "1") return false
            val key = unb64(signer.nip44Decrypt(pubkey, box.getValue("key").jsonPrimitive.content))
            val plain = aes(Cipher.DECRYPT_MODE, key, unb64(box.getValue("nonce").jsonPrimitive.content)).doFinal(unb64(box.getValue("data").jsonPrimitive.content))
            val content = Json.parseToJsonElement(String(plain)).jsonObject
            val entries = content.getValue("prefs").jsonArray
            storeFor(pubkey).edit { p -> p.clear(); decodeInto(p, entries) }
            content["viewKey"]?.jsonPrimitive?.content?.let { secrets.put(SecretNames.viewKey(pubkey), it) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    private fun aes(mode: Int, key: ByteArray, nonce: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)) }

    companion object {
        /** The folder below `noBackupFilesDir`. */
        const val DIR = "kept"
        const val SALT = "kept.salt"

        private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
        private fun unb64(text: String) = Base64.getDecoder().decode(text)

        /** Every entry as `[name, type, value]`. A pending setup is left out: it belongs to an account made here that never finished its first publish. */
        internal fun encode(prefs: Preferences): JsonArray = buildJsonArray {
            for ((key, value) in prefs.asMap()) {
                if (key.name.startsWith(PrefsPendingSetupStore.PREFIX)) continue
                val (type, json: JsonElement) = when (value) {
                    is Boolean -> "b" to JsonPrimitive(value)
                    is Int -> "i" to JsonPrimitive(value)
                    is Long -> "l" to JsonPrimitive(value)
                    is Float -> "f" to JsonPrimitive(value.toString())
                    is Double -> "d" to JsonPrimitive(value.toString())
                    is String -> "s" to JsonPrimitive(value)
                    is Set<*> -> "ss" to JsonArray(value.map { JsonPrimitive(it as String) })
                    is ByteArray -> "ba" to JsonPrimitive(b64(value))
                    else -> continue
                }
                add(buildJsonArray { add(JsonPrimitive(key.name)); add(JsonPrimitive(type)); add(json) })
            }
        }

        internal fun decodeInto(p: androidx.datastore.preferences.core.MutablePreferences, entries: JsonArray) {
            for (entry in entries) {
                val (name, type, value) = entry.jsonArray.let { Triple(it[0].jsonPrimitive.content, it[1].jsonPrimitive.content, it[2]) }
                when (type) {
                    "b" -> p[booleanPreferencesKey(name)] = value.jsonPrimitive.content.toBooleanStrict()
                    "i" -> p[intPreferencesKey(name)] = value.jsonPrimitive.content.toInt()
                    "l" -> p[longPreferencesKey(name)] = value.jsonPrimitive.content.toLong()
                    "f" -> p[floatPreferencesKey(name)] = value.jsonPrimitive.content.toFloat()
                    "d" -> p[doublePreferencesKey(name)] = value.jsonPrimitive.content.toDouble()
                    "s" -> p[stringPreferencesKey(name)] = value.jsonPrimitive.content
                    "ss" -> p[stringSetPreferencesKey(name)] = value.jsonArray.map { it.jsonPrimitive.content }.toSet()
                    "ba" -> p[byteArrayPreferencesKey(name)] = unb64(value.jsonPrimitive.content)
                }
            }
        }
    }
}
