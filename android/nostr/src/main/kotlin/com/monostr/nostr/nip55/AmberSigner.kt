package com.monostr.nostr.nip55

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.monostr.nostr.Nip44UnsupportedException
import com.monostr.nostr.Signer
import com.monostr.nostr.SigningRejectedException
import com.monostr.nostr.SilentSignUnavailable
import com.monostr.tips.event.Event
import com.monostr.tips.event.EventJson
import com.monostr.tips.event.UnsignedEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * NIP-55 signer (Amber and compatible apps). Tries the ContentResolver first
 * (no UI when the permission was remembered), then falls back to an Intent
 * through [SignerBridge].
 */
class AmberSigner(
    private val context: Context,
    val packageName: String,
    override val pubkey: String,
    private val bridge: SignerBridge,
) : Signer {

    override suspend fun sign(event: UnsignedEvent): Event {
        val json = unsignedJson(event)
        viaContentResolver(json)?.let { return it }
        val request = Nip55.signEvent(json, pubkey, packageName, java.util.UUID.randomUUID().toString())
        val (ok, response) = bridge.request(request)
        return when (val outcome = Nip55.parseSignResponse(ok, response)) {
            is Nip55.Outcome.Signed -> EventJson.decode(outcome.eventJson)
            Nip55.Outcome.Rejected -> throw SigningRejectedException()
            is Nip55.Outcome.Failed -> throw IllegalStateException("signer failed: ${outcome.reason}")
            is Nip55.Outcome.Pubkey -> throw IllegalStateException("unexpected pubkey response")
            is Nip55.Outcome.Result -> throw IllegalStateException("unexpected nip44 response")
        }
    }

    /** ContentResolver only: never opens the signer activity (NIP-42 AUTH must not pop up Amber). */
    override suspend fun signEventSilent(event: UnsignedEvent): Event =
        viaContentResolver(unsignedJson(event)) ?: throw SilentSignUnavailable()

    private suspend fun viaContentResolver(json: String): Event? = withContext(Dispatchers.IO) {
        val q = Nip55.signEventQuery(packageName, json, pubkey)
        runCatching {
            resolverQuery(q)?.use { cursor ->
                val hasRejected = cursor.getColumnIndex("rejected") >= 0
                val hasRow = cursor.moveToFirst()
                val idx = cursor.getColumnIndex("event")
                val eventJson = if (hasRow && idx >= 0) cursor.getString(idx) else null
                when (val outcome = Nip55.resolverOutcome(hasRejected, hasRow, eventJson)) {
                    is Nip55.ResolverOutcome.Signed -> EventJson.decode(outcome.eventJson)
                    Nip55.ResolverOutcome.Rejected -> throw SigningRejectedException()
                    Nip55.ResolverOutcome.Unavailable -> null
                }
            }
        }.getOrElse { if (it is SigningRejectedException) throw it else null }
    }

    override suspend fun nip44Encrypt(peerPubkey: String, plaintext: String): String = nip44(Nip55.Nip44Op.ENCRYPT, plaintext, peerPubkey)
    override suspend fun nip44Decrypt(peerPubkey: String, payload: String): String = nip44(Nip55.Nip44Op.DECRYPT, payload, peerPubkey)

    /** ContentResolver only: null unless the signer has a remembered permission for us; never opens the signer activity. */
    override suspend fun nip44DecryptSilent(peerPubkey: String, payload: String): String? =
        viaResolverText(Nip55.nip44Query(packageName, Nip55.Nip44Op.DECRYPT, payload, peerPubkey, pubkey))

    /** Resolver first (silent once permitted), then the signer activity; no answer either way means the signer has no NIP-44. */
    private suspend fun nip44(op: Nip55.Nip44Op, text: String, peerPubkey: String): String {
        viaResolverText(Nip55.nip44Query(packageName, op, text, peerPubkey, pubkey))?.let { return it }
        val request = Nip55.nip44Request(op, text, peerPubkey, pubkey, packageName, java.util.UUID.randomUUID().toString())
        val (ok, response) = bridge.request(request)
        return when (val outcome = Nip55.parseNip44Response(ok, response)) {
            is Nip55.Outcome.Result -> outcome.value
            Nip55.Outcome.Rejected -> throw SigningRejectedException()
            else -> throw Nip44UnsupportedException()
        }
    }

    /** One-column resolver answer (`result`, older signers: `signature`); null when the signer has no row for us. */
    private suspend fun viaResolverText(q: Nip55.ResolverQuery): String? = withContext(Dispatchers.IO) {
        runCatching {
            resolverQuery(q)?.use { cursor ->
                if (cursor.getColumnIndex("rejected") >= 0) throw SigningRejectedException()
                if (!cursor.moveToFirst()) return@use null
                val idx = cursor.getColumnIndex("result").takeIf { it >= 0 } ?: cursor.getColumnIndex("signature")
                if (idx < 0) null else cursor.getString(idx)?.takeIf { it.isNotEmpty() }
            }
        }.getOrElse { if (it is SigningRejectedException) throw it else null }
    }

    /**
     * NIP-55's resolver example passes `[payload, pubkey, current_user]` as the query's projection;
     * Amber reads it from there. We pass the same list as selectionArgs too, so either reading works.
     * With a null projection Amber answered nothing and every request fell back to the signer activity.
     */
    private fun resolverQuery(q: Nip55.ResolverQuery): android.database.Cursor? {
        val args = q.selectionArgs.toTypedArray()
        return context.contentResolver.query(Uri.parse(q.uri), args, "1", args, null)
    }

    /** The event JSON a NIP-55 signer expects: everything but id and sig, with our pubkey. */
    internal fun unsignedJson(event: UnsignedEvent): String = buildJsonObject {
        put("pubkey", JsonPrimitive(pubkey))
        put("created_at", JsonPrimitive(event.createdAt))
        put("kind", JsonPrimitive(event.kind))
        put("tags", buildJsonArray { event.tags.forEach { tag -> add(buildJsonArray { tag.forEach { add(JsonPrimitive(it)) } }) } })
        put("content", JsonPrimitive(event.content))
    }.toString()

    companion object {
        /** True when an app handling `nostrsigner:` is installed (needs the `<queries>` entry in the manifest). */
        fun isInstalled(context: Context): Boolean {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(Nip55.SCHEME))
            return context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
        }

        fun toIntent(request: Nip55.Request): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(request.uri)).apply {
            request.packageName?.let { `package` = it }
            request.extras.forEach { (k, v) -> putExtra(k, v) }
        }

        fun fromIntent(data: Intent?): Nip55.Response? = data?.let {
            Nip55.Response(
                result = it.getStringExtra("result"),
                event = it.getStringExtra("event"),
                packageName = it.getStringExtra("package"),
                rejected = it.getBooleanExtra("rejected", false),
            )
        }
    }
}
