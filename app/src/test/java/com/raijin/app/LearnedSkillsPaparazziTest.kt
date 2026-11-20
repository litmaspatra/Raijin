package com.raijin.app

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.foundation.layout.Column
import com.raijin.app.skill.SkillEntity
import com.raijin.app.ui.skills.LearnedSkillEditCard
import com.raijin.app.ui.skills.LearnedSkillRow
import org.junit.Rule
import org.junit.Test

/** SK-4 goldens: learned reflexes visible, editable, deletable. */
class LearnedSkillsPaparazziTest {

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5)

    private val workout = SkillEntity(
        id = 7,
        name = "Workout mode",
        pattern = "workout",
        actionType = "ORGANIZE_FILES",
        actionArgsJson = "{}",
        description = "clean storage when I say workout",
        usageCount = 12,
        createdAt = 1_800_000_000_000,
    )

    @Test
    fun learnedReflexRowsShowTriggerActionUsageAndControls() {
        paparazzi.snapshot {
            Column {
                LearnedSkillRow(skill = workout, onEdit = {}, onDelete = {})
                LearnedSkillRow(
                    skill = workout.copy(id = 8, name = "Good night lights", pattern = "good night", usageCount = 0),
                    onEdit = {},
                    onDelete = {},
                )
            }
        }
    }

    @Test
    fun editCardEditsNameAndPhraseOnly() {
        paparazzi.snapshot {
            LearnedSkillEditCard(skill = workout, onSave = { _, _ -> }, onDismiss = {})
        }
    }
}
