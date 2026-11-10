package org.ferabrasil.quests.android.ui.privacy

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * app.md §4 `ScreenPrivacy` / C5: sets `FLAG_SECURE` while the calling composable is on screen
 * (no screenshots, screen recording or recents thumbnail). Use on mnemonic, proof preview,
 * review content and wallet screens.
 */
@Composable
fun SecureScreen() {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity) {
        val window = activity.window
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        SecureCount.value++
        onDispose {
            if (--SecureCount.value == 0) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

/** Nested secure screens (e.g. during navigation transitions) keep the flag until the last one leaves. */
private object SecureCount {
    var value = 0
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
