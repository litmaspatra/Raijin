package com.raijin.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KokoroVoiceTest {
    private val lexicon = SpokenLexicon.parse(
        sequenceOf("hello h ə l o", "world w ɜ l d", "don't d o n t", "good g ʊ d", "morning m ɔ r n"),
    )

    @Test fun knownWordsCanBeSpoken() {
        assertTrue(lexicon.canSpeak("Hello, world! Good morning."))
        assertTrue(lexicon.canSpeak("Don't"))
    }

    @Test fun unknownWordIsReportedSoTheSystemVoiceReadsIt() {
        assertEquals(listOf("reminders"), lexicon.unknownWords("Hello reminders"))
        assertFalse(lexicon.canSpeak("hello zorblax"))
    }

    @Test fun numbersAreNeverSilentlyDropped() {
        assertEquals(listOf("3"), lexicon.unknownWords("hello 3 world"))
        assertEquals(listOf("6", "30"), lexicon.unknownWords("good 6:30"))
    }

    @Test fun emptyTextIsSafe() {
        assertTrue(lexicon.canSpeak(""))
        assertTrue(lexicon.canSpeak("...!"))
    }

    @Test fun voiceCatalogHasOneFemaleAndOneMale() {
        assertEquals(2, KokoroVoices.ALL.size)
        assertEquals("af_heart", KokoroVoices.forGender(VoiceGender.FEMALE).id)
        assertEquals("bm_george", KokoroVoices.forGender(VoiceGender.MALE).id)
        assertEquals(setOf(0, 1), KokoroVoices.ALL.map { it.sid }.toSet())
    }

    @Test fun unknownVoiceIdFallsBackToDefault() {
        assertEquals(KokoroVoices.DEFAULT, KokoroVoices.byId("bf_emma"))
        assertEquals(KokoroVoices.MALE, KokoroVoices.byId("bm_george"))
        assertEquals(KokoroVoices.DEFAULT, KokoroVoices.byId(null))
    }

    @Test fun textIsSplitIntoSentences() {
        assertEquals(listOf("Hello there.", "How are you?", "Fine!"), splitSentences("Hello there. How are you?  Fine!"))
        assertEquals(listOf("No end punctuation"), splitSentences("No end punctuation"))
    }
}
