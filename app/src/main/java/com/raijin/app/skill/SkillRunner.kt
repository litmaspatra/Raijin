package com.raijin.app.skill

import io.raijin.brain.skills.SkillInterpreter
import io.raijin.brain.skills.SkillMatcher
import io.raijin.brain.skills.SkillRunResult
import io.raijin.brain.skills.SkillRuntime

/**
 * SK-2: matches an utterance against installed, enabled declarative
 * skills and runs the first hit through the brain-core interpreter.
 * Returns null when nothing matches so the caller continues normal routing.
 */
class SkillRunner(
    private val installService: SkillInstallService,
    private val runLogDao: SkillRunLogDao? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** STT phase 0: read-only peek for hypothesis probing; never executes. */
    suspend fun manifests() = installService.enabledManifests()

    suspend fun tryRun(
        utterance: String,
        runtime: SkillRuntime,
        logUtterance: String? = null,
    ): SkillRunResult? {
        val match = installService.enabledManifests()
            .firstOrNull { SkillMatcher.matches(it, utterance) }
            ?: return null
        val result = SkillInterpreter.run(match, runtime)
        runLogDao?.let { dao ->
            val (outcome, detail) = when (result) {
                is SkillRunResult.Completed -> "completed" to result.log.joinToString(" | ")
                is SkillRunResult.FailedAt -> "failed_at_${result.atStep}" to result.reason
            }
            dao.insert(
                SkillRunLogEntity(
                    skill_id = match.id,
                    skill_version = match.version,
                    // On hypothesis substitution the caller passes raw+interpreted
                    // together; the store and its retention are unchanged.
                    utterance = logUtterance ?: utterance,
                    outcome = outcome,
                    detail = detail,
                    ran_at = now(),
                )
            )
            dao.prune()
        }
        return result
    }
}
