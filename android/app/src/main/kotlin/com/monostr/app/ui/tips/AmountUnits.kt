package com.monostr.app.ui.tips

import androidx.compose.runtime.staticCompositionLocalOf
import com.monostr.app.data.AmountUnit

/** The unit amounts read in (Settings → Monero); provided at the app root. */
val LocalAmountUnit = staticCompositionLocalOf { AmountUnit.XMR }
