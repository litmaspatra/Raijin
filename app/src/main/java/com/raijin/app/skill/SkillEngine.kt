package com.raijin.app.skill

import android.util.Log
import io.raijin.contract.ActionArgs
import io.raijin.contract.ActionRisk
import io.raijin.contract.ActionType
import io.raijin.contract.ActuatorPort
import io.raijin.contract.DispatchResult
import io.raijin.contract.MusicCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Dynamic Skill Execution Engine.
 *
 * Allows Raijin to learn, install, and execute custom modular skills (similar to Hermes).
 * Skills are stored locally in Room and can be triggered via natural language patterns.
 */
class SkillEngine(
    private val skillDao: SkillDao,
    private val actuator: ActuatorPort,
) {
    companion object {
        private const val TAG = "SkillEngine"
    }

    /** STT phase 0: read-only peek at installed trigger patterns; never executes. */
    suspend fun skillPatterns(): List<String> = withContext(Dispatchers.IO) {
        skillDao.getAll().map { it.pattern }
    }

    /**
     * Attempts to match and execute an installed custom skill.
     * Returns true if a skill was matched and dispatched.
     */
    suspend fun tryExecuteSkill(utterance: String, traceId: String): DispatchResult? = withContext(Dispatchers.IO) {
        val trimmed = utterance.trim()
        val skills = skillDao.getAll()

        for (skill in skills) {
            val matches = try {
                Regex(skill.pattern, RegexOption.IGNORE_CASE).containsMatchIn(trimmed)
            } catch (_: Exception) {
                trimmed.contains(skill.pattern, ignoreCase = true)
            }

            if (matches) {
                Log.i(TAG, "Matched custom skill: ${skill.name} for utterance: '$utterance'")
                skillDao.incrementUsage(skill.id)

                val actionType = try {
                    ActionType.valueOf(skill.actionType)
                } catch (_: Exception) {
                    ActionType.NONE
                }

                val args = when (actionType) {
                    ActionType.MUSIC_CONTROL -> ActionArgs.Music(MusicCommand.PAUSE)
                    else -> ActionArgs.None
                }

                val result = DispatchResult(
                    traceId = traceId,
                    actionType = actionType,
                    args = args,
                    confidence = 0.98f,
                    risk = ActionRisk.LOW,
                    vocalResponse = "Executing skill: ${skill.name}.",
                )

                actuator.execute(result)
                return@withContext result
            }
        }
        null
    }

    /**
     * Seeds initial tactical skills if database is empty.
     */
    suspend fun seedDefaultsIfEmpty() = withContext(Dispatchers.IO) {
        if (skillDao.count() == 0) {
            // PRD 2.2.1: no seeded destructive skill. Skills must fail closed and
            // never default to DELETE_FILES.
            skillDao.insert(
                SkillEntity(
                    name = "Silence Audio",
                    pattern = "^(?:silent|silence|mute audio)$",
                    actionType = "MUSIC_CONTROL",
                    description = "Pauses all playing media",
                ),
            )
            Log.i(TAG, "Seeded default skills")
        }
    }

    /**
     * Installs a new skill dynamically.
     */
    suspend fun installSkill(
        name: String,
        pattern: String,
        actionType: String,
        description: String = "",
    ): Long = withContext(Dispatchers.IO) {
        skillDao.insert(
            SkillEntity(
                name = name,
                pattern = pattern,
                actionType = actionType,
                description = description,
            ),
        )
    }
}
