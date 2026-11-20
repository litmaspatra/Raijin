package com.raijin.app.diagnostics

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.core.content.ContextCompat
import com.raijin.app.cloud.OpenAiCompatibleClient
import com.raijin.app.cloud.OpenAiConfig
import com.raijin.app.cloud.ProviderRegistryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One diagnostics row. passed == null means "not run yet / running". */
data class DiagResult(
    val id: String,
    val label: String,
    val passed: Boolean?,
    val detail: String,
    val optional: Boolean = false,
    val unsupported: Boolean = false,
) {
    val status: String get() = when {
        unsupported -> "Unavailable"
        passed == null -> "Not checked"
        passed == true -> "Ready"
        optional -> "Optional setup"
        else -> "Needs setup"
    }
}

/**
 * EX-1: objective self-tests for every capability the owner otherwise has to
 * guess about. Each check reports PASS/FAIL with the reason. Checks are
 * read-only - they never change settings or fire actions.
 */
class DiagnosticsRunner(private val context: Context) {

    private fun hasPermission(perm: String): Boolean =
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

    private fun secureList(key: String): String =
        Settings.Secure.getString(context.contentResolver, key) ?: ""

    private fun ownServicesEnabled(settingKey: String): Boolean {
        val enabled = secureList(settingKey)
        return enabled.contains(context.packageName, ignoreCase = true)
    }

    fun checkMic(): DiagResult =
        if (hasPermission(android.Manifest.permission.RECORD_AUDIO))
            DiagResult("mic", "Microphone", true, "RECORD_AUDIO granted")
        else
            DiagResult("mic", "Microphone", false, "RECORD_AUDIO not granted - allow it in system settings")

    fun checkStt(): DiagResult =
        if (SpeechRecognizer.isRecognitionAvailable(context))
            DiagResult("stt", "Speech-to-text", true, "A speech recognition service is available; offline support depends on that service")
        else
            DiagResult("stt", "Speech-to-text", false, "No speech recognition service on this device", unsupported = true)

    fun checkTts(): DiagResult {
        val intent = Intent(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA)
        val resolved = context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
        return if (resolved)
            DiagResult("tts", "Text-to-speech", true, "A TTS engine is installed")
        else
            DiagResult("tts", "Text-to-speech", false, "No TTS engine found - install Google Speech Services")
    }

    fun checkContacts(): DiagResult =
        if (hasPermission(android.Manifest.permission.READ_CONTACTS))
            DiagResult("contacts", "Contacts access", true, "READ_CONTACTS granted")
        else
            DiagResult("contacts", "Contacts access", false, "READ_CONTACTS not granted - calling/messaging by name will fail")

    fun checkAppInventory(): DiagResult {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        return if (apps.isNotEmpty())
            DiagResult("apps", "App inventory", true, "${apps.size} launchable apps visible (Android app-visibility rules may hide some)")
        else
            DiagResult("apps", "App inventory", false, "No launchable apps visible - package visibility restricted")
    }

    fun checkExactAlarms(): DiagResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return DiagResult("alarms", "Exact alarms", true, "Not required below Android 12")
        }
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return if (am.canScheduleExactAlarms())
            DiagResult("alarms", "Exact alarms", true, "Exact-alarm access granted; delivery still depends on device power settings")
        else
            DiagResult("alarms", "Exact alarms", false, "Needed for precise Raijin reminders, not for chat or opening apps. Without it reminders may be late.", optional = true)
    }

    fun checkAccessibility(): DiagResult =
        if (ownServicesEnabled(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
            DiagResult("accessibility", "Accessibility service", true, "Raijin accessibility service enabled")
        else
            DiagResult("accessibility", "Accessibility service", false, "Optional for supported in-app controls. Not needed to open apps or chat. Grants access to screen content and actions.", optional = true)

    fun checkNotificationAccess(): DiagResult =
        if (ownServicesEnabled("enabled_notification_listeners"))
            DiagResult("notif", "Notification access", true, "Notification listener enabled")
        else
            DiagResult("notif", "Notification access", false, "Optional for notification summaries. Off means messages cannot be checked, not that your inbox is empty. Grants access to notification content.", optional = true)

    fun checkStorage(): DiagResult = DiagResult(
        "storage", "Cache audit", true,
        "Current audit scans Raijin's own cache only. No photos, files or all-files access is needed.",
    )

    fun checkMedia(): DiagResult {
        val audio = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            hasPermission(android.Manifest.permission.READ_MEDIA_AUDIO)
        else hasPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        return DiagResult("media", "Local audio library", audio,
            if (audio) "Local audio read access granted."
            else "Optional for reading local audio files. Not needed for voice chat or launching a music app.",
            optional = true)
    }

    fun checkOverlay(): DiagResult =
        if (Settings.canDrawOverlays(context))
            DiagResult("overlay", "Overlay (island)", true, "Can draw over other apps")
        else
            DiagResult("overlay", "Overlay (island)", false, "Overlay permission missing - the island cannot appear")

    suspend fun checkProviders(): List<DiagResult> = withContext(Dispatchers.IO) {
        val registry = ProviderRegistryStore(context)
        val providers = registry.effectiveProviders()
        if (providers.isEmpty()) {
            return@withContext listOf(
                DiagResult("provider-none", "Cloud providers", false, "None configured - add one in the failover chain or the single endpoint above")
            )
        }
        providers.sortedBy { it.first.priority }.map { (cfg, _, key) ->
            val client = OpenAiCompatibleClient(configProvider = {
                OpenAiConfig(cfg.baseUrl, key, cfg.model, cfg.timeoutSeconds)
            })
            val res = runCatching { client.testConnection() }
                .getOrElse { false to (it.message ?: it.javaClass.simpleName) }
            if (res.first)
                DiagResult("provider-${cfg.id}", "Provider: ${cfg.label}", true, res.second)
            else
                DiagResult("provider-${cfg.id}", "Provider: ${cfg.label}", false, res.second)
        }
    }

    /** The fixed local checks in display order; providers are appended separately. */
    fun localChecks(): List<() -> DiagResult> = listOf(
        ::checkMic, ::checkStt, ::checkTts, ::checkContacts, ::checkAppInventory,
        ::checkExactAlarms, ::checkAccessibility, ::checkNotificationAccess,
        ::checkStorage, ::checkMedia, ::checkOverlay,
    )
}
