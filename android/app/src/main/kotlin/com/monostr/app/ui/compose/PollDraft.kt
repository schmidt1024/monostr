package com.monostr.app.ui.compose

/**
 * Polls task 6 (spec 2026-11-12): the poll being written in the composer. Choices are judged on their trimmed
 * text; the length is days/hours/minutes as the three pickers show it.
 */
data class PollDraft(
    val choices: List<String> = listOf("", ""),
    val days: Int = 1,
    val hours: Int = 0,
    val minutes: Int = 0,
) {
    val seconds: Long get() = days * 86_400L + hours * 3_600L + minutes * 60L

    /** The choices as they will be published: trimmed, empty ones dropped. */
    fun filled(): List<String> = choices.map { it.trim() }.filter { it.isNotEmpty() }

    /** 2 to 4 non-blank choices of at most 25 characters (code points), a length from 5 minutes to 7 days. */
    fun valid(): Boolean {
        val f = filled()
        return f.size in MIN_CHOICES..MAX_CHOICES && f.all { it.codePointCount(0, it.length) <= MAX_CHOICE_LENGTH } &&
            seconds in MIN_SECONDS..MAX_SECONDS
    }

    /**
     * Days 0-7, hours 0-23, minutes 0-59; seven days is the end of the range, so hours and minutes are 0 then. With no
     * days and no hours the minutes are at least five: the pickers cannot make a length the send would refuse.
     */
    fun withLength(d: Int, h: Int, m: Int): PollDraft {
        val days = d.coerceIn(0, 7)
        if (days == 7) return copy(days = 7, hours = 0, minutes = 0)
        val hours = h.coerceIn(0, 23)
        val min = if (days == 0 && hours == 0) (MIN_SECONDS / 60).toInt() else 0
        return copy(days = days, hours = hours, minutes = m.coerceIn(min, 59))
    }

    companion object {
        const val MIN_CHOICES = 2
        const val MAX_CHOICES = 4
        const val MAX_CHOICE_LENGTH = 25
        const val MIN_SECONDS = 300L
        const val MAX_SECONDS = 604_800L

        /** [text] cut to at most [MAX_CHOICE_LENGTH] code points (the field refuses more). */
        fun limit(text: String): String =
            if (text.codePointCount(0, text.length) <= MAX_CHOICE_LENGTH) text else text.substring(0, text.offsetByCodePoints(0, MAX_CHOICE_LENGTH))
    }
}
