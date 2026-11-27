package com.raijin.app.cloud

import org.json.JSONObject

/** BR-7: reads one line of an OpenAI-style server-sent-events stream. */
object SseParser {
    sealed interface Event {
        data class Delta(val text: String) : Event
        object Done : Event
        object Ignore : Event
    }

    fun parseLine(line: String): Event {
        val t = line.trim()
        if (!t.startsWith("data:")) return Event.Ignore
        val data = t.removePrefix("data:").trim()
        if (data == "[DONE]") return Event.Done
        if (data.isEmpty()) return Event.Ignore
        return try {
            val choices = JSONObject(data).optJSONArray("choices")
            val text = choices?.optJSONObject(0)?.optJSONObject("delta")?.optString("content", "").orEmpty()
            if (text.isEmpty()) Event.Ignore else Event.Delta(text)
        } catch (_: Exception) {
            Event.Ignore
        }
    }
}
