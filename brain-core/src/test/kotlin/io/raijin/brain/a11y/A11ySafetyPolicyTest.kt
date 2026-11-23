package io.raijin.brain.a11y

import io.raijin.brain.a11y.A11ySafetyPolicy.DenyReason
import io.raijin.brain.a11y.A11ySafetyPolicy.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class A11ySafetyPolicyTest {

    // ── AP-7 acceptance: "Ask it to pay on a UPI app → refused" ─────────────

    @Test
    fun `upi apps are denied outright`() {
        for (pkg in listOf(
            "com.google.android.apps.nbu.paisa.user", // GPay
            "com.phonepe.app",
            "net.one97.paytm",
            "in.org.npci.upiapp", // BHIM
        )) {
            val (verdict, reason) = A11ySafetyPolicy.packageVerdict(pkg)
            assertEquals(Verdict.DENIED, verdict, "$pkg must be denied")
            assertEquals(DenyReason.PAYMENT, reason)
        }
    }

    @Test
    fun `a pay action inside a upi app stays denied - no confirmation unlocks it`() {
        val (verdict, reason) = A11ySafetyPolicy.actionVerdict("com.phonepe.app", "Pay")
        assertEquals(Verdict.DENIED, verdict)
        assertEquals(DenyReason.PAYMENT, reason)
    }

    @Test
    fun `banking apps are denied`() {
        for (pkg in listOf("com.sbi.lotusintouch", "com.icicibank.imobile", "com.hdfcbank.payzapp")) {
            val (verdict, _) = A11ySafetyPolicy.packageVerdict(pkg)
            assertEquals(Verdict.DENIED, verdict, "$pkg must be denied")
        }
    }

    @Test
    fun `password managers and authenticators are denied`() {
        for (pkg in listOf(
            "com.x8bit.bitwarden",
            "com.onepassword.android",
            "com.google.android.apps.authenticator2",
        )) {
            val (verdict, reason) = A11ySafetyPolicy.packageVerdict(pkg)
            assertEquals(Verdict.DENIED, verdict, "$pkg must be denied")
            assertEquals(DenyReason.CREDENTIALS, reason)
        }
    }

    @Test
    fun `system install dialogs are denied`() {
        val (verdict, reason) = A11ySafetyPolicy.packageVerdict("com.android.packageinstaller")
        assertEquals(Verdict.DENIED, verdict)
        assertEquals(DenyReason.SYSTEM_INSTALL, reason)
    }

    // ── Irreversible actions require confirmation in allowed packages ───────

    @Test
    fun `send label requires confirmation in an allowed package`() {
        val (verdict, reason) = A11ySafetyPolicy.actionVerdict("com.whatsapp", "Send")
        assertEquals(Verdict.CONFIRM_REQUIRED, verdict)
        assertEquals(null, reason)
    }

    @Test
    fun `irreversible labels match case-insensitively and trimmed`() {
        for (label in listOf("send", " Send ", "POST", "delete", "Submit", "भेजें")) {
            val (verdict, _) = A11ySafetyPolicy.actionVerdict("com.example.any", label)
            assertEquals(Verdict.CONFIRM_REQUIRED, verdict, "'$label' must need confirmation")
        }
    }

    @Test
    fun `benign actions in ordinary apps are allowed`() {
        val (verdict, reason) = A11ySafetyPolicy.actionVerdict("com.whatsapp", "Search")
        assertEquals(Verdict.ALLOWED, verdict)
        assertEquals(null, reason)
    }

    @Test
    fun `whatsapp itself is not denied - the send flow keeps working`() {
        val (verdict, _) = A11ySafetyPolicy.packageVerdict("com.whatsapp")
        assertEquals(Verdict.ALLOWED, verdict)
    }

    @Test
    fun `deny refusal lines are human and honest`() {
        for (reason in DenyReason.entries) {
            assertTrue(reason.refusal.isNotBlank())
            assertTrue(reason.refusal.contains("hard safety rule"), "refusal must name the rule")
        }
    }
}
