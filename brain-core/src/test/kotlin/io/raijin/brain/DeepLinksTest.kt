package io.raijin.brain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeepLinksTest {

    @Test fun `maps search uri encodes the query`() {
        assertEquals("geo:0,0?q=coffee+shops+near+CP", DeepLinks.mapsSearchUri("coffee shops near CP"))
    }

    @Test fun `plus-prefixed number is explicit international regardless of region`() {
        val inNumber = DeepLinks.normalizeWhatsAppNumber("+91 98765 43210", null)
        assertEquals("919876543210", inNumber?.e164Digits)
        assertFalse(inNumber!!.regionInferred)
        // An explicit +44 stays +44 even when the device region is India.
        val ukNumber = DeepLinks.normalizeWhatsAppNumber("+44 7911 123456", "IN")
        assertEquals("447911123456", ukNumber?.e164Digits)
        assertFalse(ukNumber!!.regionInferred)
    }

    @Test fun `regional dialing prefixes are interpreted only under their region`() {
        // 00 is the international dialing prefix in India: parsed under IN
        // it reaches +91, and the region assumption stays visible.
        val underIndia = DeepLinks.normalizeWhatsAppNumber("0091 98765 43210", "IN")
        assertEquals("919876543210", underIndia?.e164Digits)
        assertTrue(underIndia!!.regionInferred)
        // 00 is NOT the US international prefix (011 is): never silently
        // reinterpreted as +91.
        assertNull(DeepLinks.normalizeWhatsAppNumber("0091 98765 43210", "US"))
        // Without any region, a dialing-prefix number is ambiguous.
        assertNull(DeepLinks.normalizeWhatsAppNumber("0091 98765 43210", null))
    }

    @Test fun `national number is normalized through the given region and flagged inferred`() {
        val normalized = DeepLinks.normalizeWhatsAppNumber("98765 43210", "IN")
        assertEquals("919876543210", normalized?.e164Digits)
        assertTrue(normalized!!.regionInferred)
        assertEquals("IN", normalized.regionIso)
    }

    @Test fun `us national number gets +1 never +91`() {
        val normalized = DeepLinks.normalizeWhatsAppNumber("202 555 0123", "US")
        assertEquals("12025550123", normalized?.e164Digits)
        assertTrue(normalized!!.regionInferred)
    }

    @Test fun `uk national trunk zero handled per national rules`() {
        val normalized = DeepLinks.normalizeWhatsAppNumber("07911 123456", "GB")
        assertEquals("447911123456", normalized?.e164Digits)
        assertTrue(normalized!!.regionInferred)
    }

    @Test fun `italian significant leading zero is preserved`() {
        // Italian landlines keep their leading 0 in E.164; hand-stripping
        // would reach the wrong subscriber.
        val normalized = DeepLinks.normalizeWhatsAppNumber("06 6982", "IT")
        assertEquals("39066982", normalized?.e164Digits)
        assertTrue(normalized!!.regionInferred)
    }

    @Test fun `digits-only international length is ambiguous not auto-trusted`() {
        // 12 digits could be +91 national storage or something else entirely;
        // without an explicit prefix it is never treated as international.
        assertNull(DeepLinks.normalizeWhatsAppNumber("919876543210", null))
        // Even with a region, a 12-digit national number is invalid there.
        assertNull(DeepLinks.normalizeWhatsAppNumber("919876543210", "IN"))
    }

    @Test fun `national number without a region is ambiguous`() {
        assertNull(DeepLinks.normalizeWhatsAppNumber("9876543210", null))
        assertNull(DeepLinks.normalizeWhatsAppNumber("9876543210", "XX"))
    }

    @Test fun `number invalid for the given region is ambiguous`() {
        // Not a valid Indian national number - never force it to +91.
        assertNull(DeepLinks.normalizeWhatsAppNumber("12345", "IN"))
        assertNull(DeepLinks.normalizeWhatsAppNumber("+12345", null))
    }

    @Test fun `whatsAppChatUri wraps the normalized digits`() {
        assertEquals(
            "https://wa.me/919876543210",
            DeepLinks.whatsAppChatUri("98765 43210", "IN"),
        )
        assertNull(DeepLinks.whatsAppChatUri("9876543210", null))
    }

    @Test fun `spoken country names map to regions and unknown names do not`() {
        assertEquals("IN", DeepLinks.regionForCountryName("India"))
        assertEquals("GB", DeepLinks.regionForCountryName("uk"))
        assertEquals("US", DeepLinks.regionForCountryName("United States"))
        assertNull(DeepLinks.regionForCountryName("Narnia"))
    }
}
