package io.raijin.brain

/**
 * The only personal context that may be added to the cloud AI's system prompt, and only
 * while the owner's "share with cloud AI" switch is on (owner-approved scope, 28 Nov):
 *  - remembered facts the owner told Raijin to remember (including the form of address),
 *  - names and short descriptions of Raijin's built-in tools and installed skills.
 * Nothing else is ever included: no notifications, contacts, call log, notes or vault content.
 */
object CloudContext {
    data class Entry(val name: String, val description: String)

    const val MAX_FACTS = 50
    const val MAX_FACT_CHARS = 200
    const val MAX_SKILLS = 40
    const val MAX_DESC_CHARS = 120
    const val MAX_TOTAL_CHARS = 5000

    /** Built-in tools, as short names and descriptions only. */
    val BUILT_IN_TOOLS: List<Entry> = listOf(
        Entry("call", "Call a contact by name."),
        Entry("message", "Send a text or WhatsApp message after the owner confirms."),
        Entry("open_app", "Open an installed app or one of its screens."),
        Entry("reminders", "Create, list and cancel reminders; set alarms and timers."),
        Entry("calendar", "Create a calendar event."),
        Entry("music_and_video", "Play music or a video, control playback and volume."),
        Entry("notifications", "Answer questions about recent notifications, on the phone."),
        Entry("obsidian_notes", "Draft notes into the owner's Obsidian vault after approval."),
        Entry("memory", "Remember facts the owner states and recall them."),
        Entry("web_search", "Look something up on the web."),
    )

    private fun clip(s: String, n: Int) = s.replace(Regex("""\s+"""), " ").trim().let { if (it.length > n) it.take(n - 1) + "…" else it }

    /** Block appended to the system prompt. Never returns personal data beyond the allowed lists. */
    fun build(
        facts: List<String>,
        addressTerm: String?,
        skills: List<Entry>,
        tools: List<Entry> = BUILT_IN_TOOLS,
    ): String {
        val sb = StringBuilder()
        sb.append("\n\nAbout the owner and this app (saved on their phone; use it, do not repeat it unprompted):\n")
        if (!addressTerm.isNullOrBlank()) {
            sb.append("- Address the owner as \"").append(clip(addressTerm, 30)).append("\" in every reply.\n")
        }
        val f = facts.map { clip(it, MAX_FACT_CHARS) }.filter { it.isNotEmpty() }.distinct().take(MAX_FACTS)
        if (f.isNotEmpty()) {
            sb.append("Facts the owner asked to remember:\n")
            f.forEach { sb.append("- ").append(it).append('\n') }
        }
        sb.append("Raijin's built-in tools:\n")
        tools.forEach { sb.append("- ").append(it.name).append(": ").append(clip(it.description, MAX_DESC_CHARS)).append('\n') }
        val s = skills.take(MAX_SKILLS)
        if (s.isNotEmpty()) {
            sb.append("Installed skills:\n")
            s.forEach { sb.append("- ").append(clip(it.name, 60)).append(": ").append(clip(it.description, MAX_DESC_CHARS)).append('\n') }
        }
        val out = sb.toString()
        return if (out.length > MAX_TOTAL_CHARS) out.take(MAX_TOTAL_CHARS) else out
    }
}
