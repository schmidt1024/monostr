package com.monostr.app.media

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.math.roundToInt

/**
 * What the media server lets this account upload as video (`GET /limits`). A trusted account gets
 * more than the default; the numbers come from the server, so they are clamped to what the app can
 * sensibly handle before anything uses them.
 */
data class VideoLimits(val maxVideoSeconds: Int, val maxVideoBytes: Long) {
    /** The longest source the app accepts: the encoder's output runs a little over the trimmed length. */
    val maxSourceMs: Long get() = maxVideoSeconds * 1000L - MARGIN_MS

    /** For the "too long" message. */
    val minutes: Int get() = (maxVideoSeconds / 60.0).roundToInt().coerceAtLeast(1)

    companion object {
        const val MARGIN_MS = 4_000L
        const val MIN_SECONDS = 10
        const val MAX_SECONDS = 900
        const val MIN_BYTES = 1_048_576L
        const val MAX_BYTES = 314_572_800L

        /** What every account gets, and what is used when the server cannot be asked. */
        val DEFAULT = VideoLimits(125, 52_428_800L)

        /**
         * The server's JSON; anything missing, malformed or outside the clamps gives null. A number in
         * quotes is not a number here (the decoder would coerce it), so the values are read as JSON primitives.
         */
        fun parse(json: String): VideoLimits? {
            val obj = try {
                Json.parseToJsonElement(json) as? JsonObject
            } catch (e: SerializationException) {
                null
            } ?: return null
            val seconds = obj.long("max_video_seconds") ?: return null
            val bytes = obj.long("max_video_bytes") ?: return null
            if (seconds !in MIN_SECONDS..MAX_SECONDS || bytes !in MIN_BYTES..MAX_BYTES) return null
            return VideoLimits(seconds.toInt(), bytes)
        }

        private fun JsonObject.long(key: String): Long? =
            (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
    }
}

/** A refused signature (cached as DEFAULT by the uploader) is kept apart from a failure (not cached). */
sealed interface LimitsAnswer {
    data class Got(val limits: VideoLimits) : LimitsAnswer
    data object Refused : LimitsAnswer
    data object Failed : LimitsAnswer
}
