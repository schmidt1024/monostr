package com.monostr.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.monostr.app.R

// the thread line runs through the middle of the list card's avatar column: 16 dp card padding + half of the 44 dp avatar
private val LINE_X = 16.dp + 22.dp
private val LINE_WIDTH = 2.dp

/** X's "Show replies" between the root and the direct parent of a long chain (spec 2026-10-16 §3); the thread line runs through it, dashed. */
@Composable
fun ShowRepliesRow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick)
            .drawBehind {
                val x = LINE_X.toPx()
                val dash = 4.dp.toPx()
                drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), LINE_WIDTH.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash)))
            }
            .padding(start = 16.dp + 44.dp + 12.dp, top = 8.dp, bottom = 8.dp)
            .testTag("show-replies"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.conversation_show_replies), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
    }
}

/** A note of the chain that is gone (not found, withdrawn, muted, deleted; spec 2026-10-16 §3): its place stays so the line still connects. */
@Composable
fun UnavailableRow(lineAbove: Boolean, lineBelow: Boolean, modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier.fillMaxWidth()
            .drawBehind {
                val x = LINE_X.toPx()
                val w = LINE_WIDTH.toPx()
                if (lineAbove) drawLine(lineColor, Offset(x, 0f), Offset(x, 10.dp.toPx()), w)
                if (lineBelow) drawLine(lineColor, Offset(x, size.height - 10.dp.toPx()), Offset(x, size.height), w)
            }
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("note-unavailable"),
    ) {
        Text(
            stringResource(R.string.conversation_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(12.dp),
        )
    }
}
