package com.raijin.app.ui.island

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.ide.common.rendering.api.SessionParams.RenderingMode
import org.junit.Rule
import org.junit.Test

/**
 * AP-3: rendered verification of the number-region clarification state.
 *
 * An option-less clarification must render as answer-needed: the question
 * text asking for a country or full number, a single Cancel control, and NO
 * affirmative button (the answer is spoken/typed, never a tap). SHRINK mode
 * keeps the canvas at the card's measured bounds, so the image also pins
 * the overlay wrap geometry.
 */
class NumberRegionSnapshotTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6,
        theme = "Theme.Raijin",
        renderingMode = RenderingMode.SHRINK,
    )

    @Test
    fun numberRegionClarificationShowsSingleCancel() {
        paparazzi.snapshot {
            IslandComposable(
                state = IslandState.NeedsInput(
                    label = "Rahul's number is saved without a usable country code. " +
                        "Say the country it belongs to, or the full number with the country code.",
                    question = "Rahul's number is saved without a usable country code. " +
                        "Say the country it belongs to, or the full number with the country code.",
                    confirmEnabled = false,
                    traceId = "t-wa4",
                    actionId = "t-wa4",
                ),
                onTap = {},
                onAction = { _, _, _ -> },
                onDismiss = {},
                initialExpanded = true,
            )
        }
    }
}
