package io.raijin.brain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** BR-10: the persona prompt loads from its versioned repo file. */
class PersonaTest {

    @Test
    fun personaLoadsFromVersionedResource() {
        assertTrue(Persona.systemPrompt.isNotBlank())
    }

    @Test
    fun personaMatchesDeclaredVersion() {
        // The lazy load would already have failed hard on a marker mismatch;
        // this pins the expected version so bumps are deliberate.
        assertEquals(1, Persona.VERSION)
    }

    @Test
    fun personaCarriesThePrdTraits() {
        val p = Persona.systemPrompt.lowercase()
        assertTrue("calm" in p, "persona must be calm")
        assertTrue("sharp" in p, "persona must be sharp")
        assertTrue("dry" in p, "persona must be dry")
        assertTrue("brief" in p, "persona must be brief")
        assertTrue("hinglish" in p, "persona must be bilingual (English/Hinglish)")
    }

    @Test
    fun personaForbidsInventedFacts() {
        assertTrue(Persona.systemPrompt.contains("Never invent facts"))
    }
}
