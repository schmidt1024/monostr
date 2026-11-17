package com.monostr.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small secrets (the nsec, later the Monero view key) encrypted with an
 * AES-GCM key that lives in the Android Keystore. Ciphertext sits in a
 * private SharedPreferences file; the key never leaves the Keystore.
 */
interface SecretStore {
    fun put(name: String, value: String)
    /** The secret, or null when it is missing or unreadable for good; a passing Keystore error also yields null but keeps the secret. */
    fun get(name: String): String?
    /** Like [get], but a passing Keystore error throws [SecretUnavailableException] instead of looking like a missing secret. */
    fun getOrThrow(name: String): String? = get(name)
    fun remove(name: String)
}

/** The Keystore could not be used right now (keystore2 busy or not ready after boot): the stored secret is kept, try again later. */
class SecretUnavailableException(cause: Throwable) : java.io.IOException("secret store unavailable", cause)

class KeystoreSecretStore(context: Context) : SecretStore {
    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    override fun put(name: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val payload = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        // commit(): the session record is written right after this, so the secret must be on disk first
        check(prefs.edit().putString(name, Base64.encodeToString(payload, Base64.NO_WRAP)).commit()) { "secret not stored" }
    }

    /** A passing Keystore error yields null here but never deletes the secret (see [getOrThrow]). */
    override fun get(name: String): String? = try {
        getOrThrow(name)
    } catch (e: SecretUnavailableException) {
        null
    }

    /**
     * Only a secret that can never be decrypted again is removed: a broken blob, or a Keystore key that is gone (device
     * transfer, security reset) or no longer matches. Any other Keystore failure may pass (keystore2 busy, not ready
     * after boot) and must not destroy the user's only copy of the key: it throws [SecretUnavailableException].
     */
    override fun getOrThrow(name: String): String? {
        val raw = prefs.getString(name, null) ?: return null
        val payload = try {
            Base64.decode(raw, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            null
        }
        if (payload == null || payload.size <= IV_BYTES) {
            remove(name)
            return null
        }
        val key = try {
            existingKey()
        } catch (e: Exception) {
            throw SecretUnavailableException(e)
        }
        if (key == null) {
            remove(name)
            return null
        }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, payload.copyOfRange(0, IV_BYTES)))
            String(cipher.doFinal(payload.copyOfRange(IV_BYTES, payload.size)), Charsets.UTF_8)
        } catch (e: AEADBadTagException) {
            // the key under the alias is not the one this secret was sealed with: it can never be read again
            remove(name)
            null
        } catch (e: KeyPermanentlyInvalidatedException) {
            remove(name)
            null
        } catch (e: java.security.GeneralSecurityException) {
            throw SecretUnavailableException(e)
        } catch (e: java.security.ProviderException) {
            throw SecretUnavailableException(e)
        }
    }

    /** The Keystore key, or null when there is none; never creates one (a new key could not open old secrets anyway). */
    private fun existingKey(): SecretKey? {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return ks.getKey(ALIAS, null) as? SecretKey
    }

    override fun remove(name: String) {
        prefs.edit().remove(name).apply()
    }

    private fun key(): SecretKey {
        existingKey()?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    companion object {
        const val SECRET_NSEC = "nsec"
        /** Private Monero view key as 64 hex chars (spec 6.4). */
        const val SECRET_VIEW_KEY = "monero.view_key"
        private const val ALIAS = "monostr-secrets"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
    }
}
