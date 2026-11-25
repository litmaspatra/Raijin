package io.raijin.brain

/**
 * BR-9: per-request relevant-context selection for cloud reasoning.
 *
 * Owner decision (2026-11-25): a cloud request carries a prompt with whatever
 * context is necessary for the correct answer — selected per request, NOT a
 * blanket rolling history. Approved behavior: on a follow-up question, up to
 * the last 3 session turns go along; anything uncertain falls back to the
 * current message only. History is in-memory only (never persisted), holds
 * only this session's utterances and Raijin's own replies, and never includes
 * contacts, memory facts, location or other apps' data.
 */

/** One conversational exchange: what the owner said and what Raijin answered. */
data class SessionTurn(
    val userText: String,
    val response: String,
    val timestampMs: Long,
)

/**
 * In-memory ring of recent conversational turns. Never written to disk; the
 * log dies with the process and can be wiped on demand ("clear history").
 */
class SessionTurnLog(private val cap: Int = DEFAULT_CAP) {
    private val turns = ArrayDeque<SessionTurn>()

    @Synchronized
    fun record(turn: SessionTurn) {
        turns.addLast(turn)
        while (turns.size > cap) turns.removeFirst()
    }

    /** Oldest-to-newest, at most [n] entries. */
    @Synchronized
    fun recent(n: Int): List<SessionTurn> = turns.toList().takeLast(n)

    @Synchronized
    fun clear() = turns.clear()

    val size: Int get() = turns.size

    companion object {
        const val DEFAULT_CAP = 20
    }
}

/**
 * Conservative follow-up detector. Returns true only on clear anaphoric
 * signals ("what about...", "and...", "why...", an explicit pronoun referring
 * back). Anything uncertain returns false, and the caller falls back to
 * sending the current message only.
 */
object FollowUpDetector {

    private val LEAD_PATTERNS = listOf(
        "what about", "how about", "and what", "and how", "and when", "and where",
        "and who", "and is", "and are", "and was", "and were", "and do", "and does",
        "and did", "and can", "and will", "and would", "and should",
        "what if", "why ",
        "also ", "so ", "then ", "but ",
    )

    private val PRONOUN = Regex(
        "\\b(it|that|this|these|those|he|she|they|him|her|them|there|the same|same one)\\b",
    )

    fun isFollowUp(text: String): Boolean {
        val t = text.trim().lowercase()
        if (t.isEmpty()) return false
        if (LEAD_PATTERNS.any { t.startsWith(it) }) return true
        if (t.startsWith("and ") && t.length > 4) return true
        return PRONOUN.containsMatchIn(t)
    }
}

/** Assembles the exact prompt string handed to the cloud endpoint. */
object SessionContextPrompt {

    private const val MAX_CONTEXT_TURNS = 3

    /**
     * Current message plus, only when it is a follow-up and history exists,
     * the last [MAX_CONTEXT_TURNS] session turns verbatim. Otherwise returns
     * [currentText] unchanged — byte-identical to the pre-BR-9 payload.
     */
    fun build(currentText: String, log: SessionTurnLog): String {
        if (!FollowUpDetector.isFollowUp(currentText)) return currentText
        val context = log.recent(MAX_CONTEXT_TURNS)
        if (context.isEmpty()) return currentText
        val sb = StringBuilder()
        sb.append("Recent conversation:\n")
        for (turn in context) {
            sb.append("User: ").append(turn.userText).append('\n')
            sb.append("Raijin: ").append(turn.response).append('\n')
        }
        sb.append("\nCurrent message: ").append(currentText)
        return sb.toString()
    }
}

/** Local clear-on-demand ("clear history", "forget this conversation"). */
object SessionHistoryClear {

    private val PHRASES = listOf(
        "clear history",
        "clear chat history",
        "clear conversation",
        "clear the conversation",
        "forget this conversation",
        "forget the conversation",
        "forget our conversation",
        "delete conversation history",
        "delete chat history",
        "forget everything i said",
    )

    fun matches(text: String): Boolean {
        val t = text.trim().lowercase().trimEnd('.', '!')
        return PHRASES.any { t == it }
    }
}
