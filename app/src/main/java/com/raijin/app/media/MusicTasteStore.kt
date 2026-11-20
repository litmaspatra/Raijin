package com.raijin.app.media

import io.raijin.brain.MusicTasteModel
import io.raijin.contract.MusicEvent
import io.raijin.contract.MusicEventType
import io.raijin.contract.MusicSource
import io.raijin.contract.MusicTastePort
import io.raijin.contract.TasteSignal
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.TimeZone

/**
 * MU-6: on-device JSON event log behind [MusicTastePort].
 *
 * Events append to a capped ring ([MAX_EVENTS] newest kept) in a single file
 * under the app's private storage. Aggregation runs through brain-core's
 * [MusicTasteModel], so ranking stays interpretable and unit-tested. Nothing
 * here ever leaves the device.
 */
class MusicTasteStore(
    private val file: File,
    private val zone: TimeZone = TimeZone.getDefault(),
    private val maxEvents: Int = MAX_EVENTS,
) : MusicTastePort {

    companion object {
        const val MAX_EVENTS = 1_000
    }

    private val lock = Any()
    private val events = ArrayDeque<MusicEvent>()

    init {
        require(maxEvents > 0) { "maxEvents must be positive" }
        load()
    }

    override suspend fun recordMusicEvent(event: MusicEvent) {
        synchronized(lock) {
            events.addLast(event)
            while (events.size > maxEvents) events.removeFirst()
            persist()
        }
    }

    override suspend fun topArtists(limit: Int, nowEpochMillis: Long?): List<TasteSignal> =
        snapshot().let { MusicTasteModel.aggregate(it, nowEpochMillis ?: now(), zone, { e -> e.artist }, limit) }

    override suspend fun topLanguages(limit: Int, nowEpochMillis: Long?): List<TasteSignal> =
        snapshot().let { MusicTasteModel.aggregate(it, nowEpochMillis ?: now(), zone, { e -> e.language }, limit) }

    override suspend fun topGenres(limit: Int, nowEpochMillis: Long?): List<TasteSignal> =
        snapshot().let { MusicTasteModel.aggregate(it, nowEpochMillis ?: now(), zone, { e -> e.genre }, limit) }

    override suspend fun recentEvents(limit: Int): List<MusicEvent> =
        synchronized(lock) { events.takeLast(limit.coerceAtLeast(0)) }

    private fun snapshot(): List<MusicEvent> = synchronized(lock) { events.toList() }

    private fun now(): Long = System.currentTimeMillis()

    private fun load() {
        if (!file.exists()) return
        runCatching {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                events.addLast(
                    MusicEvent(
                        type = MusicEventType.valueOf(o.getString("type")),
                        track = o.getString("track"),
                        artist = o.getString("artist"),
                        language = o.optString("language").ifBlank { null },
                        genre = o.optString("genre").ifBlank { null },
                        source = runCatching {
                            MusicSource.valueOf(o.optString("source"))
                        }.getOrDefault(MusicSource.UNKNOWN),
                        atEpochMillis = o.getLong("at"),
                        completionRatio = if (o.has("completion")) o.getDouble("completion").toFloat() else null,
                    ),
                )
            }
            while (events.size > maxEvents) events.removeFirst()
        }
        // A corrupt log must never crash playback: start empty instead.
    }

    private fun persist() {
        val arr = JSONArray()
        for (e in events) {
            arr.put(
                JSONObject()
                    .put("type", e.type.name)
                    .put("track", e.track)
                    .put("artist", e.artist)
                    .put("language", e.language ?: "")
                    .put("genre", e.genre ?: "")
                    .put("source", e.source.name)
                    .put("at", e.atEpochMillis)
                    .apply { e.completionRatio?.let { put("completion", it.toDouble()) } },
            )
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(file)) {
            // Rename can fail on some volumes; fall back to a direct write.
            file.writeText(arr.toString())
            tmp.delete()
        }
    }
}
