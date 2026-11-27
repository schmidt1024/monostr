package com.monostr.nostr.nip55

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * Ties a signer app to the certificate it was signed with when the account logged in (bug bounty 2026-11-26). The
 * package name a NIP-55 signer reports is only a string: once that app is gone, another one may take its name, or
 * claim its `content://<package>.SIGN_EVENT` authorities, and would be handed every text to encrypt and event to sign.
 *
 * No list of known signers: any NIP-55 app may be one. The first use pins what is installed (also for accounts from
 * before this check), a new login with the signer pins again.
 */
object SignerTrust {
    private const val PREFS = "signer_pins"

    /** The decision without Android: no pin yet trusts what is there and asks to [Verdict.PIN] it. */
    enum class Verdict { TRUSTED, PIN, REFUSED }

    fun verdict(pinned: String?, installed: String?, matchesPinned: Boolean): Verdict = when {
        installed == null -> Verdict.REFUSED // not installed, or not visible to us
        pinned == null -> Verdict.PIN
        matchesPinned -> Verdict.TRUSTED
        else -> Verdict.REFUSED
    }

    /** Called by a login with the signer: what is installed now is the signer the user just chose. */
    fun pin(context: Context, pkg: String) {
        val digest = digest(context, pkg) ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(pkg, digest).apply()
    }

    /**
     * Whether [pkg] is still the app that was pinned and, for a resolver call, owns [authority]. False on any doubt: the
     * caller then sends nothing.
     */
    fun check(context: Context, pkg: String, authority: String? = null): Boolean = runCatching {
        val pm = context.packageManager
        if (authority != null && pm.resolveContentProvider(authority, 0)?.packageName != pkg) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pinned = prefs.getString(pkg, null)
        val installed = digest(context, pkg)
        // API 28+: also true after the app rotated its key, which only the holder of the pinned one can do
        val matches = pinned != null && installed != null && (
            pinned == installed ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && pm.hasSigningCertificate(pkg, hex(pinned), PackageManager.CERT_INPUT_SHA256))
            )
        when (verdict(pinned, installed, matches)) {
            Verdict.TRUSTED -> true
            Verdict.PIN -> { prefs.edit().putString(pkg, installed).apply(); true }
            Verdict.REFUSED -> false
        }
    }.getOrDefault(false)

    /** SHA-256 of the app's current signing certificate, lowercase hex; null when it is absent or has several signers. */
    private fun digest(context: Context, pkg: String): String? = runCatching {
        val pm = context.packageManager
        val signers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo ?: return null
            if (info.hasMultipleSigners()) return null
            info.signingCertificateHistory?.lastOrNull()?.let { listOf(it) }.orEmpty()
        } else {
            @Suppress("DEPRECATION", "PackageManagerGetSignatures")
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures?.toList().orEmpty()
        }
        signers.singleOrNull()?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }
    }.getOrNull()

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/** The signer app under the account's package name is not the one the account logged in with: nothing is sent to it. */
class SignerChangedException : IllegalStateException("the signer app changed since the login")
