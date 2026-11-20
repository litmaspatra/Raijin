package com.raijin.app.hud

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.raijin.app.R
import com.raijin.app.ui.island.IslandComposable
import com.raijin.app.ui.island.IslandState
import com.raijin.app.ui.theme.RaijinTheme
import kotlinx.coroutines.flow.StateFlow

/**
 * Always-on transparent overlay that sits where the system Dynamic Island lives.
 *
 * Positioning & offset:
 *   - Customizable vertical offset from status bar so it can sit comfortably
 *     below system punch-holes or system dynamic island.
 */
class HudOverlay(private val context: Context) {

    private val themedContext: Context = ContextThemeWrapper(context, R.style.Theme_Raijin)

    private var isAttached = false
    private var composeView: ComposeView? = null
    private val wm: WindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val lifecycleOwner = OverlayLifecycleOwner()

    companion object {
        private const val PREFS_NAME = "raijin_hud_prefs"
        private const val KEY_OFFSET_Y = "hud_offset_y"
        const val DEFAULT_OFFSET_DP = 28
    }

    fun attach(
        stateFlow: StateFlow<IslandState>,
        onTap: () -> Unit,
        onAction: (traceId: String, actionId: String, payload: String) -> Unit,
        onDismiss: (() -> Unit)? = null,
    ) {
        if (isAttached) return
        lifecycleOwner.start()

        val view = ComposeView(themedContext).also { cv ->
            cv.setViewTreeLifecycleOwner(lifecycleOwner)
            cv.setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            cv.setContent {
                val state by stateFlow.collectAsState()
                RaijinTheme {
                    IslandComposable(
                        state = state,
                        onTap = onTap,
                        onAction = onAction,
                        onDismiss = onDismiss,
                    )
                }
            }
            composeView = cv
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val offsetDp = prefs.getInt(KEY_OFFSET_Y, DEFAULT_OFFSET_DP)
        val density = context.resources.displayMetrics.density

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = statusBarHeight() + (offsetDp * density).toInt()
        }

        try {
            wm.addView(view, params)
            isAttached = true
        } catch (e: Exception) {
            Log.e("HudOverlay", "Failed adding view to WindowManager: ${e.message}", e)
        }
    }

    fun updateVerticalOffset(offsetDp: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_OFFSET_Y, offsetDp).apply()

        if (isAttached && composeView != null) {
            val density = context.resources.displayMetrics.density
            val params = composeView?.layoutParams as? WindowManager.LayoutParams ?: return
            params.y = statusBarHeight() + (offsetDp * density).toInt()
            try {
                wm.updateViewLayout(composeView, params)
            } catch (e: Exception) {
                Log.w("HudOverlay", "Error updating HUD offset: ${e.message}")
            }
        }
    }

    fun detach() {
        if (!isAttached) return
        lifecycleOwner.stop()
        composeView?.let {
            try { wm.removeViewImmediate(it) } catch (_: Exception) {}
        }
        composeView = null
        isAttached = false
    }

    private fun statusBarHeight(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = wm.currentWindowMetrics.windowInsets
            insets.getInsetsIgnoringVisibility(
                android.view.WindowInsets.Type.statusBars()
            ).top
        } else {
            val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (resId > 0) context.resources.getDimensionPixelSize(resId) else 0
        }
    }

    private inner class OverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateController = SavedStateRegistryController.create(this)

        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

        fun start() {
            savedStateController.performAttach()
            savedStateController.performRestore(null)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        fun stop() {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
    }
}
