package com.raijin.app.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.raijin.app.ui.theme.RaijinTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertTrue

/** UI-6 staging render (for owner inspection) plus a send-wiring check. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TypeToRaijinRenderTest {
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

    @Test fun emptyField() {
        compose.setContent { RaijinTheme { TypeToRaijinCard("", {}, {}, {}) } }
        save("type-to-raijin-empty")
    }

    @Test fun withText() {
        compose.setContent { RaijinTheme { TypeToRaijinCard("remind me to call Rahul at 5 pm", {}, {}, {}) } }
        save("type-to-raijin-text")
    }

    @Test fun sendButtonCallsBack() {
        var sent = false
        compose.setContent { RaijinTheme { TypeToRaijinCard("open whatsapp", {}, { sent = true }, {}) } }
        compose.onNodeWithText("Send").performClick()
        assertTrue(sent)
    }
}
