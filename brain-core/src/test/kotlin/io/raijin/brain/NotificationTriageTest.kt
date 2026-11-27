package io.raijin.brain

import io.raijin.brain.BriefingText.AppDigest
import io.raijin.brain.NotificationTriage.Importance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotificationTriageTest {
    @Test fun billDueIsImportant() =
        assertEquals(Importance.IMPORTANT, NotificationTriage.classify("Electricity bill due tomorrow, Rs 1,240"))

    @Test fun deliveryIsImportant() =
        assertEquals(Importance.IMPORTANT, NotificationTriage.classify("Your order is out for delivery"))

    @Test fun saleIsPromo() =
        assertEquals(Importance.PROMO, NotificationTriage.classify("Flat 50% off on shoes. Shop now!"))

    @Test fun cashbackOnBillIsPromoNotImportant() =
        assertEquals(Importance.PROMO, NotificationTriage.classify("Pay your bill due today and get cashback"))

    @Test fun overdueStaysImportantEvenWithOffer() =
        assertEquals(Importance.IMPORTANT, NotificationTriage.classify("Payment failed. Retry now for an exclusive offer"))

    @Test fun plainTextIsNormal() =
        assertEquals(Importance.NORMAL, NotificationTriage.classify("Rahul liked your photo"))

    @Test fun appKindKnownAndUnknown() {
        assertEquals("email", NotificationTriage.appKind("com.google.android.gm"))
        assertEquals(null, NotificationTriage.appKind("com.unknown.app"))
    }

    @Test fun digestShowsImportantFirstAndFlagsPromo() {
        val out = BriefingText.notificationDigest(
            true, true,
            listOf(
                AppDigest("Swiggy", 3, "Flat 50% off", promoCount = 3, appKind = "shopping and food"),
                AppDigest("Gmail", 2, "Bill due Friday", importantCount = 1, importantSnippet = "Electricity bill due Friday", appKind = "email"),
            ),
        )
        assertTrue(out.startsWith("Important, Gmail (email): Electricity bill due Friday."), out)
        assertTrue(out.contains("Gmail (email): 1 other."), out)
        assertTrue(out.contains("Swiggy (shopping and food): 3 promotional."), out)
    }

    @Test fun untriagedDigestIsUnchanged() {
        val out = BriefingText.notificationDigest(true, true, listOf(AppDigest("Bank", 2, "Statement ready")))
        assertEquals("Bank: Statement ready, plus 1 more.", out)
    }
}
