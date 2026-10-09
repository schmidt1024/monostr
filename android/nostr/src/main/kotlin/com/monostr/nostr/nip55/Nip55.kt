package com.monostr.nostr.nip55

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Pure NIP-55 request/response codec. Android glue (Intents, ContentResolver)
 * lives in [AmberSigner]; everything here is testable on the JVM.
 */
object Nip55 {
    const val SCHEME = "nostrsigner:"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Serializable
    data class Permission(val type: String, val kind: Int? = null)

    /** Permissions requested at login so later signing runs via ContentResolver without UI. */
    val defaultPermissions: List<Permission> = listOf(
        Permission("sign_event", 0), Permission("sign_event", 1), Permission("sign_event", 3),
        Permission("sign_event", 6), Permission("sign_event", 7), Permission("sign_event", 10002),
        Permission("sign_event", 10037), Permission("sign_event", 9738), Permission("sign_event", 27235),
        Permission("sign_event", 10003), Permission("nip44_encrypt"), Permission("nip44_decrypt"),
        // seals (NIP-59), NIP-42 relay auth, and the kind 10050 DM relay list (Plan 10b).
        Permission("sign_event", 13), Permission("sign_event", 22242), Permission("sign_event", 10050),
        // Blossom authorization for picture uploads (Plan 10e).
        Permission("sign_event", 24242),
        // NIP-09 deletion requests (Plan 11-A).
        Permission("sign_event", 5),
        // NIP-51 mute list (Plan 11-B).
        Permission("sign_event", 10000),
    )

    fun permissionsJson(permissions: List<Permission> = defaultPermissions): String =
        json.encodeToString(ListSerializer(Permission.serializer()), permissions)

    /** A request as the Android layer must send it: URI data plus intent extras. */
    data class Request(val uri: String, val extras: Map<String, String>, val packageName: String?)

    fun getPublicKey(permissions: List<Permission> = defaultPermissions): Request = Request(
        uri = SCHEME,
        extras = mapOf("type" to "get_public_key", "permissions" to permissionsJson(permissions)),
        packageName = null,
    )

    fun signEvent(eventJson: String, currentUser: String, packageName: String, id: String): Request = Request(
        uri = SCHEME + eventJson,
        extras = mapOf("type" to "sign_event", "id" to id, "current_user" to currentUser),
        packageName = packageName,
    )

    /** ContentResolver query for background signing: uri plus selectionArgs. */
    data class ResolverQuery(val uri: String, val selectionArgs: List<String>)

    fun signEventQuery(packageName: String, eventJson: String, currentUser: String): ResolverQuery =
        ResolverQuery("content://$packageName.SIGN_EVENT", listOf(eventJson, "", currentUser))

    enum class Nip44Op(val resolver: String, val type: String) {
        ENCRYPT("NIP44_ENCRYPT", "nip44_encrypt"),
        DECRYPT("NIP44_DECRYPT", "nip44_decrypt"),
    }

    /** ContentResolver call for NIP-44: `content://<pkg>.NIP44_ENCRYPT|NIP44_DECRYPT`, args = text, peer pubkey, current user. */
    fun nip44Query(packageName: String, op: Nip44Op, text: String, peerPubkey: String, currentUser: String): ResolverQuery =
        ResolverQuery("content://$packageName.${op.resolver}", listOf(text, peerPubkey, currentUser))

    /** Intent fallback for NIP-44 when the resolver has no answer (first use, permission not yet granted). */
    fun nip44Request(op: Nip44Op, text: String, peerPubkey: String, currentUser: String, packageName: String, id: String): Request = Request(
        uri = SCHEME + text,
        extras = mapOf("type" to op.type, "id" to id, "current_user" to currentUser, "pubkey" to peerPubkey),
        packageName = packageName,
    )

    /** Result extras as returned by the signer activity. */
    data class Response(val result: String?, val event: String?, val packageName: String?, val rejected: Boolean)

    sealed class Outcome {
        data class Signed(val eventJson: String) : Outcome()
        data class Pubkey(val pubkey: String, val packageName: String) : Outcome()
        data class Result(val value: String) : Outcome()
        data object Rejected : Outcome()
        data class Failed(val reason: String) : Outcome()
    }

    /** Outcome of a ContentResolver SIGN_EVENT answer. A `rejected` column means "always reject", regardless of rows. */
    sealed class ResolverOutcome {
        data class Signed(val eventJson: String) : ResolverOutcome()
        data object Rejected : ResolverOutcome()
        data object Unavailable : ResolverOutcome()
    }

    fun resolverOutcome(hasRejectedColumn: Boolean, hasRow: Boolean, eventJson: String?): ResolverOutcome = when {
        hasRejectedColumn -> ResolverOutcome.Rejected
        !hasRow || eventJson.isNullOrBlank() -> ResolverOutcome.Unavailable
        else -> ResolverOutcome.Signed(eventJson)
    }

    /**
     * As for NIP-44, a dismissed prompt is a user decision: an explicit `rejected` and a non-ok
     * result (RESULT_CANCELED, Back pressed) map to [Outcome.Rejected], which [AmberSigner] turns
     * into `SigningRejectedException`. [Outcome.Failed] is an ok result without a usable answer.
     */
    fun parseSignResponse(resultOk: Boolean, response: Response?): Outcome = when {
        response?.rejected == true -> Outcome.Rejected
        !resultOk -> Outcome.Rejected
        response == null -> Outcome.Failed("empty result")
        !response.event.isNullOrBlank() -> Outcome.Signed(response.event)
        else -> Outcome.Failed("no event in result")
    }

    fun parsePubkeyResponse(resultOk: Boolean, response: Response?): Outcome = when {
        !resultOk -> Outcome.Failed("signer returned an error")
        response == null -> Outcome.Failed("empty result")
        response.rejected -> Outcome.Rejected
        response.result.isNullOrBlank() || response.packageName.isNullOrBlank() -> Outcome.Failed("no pubkey in result")
        else -> Outcome.Pubkey(normalizePubkey(response.result), response.packageName)
    }

    /**
     * A dismissed prompt is a user decision, not a capability statement: both an explicit
     * `rejected` column and a non-ok result (RESULT_CANCELED, Back pressed, launcher failure)
     * map to [Outcome.Rejected]. [Outcome.Failed] (which callers turn into
     * `Nip44UnsupportedException`) is reserved for a genuinely empty answer on an ok result,
     * or no response at all.
     */
    fun parseNip44Response(resultOk: Boolean, response: Response?): Outcome = when {
        response?.rejected == true -> Outcome.Rejected
        !resultOk -> Outcome.Rejected
        response == null -> Outcome.Failed("empty result")
        response.result.isNullOrEmpty() -> Outcome.Failed("no result")
        else -> Outcome.Result(response.result)
    }

    /** Signers may answer with npub; the app works with hex. */
    fun normalizePubkey(value: String): String = if (value.startsWith("npub1")) {
        rust.nostr.sdk.PublicKey.parse(value).toHex()
    } else value.lowercase()
}
