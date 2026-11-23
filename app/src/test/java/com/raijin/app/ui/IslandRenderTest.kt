package com.raijin.app.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.raijin.app.ui.island.IslandComposable
import com.raijin.app.ui.island.IslandState
import com.raijin.app.ui.theme.RaijinTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IslandRenderTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private fun save(name: String) {
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            val file = File("build/screenshots/$name.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    @Test fun compactIdle() {
        compose.setContent { RaijinTheme { IslandComposable(IslandState.Idle, {}, { _, _, _ -> }) } }
        save("island-idle")
    }
    @Test fun largeFontIdle() {
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(2f, 1.6f)) {
                RaijinTheme { IslandComposable(IslandState.Idle, {}, { _, _, _ -> }) }
            }
        }
        save("island-idle-largefont")
    }
    @Test fun listening() {
        compose.setContent { RaijinTheme { IslandComposable(IslandState.Listening, {}, { _, _, _ -> }) } }
        save("island-listening")
    }
    @Test fun expandedAnswer() {
        compose.setContent { RaijinTheme { IslandComposable(IslandState.Done("GST is a tax on goods and services. It combines central and state taxes. Businesses collect it on sales and claim credit for eligible purchases. Rates vary by category. Check the current rate before billing."), {}, { _, _, _ -> }) } }
        save("island-expanded")
    }
    @Test fun undoableChip() {
        compose.setContent {
            RaijinTheme {
                IslandComposable(
                    IslandState.Undoable(
                        label = "Reminder set for call Rahul.",
                        undoToken = "token-fixture",
                        windowMs = 5_000L,
                    ),
                    {},
                    { _, _, _ -> },
                )
            }
        }
        save("island-undoable")
    }
    @Test fun diagnostics() {
        compose.setContent {
            RaijinTheme {
                androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.width(360.dp)) {
                    DiagnosticsSection(listOf(
                        com.raijin.app.diagnostics.DiagResult("apps", "App inventory", true, "109 launchable apps visible"),
                        com.raijin.app.diagnostics.DiagResult("alarms", "Exact alarms", false, "Needed for precise Raijin reminders, not for chat or opening apps. Without it reminders may be late.", optional = true),
                        com.raijin.app.diagnostics.DiagResult("notif", "Notification summaries", false, "Optional. Off means messages cannot be checked, not that your inbox is empty.", optional = true),
                        com.raijin.app.diagnostics.DiagResult("storage", "Cache audit", true, "Only Raijin's own cache. No photos or all-files access needed."),
                    ))
                }
            }
        }
        save("diagnostics")
    }
}
