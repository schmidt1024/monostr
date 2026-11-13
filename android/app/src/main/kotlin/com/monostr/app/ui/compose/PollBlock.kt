package com.monostr.app.ui.compose

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.monostr.app.R
import com.monostr.app.ui.common.GuardedDropdownMenu
import com.monostr.app.ui.theme.Spacing

/** Polls task 6 (mockup poll-compose-2): the choices, the length pickers and «Remove poll», under the note's text. */
@Composable
fun PollBlock(
    poll: PollDraft,
    enabled: Boolean,
    onChoice: (Int, String) -> Unit,
    onAdd: () -> Unit,
    onLength: (Int, Int, Int) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large).padding(Spacing.m).testTag("compose-poll"),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        poll.choices.forEachIndexed { i, text ->
            val label = stringResource(if (i < PollDraft.MIN_CHOICES) R.string.poll_choice else R.string.poll_choice_optional, i + 1)
            OutlinedTextField(
                value = text, onValueChange = { onChoice(i, it) }, enabled = enabled, singleLine = true,
                label = { Text(label) },
                suffix = { Text("${text.codePointCount(0, text.length)}/${PollDraft.MAX_CHOICE_LENGTH}", style = MaterialTheme.typography.labelSmall) },
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().testTag("compose-poll-choice-$i"),
            )
        }
        if (poll.choices.size < PollDraft.MAX_CHOICES) {
            TextButton(onClick = onAdd, enabled = enabled, modifier = Modifier.testTag("compose-poll-add")) { Text("+ " + stringResource(R.string.poll_add_choice)) }
        }
        Text(stringResource(R.string.poll_length), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val capped = poll.days == 7
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            LengthPicker(stringResource(R.string.poll_days), poll.days, 0..7, enabled, "compose-poll-days", Modifier.weight(1f)) { onLength(it, poll.hours, poll.minutes) }
            LengthPicker(stringResource(R.string.poll_hours), poll.hours, 0..23, enabled && !capped, "compose-poll-hours", Modifier.weight(1f)) { onLength(poll.days, it, poll.minutes) }
            LengthPicker(stringResource(R.string.poll_minutes), poll.minutes, 0..59, enabled && !capped, "compose-poll-minutes", Modifier.weight(1f)) { onLength(poll.days, poll.hours, it) }
        }
        TextButton(onClick = onRemove, enabled = enabled, modifier = Modifier.align(Alignment.CenterHorizontally).testTag("compose-poll-remove")) {
            Text(stringResource(R.string.poll_remove), color = MaterialTheme.colorScheme.error)
        }
    }
}

/** A select in the look of an outlined field: label above the value, a menu of [range] on tap. */
@Composable
private fun LengthPicker(label: String, value: Int, range: IntRange, enabled: Boolean, tag: String, modifier: Modifier, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Column(
            Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.38f)
                .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
                .clickable(enabled = enabled, role = Role.DropdownList) { open = true }
                .padding(horizontal = Spacing.m, vertical = Spacing.s).testTag(tag),
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$value", style = MaterialTheme.typography.bodyLarge)
                // the arrow is decoration: a screen reader hears the label, the value and «drop-down list»
                Text(" ▾", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.clearAndSetSemantics {})
            }
        }
        GuardedDropdownMenu(expanded = open && enabled, onDismissRequest = { open = false }) {
            range.forEach { n ->
                DropdownMenuItem(text = { Text(n.toString()) }, onClick = { open = false; onPick(n) }, modifier = Modifier.testTag("$tag-$n"))
            }
        }
    }
}
