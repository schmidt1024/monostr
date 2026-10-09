package com.monostr.app.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.monostr.app.R
import com.monostr.app.ui.common.asString

/**
 * Spec 5.2: one tile per attached picture, in the order they will appear in the note - preview,
 * progress while it uploads, a mark when it failed, and a button to take it off. Failures are
 * spelled out below the row, each with "try again".
 */
@Composable
fun AttachmentTiles(attachments: List<Attachment>, enabled: Boolean, onRemove: (Long) -> Unit, onRetry: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            attachments.forEachIndexed { i, a ->
                Box(
                    Modifier.size(84.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).testTag("compose-attachment-$i"),
                ) {
                    AsyncImage(model = a.source, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    if (a.uploading) {
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                progress = { a.progress },
                                color = Color.White,
                                modifier = Modifier.size(32.dp).testTag("compose-attachment-progress-$i"),
                            )
                        }
                    }
                    if (a.error != null) {
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    // the button keeps its full 48 dp touch target; only the dark disc inside is small
                    IconButton(
                        onClick = { onRemove(a.id) },
                        enabled = enabled,
                        modifier = Modifier.align(Alignment.TopEnd).testTag("compose-attachment-remove-$i"),
                    ) {
                        Box(Modifier.size(24.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.compose_attachment_remove), tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
        attachments.forEachIndexed { i, a ->
            a.error?.let { error ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        error.asString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f).testTag("compose-attachment-error-$i"),
                    )
                    TextButton(onClick = { onRetry(a.id) }, enabled = enabled, modifier = Modifier.testTag("compose-attachment-retry-$i")) {
                        Text(stringResource(R.string.upload_retry))
                    }
                }
            }
        }
    }
}
