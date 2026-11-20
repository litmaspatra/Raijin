package com.raijin.app.diagnostics

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One ledger entry: something Raijin did, or a consent decision the owner made. */
data class ActionLogEntry(
    val epochMs: Long,
    val kind: Kind,
    val summary: String,
    val outcome: String,
) {
    enum class Kind { ACTION, CONSENT }
}

/**
 * EX-1/EX-4: on-device action log + consent ledger. SharedPreferences ring
 * buffer (newest last, capped at 200). Local-only: never leaves the device,
 * never includes API keys or message bodies.
 */
class ActionLogStore(context: Context) {
    private val prefs = context.getSharedPreferences("raijin_action_log", Context.MODE_PRIVATE)

    fun append(kind: ActionLogEntry.Kind, summary: String, outcome: String) {
        val entries = list().toMutableList()
        entries += ActionLogEntry(System.currentTimeMillis(), kind, summary, outcome)
        val trimmed = if (entries.size > MAX_ENTRIES) entries.takeLast(MAX_ENTRIES) else entries
        val array = JSONArray()
        trimmed.forEach { e ->
            array.put(JSONObject().apply {
                put("ts", e.epochMs)
                put("kind", e.kind.name)
                put("summary", e.summary)
                put("outcome", e.outcome)
            })
        }
        prefs.edit().putString(KEY_LOG, array.toString()).apply()
    }

    fun list(): List<ActionLogEntry> {
        val json = prefs.getString(KEY_LOG, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                ActionLogEntry(
                    epochMs = o.getLong("ts"),
                    kind = ActionLogEntry.Kind.valueOf(o.getString("kind")),
                    summary = o.getString("summary"),
                    outcome = o.getString("outcome"),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun clear() = prefs.edit().remove(KEY_LOG).apply()

    companion object {
        private const val KEY_LOG = "entries_json"
        private const val MAX_ENTRIES = 200
    }
}
