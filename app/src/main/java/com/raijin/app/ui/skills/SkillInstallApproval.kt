package com.raijin.app.ui.skills

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.raijin.app.skill.SkillInstallService
import com.raijin.app.ui.theme.RaijinAmber
import com.raijin.app.ui.theme.RaijinBlack
import com.raijin.app.ui.theme.RaijinBlue
import com.raijin.app.ui.theme.RaijinBlueLight
import com.raijin.app.ui.theme.RaijinGreen
import com.raijin.app.ui.theme.RaijinRed
import com.raijin.app.ui.theme.RaijinSurface
import com.raijin.app.ui.theme.RaijinText
import com.raijin.app.ui.theme.RaijinTextDim
import com.raijin.app.ui.theme.RaijinWhite
import io.raijin.brain.skills.SkillEgress
import io.raijin.brain.skills.SkillInstaller
import io.raijin.brain.skills.SkillManifest

/** Human label for each egress class. */
fun egressLabel(egress: SkillEgress): String = when (egress) {
    SkillEgress.L0_LOCAL -> "On-device only"
    SkillEgress.L1_REDACTED -> "Network, redacted"
    SkillEgress.L2_CONSENT -> "Network, asks each time"
}

/**
 * Owner-facing meaning of each egress class (PRD section 6). Wording stays
 * accurate about the mechanism: skill steps never open their own network
 * connections, but content can leave through other apps and the owner's
 * own accounts, and the privacy-pattern check only catches phone numbers
 * and email addresses.
 */
fun egressRiskText(egress: SkillEgress): String = when (egress) {
    SkillEgress.L0_LOCAL ->
        "Steps can only use on-device tools. Other apps on this phone may still sync their own data as usual."
    SkillEgress.L1_REDACTED ->
        "May hand content to apps or accounts already on this phone. Asks you first if it spots a phone number or email."
    SkillEgress.L2_CONSENT ->
        "May send content off-device as the action's purpose, and asks you each time."
}

/** Human label for a stored run outcome ("completed", "failed_at_N"). */
fun runOutcomeLabel(outcome: String): String =
    if (outcome == "completed") "Completed"
    else if (outcome.startsWith("failed_at_")) "Failed at step ${outcome.removePrefix("failed_at_")}"
    else outcome

/**
 * SK-2 owner approval gate as a fully self-rendered card: title, scrollable
 * content and the approve/cancel controls are all composed here, so the
 * Paparazzi goldens capture exactly what the owner sees. The only platform
 * chrome is the Dialog window dim behind it.
 */
@Composable
fun SkillInstallApprovalDialog(
    ready: SkillInstallService.PlanOutcome.Ready,
    onApprove: () -> Unit,
    onCancel: () -> Unit,
) {
    Dialog(onDismissRequest = onCancel) {
        SkillInstallApprovalCard(ready, onApprove, onCancel)
    }
}

@Composable
fun SkillInstallApprovalCard(
    ready: SkillInstallService.PlanOutcome.Ready,
    onApprove: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    contentScroll: androidx.compose.foundation.ScrollState = rememberScrollState(),
) {
    val alreadyInstalled = ready.plan is SkillInstaller.InstallPlan.AlreadyInstalled
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = RaijinSurface,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                if (alreadyInstalled) "SKILL ALREADY INSTALLED" else "APPROVE SKILL INSTALL",
                color = if (alreadyInstalled) RaijinAmber else RaijinBlueLight,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(contentScroll),
            ) {
                SkillInstallApprovalContent(ready)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onCancel) {
                    Text(if (alreadyInstalled) "OK" else "Cancel", color = RaijinText)
                }
                if (!alreadyInstalled) {
                    Spacer(Modifier.padding(4.dp))
                    Button(
                        onClick = onApprove,
                        colors = ButtonDefaults.buttonColors(containerColor = RaijinBlue, contentColor = RaijinBlack),
                    ) {
                        Text(
                            if (ready.plan is SkillInstaller.InstallPlan.Upgrade) "Approve upgrade" else "Approve install",
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

/** The approval body: identity, source, capabilities and egress risk. */
@Composable
fun SkillInstallApprovalContent(ready: SkillInstallService.PlanOutcome.Ready) {
    val plan = ready.plan
    val manifest: SkillManifest = when (plan) {
        is SkillInstaller.InstallPlan.FreshInstall -> plan.manifest
        is SkillInstaller.InstallPlan.Upgrade -> plan.manifest
        is SkillInstaller.InstallPlan.AlreadyInstalled -> plan.manifest
        is SkillInstaller.InstallPlan.Reject -> error("Reject never reaches the approval dialog")
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(manifest.name, color = RaijinWhite, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(
            "${manifest.id} v${manifest.version}",
            color = RaijinText,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
        Text("Source: ${ready.source}", color = RaijinText, fontSize = 11.sp)
        Text(
            "Integrity checksum verified - publisher not authenticated",
            color = RaijinAmber,
            fontSize = 10.sp,
        )

        when (plan) {
            is SkillInstaller.InstallPlan.FreshInstall -> {
                CapabilityBlock("Capabilities requested", plan.capabilities, RaijinAmber)
                EgressRow(plan.egress)
            }
            is SkillInstaller.InstallPlan.Upgrade -> {
                Text(
                    "Upgrade: v${plan.fromVersion} -> v${manifest.version}",
                    color = RaijinBlue,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
                if (plan.newCapabilities.isNotEmpty()) {
                    CapabilityBlock("New capabilities", plan.newCapabilities, RaijinAmber)
                }
                if (plan.removedCapabilities.isNotEmpty()) {
                    CapabilityBlock("Removed capabilities", plan.removedCapabilities, RaijinGreen)
                }
                EgressRow(plan.manifest.egress)
            }
            is SkillInstaller.InstallPlan.AlreadyInstalled -> {
                Text(
                    "This exact version is already installed. Nothing to do.",
                    color = RaijinText,
                    fontSize = 11.sp,
                )
            }
            is SkillInstaller.InstallPlan.Reject -> Unit
        }
    }
}

@Composable
private fun CapabilityBlock(title: String, capabilities: List<String>, tint: androidx.compose.ui.graphics.Color) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = RaijinWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        if (capabilities.isEmpty()) {
            Text("none", color = RaijinText, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        } else {
            capabilities.forEach { cap ->
                Text(
                    "• $cap",
                    color = tint,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(RaijinBlack.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun EgressRow(egress: SkillEgress) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "Network access: ${egressLabel(egress)}",
            color = RaijinBlueLight,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(egressRiskText(egress), color = RaijinText, fontSize = 11.sp)
    }
}

/** Shown when a payload never reaches approval: parse or plan rejection. */
@Composable
fun SkillInstallRejectedDialog(
    rejected: SkillInstallService.PlanOutcome.Rejected,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        SkillInstallRejectedCard(rejected, onDismiss)
    }
}

@Composable
fun SkillInstallRejectedCard(
    rejected: SkillInstallService.PlanOutcome.Rejected,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    contentScroll: androidx.compose.foundation.ScrollState = rememberScrollState(),
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = RaijinSurface,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "SKILL REJECTED",
                color = RaijinRed,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(contentScroll),
            ) {
                SkillInstallRejectedContent(rejected)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) {
                    Text("OK", color = RaijinText)
                }
            }
        }
    }
}

/** Rejection body: every reason, nothing installed. */
@Composable
fun SkillInstallRejectedContent(rejected: SkillInstallService.PlanOutcome.Rejected) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Nothing was installed. Reasons:", color = RaijinText, fontSize = 11.sp)
        Spacer(Modifier.height(2.dp))
        rejected.reasons.forEach { reason ->
            Text(
                "• $reason",
                color = RaijinWhite,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(RaijinBlack.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}
