package io.raijin.brain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WakeKeywordsTest {
    private val m = "\u2581"
    private val tok = UnigramTokenizer.parse(
        listOf("${m}HE" to -4f, "Y" to -3f, "${m}E" to -5f, "V" to -6f, "A" to -2f, "${m}EV" to -9f, "VA" to -10f, "${m}" to -7f, "E" to -3f, "${m}EVA" to -20f)
            .joinToString("\n") { "${it.first}\t${it.second}" },
    )

    @Test fun picksHighestScoringSplit() {
        // ▁E + V + A = -13 beats ▁EV + A = -11? no: -11 is higher; ▁EVA = -20 is lowest
        assertEquals(listOf("${m}HE", "Y", "${m}EV", "A"), tok.encode("hey eva"))
    }

    @Test fun lineHasScoreAndThreshold() {
        assertEquals("${m}HE Y ${m}EV A :1.5 #0.25", WakeKeywords.line("Eva", tok))
    }

    @Test fun unknownLettersGiveNull() {
        assertNull(WakeKeywords.line("Q", tok))
    }
}
