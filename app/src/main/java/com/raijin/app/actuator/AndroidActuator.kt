package com.raijin.app.actuator

import android.app.AlarmManager
import android.provider.AlarmClock
import android.app.PendingIntent
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.util.Log
import android.view.KeyEvent
import com.raijin.app.RaijinDatabase
import com.raijin.app.storage.StorageAuditor
import com.raijin.app.permissions.ActionExecutor
import com.raijin.app.permissions.PermissionBroker
import com.raijin.app.reminder.ReminderBroadcastReceiver
import com.raijin.app.reminder.ReminderEntity
import io.raijin.contract.ActionArgs
import io.raijin.contract.ActionResult
import io.raijin.contract.ActionTarget
import io.raijin.contract.ActionType
import io.raijin.contract.ActuatorPort
import io.raijin.contract.DispatchResult
import io.raijin.contract.MessageChannel
import io.raijin.contract.MusicCommand
import io.raijin.contract.UndoAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Concrete Android Actuator implementation for Raijin.
 *
 * Implements [ActuatorPort] from :brain-contract to execute real actions on the device:
 * - App launches
 * - Telephony calls (with optional speaker mode)
 * - SMS & WhatsApp messaging
 * - Exact-alarm reminders persisted to Room
 * - Media & music playback controls
 * - Storage diagnostics and cache cleanup
 * - Android Sharesheet invocation
 */
class AndroidActuator(
    private val context: Context,
    private val permissionBroker: PermissionBroker,
    private val actionExecutor: ActionExecutor,
    private val database: RaijinDatabase,
    /**
     * ME-5: STRICT confirmation mode. When non-null, called before any
     * [REVERSIBLE_ACTIONS] action; returning false denies the action.
     * Irreversible/dangerous actions keep their own separate consents.
     */
    private val confirmReversible: (suspend (ActionType) -> Boolean)? = null,
) : ActuatorPort {

    companion object {
        /** ME-5: reversible actions gated by strict confirmation mode. */
        private val REVERSIBLE_ACTIONS = setOf(
            ActionType.OPEN_APP,
            ActionType.OPEN_APP_SETTINGS,
            ActionType.SET_ALARM,
            ActionType.SET_TIMER,
            ActionType.DISMISS_ALARM,
            ActionType.MUSIC_CONTROL,
            ActionType.MUSIC_FEEDBACK,
            ActionType.CREATE_REMINDER,
            ActionType.CANCEL_REMINDER,
        )

        private const val TAG = "AndroidActuator"
    }

    private val storageAuditor = StorageAuditor(context)

    override suspend fun execute(action: DispatchResult): ActionResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "Executing ActionType: ${action.actionType} [traceId: ${action.traceId}]")
        // ME-3: structured signature only (never args) for the pattern miner.
        runCatching {
            com.raijin.app.RaijinDatabase.getInstance(this@AndroidActuator.context).interactionLogDao()
                .insert(com.raijin.app.memory.InteractionLogEntity(signature = "action:${action.actionType.name}"))
        }.onFailure { Log.w(TAG, "interaction log write failed: ${it.javaClass.simpleName}") }
        if (action.actionType in REVERSIBLE_ACTIONS && confirmReversible != null && !confirmReversible.invoke(action.actionType)) {
            Log.i(TAG, "Strict mode: owner declined ${action.actionType} [traceId: ${action.traceId}]")
            return@withContext ActionResult.DENIED("Declined by owner (strict confirmation mode)")
        }
        try {
            when (action.actionType) {
                ActionType.OPEN_APP -> executeOpenApp(action)
                ActionType.OPEN_APP_SETTINGS -> executeOpenAppSettings(action)
                ActionType.OPEN_DEEP_LINK -> executeOpenDeepLink(action)
                ActionType.CALL_CONTACT -> executeCallContact(action)
                ActionType.SEND_MESSAGE -> executeSendMessage(action)
                ActionType.CREATE_REMINDER -> executeCreateReminder(action)
                ActionType.SET_ALARM -> executeSetAlarm(action)
                ActionType.SET_TIMER -> executeSetTimer(action)
                ActionType.CANCEL_REMINDER -> executeCancelReminder(action)
                ActionType.LIST_REMINDERS -> executeListReminders()
                ActionType.DISMISS_ALARM -> executeDismissAlarm(action)
                ActionType.MUSIC_CONTROL -> executeMusicControl(action)
                ActionType.MUSIC_FEEDBACK -> executeMusicFeedback(action)
                // Disabled this release: file deletion is out of release
                // scope (PRD ST-x is future work). Fails closed with an
                // unsupported message; no deletion path is reached.
                ActionType.DELETE_FILES ->
                    ActionResult.FAILED("Unsupported action in this release: file deletion is disabled")
                ActionType.FIND_FILE,
                ActionType.ORGANIZE_FILES -> executeStorageManagement(action)
                ActionType.SHARE_FILE,
                ActionType.SHARE_CONTACT -> executeShare(action)
                ActionType.WRITE_VAULT_NOTE -> executeWriteVaultNote(action)
                ActionType.NONE,
                ActionType.CHAT_REPLY -> ActionResult.EXECUTED()
                else -> ActionResult.FAILED("Unsupported action type: ${action.actionType}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Actuation failed with exception: ${e.message}", e)
            ActionResult.FAILED("Execution error: ${e.message}")
        }
    }

    // ── App Launching ─────────────────────────────────────────────────────────

    private fun executeOpenApp(action: DispatchResult): ActionResult {
        val target = action.targets.filterIsInstance<ActionTarget.App>().firstOrNull()
            ?: return ActionResult.FAILED("Missing target app")

        val appQuery = (target.displayName ?: target.ref).lowercase().trim()
        val pkg = permissionBroker.resolveAppPackage(appQuery, target.spokenAlternatives)

        if (pkg != null) {
            val launchIntent = (context.packageManager.getLaunchIntentForPackage(pkg)
                ?: context.packageManager.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg), 0
                ).firstOrNull()?.let { info ->
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        .setComponent(android.content.ComponentName(pkg, info.activityInfo.name))
                })?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (launchIntent != null) {
                return try {
                    context.startActivity(launchIntent)
                    Log.i(TAG, "Launched app package: $pkg")
                    ActionResult.EXECUTED()
                } catch (e: Exception) {
                    Log.w(TAG, "startActivity failed for $pkg: ${e.message}")
                    ActionResult.FAILED(
                        "Could not open '${target.displayName ?: appQuery}': ${e.message}." + backgroundLaunchHint()
                    )
                }
            } else {
                Log.w(TAG, "Resolved package $pkg but found no launchable activity for '$appQuery'")
            }
        }

        // Generic Intent Fallbacks for system utilities
        val fallbackIntent = when {
            appQuery.contains("camera") -> {
                Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            appQuery.contains("setting") -> {
                Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            appQuery.contains("calculat") -> {
                val calcPkgs = listOf(
                    "com.google.android.calculator",
                    "com.sec.android.app.popupcalculator",
                    "com.miui.calculator",
                    "com.coloros.calculator",
                    "com.android.calculator2",
                    "com.oneplus.calculator",
                )
                calcPkgs.firstNotNullOfOrNull { context.packageManager.getLaunchIntentForPackage(it) }?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            appQuery.contains("browser") || appQuery.contains("internet") -> {
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            appQuery.contains("gallery") || appQuery.contains("photo") -> {
                Intent(Intent.ACTION_VIEW).apply {
                    type = "image/*"
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            appQuery.contains("dialer") || appQuery.contains("phone") -> {
                Intent(Intent.ACTION_DIAL).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            appQuery.contains("clock") || appQuery.contains("alarm") -> {
                Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            appQuery.contains("calendar") -> {
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            else -> null
        }

        if (fallbackIntent != null) {
            return try {
                context.startActivity(fallbackIntent)
                Log.i(TAG, "Launched app via fallback intent for query: $appQuery")
                ActionResult.EXECUTED()
            } catch (e: Exception) {
                Log.w(TAG, "Fallback intent failed: ${e.message}")
                ActionResult.FAILED("Could not open '$appQuery': ${e.message}")
            }
        }

        Log.w(TAG, "No installed app matched '$appQuery'")
        return ActionResult.FAILED("Could not find installed app matching '$appQuery'")
    }

    /**
     * Some OEMs (notably vivo/iQOO) silently block activity launches from a
     * foreground service unless the user grants a manufacturer-specific
     * background-launch permission. Android throws nothing in that case, so we
     * can only surface the likely cause and its settings route, never claim the
     * app definitely opened or definitely was blocked.
     */
    private fun backgroundLaunchHint(): String {
        val maker = (android.os.Build.MANUFACTURER ?: "").lowercase()
        val brand = (android.os.Build.BRAND ?: "").lowercase()
        return if ("vivo" in maker || "iqoo" in maker || "iqoo" in brand || "vivo" in brand) {
            " If nothing opened, this phone may be blocking background launches: " +
                "enable Settings > Apps > Raijin > Display pop-up windows while running in background, then retry."
        } else {
            ""
        }
    }

    // ── Phone Calls ───────────────────────────────────────────────────────────

    private suspend fun executeCallContact(action: DispatchResult): ActionResult {
        if (!permissionBroker.canCallPhone()) {
            return ActionResult.DENIED("CALL_PHONE permission not granted")
        }

        val target = action.targets.filterIsInstance<ActionTarget.Contact>().firstOrNull()
            ?: return ActionResult.FAILED("Missing target contact")

        val contactQuery = target.displayName ?: target.ref
        // Use the exact number selected and confirmed in the brain. Looking
        // up the display name again can choose another same-name contact.
        var phoneNumber = target.ref.takeIf { it.startsWith("contact:") }
            ?.removePrefix("contact:")?.takeIf { it.matches(Regex("[+0-9() -]{7,}")) }
            ?: permissionBroker.findContactPhoneNumber(contactQuery)

        // Fallback: check local memory facts if phone number not in contacts
        if (phoneNumber == null) {
            val memories = database.memoryDao().findEntities(contactQuery, 3)
            for (m in memories) {
                val digits = m.text.replace("[^0-9+]".toRegex(), "")
                if (digits.length >= 7) {
                    phoneNumber = digits
                    break
                }
            }
        }

        // Fallback: If still no phone number, launch dialer with contactQuery primed
        if (phoneNumber == null) {
            val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(contactQuery)}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(dialIntent)
            return ActionResult.EXECUTED()
        }

        val callArgs = action.args as? ActionArgs.Call
        if (callArgs?.speaker == true) {
            try {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                audioManager.isSpeakerphoneOn = true
                Log.d(TAG, "Speakerphone mode enabled for call")
            } catch (e: Exception) {
                Log.w(TAG, "Could not force speakerphone: ${e.message}")
            }
        }

        return try {
            val uri = Uri.parse("tel:${Uri.encode(phoneNumber)}")
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            if (telecom != null) {
                telecom.placeCall(uri, Bundle())
            } else {
                val intent = Intent(Intent.ACTION_CALL, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
            ActionResult.EXECUTED()
        } catch (e: Exception) {
            ActionResult.FAILED("Call placement failed: ${e.message}")
        }
    }

    // ── AP-3: deep links into known apps ────────────────────────────────────

    private fun executeOpenDeepLink(action: DispatchResult): ActionResult {
        val args = action.args as? ActionArgs.DeepLink
            ?: return ActionResult.FAILED("Missing deep link arguments")
        return when (args.app) {
            "maps" -> openMapsSearch(args.target)
            "whatsapp" -> openWhatsAppChat(action, args.target)
            else -> ActionResult.FAILED("No deep link known for app: ${args.app}")
        }
    }

    private fun openMapsSearch(query: String): ActionResult {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(io.raijin.brain.DeepLinks.mapsSearchUri(query))).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.packageManager.getPackageInfo("com.google.android.apps.maps", 0)
                setPackage("com.google.android.apps.maps")
            } catch (_: PackageManager.NameNotFoundException) {
                // No Maps app: any geo: handler may take it.
            }
        }
        return try {
            context.startActivity(intent)
            ActionResult.EXECUTED()
        } catch (e: Exception) {
            ActionResult.FAILED("No app can show a map search: ${e.message}")
        }
    }

    /** Device region (ISO alpha-2 uppercase) from SIM, then locale; null when unknown. */
    private fun deviceRegionIso(): String? {
        val sim = (context.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager)
            ?.simCountryIso?.trim()?.uppercase()
        if (!sim.isNullOrEmpty()) return sim
        return java.util.Locale.getDefault().country.trim().uppercase().takeIf { it.length == 2 }
    }

    private fun openWhatsAppChat(action: DispatchResult, contactName: String): ActionResult {
        val target = action.targets.filterIsInstance<ActionTarget.Contact>().firstOrNull()
        // Same resolution order as messaging: the confirmed ref first, a fresh
        // lookup by name only as fallback.
        val phoneNumber = target?.ref?.takeIf { it.startsWith("contact:") }
            ?.removePrefix("contact:")?.takeIf { it.matches(Regex("[+0-9() -]{7,}")) }
            ?: permissionBroker.findContactPhoneNumber(target?.displayName ?: contactName)
            ?: return ActionResult.FAILED("No phone number found for $contactName")
        // Region comes from device metadata (SIM, then locale) - never assumed.
        // The brain normally clarifies ambiguity before this point; this is
        // the honest fallback, never a guessed country.
        val regionIso = deviceRegionIso()
        val uri = io.raijin.brain.DeepLinks.whatsAppChatUri(phoneNumber, regionIso)
            ?: return ActionResult.FAILED(
                "I can't tell which country $contactName's number belongs to. " +
                    "Tell me the country or the full number with the country code."
            )
        try {
            context.packageManager.getPackageInfo("com.whatsapp", 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return ActionResult.FAILED("WhatsApp is not installed")
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            setPackage("com.whatsapp")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            ActionResult.EXECUTED()
        } catch (e: Exception) {
            ActionResult.FAILED("Could not open WhatsApp chat: ${e.message}")
        }
    }

    // ── Messaging (SMS & WhatsApp) ────────────────────────────────────────────

    private suspend fun executeSendMessage(action: DispatchResult): ActionResult {
        val target = action.targets.filterIsInstance<ActionTarget.Contact>().firstOrNull()
            ?: return ActionResult.FAILED("Missing target contact for message")

        val msgArgs = action.args as? ActionArgs.Message
            ?: return ActionResult.FAILED("Missing message content arguments")

        val contactQuery = target.displayName ?: target.ref
        // Use the exact number selected and confirmed in the brain. Looking
        // up the display name again can choose another same-name contact.
        var phoneNumber = target.ref.takeIf { it.startsWith("contact:") }
            ?.removePrefix("contact:")?.takeIf { it.matches(Regex("[+0-9() -]{7,}")) }
            ?: permissionBroker.findContactPhoneNumber(contactQuery)

        // Fallback: check local memory facts if phone number not in contacts
        if (phoneNumber == null) {
            val memories = database.memoryDao().findEntities(contactQuery, 3)
            for (m in memories) {
                val digits = m.text.replace("[^0-9+]".toRegex(), "")
                if (digits.length >= 7) {
                    phoneNumber = digits
                    break
                }
            }
        }

        // Fallback: If still no phone number, launch SMS composer with text primed
        if (phoneNumber == null) {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(contactQuery)}")).apply {
                putExtra("sms_body", msgArgs.text)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return ActionResult.EXECUTED()
        }

        return when (msgArgs.channel) {
            MessageChannel.WHATSAPP -> {
                when (val result = actionExecutor.execute("whatsapp_message", mapOf("phone_number" to phoneNumber, "message" to msgArgs.text))) {
                    is ActionExecutor.Result.Success -> ActionResult.EXECUTED()
                    is ActionExecutor.Result.Denied -> ActionResult.DENIED(result.reason)
                    is ActionExecutor.Result.Failure -> ActionResult.FAILED(result.error)
                }
            }
            MessageChannel.SMS,
            MessageChannel.AUTO -> {
                sendSmsOrFallback(phoneNumber, msgArgs.text)
            }
            else -> sendSmsOrFallback(phoneNumber, msgArgs.text)
        }
    }

    private fun sendSmsOrFallback(phoneNumber: String, text: String): ActionResult {
        return if (permissionBroker.canSendSms()) {
            try {
                val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }
                val parts = smsManager.divideMessage(text)
                if (parts.size > 1) {
                    smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
                } else {
                    smsManager.sendTextMessage(phoneNumber, null, text, null, null)
                }
                Log.i(TAG, "SMS successfully dispatched to $phoneNumber")
                ActionResult.EXECUTED()
            } catch (e: Exception) {
                ActionResult.FAILED("SMS delivery failed: ${e.message}")
            }
        } else {
            // Intent fallback if background SMS permission is absent
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(phoneNumber)}")).apply {
                putExtra("sms_body", text)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ActionResult.EXECUTED()
        }
    }

    // ── Reminders & Alarms ────────────────────────────────────────────────────

    private suspend fun executeCreateReminder(action: DispatchResult): ActionResult {
        val args = action.args as? ActionArgs.Reminder
            ?: return ActionResult.FAILED("Missing reminder arguments")

        val triggerEpochMs = if (args.triggerAtEpochMillis > 0) {
            args.triggerAtEpochMillis
        } else {
            System.currentTimeMillis() + 60_000L // default 1 min
        }

        val reminder = ReminderEntity(
            text = args.title,
            triggerTime = triggerEpochMs / 1000,
            recurrenceRule = args.recurrenceRule,
            condition = args.condition,
            // TM-9: a watched condition arms AUTO cancellation on reply.
            conditionWatchMode = if (args.conditionWatch != null) "AUTO" else "NONE",
            conditionContact = args.conditionWatch?.contactName,
            createdAt = System.currentTimeMillis() / 1000,
        )

        val id = database.reminderDao().upsert(reminder)

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderBroadcastReceiver::class.java).apply {
            setAction(ReminderBroadcastReceiver.ACTION_FIRE)
            putExtra(ReminderBroadcastReceiver.EXTRA_REMINDER_ID, id)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // TM-4: shared helper - falls back to an inexact alarm when the
        // revocable exact-alarm permission is denied instead of crashing.
        ReminderBroadcastReceiver.scheduleAlarmCompat(alarmManager, triggerEpochMs, pendingIntent)

        Log.i(TAG, "Scheduled reminder '$args.title' for epoch $triggerEpochMs")
        // TM-7: creation is reversible within the undo window - delete the row
        // and cancel the alarm if the owner taps Undo on the confirmation chip.
        return ActionResult.EXECUTED(undoAction = UndoAction.DeleteReminder(id))
    }

    /**
     * TM-7: reverse an action that reported an undo offer. Unknown or
     * unsupported undo kinds fail honestly instead of pretending.
     */
    override suspend fun undo(action: UndoAction): ActionResult = when (action) {
        is UndoAction.DeleteReminder -> {
            val dao = database.reminderDao()
            val existing = dao.findById(action.reminderId)
                ?: return ActionResult.FAILED("Reminder already gone")
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, ReminderBroadcastReceiver::class.java).apply {
                setAction(ReminderBroadcastReceiver.ACTION_FIRE)
                putExtra(ReminderBroadcastReceiver.EXTRA_REMINDER_ID, action.reminderId)
            }
            val pi = PendingIntent.getBroadcast(
                context, action.reminderId.toInt(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            alarmManager.cancel(pi)
            pi.cancel()
            dao.markFired(action.reminderId)
            Log.i(TAG, "Undo: cancelled reminder ${action.reminderId} ('${existing.text}')")
            ActionResult.EXECUTED("Undone: '${existing.text}' cancelled.")
        }
    }

    // ── TM-2/TM-3: system Clock alarms and timers ─────────────────────────────

    /**
     * TM-2: hand the alarm to the system Clock app (normal SET_ALARM
     * permission, declared in the manifest). EXTRA_SKIP_UI asks the clock app
     * to set it silently; OEM clocks may ignore that and show their UI with
     * the alarm prefilled - acceptable per PRD, and the catch reports an
     * honest failure if the OEM blocks the background activity start.
     */
    private fun executeSetAlarm(action: DispatchResult): ActionResult {
        val args = action.args as? ActionArgs.Alarm
            ?: return ActionResult.FAILED("Missing alarm arguments")
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, args.hour)
                putExtra(AlarmClock.EXTRA_MINUTES, args.minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, args.label ?: "Raijin alarm")
                putExtra(AlarmClock.EXTRA_SKIP_UI, args.skipUi)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.i(TAG, "Alarm handed to Clock app for %02d:%02d".format(args.hour, args.minute))
            ActionResult.EXECUTED()
        } catch (e: Exception) {
            Log.e(TAG, "Clock app refused the alarm intent: ${e.message}", e)
            ActionResult.FAILED("Could not open the Clock app to set the alarm")
        }
    }

    /** TM-3: countdown timer via the system Clock app. */
    private fun executeSetTimer(action: DispatchResult): ActionResult {
        val args = action.args as? ActionArgs.Timer
            ?: return ActionResult.FAILED("Missing timer arguments")
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, args.lengthSeconds)
                putExtra(AlarmClock.EXTRA_MESSAGE, args.label ?: "Raijin timer")
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.i(TAG, "Timer handed to Clock app for ${args.lengthSeconds}s")
            ActionResult.EXECUTED()
        } catch (e: Exception) {
            Log.e(TAG, "Clock app refused the timer intent: ${e.message}", e)
            ActionResult.FAILED("Could not open the Clock app to set the timer")
        }
    }

    /**
     * AP-3: open the app's system settings page (application details).
     * Resolves the spoken name through the same inventory path as OPEN_APP.
     */
    private fun executeOpenAppSettings(action: DispatchResult): ActionResult {
        val target = action.targets.filterIsInstance<ActionTarget.App>().firstOrNull()
            ?: return ActionResult.FAILED("Missing target app")
        val appQuery = (target.displayName ?: target.ref).lowercase().trim()
        val pkg = permissionBroker.resolveAppPackage(appQuery, target.spokenAlternatives)
            ?: return ActionResult.FAILED("Could not find an app named ${target.displayName ?: target.ref}")
        return try {
            val intent = Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:$pkg"),
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(intent)
            ActionResult.EXECUTED()
        } catch (e: Exception) {
            Log.e(TAG, "Could not open settings for $pkg: ${e.message}", e)
            ActionResult.FAILED("Could not open settings for ${target.displayName ?: pkg}")
        }
    }

    // ── TM-7: cancel / list reminders, dismiss alarms ────────────────────────

    private suspend fun executeCancelReminder(action: DispatchResult): ActionResult {
        val args = action.args as? ActionArgs.ReminderQuery
            ?: return ActionResult.FAILED("Missing reminder query")
        val dao = database.reminderDao()
        val matches = dao.findAllUnfired().filter { r ->
            val timeOk = if (args.hour != null) {
                val t = r.triggerTime ?: return@filter false
                val zdt = java.time.Instant.ofEpochSecond(t).atZone(java.time.ZoneId.systemDefault())
                zdt.hour == args.hour && (args.minute == null || zdt.minute == args.minute)
            } else true
            val titleOk = args.titleFragment?.let { frag ->
                r.text.contains(frag, ignoreCase = true)
            } ?: true
            timeOk && titleOk
        }
        if (matches.isEmpty()) {
            speak("No matching pending reminder found.")
            return ActionResult.FAILED("No matching pending reminder")
        }
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val names = mutableListOf<String>()
        for (r in matches) {
            val intent = Intent(context, ReminderBroadcastReceiver::class.java).apply {
                setAction(ReminderBroadcastReceiver.ACTION_FIRE)
                putExtra(ReminderBroadcastReceiver.EXTRA_REMINDER_ID, r.id)
            }
            val pi = PendingIntent.getBroadcast(
                context, r.id.toInt(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            alarmManager.cancel(pi)
            pi.cancel()
            dao.markFired(r.id)
            names += r.text
        }
        speak("Cancelled ${matches.size} reminder(s): ${names.joinToString(", ")}.")
        return ActionResult.EXECUTED()
    }

    private suspend fun executeListReminders(): ActionResult {
        val unfired = database.reminderDao().findAllUnfired()
            .filter { it.triggerTime != null }
            .sortedBy { it.triggerTime }
        if (unfired.isEmpty()) {
            speak("You have no pending reminders.")
            return ActionResult.EXECUTED()
        }
        val fmt = java.time.format.DateTimeFormatter.ofPattern("EEE h:mm a")
        val lines = unfired.take(5).map { r ->
            val zdt = java.time.Instant.ofEpochSecond(r.triggerTime!!).atZone(java.time.ZoneId.systemDefault())
            "${r.text} at ${zdt.format(fmt)}"
        }
        val more = if (unfired.size > 5) " And ${unfired.size - 5} more." else ""
        speak("You have ${unfired.size} pending reminder(s). ${lines.joinToString(". ")}.$more")
        return ActionResult.EXECUTED()
    }

    /** TM-7: best-effort dismiss of a matching upcoming alarm (Clock app may ignore it). */
    private fun executeDismissAlarm(action: DispatchResult): ActionResult {
        val args = action.args as? ActionArgs.Alarm
            ?: return ActionResult.FAILED("Missing alarm arguments")
        return try {
            val intent = Intent(AlarmClock.ACTION_DISMISS_ALARM).apply {
                putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_TIME)
                putExtra(AlarmClock.EXTRA_HOUR, args.hour)
                putExtra(AlarmClock.EXTRA_MINUTES, args.minute)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ActionResult.EXECUTED()
        } catch (e: Exception) {
            Log.e(TAG, "Clock app refused the dismiss intent: ${e.message}", e)
            ActionResult.FAILED("Could not ask the Clock app to dismiss the alarm")
        }
    }

    private fun speak(text: String) {
        com.raijin.app.permissions.ActionExecutor.tts?.speak(text)
    }

    // ── Music Playback Control ────────────────────────────────────────────────

    private val mediaSessionCommander: com.raijin.app.media.MediaSessionCommander by lazy {
        com.raijin.app.media.MediaSessionCommander(context)
    }

    private val musicTasteStore: com.raijin.app.media.MusicTasteStore by lazy {
        com.raijin.app.media.MusicTasteStore(java.io.File(context.filesDir, "music_taste.json"))
    }

    /**
     * MU-8: tag the currently playing track with the owner's feedback. The
     * track is read from the active media session (Raijin's own player
     * publishes one too), never guessed.
     */
    private suspend fun executeMusicFeedback(action: DispatchResult): ActionResult {
        val args = action.args as? ActionArgs.Feedback
            ?: return ActionResult.FAILED("Missing music feedback arguments")
        val track = try {
            mediaSessionCommander.currentTrack()
        } catch (e: com.raijin.app.media.MediaSessionCommander.ListenerUnavailableException) {
            return ActionResult.FAILED(
                "I can't see what's playing until notification access is on for Raijin - it is how I read the active player.",
            )
        } ?: return ActionResult.FAILED("I don't see anything playing right now, so I can't tag it.")
        musicTasteStore.recordMusicEvent(
            io.raijin.contract.MusicEvent(
                type = com.raijin.app.media.MediaSessionLogic.feedbackEventType(args.sentiment),
                track = track.first,
                artist = track.second,
                source = io.raijin.contract.MusicSource.UNKNOWN,
                atEpochMillis = System.currentTimeMillis(),
            ),
        )
        Log.i(TAG, "Taste feedback ${args.sentiment} recorded for ${track.first}")
        return ActionResult.EXECUTED(
            com.raijin.app.media.MediaSessionLogic.feedbackMessage(args.sentiment, track.first, track.second),
        )
    }

    private suspend fun executeMusicControl(action: DispatchResult): ActionResult {
        val args = action.args as? ActionArgs.Music
            ?: return ActionResult.FAILED("Missing music command arguments")

        if (args.command == MusicCommand.PLAY_SOMETHING) {
            // MU-7: taste-weighted pick from the local library (Tier 1).
            // The pick uses the on-device taste model only; nothing leaves
            // the phone. An empty library fails honestly with guidance.
            val index = com.raijin.app.media.LocalMusicProvider.index(context)
            if (index.size == 0) {
                return ActionResult.FAILED(
                    "I don't see any songs on this phone yet. Say play followed by a song or artist and I'll try your streaming app.",
                )
            }
            val events = musicTasteStore.recentEvents(com.raijin.app.media.MusicTasteStore.MAX_EVENTS)
            val tracks = index.all()
            val pick = io.raijin.brain.MusicTasteSampler.pick(
                candidates = tracks.map {
                    io.raijin.brain.MusicTasteSampler.Candidate(it.contentUri, it.title, it.artist)
                },
                events = events,
                nowEpochMillis = System.currentTimeMillis(),
            ) ?: return ActionResult.FAILED("I couldn't pick a song right now.")
            val track = tracks.first { it.contentUri == pick.id }
            com.raijin.app.media.RaijinPlayerService.playTrack(context, track)
            Log.i(TAG, "MU-7 taste pick: ${track.title} (library ${index.size}, events ${events.size})")
            return ActionResult.EXECUTED(
                "Picking ${track.title}" +
                    (if (track.artist.isBlank()) "." else " by ${track.artist}."),
            )
        }

        val playQuery = args.query
        if (args.command == MusicCommand.PLAY_QUERY && !playQuery.isNullOrBlank()) {
            // MU-1 Tier 1: a confident local-library match plays in Raijin's
            // own player - deterministic and instant. Anything weaker falls
            // through to Tier 2 (system play-from-search, streaming app).
            val localMatch = com.raijin.app.media.LocalMusicProvider.index(context).bestMatch(playQuery)
            if (localMatch != null) {
                com.raijin.app.media.RaijinPlayerService.playTrack(context, localMatch.track)
                Log.i(TAG, "Tier-1 local play: ${localMatch.track.title} (score ${localMatch.score})")
                return ActionResult.EXECUTED(
                    "Playing ${localMatch.track.title}" +
                        (if (localMatch.track.artist.isNotBlank()) " by ${localMatch.track.artist}." else "."),
                )
            }
            val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                putExtra(SearchManager.QUERY, playQuery)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return ActionResult.EXECUTED()
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // MU-4: volume steps act on the music stream directly - no media
        // session or key-event routing needed, so they always work.
        when (args.command) {
            MusicCommand.VOLUME_UP, MusicCommand.VOLUME_DOWN -> {
                val direction = if (args.command == MusicCommand.VOLUME_UP) {
                    AudioManager.ADJUST_RAISE
                } else {
                    AudioManager.ADJUST_LOWER
                }
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
                Log.d(TAG, "Adjusted music volume: $direction")
                return ActionResult.EXECUTED()
            }
            // Now-playing reads the active player's MediaController session
            // (honest failure when none is visible or access is off).
            MusicCommand.NOW_PLAYING -> return mediaSessionCommander.nowPlaying()
            // The framework session API exposes no repeat control (only
            // media3/compat do), and media keys cannot express it - repeat
            // becomes real with Raijin's own Media3 player (MU-1).
            MusicCommand.REPEAT ->
                return ActionResult.FAILED(
                    "Repeat needs Raijin's own music player, which is still being built. I can pause, skip, or change the volume meanwhile.",
                )
            else -> Unit
        }

        // MU-2: prefer the active player's own session for transport; fall
        // back to media-key dispatch when no live session is visible (e.g.
        // notification access off, or the player publishes no session).
        if (args.command != MusicCommand.PLAY_QUERY && mediaSessionCommander.transport(args.command)) {
            Log.d(TAG, "Transport routed via MediaController session: ${args.command}")
            return ActionResult.EXECUTED()
        }

        val keyCode = when (args.command) {
            MusicCommand.PLAY, MusicCommand.RESUME -> KeyEvent.KEYCODE_MEDIA_PLAY
            MusicCommand.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
            MusicCommand.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            MusicCommand.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            MusicCommand.PLAY_QUERY -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            // Unreachable: handled above; kept so the when stays exhaustive.
            MusicCommand.VOLUME_UP, MusicCommand.VOLUME_DOWN,
            MusicCommand.REPEAT, MusicCommand.NOW_PLAYING,
            MusicCommand.PLAY_SOMETHING -> KeyEvent.KEYCODE_UNKNOWN
        }

        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        Log.d(TAG, "Dispatched media key code: $keyCode")
        return ActionResult.EXECUTED()
    }

    // ── Storage Management ────────────────────────────────────────────────────

    private fun executeStorageManagement(action: DispatchResult): ActionResult {
        val fileTargets = action.targets.filterIsInstance<ActionTarget.File>()
        if (fileTargets.isNotEmpty()) {
            // Execution boundary: user-file deletion is disabled this release,
            // for EVERY action type routed here (FIND_FILE/ORGANIZE_FILES
            // included - a find must never delete). Restore with PRD ST-6.
            return ActionResult.FAILED("File deletion is disabled in this release")
        }

        // Ambient approved junk & cache cleaning via StorageAuditor
        val freedBytes = storageAuditor.cleanApproved(setOf("internal_cache", "external_cache", "logs_cache"))
        Log.i(TAG, "Storage cleaned: ${StorageAuditor.formatBytes(freedBytes)} approved junk pruned. User files safe.")
        return ActionResult.EXECUTED()
    }

    // ── Sharing ───────────────────────────────────────────────────────────────

    /**
     * OB-1: write a note into the owner-configured Obsidian vault ONLY.
     * Refuses when the vault root is unset (never guesses a folder), when the
     * resolved path escapes the root, or when create-only would overwrite an
     * existing note. Append mode preserves existing content and frontmatter.
     */
    private suspend fun executeWriteVaultNote(action: DispatchResult): ActionResult {
        val args = action.args as? ActionArgs.VaultNote
            ?: return ActionResult.FAILED("Missing vault note arguments")
        val rootPath = com.raijin.app.obsidian.ObsidianVaultStore(context).vaultRoot
            ?: return ActionResult.FAILED("Obsidian vault root is not configured")
        val root = java.io.File(rootPath)
        if (!root.isDirectory) return ActionResult.FAILED("Configured vault root is not a directory: $rootPath")
        val target = java.io.File(root, args.relativePath)
        val rootCanonical = root.canonicalPath
        val targetCanonical = target.canonicalPath
        if (targetCanonical != rootCanonical && !targetCanonical.startsWith(rootCanonical + java.io.File.separator)) {
            return ActionResult.DENIED("Note path escapes the vault root")
        }
        if (target.exists() && !args.append) {
            return ActionResult.DENIED("Note already exists (create-only): ${args.relativePath}")
        }
        return try {
            target.parentFile?.mkdirs()
            if (args.append) target.appendText(args.content, Charsets.UTF_8)
            else target.writeText(args.content, Charsets.UTF_8)
            ActionResult.EXECUTED("Note ${if (args.append) "appended to" else "written"}: ${args.relativePath}")
        } catch (e: Exception) {
            ActionResult.FAILED("Vault write failed: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun executeShare(action: DispatchResult): ActionResult {
        val fileTarget = action.targets.filterIsInstance<ActionTarget.File>().firstOrNull()
        return if (fileTarget != null) {
            when (val res = actionExecutor.execute("share_file", mapOf("file_path" to fileTarget.ref))) {
                is ActionExecutor.Result.Success -> ActionResult.EXECUTED()
                is ActionExecutor.Result.Denied -> ActionResult.DENIED(res.reason)
                is ActionExecutor.Result.Failure -> ActionResult.FAILED(res.error)
            }
        } else {
            ActionResult.EXECUTED()
        }
    }
}
