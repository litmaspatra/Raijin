package com.raijin.app.permissions

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telecom.TelecomManager
import android.util.Log
import com.raijin.app.audio.RaijinTTS
import com.raijin.app.diagnostics.ActionLogEntry
import com.raijin.app.diagnostics.ActionLogStore
import com.raijin.app.ui.accessibility.RaijinAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Executes validated actions on behalf of the Raijin brain.
 *
 * The brain proposes actions as structured JSON; BrainBridge deserialises them and delegates
 * here. This class holds the real Android side-effects: calls, file ops, WhatsApp sends, etc.
 *
 * All public methods are suspend functions — they may need to wait for callbacks (e.g. the
 * accessibility service completing a WhatsApp send) or perform I/O.
 *
 * ## Result
 * Every action returns a sealed [Result]:
 * - [Result.Success]  — action completed successfully, optional human-readable [Result.Success.message].
 * - [Result.Denied]   — missing permission or service; includes a user-facing [Result.Denied.reason].
 * - [Result.Failure]  — action attempted but failed; includes error detail.
 */
class ActionExecutor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val permissionBroker: PermissionBroker,
    private val actionLog: ActionLogStore = ActionLogStore(context),
) {
    companion object {
        private const val TAG = "ActionExecutor"

        private val WHATSAPP_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")

        /** Injected by RaijinService; used for reminder_speak action. */
        var tts: RaijinTTS? = null
    }

    // =========================================================================
    // Sealed result type
    // =========================================================================

    sealed class Result {
        data class Success(val message: String = "") : Result()
        data class Denied(val reason: String) : Result()
        data class Failure(val error: String) : Result()
    }

    // =========================================================================
    // Dispatch — maps action type strings to concrete implementations
    // =========================================================================

    /**
     * Execute a named action with a map of string parameters.
     *
     * @param type   Action type key as produced by the brain (e.g. "call", "whatsapp_message").
     * @param params Key-value parameter map. All values are strings; implementors parse as needed.
     */
    suspend fun execute(type: String, params: Map<String, String>): Result {
        Log.d(TAG, "Executing action: $type with params: $params")
        val result = when (type) {
            "call"              -> executeCall(params)
            // Recognised but disabled this release: storage cleaning is out of
            // release scope (PRD ST-x is future work). Kept as an explicit
            // branch so aliases/LLM output fail safely instead of falling
            // through to "unknown".
            "soft_delete"       -> Result.Failure("Unsupported in this release: soft_delete (file deletion disabled)")
            "open_file"         -> executeOpenFile(params)
            "share_file"        -> executeShareFile(params)
            "whatsapp_message"  -> executeWhatsAppMessage(params)
            "read_notification" -> executeReadNotification(params)
            "reminder_speak"    -> executeReminderSpeak(params)
            else                -> Result.Failure("Unknown action type: $type")
        }
        // EX-1/EX-4: record every attempted action in the on-device ledger.
        // Summary keeps the target but never message bodies or secrets.
        val target = params["contact_name"] ?: params["phone_number"] ?: params["to"]
            ?: params["path"] ?: params["text"]?.take(40) ?: ""
        val summary = if (target.isBlank()) type else "$type: $target"
        val outcome = when (result) {
            is Result.Success -> "OK" + (if (result.message.isBlank()) "" else " - ${result.message}")
            is Result.Denied -> "DENIED - ${result.reason}"
            is Result.Failure -> "FAILED - ${result.error}"
        }
        runCatching { actionLog.append(ActionLogEntry.Kind.ACTION, summary, outcome) }
            .onFailure { Log.w(TAG, "action log write failed: ${it.javaClass.simpleName}") }
        // ME-3: structured signature only (never params) for the pattern miner.
        runCatching {
            com.raijin.app.RaijinDatabase.getInstance(context).interactionLogDao()
                .insert(com.raijin.app.memory.InteractionLogEntity(signature = "action:$type"))
        }.onFailure { Log.w(TAG, "interaction log write failed: ${it.javaClass.simpleName}") }
        return result
    }

    // =========================================================================
    // Action: call
    // =========================================================================

    /**
     * Dials the given phone number using the system dialler / TelecomManager.
     *
     * Required params:
     * - `phone_number` — E.164 or local number string.
     * - `contact_name` — Display name for logging / TTS announcement (optional).
     */
    // CALL_PHONE is verified by permissionBroker.ensureCallPermission() just below;
    // lint cannot see through the broker indirection.
    @android.annotation.SuppressLint("MissingPermission")
    private suspend fun executeCall(params: Map<String, String>): Result {
        val number = params["phone_number"]?.takeIf { it.isNotBlank() }
            ?: return Result.Failure("Missing param: phone_number")

        val hasPermission = permissionBroker.ensureCallPermission()
        if (!hasPermission) {
            return Result.Denied("CALL_PHONE permission not granted. Grant it in Settings > Apps > Raijin.")
        }

        return withContext(Dispatchers.Main) {
            try {
                val uri = Uri.parse("tel:${Uri.encode(number)}")
                val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager

                if (telecom != null) {
                    // Use TelecomManager for full call control (required for CALL_PHONE).
                    val extras = android.os.Bundle()
                    telecom.placeCall(uri, extras)
                } else {
                    // Fallback: ACTION_CALL intent (still requires CALL_PHONE permission).
                    val intent = Intent(Intent.ACTION_CALL, uri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }

                val name = params["contact_name"]?.let { " ($it)" } ?: ""
                Result.Success("Calling$name $number")
            } catch (e: Exception) {
                Log.e(TAG, "Call failed", e)
                Result.Failure("Call failed: ${e.message}")
            }
        }
    }

    // =========================================================================
    // Action: soft_delete
    // =========================================================================

    /**
     * DISABLED this release: file deletion is out of release scope (PRD ST-6
     * 24-hour trash lands with the storage workstream). Always denied.
     */
    private suspend fun executeSoftDelete(params: Map<String, String>): Result {
        // Execution boundary: file deletion is disabled this release (PRD ST-6
        // 24-hour trash lands with the storage workstream). No caller, alias
        // or LLM-produced parameter set reaches user files through here.
        return Result.Denied("File deletion is disabled in this release.")
    }

    // =========================================================================
    // Action: open_file
    // =========================================================================

    /**
     * Opens a file with the appropriate viewer via an implicit Intent.
     *
     * Required params:
     * - `file_path` — Absolute path of the file to open.
     * - `mime_type` — MIME type (e.g. "application/pdf"). Optional — inferred from extension if absent.
     */
    private suspend fun executeOpenFile(params: Map<String, String>): Result {
        val filePath = params["file_path"]?.takeIf { it.isNotBlank() }
            ?: return Result.Failure("Missing param: file_path")

        return withContext(Dispatchers.Main) {
            try {
                val file = java.io.File(filePath)
                if (!file.exists()) {
                    return@withContext Result.Failure("File not found: $filePath")
                }

                val mime = params["mime_type"]
                    ?: android.webkit.MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(file.extension.lowercase())
                    ?: "*/*"

                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )

                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                context.startActivity(intent)
                Result.Success("Opened: ${file.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Open file failed", e)
                Result.Failure("Could not open file: ${e.message}")
            }
        }
    }

    // =========================================================================
    // Action: share_file
    // =========================================================================

    /**
     * Shares a file via the Android share sheet (ACTION_SEND).
     *
     * Required params:
     * - `file_path` — Absolute path of the file to share.
     * - `mime_type` — MIME type. Optional — inferred from extension if absent.
     * - `target_package` — Optional package to direct the share to (e.g. "com.whatsapp").
     */
    private suspend fun executeShareFile(params: Map<String, String>): Result {
        val filePath = params["file_path"]?.takeIf { it.isNotBlank() }
            ?: return Result.Failure("Missing param: file_path")

        return withContext(Dispatchers.Main) {
            try {
                val file = java.io.File(filePath)
                if (!file.exists()) {
                    return@withContext Result.Failure("File not found: $filePath")
                }

                val mime = params["mime_type"]
                    ?: android.webkit.MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(file.extension.lowercase())
                    ?: "*/*"

                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )

                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                val targetPkg = params["target_package"]
                val launchIntent = if (targetPkg != null) {
                    shareIntent.apply {
                        setPackage(targetPkg)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                } else {
                    Intent.createChooser(shareIntent, "Share via").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }

                context.startActivity(launchIntent)
                Result.Success("Sharing: ${file.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Share file failed", e)
                Result.Failure("Could not share file: ${e.message}")
            }
        }
    }

    // =========================================================================
    // Action: whatsapp_message  (Phase 2)
    // =========================================================================

    /**
     * Sends a WhatsApp message to a resolved phone number.
     *
     * Strategy:
     * 1. If [RaijinAccessibilityService] is active — opens WhatsApp via Intent (pre-fills
     *    compose box) and lets the accessibility service click Send automatically.
     * 2. If accessibility is unavailable — falls back to Intent-only: opens WhatsApp with
     *    the message pre-filled. User must tap Send manually. Reports [Result.Success] since
     *    the message is composed and visible — user intent is satisfied.
     *
     * Required params:
     * - `phone_number` — Phone number in E.164-compatible format (e.g. "919876543210").
     * - `message`      — Message text to send.
     */
    private suspend fun executeWhatsAppMessage(params: Map<String, String>): Result {
        val phoneNumber = params["phone_number"]?.takeIf { it.isNotBlank() }
            ?: return Result.Failure("Missing param: phone_number")

        val message = params["message"]?.takeIf { it.isNotBlank() }
            ?: return Result.Failure("Missing param: message")

        // Check WhatsApp is installed.
        val whatsAppPackage = resolveInstalledWhatsAppPackage()
            ?: return Result.Denied(
                "WhatsApp is not installed on this device. " +
                    "Install com.whatsapp or com.whatsapp.w4b to send messages via Raijin.",
            )

        Log.d(TAG, "WhatsApp package resolved: $whatsAppPackage")

        val accessibilityService = RaijinAccessibilityService.instance

        return if (accessibilityService != null && RaijinAccessibilityService.isEnabled(context)) {
            // Accessibility path — service will auto-click Send.
            Log.d(TAG, "Using accessibility path for WhatsApp send")
            sendViaAccessibility(accessibilityService, phoneNumber, message)
        } else {
            // Intent-only fallback — compose box pre-filled, user taps Send.
            Log.w(TAG, "Accessibility service not available — falling back to Intent-only path")
            sendViaIntentOnly(phoneNumber, message)
        }
    }

    private suspend fun sendViaAccessibility(
        service: RaijinAccessibilityService,
        phoneNumber: String,
        message: String,
    ): Result = suspendCancellableCoroutine { cont ->
        service.sendWhatsAppMessage(phoneNumber, message) { success ->
            if (cont.isActive) {
                if (success) {
                    cont.resume(Result.Success("WhatsApp message sent to $phoneNumber"))
                } else {
                    // Timed out — fall through to success since intent was launched.
                    cont.resume(
                        Result.Success(
                            "WhatsApp opened. Message pre-filled — tap Send if not already sent.",
                        ),
                    )
                }
            }
        }
    }

    private suspend fun sendViaIntentOnly(phoneNumber: String, message: String): Result {
        return withContext(Dispatchers.Main) {
            try {
                val uri = Uri.parse(
                    "https://api.whatsapp.com/send?phone=$phoneNumber&text=${Uri.encode(message)}",
                )
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)

                // Accessibility not enabled — guide the user.
                Result.Denied(
                    "Accessibility service not enabled. " +
                        "Enable Raijin in Settings > Accessibility > Raijin to auto-send messages. " +
                        "WhatsApp has been opened with your message pre-filled — tap Send to deliver it.",
                )
            } catch (e: Exception) {
                Log.e(TAG, "Intent-only WhatsApp send failed", e)
                Result.Failure("Could not open WhatsApp: ${e.message}")
            }
        }
    }

    /**
     * Returns the package name of the first installed WhatsApp variant, or null if none found.
     */
    private fun resolveInstalledWhatsAppPackage(): String? {
        val pm = context.packageManager
        return WHATSAPP_PACKAGES.firstOrNull { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }
    }

    // =========================================================================
    // Action: read_notification  (Phase 2)
    // =========================================================================

    /**
     * Acknowledges a notification-read action from the brain.
     *
     * TTS is handled by the brain's event stream (the `lines` field is read by the island
     * layer and passed to [RaijinTTS]). This executor simply returns success so the brain
     * knows the Android layer accepted the action.
     *
     * Required params:
     * - `package` — Package name of the app that posted the notification (informational).
     * - `text`    — Notification text that was / will be spoken (informational).
     */
    private suspend fun executeReadNotification(params: Map<String, String>): Result {
        val pkg  = params["package"]  ?: "(unknown)"
        val text = params["text"]     ?: ""

        Log.d(TAG, "read_notification from $pkg: \"${text.take(80)}\"")

        // TTS is driven by the IslandViewModel / RaijinTTS layer from the event stream.
        // Nothing more to do here — the brain controls what gets spoken.
        return Result.Success()
    }

    // =========================================================================
    // Action: reminder_speak
    // =========================================================================

    /**
     * Speaks the reminder text via RaijinTTS.
     * Called by ReminderBroadcastReceiver's action flow when the reminder fires.
     *
     * Required params:
     * - `text` — Reminder text to speak aloud.
     */
    private suspend fun executeReminderSpeak(params: Map<String, String>): Result {
        val text = params["text"]?.takeIf { it.isNotBlank() }
            ?: return Result.Failure("Missing param: text")

        val raijinTts = tts ?: return Result.Denied("TTS not initialised.")

        return suspendCancellableCoroutine { cont ->
            raijinTts.speak(text) { _ ->
                if (cont.isActive) cont.resume(Result.Success("Spoken: $text"))
            }
        }
    }
}
