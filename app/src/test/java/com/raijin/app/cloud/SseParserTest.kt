package com.raijin.app.cloud

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SseParserTest {
    @Test fun deltaIsRead() =
        assertEquals(SseParser.Event.Delta("Hel"), SseParser.parseLine("""data: {"choices":[{"delta":{"content":"Hel"}}]}"""))

    @Test fun doneMarker() = assertEquals(SseParser.Event.Done, SseParser.parseLine("data: [DONE]"))

    @Test fun roleOnlyChunkIsIgnored() =
        assertEquals(SseParser.Event.Ignore, SseParser.parseLine("""data: {"choices":[{"delta":{"role":"assistant"}}]}"""))

    @Test fun commentsAndBlankLinesAreIgnored() {
        assertEquals(SseParser.Event.Ignore, SseParser.parseLine(": keep-alive"))
        assertEquals(SseParser.Event.Ignore, SseParser.parseLine(""))
    }

    @Test fun garbageIsIgnored() = assertEquals(SseParser.Event.Ignore, SseParser.parseLine("data: {not json"))
}
