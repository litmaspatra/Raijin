package com.raijin.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SpeechQueueTest {
    @Test fun playsInOrderOneAtATime() {
        val played = CopyOnWriteArrayList<String>()
        val done = CountDownLatch(3)
        val q = SpeechQueue { played += it; Thread.sleep(20); done.countDown() }
        q.enqueue("one"); q.enqueue("two"); q.enqueue("three")
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("one", "two", "three"), played.toList())
    }

    @Test fun clearDropsSentencesThatHaveNotStarted() {
        val played = CopyOnWriteArrayList<String>()
        val first = CountDownLatch(1)
        val release = CountDownLatch(1)
        val q = SpeechQueue { played += it; first.countDown(); release.await(5, TimeUnit.SECONDS) }
        q.enqueue("one"); q.enqueue("two"); q.enqueue("three")
        assertTrue(first.await(5, TimeUnit.SECONDS))
        q.clear()
        release.countDown()
        Thread.sleep(200)
        assertEquals(listOf("one"), played.toList())
        assertFalse(q.isBusy)
    }

    @Test fun aFailingSentenceDoesNotStopTheQueue() {
        val played = CopyOnWriteArrayList<String>()
        val done = CountDownLatch(1)
        val q = SpeechQueue { if (it == "bad") error("boom"); played += it; if (it == "two") done.countDown() }
        q.enqueue("bad"); q.enqueue("two")
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("two"), played.toList())
    }
}
