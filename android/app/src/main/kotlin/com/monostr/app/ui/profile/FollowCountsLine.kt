package com.monostr.app.ui.profile

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.font.FontWeight
import com.monostr.app.R
import com.monostr.app.ui.common.formatCount
import com.monostr.nostr.repo.FollowCounts
import java.text.NumberFormat
import java.util.Locale

/**
 * X-style "12 Following · 3,373 Followers": bold numbers, muted labels; each half only when known. Each half is a link
 * to its list (spec 2026-10-30 follow lists) when [onFollowing] / [onFollowers] are given.
 */
@Composable
fun FollowCountsLine(counts: FollowCounts, modifier: Modifier = Modifier, onFollowing: (() -> Unit)? = null, onFollowers: (() -> Unit)? = null) {
    val locale = LocalConfiguration.current.locales[0]
    val number = SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    val following = counts.following?.let { n -> numberInto("$PLACEHOLDER ${stringResource(R.string.profile_following_label)}", NumberFormat.getIntegerInstance(locale).format(n)) }
    // the template is resolved with a placeholder, so the bold range is the placeholder's, never a guess at where digits are
    val followers = counts.followers?.let { n -> numberInto(pluralStringResource(R.plurals.profile_followers_label, followersQuantity(n), PLACEHOLDER), followersNumber(n, locale)) }
    val text = buildAnnotatedString {
        listOfNotNull(following?.let { it to onFollowing }, followers?.let { it to onFollowers }).forEachIndexed { i, (part, onClick) ->
            val (sentence, range) = part
            if (i > 0) append(" · ")
            val start = length
            // tappable, but it reads as before: no underline, no link colour (user 2026-10-30)
            if (onClick != null) withLink(LinkAnnotation.Clickable(if (part === following) "following" else "followers", TextLinkStyles(SpanStyle())) { onClick() }) { append(sentence) }
            else append(sentence)
            addStyle(number, start + range.first, start + range.last + 1)
        }
    }
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier.testTag("profile-follow-counts"))
}

/** Full number up to four digits (X shows "3,373"), compact above ("12k"). */
internal fun followersNumber(n: Long, locale: Locale): String =
    if (n < 10_000) NumberFormat.getIntegerInstance(locale).format(n) else formatCount(n, locale)

/** The plural case: the number itself, or a round "many" once the line shows a compact "12k" (ru would otherwise decline 10 001 as singular). */
internal fun followersQuantity(n: Long): Int = if (n < 10_000) n.toInt() else 10_000

private const val PLACEHOLDER = "\u0000"

/** [template] with its placeholder replaced by [number], and the range the number occupies (the whole text when there is no placeholder). */
internal fun numberInto(template: String, number: String): Pair<String, IntRange> {
    val at = template.indexOf(PLACEHOLDER)
    if (at < 0) return template to template.indices
    return template.replaceRange(at, at + PLACEHOLDER.length, number) to (at until at + number.length)
}
