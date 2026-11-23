package com.raijin.app.bridge

/**
 * TM-9: deterministic matcher between an incoming notification title
 * (the sender's display name on messaging apps) and a conditional
 * reminder's watched contact. Pure string logic; on-device only.
 */
object ConditionMatcher {

    fun normalize(s: String): String =
        s.trim().lowercase().replace(Regex("\\s+"), " ")

    /**
     * A title matches a contact when one contains the other after
     * normalization ("Rahul Sharma" vs "Rahul", "Amma" vs "Amma ❤️"
     * handled by the contains direction that fits).
     */
    fun matches(notificationTitle: String, contactName: String): Boolean {
        val t = normalize(notificationTitle)
        val c = normalize(contactName)
        if (t.isEmpty() || c.isEmpty()) return false
        return t.contains(c) || c.contains(t)
    }
}
