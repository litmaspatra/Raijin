package com.raijin.app

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.foundation.layout.Column
import com.raijin.app.memory.MemoryFactEntity
import com.raijin.app.memory.MemoryPatternEntity
import com.raijin.app.ui.memory.AliasRow
import com.raijin.app.ui.memory.FactEditCard
import com.raijin.app.ui.memory.ForgetEverythingCard
import com.raijin.app.ui.memory.MemoryFactRow
import com.raijin.app.ui.memory.PatternRow
import com.raijin.app.ui.memory.WhatIKnowFooter
import org.junit.Rule
import org.junit.Test

/** ME-4 goldens: "What I know about you" rows, edit card, forget-everything. */
class WhatIKnowPaparazziTest {

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5)

    private val fact = MemoryFactEntity(
        id = 3,
        kind = "FACT",
        text = "The sky appears blue from scattered sunlight",
        tier = "COLD",
        createdAt = 1_800_000_000_000,
    )

    private val pattern = MemoryPatternEntity(
        id = 2,
        signature = "action:set_alarm",
        dayPart = "MORNING",
        count = 7,
        firstAt = 1L,
        lastAt = 2L,
    )

    @Test
    fun rowsShowFactsAliasesPatternsWithControls() {
        paparazzi.snapshot {
            Column {
                MemoryFactRow(fact = fact, onEdit = {}, onDelete = {})
                MemoryFactRow(
                    fact = fact.copy(id = 4, kind = "PREFERENCE", text = "prefers window seats on trains", tier = "WARM"),
                    onEdit = {},
                    onDelete = {},
                )
                AliasRow(spokenForm = "zeus", meaning = "opens YouTube", onDelete = {})
                PatternRow(
                    pattern = pattern,
                    description = "You often set alarm in the morning (7 times)",
                    onDelete = {},
                )
                WhatIKnowFooter(onExport = {}, onForgetEverything = {})
            }
        }
    }

    @Test
    fun factEditCardEditsTextAndRetention() {
        paparazzi.snapshot {
            FactEditCard(fact = fact, onSave = { _, _ -> }, onDismiss = {})
        }
    }

    @Test
    fun forgetEverythingCardExplainsScopeAndKeepsEscape() {
        paparazzi.snapshot {
            ForgetEverythingCard(factCount = 42, onConfirm = {}, onDismiss = {})
        }
    }
}
