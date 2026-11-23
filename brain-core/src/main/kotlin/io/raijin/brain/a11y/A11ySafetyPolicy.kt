package io.raijin.brain.a11y

/**
 * AP-7: hard safety rules for accessibility-driven control.
 *
 * Every accessibility action (current: the WhatsApp send tap; future: the
 * AP-6 see-decide-act loop) must pass this policy first. The policy is pure
 * data + matching, so it is fully JVM-testable and the service layer stays
 * a thin enforcement shell.
 *
 * Rules (PRD section 7.4, AP-7):
 * 1. Default denylist: banking/UPI/payment apps, password managers and
 *    authenticators, and system install/uninstall dialogs are never acted on.
 * 2. Payments are blocked OUTRIGHT: no confirmation can unlock an action in
 *    a payment-context package.
 * 3. Irreversible in-app actions (send, post, pay, delete, submit, ...)
 *    always require confirmation. The confirmation point is the brain's own
 *    owner confirmation (e.g. SEND_MESSAGE); the policy only records that
 *    one is required, the caller proves it happened.
 */
object A11ySafetyPolicy {

    /** What the policy decided about a package or action. */
    enum class Verdict {
        /** The action may proceed without an extra confirmation. */
        ALLOWED,

        /** The action may proceed only after an explicit owner confirmation. */
        CONFIRM_REQUIRED,

        /** The action must be refused. No confirmation can unlock it. */
        DENIED,
    }

    /** Why a package is denied, for honest owner-facing refusal lines. */
    enum class DenyReason(val refusal: String) {
        PAYMENT("I can't act inside payment or banking apps. That's a hard safety rule."),
        CREDENTIALS("I can't act inside password managers or authenticator apps. That's a hard safety rule."),
        SYSTEM_INSTALL("I can't interact with system install or uninstall dialogs. That's a hard safety rule."),
    }

    // ── Denylist (package prefixes; matched case-insensitively) ─────────────
    //
    // Prefix matching (not exact equality) so vendor forks and per-device
    // package variants stay covered. Keep entries lowercase.

    private val PAYMENT_PACKAGES = listOf(
        // UPI / wallets (India-first, per owner context)
        "com.google.android.apps.nbu.paisa.user", // Google Pay
        "com.phonepe.app",
        "net.one97.paytm",
        "in.org.npci.upiapp", // BHIM
        "com.mobikwik_new",
        "com.freecharge.android",
        // Banking apps
        "com.sbi.lotusintouch", // YONO SBI
        "com.hdfcbank.payzapp",
        "com.icicibank.imobile",
        "com.axis.mobile",
        "com.kotak.bank",
        "com.idfcfirstbank.optimus",
        "com.bankofbaroda.mconnect",
        "com.pnb.kitkat", // PNB One
        "com.unionbank.ecommerce.mobile.android", // Union Bank vyom
        "com.csam.icici.bank.imobile", // iMobile variant
        // Global payment apps
        "com.paypal.android.p2pmobile",
        "com.squareup.cash",
        "com.venmo",
    )

    private val CREDENTIAL_PACKAGES = listOf(
        "com.x8bit.bitwarden",
        "com.onepassword.android",
        "com.lastpass.lpandroid",
        "com.dashlane",
        "com.authy.authy",
        "com.microsoft.authenticator",
        "com.google.android.apps.authenticator2",
        "com.kpmanager", // Keeps2Offline / KP2A family prefix
        "keepass2android",
    )

    private val SYSTEM_INSTALL_PACKAGES = listOf(
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.miui.packageinstaller",
        "com.samsung.android.packageinstaller",
        "com.android.vending", // Play Store install/uninstall surface
    )

    /**
     * Labels (case-insensitive, matched on trimmed node text/content-desc)
     * whose tap is irreversible or outward-facing and therefore always needs
     * an explicit owner confirmation first.
     */
    private val IRREVERSIBLE_LABELS = setOf(
        "send", "post", "pay", "pay now", "delete", "submit", "transfer",
        "purchase", "buy", "buy now", "place order", "publish", "tweet",
        "confirm payment", "proceed to pay", "send money", "send payment",
        "भेजें", "भुगतान करें", "हटाएं", // Hindi: send, pay, delete
    )

    /** The package verdict. Deny reasons are stable and owner-readable. */
    fun packageVerdict(packageName: String): Pair<Verdict, DenyReason?> {
        val pkg = packageName.lowercase()
        return when {
            matchesAny(pkg, PAYMENT_PACKAGES) -> Verdict.DENIED to DenyReason.PAYMENT
            matchesAny(pkg, CREDENTIAL_PACKAGES) -> Verdict.DENIED to DenyReason.CREDENTIALS
            matchesAny(pkg, SYSTEM_INSTALL_PACKAGES) -> Verdict.DENIED to DenyReason.SYSTEM_INSTALL
            else -> Verdict.ALLOWED to null
        }
    }

    /**
     * The verdict for tapping a node labelled [nodeLabel] inside [packageName].
     * Package denial always wins; an irreversible label in an allowed package
     * requires confirmation; anything else is allowed.
     */
    fun actionVerdict(packageName: String, nodeLabel: String): Pair<Verdict, DenyReason?> {
        val (pkgVerdict, reason) = packageVerdict(packageName)
        if (pkgVerdict == Verdict.DENIED) return pkgVerdict to reason
        val label = nodeLabel.trim().lowercase()
        return if (label in IRREVERSIBLE_LABELS) {
            Verdict.CONFIRM_REQUIRED to null
        } else {
            Verdict.ALLOWED to null
        }
    }

    private fun matchesAny(pkg: String, prefixes: List<String>): Boolean =
        prefixes.any { pkg.startsWith(it) }
}
