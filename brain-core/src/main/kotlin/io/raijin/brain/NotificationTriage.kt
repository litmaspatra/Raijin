package io.raijin.brain

/**
 * NT-2 briefing triage, rule-based and fully on-device. Labels a notification
 * as important, promotional or ordinary from plain keyword rules. Only the
 * label is kept; the text itself is never stored or sent anywhere.
 */
object NotificationTriage {
    enum class Importance { IMPORTANT, PROMO, NORMAL }

    private val STRONG_IMPORTANT = Regex(
        """\b(overdue|payment failed|failed payment|security alert|suspicious|unauthori[sz]ed|debited|credited|declined|""" +
            """out for delivery|delivered|arriving (?:today|tomorrow)|flight|boarding|""" +
            """meeting (?:at|in|starts)|starts in \d+|due (?:today|tomorrow|on|by)|bill (?:is )?due|emi due|appointment)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val PROMO = Regex(
        """(\b\d{1,3}\s?% ?off\b|\bflat (?:rs\.?|₹)?\s?\d+|\boffers?\b|\bsale\b|\bdiscounts?\b|\bcashback\b|\bcoupons?\b|""" +
            """\bdeals?\b|\blimited time\b|\bhurry\b|\bexclusive\b|\bshop now\b|\bbuy \d+|\bfree delivery\b|\bsave (?:up to )?(?:rs\.?|₹)?\s?\d+|""" +
            """\bunsubscribe\b|\bnew arrivals?\b|\blast chance\b|\bwin\b)""",
        RegexOption.IGNORE_CASE,
    )

    // Strong signals that stay important even if the text also mentions an offer.
    private val ALWAYS_IMPORTANT = Regex(
        """\b(overdue|payment failed|failed payment|security alert|suspicious|unauthori[sz]ed|debited|declined)\b""",
        RegexOption.IGNORE_CASE,
    )

    fun classify(text: String): Importance {
        if (text.isBlank()) return Importance.NORMAL
        if (ALWAYS_IMPORTANT.containsMatchIn(text)) return Importance.IMPORTANT
        if (PROMO.containsMatchIn(text)) return Importance.PROMO
        if (STRONG_IMPORTANT.containsMatchIn(text)) return Importance.IMPORTANT
        return Importance.NORMAL
    }

    private val KINDS: List<Pair<List<String>, String>> = listOf(
        listOf("com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.thoughtcrime.securesms") to "chat messages",
        listOf("com.google.android.apps.messaging", "com.samsung.android.messaging") to "text messages",
        listOf("com.google.android.gm", "com.microsoft.office.outlook", "com.yahoo.mobile.client.android.mail") to "email",
        listOf("net.one97.paytm", "com.phonepe.app", "com.google.android.apps.nbu.paisa.user", "in.amazon.mShop.android.shopping.pay") to "payments",
        listOf("com.amazon.mShop.android.shopping", "com.flipkart.android", "com.myntra.android", "in.swiggy.android", "com.application.zomato", "com.grofers.customerapp", "com.zepto.app") to "shopping and food",
        listOf("com.instagram.android", "com.facebook.katana", "com.twitter.android", "com.linkedin.android", "com.snapchat.android") to "social media",
        listOf("com.google.android.calendar") to "calendar",
    )

    /** Plain-words description of what an app is, or null when unknown (never guessed). */
    fun appKind(pkg: String): String? = KINDS.firstOrNull { pkg in it.first }?.second
}
