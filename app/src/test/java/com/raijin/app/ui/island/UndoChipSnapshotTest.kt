package com.raijin.app.ui.island

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.ide.common.rendering.api.SessionParams.RenderingMode
import org.junit.Rule
import org.junit.Test

/**
 * TM-7: rendered verification of the post-creation undo chip.
 *
 * After a reminder is created, the island shows the green DONE header, the
 * confirmation line, and a single Undo control while the offer is live.
 * SHRINK mode pins the overlay wrap geometry like the other island states.
 */
class UndoChipSnapshotTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6,
        theme = "Theme.Raijin",
        renderingMode = RenderingMode.SHRINK,
    )

    @Test
    fun undoChipShowsConfirmationAndSingleUndoControl() {
        paparazzi.snapshot {
            IslandComposable(
                state = IslandState.Undoable(
                    label = "Reminder set for call Rahul.",
                    undoToken = "token-fixture",
                    windowMs = 5_000L,
                ),
                onTap = {},
                onAction = { _, _, _ -> },
                onDismiss = {},
                initialExpanded = true,
            )
        }
    }
}
