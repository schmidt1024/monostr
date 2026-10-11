package com.monostr.app.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast

/** Copies [text]; below Android 13 a toast says [copiedLabel], from 13 on the system confirms a copy itself. */
fun copyToClipboard(context: Context, text: String, copiedLabel: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("", text))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, copiedLabel, Toast.LENGTH_SHORT).show()
}
