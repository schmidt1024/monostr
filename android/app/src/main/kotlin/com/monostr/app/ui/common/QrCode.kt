package com.monostr.app.ui.common

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.monostr.app.R

/**
 * QR code rendered locally with ZXing; used for addresses and `monero:` URIs (spec 5.5, 6.2).
 * Always black on a white frame, in both light and dark mode (spec 4.3).
 */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier, size: Int = 512) {
    val bitmap = remember(text, size) { qrBitmap(text, size) }
    Box(modifier.background(androidx.compose.ui.graphics.Color.White).padding(8.dp)) {
        Image(bitmap = bitmap.asImageBitmap(), contentDescription = stringResource(R.string.qr_content_description))
    }
}

fun qrBitmap(text: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val pixels = IntArray(size * size) { i -> if (matrix.get(i % size, i / size)) Color.BLACK else Color.WHITE }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
}
