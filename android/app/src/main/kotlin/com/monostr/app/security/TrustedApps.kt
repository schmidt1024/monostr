package com.monostr.app.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * The other apps Monostr hands text or links to, each with the SHA-256 of the certificate its publisher signs with
 * (security review 2026-11-26). A package name alone proves nothing: where the real app is missing, any app may take its
 * name and would receive the note to translate or the link opened «in Tor Browser».
 *
 * Read from the published APKs on 2026-11-26: Offline Translator 0.8.7 from F-Droid and from GitHub (one certificate,
 * F-Droid ships the developer's signature), Tor Browser 16.0.3 and 16.0a14 from dist.torproject.org.
 */
object TrustedApps {
    const val OFFLINE_TRANSLATOR = "dev.davidv.translator"
    const val TOR_BROWSER = "org.torproject.torbrowser"
    const val TOR_BROWSER_ALPHA = "org.torproject.torbrowser_alpha"

    val CERTIFICATES: Map<String, Set<String>> = mapOf(
        OFFLINE_TRANSLATOR to setOf("2b3806e745d809018a51be58d0635ffc74cc9733439407ab1ed0424a4db3e1fb"),
        TOR_BROWSER to setOf("20061f045e737c67375c17794cfedb436a03cec6bacb7cb9f96642205ca2cec8"),
        TOR_BROWSER_ALPHA to setOf("15f760b41acbe4783e667102c9f67119be2af62fab07763f9d57f01e5e1074e1"),
    )

    /** Whether [pkg] is one of ours to trust and [hasCertificate] finds one of its certificates (SHA-256 bytes) on the installed app. */
    fun isTrusted(pkg: String, hasCertificate: (ByteArray) -> Boolean): Boolean =
        CERTIFICATES[pkg].orEmpty().any { hex -> hasCertificate(bytes(hex)) }

    /** Asks the package manager; false when [pkg] is absent, unknown to us or signed by someone else. */
    fun isTrusted(context: Context, pkg: String): Boolean = runCatching {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // also true for a certificate the app rotated away from, which is what its publisher proved it owned
            isTrusted(pkg) { sha -> pm.hasSigningCertificate(pkg, sha, PackageManager.CERT_INPUT_SHA256) }
        } else {
            @Suppress("DEPRECATION", "PackageManagerGetSignatures")
            val signatures = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures.orEmpty()
            // Android 8: every signer must be a known one, a second signer of someone else's is not ours to trust
            val digests = signatures.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()) }
            digests.isNotEmpty() && digests.all { d -> isTrusted(pkg) { it.contentEquals(d) } }
        }
    }.getOrDefault(false)

    private fun bytes(hex: String): ByteArray = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
