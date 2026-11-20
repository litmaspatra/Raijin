package com.raijin.app.ui.island

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.ide.common.rendering.api.SessionParams.RenderingMode
import org.junit.Rule
import org.junit.Test

/**
 * UI-5: rendered verification of the long-answer card inside the real
 * island container (glass surface + HUD border, 310dp target width).
 *
 * RenderingMode.SHRINK shrinks the canvas to the composable's measured
 * size, so image bounds ARE the card bounds: this verifies the overlay
 * wrap_content geometry (card wraps its content, never fills the screen),
 * not just the text content. A full-screen-height image here means the
 * container fails wrap_content, same as on device.
 */
class LongAnswerSnapshotTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6,
        theme = "Theme.Raijin",
        renderingMode = RenderingMode.SHRINK,
    )

    private val longText =
        "GST is a destination-based consumption tax levied on the supply of goods and services in India. " +
            "It replaced a cascade of central and state levies with one unified tax. " +
            "It has four main slabs: 5, 12, 18 and 28 percent."

    // Just over the 60-char LongAnswer threshold.
    private val shortText =
        "GST has four main slabs in India: 5, 12, 18 and 28 percent today."

    private fun render(text: String, expanded: Boolean) {
        paparazzi.snapshot {
            IslandComposable(
                state = IslandState.LongAnswer(text),
                onTap = {},
                onAction = { _, _, _ -> },
                onDismiss = {},
                initialExpanded = expanded,
            )
        }
    }

    @Test
    fun longAnswerCollapsed() = render(longText, expanded = false)

    @Test
    fun longAnswerExpanded() = render(longText, expanded = true)

    @Test
    fun shortAnswerExpanded() = render(shortText, expanded = true)

    /**
     * Overflowing answer (far beyond the 220dp cap): the "top" frame must
     * show the card capped at the 220dp scroll window (not grown to fit the
     * text), and the "end" frame - after scrolling the hoisted ScrollState
     * to maxValue - must show the end of the answer, proving the full text
     * is reachable by scrolling while "tap to dismiss" stays put below.
     * Scroll drags are consumed by verticalScroll, so scrolling never
     * triggers the dismiss clickable (Compose pointer-input priority);
     * these frames pin the visual half of that contract.
     */
    @Test
    fun overflowingAnswerCapsAndScrollsToEnd() {
        val overflowText = longText + " " + (longText + " ").repeat(8).trim()
        val scrollState = androidx.compose.foundation.ScrollState(0)
        paparazzi.snapshot(name = "overflow_top") {
            IslandComposable(
                state = IslandState.LongAnswer(overflowText),
                onTap = {},
                onAction = { _, _, _ -> },
                onDismiss = {},
                initialExpanded = true,
                scrollState = scrollState,
            )
        }
        kotlinx.coroutines.runBlocking { scrollState.scrollTo(scrollState.maxValue) }
        paparazzi.snapshot(name = "overflow_end") {
            IslandComposable(
                state = IslandState.LongAnswer(overflowText),
                onTap = {},
                onAction = { _, _, _ -> },
                onDismiss = {},
                initialExpanded = true,
                scrollState = scrollState,
            )
        }
    }
}
