package io.raijin.brain.skills

import io.raijin.contract.ActionArgs
import io.raijin.contract.ActionType
import io.raijin.contract.MusicCommand

/**
 * SK-2: maps a validated skill CallTool step onto a contract
 * (ActionType, ActionArgs) pair the actuator can execute.
 *
 * Fail-closed by design: only the documented subset below is supported;
 * anything else - a registry tool this mapper does not understand, a
 * missing argument, a malformed value - is an error, never a guess.
 * Widening the subset is an explicit code change with tests.
 */
object SkillToolMapper {

    data class MappedToolCall(
        val actionType: ActionType,
        val args: ActionArgs,
    )

    sealed interface MapResult {
        data class Ok(val call: MappedToolCall) : MapResult
        data class Error(val reason: String) : MapResult
    }

    private val TIME_OF_DAY = Regex("""(\d{1,2}):(\d{2})""")

    fun map(tool: String, args: Map<String, String>): MapResult = when (tool) {
        ActionType.SET_ALARM.name -> mapAlarm(args)
        ActionType.SET_TIMER.name -> mapTimer(args)
        ActionType.MUSIC_CONTROL.name -> mapMusic(args)
        else -> MapResult.Error("tool $tool is not supported by the skill runtime yet")
    }

    /** args: time "H:mm"/"HH:mm", or hour+minute; optional label. */
    private fun mapAlarm(args: Map<String, String>): MapResult {
        val time = args["time"]
        val (hour, minute) = when {
            time != null -> {
                val match = TIME_OF_DAY.matchEntire(time.trim())
                    ?: return MapResult.Error("alarm time must be H:mm, was '$time'")
                match.groupValues[1].toInt() to match.groupValues[2].toInt()
            }

            args["hour"] != null && args["minute"] != null -> {
                val h = args["hour"]!!.toIntOrNull()
                    ?: return MapResult.Error("alarm hour is not a number")
                val m = args["minute"]!!.toIntOrNull()
                    ?: return MapResult.Error("alarm minute is not a number")
                h to m
            }

            else -> return MapResult.Error("alarm needs time (H:mm) or hour+minute")
        }
        if (hour !in 0..23 || minute !in 0..59) {
            return MapResult.Error("alarm time out of range: $hour:$minute")
        }
        return MapResult.Ok(
            MappedToolCall(
                ActionType.SET_ALARM,
                ActionArgs.Alarm(hour = hour, minute = minute, label = args["label"]),
            ),
        )
    }

    /** args: seconds, or minutes; optional label. */
    private fun mapTimer(args: Map<String, String>): MapResult {
        val seconds = args["seconds"]?.toIntOrNull()
            ?: args["minutes"]?.toIntOrNull()?.let { it * 60 }
            ?: return MapResult.Error("timer needs seconds or minutes")
        if (seconds !in 1..86_400) {
            return MapResult.Error("timer length out of range: ${seconds}s")
        }
        return MapResult.Ok(
            MappedToolCall(
                ActionType.SET_TIMER,
                ActionArgs.Timer(lengthSeconds = seconds, label = args["label"]),
            ),
        )
    }

    /** args: command (MusicCommand name); query required for PLAY_QUERY. */
    private fun mapMusic(args: Map<String, String>): MapResult {
        val commandName = args["command"]
            ?: return MapResult.Error("music control needs a command")
        val command = MusicCommand.entries.firstOrNull { it.name == commandName.uppercase() }
            ?: return MapResult.Error("unknown music command: $commandName")
        if (command == MusicCommand.PLAY_QUERY && args["query"].isNullOrBlank()) {
            return MapResult.Error("PLAY_QUERY needs a query")
        }
        return MapResult.Ok(
            MappedToolCall(
                ActionType.MUSIC_CONTROL,
                ActionArgs.Music(command = command, query = args["query"]),
            ),
        )
    }
}
