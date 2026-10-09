package com.monostr.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
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
    fun get(name: String): String?
    fun remove(name: String)
}

class KeystoreSecretStore(context: Context) : SecretStore {
    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    override fun put(name: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val payload = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        // commit(): the session record is written right after this, so the secret must be on disk first
        check(prefs.edit().putString(name, Base64.encodeToString(payload, Base64.NO_WRAP)).commit()) { "secret not stored" }
    }

    /** A lost or invalidated Keystore key (device transfer, security reset) yields null, never an exception. */
    override fun get(name: String): String? {
        val raw = prefs.getString(name, null) ?: return null
        return try {
            val payload = Base64.decode(raw, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, payload.copyOfRange(0, IV_BYTES)))
            String(cipher.doFinal(payload.copyOfRange(IV_BYTES, payload.size)), Charsets.UTF_8)
        } catch (e: java.security.GeneralSecurityException) {
            remove(name)
            null
        } catch (e: IllegalArgumentException) {
            remove(name)
            null
        }
    }

    override fun remove(name: String) {
        prefs.edit().remove(name).apply()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
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
