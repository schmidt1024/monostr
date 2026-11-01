package com.monostr.app.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.monostr.app.R
import com.monostr.nostr.model.LightningInvoice
import java.text.NumberFormat

/**
 * Spec 2026-11-01: a BOLT11 invoice as a card, read locally from the invoice (no network): amount, the payee's
 * description as plain text, «Expired» past its expiry. «Open in wallet» hands it to the user's Lightning wallet; monostr
 * pays nothing itself. A long press copies the invoice.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InvoiceCard(invoice: LightningInvoice, modifier: Modifier = Modifier, now: () -> Long = { System.currentTimeMillis() / 1000 }) {
    val context = LocalContext.current
    val copied = stringResource(R.string.invoice_copied)
    val expired = remember(invoice.raw) { invoice.expired(now()) }
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .combinedClickable(onClick = {}, onLongClick = { copyToClipboard(context, invoice.raw, copied) })
            .padding(12.dp)
            .testTag("invoice-card"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("⚡ " + stringResource(R.string.invoice_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (invoice.network != LightningInvoice.Network.MAINNET) {
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.invoice_testnet), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
        // the button beside the amount, the title above both on the full width (user 2026-11-01)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val sats = invoice.amountSat
                Text(
                    if (sats == null) stringResource(R.string.invoice_any_amount) else stringResource(R.string.invoice_sats, NumberFormat.getIntegerInstance().format(sats)),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 2.dp).testTag("invoice-amount"),
                )
                // the payee's free text: shown as it is, never parsed for links or mentions (spec, answered 2026-11-01)
                invoice.description?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("invoice-description"))
                }
            }
            Spacer(Modifier.width(8.dp))
            if (expired) {
                Text(stringResource(R.string.invoice_expired), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("invoice-expired"))
            } else {
                OutlinedButton(onClick = { openInvoice(context, invoice.raw, copied) }, modifier = Modifier.testTag("invoice-open")) {
                    Text(stringResource(R.string.invoice_open))
                }
            }
        }
    }
}

/** `lightning:` to the user's wallet; without one the invoice is copied (spec 2026-11-01 §3). */
fun openInvoice(context: Context, raw: String, copiedLabel: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("lightning:$raw")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        copyToClipboard(context, raw, copiedLabel)
    }
}
