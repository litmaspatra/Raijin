package io.raijin.brain

/**
 * Voice self-edit: what Raijin may change about itself when the owner says so, and what it must
 * not. The boundary is a fixed allow-list, not a judgement call:
 *
 *  - EDITABLE (takes effect at once, announced, undoable): form of address, speech speed,
 *    the on-device voice (female/male). Remembered facts are handled by the memory path.
 *  - LOCKED (voice can never change it): code and updates, safety/privacy/consent gates,
 *    permissions, the tool list.
 *  - ASK FIRST (never by voice alone): anything that changes what leaves the phone, the cloud
 *    brain or its keys. Spoken or overheard audio is not enough consent; these are confirmed on
 *    screen in Settings.
 */
object SelfEdit {
    enum class Reason { CORE_LOCKED, ASK_FIRST }

    sealed interface Command {
        data class SetAddress(val term: String) : Command
        /** Relative change: positive is faster. */
        data class SpeedStep(val delta: Float) : Command
        data class SetSpeed(val factor: Float) : Command
        data class SetVoiceGender(val male: Boolean) : Command
        data object Undo : Command
        data class Refuse(val reason: Reason) : Command
    }

    const val MIN_SPEED = 0.5f
    const val MAX_SPEED = 2.0f
    private const val STEP = 0.2f

    private val UNDO = Regex(
        """^(?:please\s+)?(?:undo(?:\s+(?:that|the\s+last\s+change|it))?|revert\s+(?:that|it)|take\s+that\s+back|change\s+it\s+back)$""",
        RegexOption.IGNORE_CASE,
    )
    private val FASTER = Regex(
        """^(?:please\s+)?(?:speak|talk)\s+(?:a\s+(?:bit|little)\s+)?(faster|slower|more\s+quickly|more\s+slowly)$""",
        RegexOption.IGNORE_CASE,
    )
    private val SPEED_TO = Regex(
        """^(?:please\s+)?(?:set|change)\s+(?:your\s+)?(?:speech|speaking|voice)\s+speed\s+to\s+(\d(?:\.\d)?)(?:\s*x)?$""",
        RegexOption.IGNORE_CASE,
    )
    private val GENDER_A = Regex(
        """^(?:please\s+)?(?:use|switch\s+to|change\s+to|speak\s+(?:with|in)|sound\s+like)\s+(?:a\s+|the\s+)?(male|female|man|woman)(?:'s)?(?:\s+voice)?$""",
        RegexOption.IGNORE_CASE,
    )
    private val GENDER_B = Regex(
        """^(?:please\s+)?(?:change|switch)\s+(?:your\s+|the\s+)?voice\s+to\s+(?:a\s+)?(male|female|man|woman)(?:\s+voice)?$""",
        RegexOption.IGNORE_CASE,
    )

    private val LOCKED = listOf(
        Regex("""\b(?:change|edit|rewrite|modify|update|delete|disable|turn\s+off|bypass|remove|override)\s+(?:your|the)\s+(?:own\s+)?(?:code|core|source|safety|privacy|consent|confirmation|guard\s?rails?|permissions?)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:grant|give)\s+(?:yourself|you)\s+(?:more\s+)?(?:permission|permissions|access)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:install|update)\s+(?:yourself|a\s+new\s+version\s+of\s+yourself)\b""", RegexOption.IGNORE_CASE),
    )
    private val ASK_FIRST = listOf(
        Regex("""\b(?:send|share|upload|forward|give)\b.*\b(?:contacts?|call\s+(?:log|history)|notifications?|messages|notes|vault|location|photos)\b.*\b(?:cloud|server|online|internet|openai|outside|off\s+(?:the\s+)?phone)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:turn\s+(?:on|off)|enable|disable|change|switch)\s+(?:the\s+|your\s+)?(?:cloud\s+(?:context|ai|brain)|cloud|api\s+key|ai\s+provider|brain\s+provider)\b""", RegexOption.IGNORE_CASE),
    )

    /** [remembered] is true when [text] is the body of an explicit "remember ..." request. */
    fun parse(text: String, remembered: Boolean = false): Command? {
        val t = text.trim().trimEnd('.', '!', '?').trim()
        if (t.isEmpty()) return null
        if (UNDO.matches(t)) return Command.Undo
        LOCKED.firstOrNull { it.containsMatchIn(t) }?.let { return Command.Refuse(Reason.CORE_LOCKED) }
        ASK_FIRST.firstOrNull { it.containsMatchIn(t) }?.let { return Command.Refuse(Reason.ASK_FIRST) }
        AddressForm.parse(text, remembered)?.let { return Command.SetAddress(it) }
        FASTER.matchEntire(t)?.let {
            val faster = it.groupValues[1].lowercase().let { w -> w == "faster" || w.endsWith("quickly") }
            return Command.SpeedStep(if (faster) STEP else -STEP)
        }
        SPEED_TO.matchEntire(t)?.let {
            val v = it.groupValues[1].toFloatOrNull() ?: return null
            if (v in MIN_SPEED..MAX_SPEED) return Command.SetSpeed(v)
        }
        (GENDER_A.matchEntire(t) ?: GENDER_B.matchEntire(t))?.let {
            val w = it.groupValues[1].lowercase()
            return Command.SetVoiceGender(male = w == "male" || w == "man")
        }
        return null
    }

    fun clampSpeed(v: Float): Float = v.coerceIn(MIN_SPEED, MAX_SPEED)

    fun refusal(reason: Reason): String = when (reason) {
        Reason.CORE_LOCKED ->
            "I can't change that by voice. It's part of my core, so it only changes in a new app version."
        Reason.ASK_FIRST ->
            "That would change what leaves your phone, so I won't do it by voice. Please confirm it yourself in Settings."
    }
}

/** One undoable self-edit. [key] names the setting; [before]/[after] are its stored text values. */
data class SelfEditEntry(val key: String, val before: String?, val after: String?, val memoryText: String? = null)

/** Small last-in-first-out log of self-edits so "undo" can restore the previous value. Persistence is injected. */
class SelfEditLog(private val load: () -> String, private val save: (String) -> Unit, private val max: Int = 20) {
    private fun enc(s: String?) = if (s == null) "~" else "_" + java.net.URLEncoder.encode(s, "UTF-8")
    private fun dec(s: String) = if (s == "~") null else java.net.URLDecoder.decode(s.removePrefix("_"), "UTF-8")

    fun all(): List<SelfEditEntry> = load().lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
        val p = line.split(' ')
        if (p.size != 4) null else SelfEditEntry(dec(p[0]) ?: return@mapNotNull null, dec(p[1]), dec(p[2]), dec(p[3]))
    }.toList()

    fun push(e: SelfEditEntry) {
        val list = (all() + e).takeLast(max)
        save(list.joinToString("\n") { "${enc(it.key)} ${enc(it.before)} ${enc(it.after)} ${enc(it.memoryText)}" })
    }

    fun pop(): SelfEditEntry? {
        val list = all()
        val last = list.lastOrNull() ?: return null
        val rest = list.dropLast(1)
        save(rest.joinToString("\n") { "${enc(it.key)} ${enc(it.before)} ${enc(it.after)} ${enc(it.memoryText)}" })
        return last
    }
}
