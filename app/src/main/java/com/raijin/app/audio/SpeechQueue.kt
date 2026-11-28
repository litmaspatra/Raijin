package com.raijin.app.audio

/**
 * Plays queued sentences one after another on a worker thread, so a streamed answer is read in
 * order whether each sentence uses the on-device voice or the system voice. [playOne] blocks until
 * the sentence has finished. [clear] drops everything that has not started yet.
 */
class SpeechQueue(private val playOne: (String) -> Unit) {
    private val lock = Object()
    private val items = ArrayDeque<String>()
    private var worker: Thread? = null

    val isBusy: Boolean get() = synchronized(lock) { worker != null || items.isNotEmpty() }

    fun enqueue(text: String) {
        synchronized(lock) {
            items.addLast(text)
            if (worker == null) {
                worker = Thread({ run() }, "raijin-speech").also { it.isDaemon = true; it.start() }
            }
        }
    }

    fun clear() {
        synchronized(lock) { items.clear() }
    }

    private fun run() {
        while (true) {
            val next = synchronized(lock) {
                val n = items.removeFirstOrNull()
                if (n == null) worker = null
                n
            } ?: return
            runCatching { playOne(next) }
        }
    }
}
