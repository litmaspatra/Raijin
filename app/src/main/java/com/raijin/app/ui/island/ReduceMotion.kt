package com.raijin.app.ui.island

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * UI-2: Android "Remove animations" (animator duration scale = 0) support.
 *
 * When enabled, the island drops every infinite/spring animation and keeps
 * only static content and snap changes, so motion-sensitive users get the
 * crossfade-only experience the PRD requires.
 */
object ReduceMotion {

    /** True when the system animator duration scale is 0 (animations removed). */
    fun isEnabled(context: Context): Boolean =
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f

    /**
     * Read once per composition site. The island recomposes on every state
     * change, so a mid-session settings change is picked up on the next
     * transition; no observer loop.
     */
    @Composable
    fun rememberIsEnabled(): Boolean {
        val context = LocalContext.current
        return remember { isEnabled(context) }
    }
}
