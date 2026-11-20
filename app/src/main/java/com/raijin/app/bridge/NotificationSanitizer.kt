package com.raijin.app.bridge

/**
 * On-device sanitizer for notification content summaries.
 *
 * Everything runs locally: no notification text ever leaves the device.
 * A notification body is either reduced to a short sanitized one-liner
 * ("You have used 50% of your data") or fully suppressed (count-only),
 * never partially leaked.
 */
object NotificationSanitizer {

    const val MAX_SNIPPET_LEN = 80

    /**
     * Messaging apps: bodies are always suppressed (count-only), because their
     * content is personal conversation, not transactional status.
     */
    private val MESSAGING_PACKAGES = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "com.facebook.orca",
        "com.instagram.android",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "com.android.messaging",
        "org.thoughtcrime.securesms",
        "com.snapchat.android",
        "com.discord",
        "com.slack",
        "com.microsoft.teams",
    )

    /** Whole-body suppression triggers: credentials and one-time codes. */
    private val SECRET_KEYWORDS = Regex(
        "(?i)\\b(otp|one[ -]?time|verification code|security code|login code|auth(?:entication)? code|passcode|password|mpin|cvv)\\b",
    )
    private val EMBEDDED_CODE = Regex("(?i)\\b(?:code|otp|pin)\\s*(?:is|:)?\\s*\\d{4,8}\\b")

    private val URL = Regex("(?i)https?://\\S+|www\\.\\S+")
    private val EMAIL = Regex("\\b[\\w.+-]+@[\\w-]+\\.[\\w.]+\\b")
    private val PHONE = Regex("\\+?\\d[\\d -]{8,}\\d")

    // ── deterministic alert extraction ────────────────────────────────────
    // Each extractor only re-states facts literally present in the text.
    // Notification text is untrusted data: it is never executed or followed.

    /** "You have used 50% of your data plan" -> "50% of data used". */
    private val DATA_USED = Regex(
        "(?i)used\\s+(\\d{1,3})%\\s*of\\s*(?:your\\s*)?(data|quota|plan)|" +
            "(\\d{1,3})%\\s*of\\s*(?:your\\s*)?(data|quota|plan)\\s+(?:is\\s+)?(?:used|consumed|exhausted)",
    )

    /** "Only 2 GB left" / "1.5 GB remaining" -> "2 GB of data left". */
    private val DATA_REMAINING = Regex(
        "(?i)([\\d.]+)\\s*(GB|MB)\\s*(?:of\\s*data\\s*)?(?:left|remaining)",
    )

    /** Delivery status phrases. */
    private val OUT_FOR_DELIVERY = Regex("(?i)\\bout for delivery\\b")
    private val DELIVERED = Regex("(?i)\\b(?:delivered|delivery completed)\\b")
    private val ARRIVING = Regex("(?i)\\barriving\\s+(today|tomorrow|[A-Za-z]+day)\\b")

    /**
     * Deterministic extraction for recognizable alerts. Returns a concise
     * fact line only when the pattern grounds it in the text; otherwise null
     * and the caller falls back to a sanitized PREVIEW.
     */
    fun extractAlert(text: String): String? {
        DATA_USED.find(text)?.let { m ->
            val pct = m.groupValues[1].ifBlank { m.groupValues[3] }
            return "$pct% of data used"
        }
        DATA_REMAINING.find(text)?.let { m ->
            return "${m.groupValues[1]} ${m.groupValues[2].uppercase()} of data left"
        }
        if (OUT_FOR_DELIVERY.containsMatchIn(text)) return "Package out for delivery"
        if (DELIVERED.containsMatchIn(text)) return "Package delivered"
        ARRIVING.find(text)?.let { m ->
            return "Arriving ${m.groupValues[1].lowercase()}"
        }
        return null
    }

    /**
     * True when this notification's body must never be summarized. */
    fun isBodySuppressed(pkg: String, category: String?, visibility: Int, secretVisibility: Int): Boolean =
        pkg in MESSAGING_PACKAGES ||
            category == android.app.Notification.CATEGORY_MESSAGE ||
            visibility == secretVisibility

    /**
     * Reduce a notification body to a safe one-line snippet.
     *
     * @return the sanitized snippet, or null when the body must be suppressed
     *         entirely (secret content) or is empty.
     */
    fun sanitize(raw: String): String? {        var s = raw.replace(Regex("\\s+"), " ").trim()
        if (s.isEmpty()) return null
        if (SECRET_KEYWORDS.containsMatchIn(s) || EMBEDDED_CODE.containsMatchIn(s)) return null
        s = s.replace(URL, "[link]").replace(EMAIL, "[email]").replace(PHONE, "[number]")
        s = s.replace(Regex("\\s+"), " ").trim()
        if (s.isEmpty()) return null
        if (s.length > MAX_SNIPPET_LEN) s = s.take(MAX_SNIPPET_LEN - 3).trimEnd() + "..."
        return s
    }
}
