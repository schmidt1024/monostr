package com.monostr.app.work

import android.content.Context
import java.security.SecureRandom

/**
 * A random secret per installation that the DM notifications put into their tap intent. The launcher activity is
 * exported, so any app can start it with extras of its choice; only an intent carrying this token may open a chat.
 */
object OpenToken {
    private const val PREFS = "open_token"
    private const val KEY = "token"
    @Volatile private var cached: String? = null

    fun get(context: Context): String {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val token = prefs.getString(KEY, null) ?: ByteArray(16).also { SecureRandom().nextBytes(it) }
                .joinToString("") { "%02x".format(it) }
                .also { prefs.edit().putString(KEY, it).commit() }
            cached = token
            return token
        }
    }

    /** Constant time: the comparison must not tell a guessing app how much of the token it got right. */
    fun matches(context: Context, candidate: String?): Boolean =
        candidate != null && java.security.MessageDigest.isEqual(candidate.toByteArray(), get(context).toByteArray())
}
