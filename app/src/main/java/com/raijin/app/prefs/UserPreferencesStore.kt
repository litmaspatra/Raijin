package com.raijin.app.prefs

import android.content.Context
import android.content.SharedPreferences

/**
 * ME-5: owner preferences. One typed store; every preference has a safe
 * default so nothing breaks before the owner ever opens the screen.
 *
 * Honoring status (kept honest in STATUS.md):
 * - language: read by STT/TTS setup (wiring lands with the prefs screen)
 * - ttsVoice/ttsSpeed: written through to the Kokoro voice prefs so the
 *   existing audio path honors them without a refactor
 * - confirmationStrictness: read by ask/consent flows
 * - protectedPaths: read by storage/delete flows before touching files
 * - quietHours: read by MorningBriefTrigger and proactive sentinel
 * - preferredProviders: read by ProviderRegistry ordering
 */
class UserPreferencesStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val appContext = context.applicationContext

    enum class Language(val tag: String) { ENGLISH("en"), HINDI("hi") }

    /** How readily Raijin acts without asking first. */
    enum class ConfirmationStrictness { STRICT, BALANCED, RELAXED }

    var language: Language
        get() = Language.entries.firstOrNull { it.tag == prefs.getString(KEY_LANGUAGE, Language.ENGLISH.tag) }
            ?: Language.ENGLISH
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value.tag).apply()

    /** Null when the owner never chose a language (callers fall back to device locale). */
    val explicitLanguage: Language?
        get() = if (prefs.contains(KEY_LANGUAGE)) language else null

    var ttsVoice: String
        get() = prefs.getString(KEY_TTS_VOICE, DEFAULT_VOICE) ?: DEFAULT_VOICE
        set(value) {
            prefs.edit().putString(KEY_TTS_VOICE, value).apply()
            // Mirror into the Kokoro store so the existing audio path honors it.
            kokoroPrefs().edit().putString("voice", value).apply()
        }

    var ttsSpeed: Float
        get() = prefs.getFloat(KEY_TTS_SPEED, 1.0f)
        set(value) {
            val clamped = value.coerceIn(0.5f, 2.0f)
            prefs.edit().putFloat(KEY_TTS_SPEED, clamped).apply()
            kokoroPrefs().edit().putFloat("speed", clamped).apply()
        }

    /** The assistant's own name; the wake phrase is "hey <name>". */
    var assistantName: String
        get() = prefs.getString(KEY_ASSISTANT_NAME, null)?.takeIf { it.isNotBlank() } ?: io.raijin.brain.AssistantName.DEFAULT
        set(value) { prefs.edit().putString(KEY_ASSISTANT_NAME, value.trim()).apply() }

    /** Set while Raijin has asked "male or female name?" and waits for the answer. */
    var pendingGenderForName: String?
        get() = prefs.getString(KEY_PENDING_GENDER, null)?.takeIf { it.isNotBlank() }
        set(value) { if (value == null) prefs.edit().remove(KEY_PENDING_GENDER).apply() else prefs.edit().putString(KEY_PENDING_GENDER, value).apply() }

    /** How the owner wants to be addressed ("sir"); null keeps the default. Set by "call me X always". */
    var addressTerm: String?
        get() = prefs.getString(KEY_ADDRESS_TERM, null)?.takeIf { it.isNotBlank() }
        set(value) {
            if (value.isNullOrBlank()) prefs.edit().remove(KEY_ADDRESS_TERM).apply()
            else prefs.edit().putString(KEY_ADDRESS_TERM, value.trim()).apply()
        }

    /**
     * Owner-approved (28 Nov): add remembered facts, the form of address and tool/skill names
     * and descriptions to the cloud AI's prompt. On by default; the owner can switch it off.
     */
    var shareContextWithCloud: Boolean
        get() = prefs.getBoolean(KEY_SHARE_CLOUD_CONTEXT, true)
        set(value) = prefs.edit().putBoolean(KEY_SHARE_CLOUD_CONTEXT, value).apply()

    /** Name of the chosen system TTS voice; null means "pick the best installed one". */
    var systemVoice: String?
        get() = prefs.getString(KEY_SYSTEM_VOICE, null)
        set(value) {
            if (value == null) prefs.edit().remove(KEY_SYSTEM_VOICE).apply()
            else prefs.edit().putString(KEY_SYSTEM_VOICE, value).apply()
        }

    var ttsPitch: Float
        get() = prefs.getFloat(KEY_TTS_PITCH, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_TTS_PITCH, value.coerceIn(0.5f, 2.0f)).apply()

    /**
     * Background lookup of the first YouTube result for "play <song>" / "play a video of X".
     * Only the query text (and the IP address any web request carries) goes to youtube.com.
     * Off = the app's own search opens instead.
     */
    var onlineMediaLookup: Boolean
        get() = prefs.getBoolean(KEY_ONLINE_LOOKUP, true)
        set(value) = prefs.edit().putBoolean(KEY_ONLINE_LOOKUP, value).apply()

    /**
     * CT-5: rank contacts using the phone's own call history (how often, how
     * recently, at this time of day). Read live on the phone, never stored or sent.
     */
    var useContactHistory: Boolean
        get() = prefs.getBoolean(KEY_CONTACT_HISTORY, true)
        set(value) = prefs.edit().putBoolean(KEY_CONTACT_HISTORY, value).apply()

    var confirmationStrictness: ConfirmationStrictness
        get() = ConfirmationStrictness.entries.firstOrNull {
            it.name == prefs.getString(KEY_CONFIRMATION, ConfirmationStrictness.BALANCED.name)
        } ?: ConfirmationStrictness.BALANCED
        set(value) = prefs.edit().putString(KEY_CONFIRMATION, value.name).apply()

    /** Paths that storage cleanup and delete actions must never touch. */
    var protectedPaths: Set<String>
        get() = prefs.getStringSet(KEY_PROTECTED_PATHS, DEFAULT_PROTECTED_PATHS) ?: DEFAULT_PROTECTED_PATHS
        set(value) = prefs.edit().putStringSet(KEY_PROTECTED_PATHS, value).apply()

    fun isProtectedPath(path: String): Boolean =
        protectedPaths.any { protected -> path == protected || path.startsWith("$protected/") }

    var quietHoursEnabled: Boolean
        get() = prefs.getBoolean(KEY_QUIET_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_QUIET_ENABLED, value).apply()

    /** Inclusive start hour (0-23). */
    var quietHoursStart: Int
        get() = prefs.getInt(KEY_QUIET_START, 22)
        set(value) = prefs.edit().putInt(KEY_QUIET_START, value.coerceIn(0, 23)).apply()

    /** Exclusive end hour (0-23). May wrap past midnight. */
    var quietHoursEnd: Int
        get() = prefs.getInt(KEY_QUIET_END, 6)
        set(value) = prefs.edit().putInt(KEY_QUIET_END, value.coerceIn(0, 23)).apply()

    /** True when quiet hours are on and [hour] falls inside [start, end). */
    fun isQuietHour(hour: Int): Boolean {
        if (!quietHoursEnabled) return false
        val start = quietHoursStart
        val end = quietHoursEnd
        return if (start <= end) hour in start until end else hour >= start || hour < end
    }

    /** Ordered provider ids; first is preferred. Empty = registry default order. */
    var preferredProviders: List<String>
        get() = prefs.getString(KEY_PROVIDERS, "")?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
        set(value) = prefs.edit().putString(KEY_PROVIDERS, value.joinToString(",")).apply()

    private fun kokoroPrefs(): SharedPreferences =
        appContext.getSharedPreferences("raijin_kokoro_voice", Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "raijin_user_prefs"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_TTS_VOICE = "tts_voice"
        private const val KEY_TTS_SPEED = "tts_speed"
        private const val KEY_SYSTEM_VOICE = "system_tts_voice"
        private const val KEY_TTS_PITCH = "tts_pitch"
        private const val KEY_ONLINE_LOOKUP = "online_media_lookup"
        private const val KEY_CONTACT_HISTORY = "use_contact_history"
        private const val KEY_CONFIRMATION = "confirmation_strictness"
        private const val KEY_PROTECTED_PATHS = "protected_paths"
        private const val KEY_QUIET_ENABLED = "quiet_hours_enabled"
        private const val KEY_QUIET_START = "quiet_hours_start"
        private const val KEY_QUIET_END = "quiet_hours_end"
        private const val KEY_PROVIDERS = "preferred_providers"

        const val KEY_ADDRESS_TERM = "address_term"
        const val KEY_ASSISTANT_NAME = "assistant_name"
        const val KEY_PENDING_GENDER = "pending_gender_for_name"
        const val KEY_SHARE_CLOUD_CONTEXT = "share_cloud_context"
        const val DEFAULT_VOICE = "bf_emma"
        val DEFAULT_PROTECTED_PATHS = setOf(
            "/storage/emulated/0/DCIM",
            "/storage/emulated/0/Pictures",
            "/storage/emulated/0/Documents",
        )
    }
}
