package com.raijin.app

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import com.raijin.app.skill.SkillInstallService
import com.raijin.app.skill.SkillJsonParser
import com.raijin.app.skill.SkillRunLogEntity
import com.raijin.app.ui.skills.SkillInstallApprovalCard
import com.raijin.app.ui.skills.SkillInstallRejectedCard
import com.raijin.app.ui.skills.SkillRunLogRow
import io.raijin.brain.skills.SkillEgress
import io.raijin.brain.skills.SkillInstaller
import org.junit.Rule
import org.junit.Test

/**
 * SK-2 approval-gate goldens. Snapshot the fully composed cards (title,
 * scrollable content, approve/cancel controls) - exactly what the owner
 * sees inside the Dialog window.
 */
class SkillInstallApprovalPaparazziTest {

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5)

    private fun manifest(payload: String) =
        (SkillJsonParser.parse(payload) as SkillJsonParser.ParseResult.Ok).manifest

    private val goodNight = manifest(
        """
        {
          "id": "good-night", "version": 1, "name": "Good night",
          "triggers": { "phrases": ["good night"] },
          "steps": [
            { "type": "call_tool", "tool": "SET_ALARM", "args": { "time": "6:30", "label": "wake" } },
            { "type": "speak", "text": "Good night, boss" }
          ],
          "requires": ["alarms"], "egress_class": "l0"
        }
        """.trimIndent()
    )

    private val goodNightV2 = manifest(
        """
        {
          "id": "good-night", "version": 2, "name": "Good night",
          "triggers": { "phrases": ["good night"] },
          "steps": [
            { "type": "call_tool", "tool": "SET_ALARM", "args": { "time": "6:30", "label": "wake" } },
            { "type": "speak", "text": "Good night, boss" }
          ],
          "requires": ["alarms", "mic"], "egress_class": "l2"
        }
        """.trimIndent()
    )

    @Test
    fun freshInstallCardShowsControlsAndEgress() {
        val ready = SkillInstallService.PlanOutcome.Ready(
            payloadJson = "{}",
            source = "github:litmaspatra/raijin-skills",
            plan = SkillInstaller.InstallPlan.FreshInstall(
                manifest = goodNight,
                capabilities = listOf("alarms"),
                egress = SkillEgress.L0_LOCAL,
            ),
        )
        paparazzi.snapshot {
            SkillInstallApprovalCard(ready, onApprove = {}, onCancel = {})
        }
    }

    @Test
    fun upgradeCardShowsVersionAndCapabilityDiff() {
        val ready = SkillInstallService.PlanOutcome.Ready(
            payloadJson = "{}",
            source = "https:https://example.com/skill.json",
            plan = SkillInstaller.InstallPlan.Upgrade(
                manifest = goodNightV2,
                fromVersion = 1,
                newCapabilities = listOf("mic"),
                removedCapabilities = emptyList(),
            ),
        )
        paparazzi.snapshot {
            SkillInstallApprovalCard(ready, onApprove = {}, onCancel = {})
        }
    }

    @Test
    fun longContentScrollsAndKeepsControlsVisible() {
        val ready = SkillInstallService.PlanOutcome.Ready(
            payloadJson = "{}",
            source = "github:litmaspatra/raijin-skills",
            plan = SkillInstaller.InstallPlan.FreshInstall(
                manifest = goodNight,
                capabilities = (1..12).map { "capability-$it" },
                egress = SkillEgress.L1_REDACTED,
            ),
        )
        // Top of the scroll region: first capabilities visible, controls pinned.
        paparazzi.snapshot("long_content_top") {
            SkillInstallApprovalCard(ready, onApprove = {}, onCancel = {}, contentScroll = ScrollState(0))
        }
        // Scrolled to the end: last capabilities reachable, controls still pinned.
        paparazzi.snapshot("long_content_end") {
            SkillInstallApprovalCard(ready, onApprove = {}, onCancel = {}, contentScroll = ScrollState(10_000))
        }
    }

    @Test
    fun rejectedCardShowsReasonsAndDismissControl() {
        val rejected = SkillInstallService.PlanOutcome.Rejected(
            listOf("unknown tool 'FLY_TO_MOON' at step 2", "payload sha256 mismatch"),
        )
        paparazzi.snapshot {
            SkillInstallRejectedCard(rejected, onDismiss = {})
        }
    }

    @Test
    fun runLogRowsShowHumanOutcomeLabels() {
        paparazzi.snapshot {
            Column {
                SkillRunLogRow(
                    SkillRunLogEntity(
                        skill_id = "good-night", skill_version = 1,
                        utterance = "okay good night", outcome = "completed",
                        detail = "ok", ran_at = 1_800_000_000_000,
                    )
                )
                SkillRunLogRow(
                    SkillRunLogEntity(
                        skill_id = "morning-brief", skill_version = 2,
                        utterance = "morning brief please", outcome = "failed_at_1",
                        detail = "no clock app", ran_at = 1_800_000_100_000,
                    )
                )
            }
        }
    }
}
