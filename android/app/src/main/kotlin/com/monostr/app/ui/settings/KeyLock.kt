package com.monostr.app.ui.settings

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.monostr.app.R

/**
 * «Ask for the screen lock before the key is shown» (security review 2026-11-26), off unless the user turns it on: the
 * owner's decision of 2026-10-17 stands, a prompt nobody asked for locked people out of their own key. One switch for
 * the phone, not per account: it guards the hand that holds the phone.
 */
object KeyLock {
    private const val PREFS = "key_lock"
    private const val KEY = "enabled"

    fun enabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun set(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }

    /**
     * Whether the screen lock is asked for. A phone without one asks nothing even with the switch on: taking the lock
     * off the phone needed the lock, and the key must stay reachable.
     */
    fun asks(enabled: Boolean, deviceSecure: Boolean): Boolean = enabled && deviceSecure
}

/** The phone's screen lock, as far as the key gate needs it; a fake in device tests. */
interface ScreenLock {
    fun deviceSecure(context: Context): Boolean

    /** Android 11+: the system prompt with fingerprint or face and the PIN as the way around them; calls [onOk] only on success. */
    fun prompt(activity: Activity, title: String, onOk: () -> Unit)
}

object SystemScreenLock : ScreenLock {
    override fun deviceSecure(context: Context): Boolean =
        runCatching { context.getSystemService(KeyguardManager::class.java).isDeviceSecure }.getOrDefault(false)

    override fun prompt(activity: Activity, title: String, onOk: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) = onOk()
        }
        BiometricPrompt.Builder(activity).setTitle(title)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build().authenticate(CancellationSignal(), activity.mainExecutor, callback)
    }
}

val LocalScreenLock = staticCompositionLocalOf<ScreenLock> { SystemScreenLock }

/**
 * Runs an action behind the screen lock: at once when [KeyLock.asks] says no (or with `always = false` and the switch
 * off), else after the system prompt succeeded. A cancelled or failed prompt runs nothing.
 */
@Composable
fun rememberKeyUnlock(): (always: Boolean, action: () -> Unit) -> Unit {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val lock = LocalScreenLock.current
    val title = stringResource(R.string.settings_show_key_title)
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val current by rememberUpdatedState(pending)
    // Android 8–10 have no prompt that takes the PIN as well: the system's own confirm screen does it there
    val legacy = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val action = current
        pending = null
        if (result.resultCode == Activity.RESULT_OK) action?.invoke()
    }
    return remember(context, activity, lock, title) {
        { always, action ->
            val secure = lock.deviceSecure(context)
            val asks = if (always) secure else KeyLock.asks(KeyLock.enabled(context), secure)
            when {
                !asks || activity == null -> action()
                lock !== SystemScreenLock || Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> lock.prompt(activity, title, action)
                else -> {
                    @Suppress("DEPRECATION")
                    val intent = context.getSystemService(KeyguardManager::class.java).createConfirmDeviceCredentialIntent(title, null)
                    if (intent == null) action() else { pending = action; legacy.launch(intent) }
                }
            }
        }
    }
}
