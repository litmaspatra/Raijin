package com.raijin.app.ui

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raijin.app.ui.theme.*

/**
 * EX-2: OEM setup wizard for iQOO/vivo (OriginOS/Funtouch) and other aggressive
 * battery managers. Each row deep-links to the relevant system screen where a
 * known route exists, and falls back to plain instructions otherwise. Status
 * is auto-detected where the platform allows it (overlay, battery).
 */
@Composable
fun OemSetupSection() {
    val context = LocalContext.current
    var tick by remember { mutableStateOf(0) } // bump to re-detect status on resume/tap

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = RaijinSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "KEEP RAIJIN ALIVE (EX-2)",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = RaijinGold,
            )
            Text(
                "iQOO/vivo and similar phones quietly kill background apps. Complete these once so the island and voice loop survive. Detected: ${Build.MANUFACTURER} ${Build.MODEL}.",
                fontSize = 11.sp,
                color = RaijinText,
            )

            SetupRow(
                title = "1. Floating window / overlay",
                status = if (Settings.canDrawOverlays(context)) "DONE" else "REQUIRED",
                done = Settings.canDrawOverlays(context),
                actionLabel = "Open",
            ) {
                openFirst(
                    context,
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
                )
            }

            val batteryOk = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .isIgnoringBatteryOptimizations(context.packageName)
            SetupRow(
                title = "2. Battery: allow background activity",
                status = if (batteryOk) "DONE" else "REQUIRED",
                done = batteryOk,
                actionLabel = "Open",
            ) {
                openFirst(
                    context,
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
                )
            }

            SetupRow(
                title = "3. Autostart",
                status = "MANUAL CHECK",
                done = null,
                actionLabel = "Open",
            ) {
                openFirst(
                    context,
                    // vivo / iQOO iManager autostart
                    componentIntent("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                    componentIntent("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
                    componentIntent("com.iqoo.secure", "com.iqoo.secure.MainActivity"),
                    // ColorOS / MIUI fallbacks
                    componentIntent("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                    componentIntent("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
                )
            }

            SetupRow(
                title = "4. Background pop-ups",
                status = "MANUAL CHECK",
                done = null,
                actionLabel = "Open",
            ) {
                openFirst(
                    context,
                    componentIntent("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.SoftPermissionDetailActivity"),
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
                )
            }

            Text(
                "If a button opens the wrong screen: Settings → Apps → Raijin → enable Autostart, Background pop-ups, and set Battery to Unrestricted.",
                fontSize = 10.sp,
                color = RaijinTextDim,
            )
        }
    }

    // Re-detect status when returning from a settings screen.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

private fun componentIntent(pkg: String, cls: String): Intent =
    Intent().setComponent(ComponentName(pkg, cls))

private fun openFirst(context: Context, vararg intents: Intent) {
    for (intent in intents) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
            // try the next route
        } catch (_: SecurityException) {
            // OEM blocked the deep link; try the next route
        }
    }
}

@Composable
private fun SetupRow(
    title: String,
    status: String,
    done: Boolean?,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 12.sp, color = RaijinBlueLight)
            Text(
                status,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = when (done) {
                    true -> RaijinGreen
                    false -> RaijinRed
                    null -> RaijinGold
                },
            )
        }
        OutlinedButton(onClick = onAction, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
            Text(actionLabel, fontSize = 11.sp, color = RaijinBlueLight)
        }
    }
}
