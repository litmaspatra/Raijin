package io.raijin.brain

import kotlin.test.Test
import kotlin.test.assertEquals

class SentenceChunkerTest {
    @Test fun splitsAcrossDeltas() {
        val c = SentenceChunker()
        assertEquals(emptyList(), c.feed("The sky is blue beca"))
        assertEquals(listOf("The sky is blue because of scattering."), c.feed("use of scattering. Next"))
        assertEquals(listOf("Next sentence follows here."), c.feed(" sentence follows here. "))
        assertEquals(emptyList(), c.finish())
    }

    @Test fun decimalsDoNotSplit() {
        val c = SentenceChunker()
        assertEquals(emptyList(), c.feed("The value is 3.5 percent"))
        assertEquals(listOf("The value is 3.5 percent today."), c.feed(" today. "))
    }

    @Test fun shortFragmentJoinsNext() {
        val c = SentenceChunker()
        assertEquals(listOf("Yes. That is correct."), c.feed("Yes. That is correct. "))
    }

    @Test fun finishFlushesTail() {
        val c = SentenceChunker()
        c.feed("Hello there my friend")
        assertEquals(listOf("Hello there my friend"), c.finish())
    }

    @Test fun hindiDandaSplits() {
        val c = SentenceChunker()
        assertEquals(listOf("मौसम आज बहुत अच्छा है\u0964"), c.feed("मौसम आज बहुत अच्छा है\u0964 और"))
    }

    @Test fun newlineSplits() {
        val c = SentenceChunker()
        assertEquals(listOf("First line of the list"), c.feed("First line of the list\nSecond"))
    }
}
