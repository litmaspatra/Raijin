package com.raijin.app.audio

/** Process-wide facts the recorder code wants to report. */
object MicState {
    /** True once the assistant service has started with the microphone foreground type. */
    @Volatile var serviceHasMicType: Boolean = false
}

/** Plain-words summary of what the microphone did during one listen. Pure, so it is unit-tested. */
object ListenDiagnostics {
    fun describe(
        stateAfterStart: Int?,
        reads: Int,
        silentReads: Int,
        negativeRead: Int?,
        clientSilenced: Boolean?,
        appImportance: Int?,
        micService: Boolean,
    ): String {
        val state = when (stateAfterStart) {
            null -> "recorder never created"
            3 -> "recording"
            1 -> "stopped"
            else -> "state $stateAfterStart"
        }
        val importance = when {
            appImportance == null -> "unknown"
            appImportance <= 100 -> "foreground"
            appImportance <= 125 -> "foreground service"
            appImportance <= 200 -> "visible or perceptible"
            else -> "background ($appImportance)"
        }
        val silenced = when (clientSilenced) { true -> "YES" ; false -> "no"; null -> "unknown" }
        val read = if (negativeRead != null) ", read error $negativeRead" else ""
        return "recorder $state, $reads reads ($silentReads all-zero)$read, Android silenced the mic: $silenced, app is $importance, mic service type: ${if (micService) "yes" else "no"}"
    }
}
