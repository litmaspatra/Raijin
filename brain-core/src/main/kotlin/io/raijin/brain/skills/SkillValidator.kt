package io.raijin.brain.skills

/**
 * SK-1: manifest validator. Every problem is a validation error;
 * an unknown tool or action is NEVER rewritten to another action
 * (PRD 7.9 fail-closed rule).
 */
object SkillValidator {

    const val MAX_REPEAT_TIMES = 10
    const val MAX_WAIT_MILLIS = 60_000L
    const val MAX_STEPS_TOTAL = 100
    const val MAX_NESTING_DEPTH = 4

    sealed interface SkillError {
        val message: String

        data class BlankField(val field: String) : SkillError {
            override val message get() = "blank field: $field"
        }

        data class BadVersion(val version: Int) : SkillError {
            override val message get() = "version must be >= 1, was $version"
        }

        data class UnknownTool(val tool: String) : SkillError {
            override val message get() = "unknown tool: $tool"
        }

        data class UnknownCapability(val capability: String) : SkillError {
            override val message get() = "unknown required capability: $capability"
        }

        data class RepeatOutOfBounds(val times: Int) : SkillError {
            override val message get() = "repeat times must be 1..$MAX_REPEAT_TIMES, was $times"
        }

        data class WaitOutOfBounds(val millis: Long) : SkillError {
            override val message get() = "wait must be 0..$MAX_WAIT_MILLIS ms, was $millis"
        }

        data object TooManySteps : SkillError {
            override val message get() = "skill has more than $MAX_STEPS_TOTAL steps"
        }

        data object TooDeep : SkillError {
            override val message get() = "steps nested deeper than $MAX_NESTING_DEPTH"
        }

        data object EmptySteps : SkillError {
            override val message get() = "skill has no steps"
        }

        data object EmptyTriggers : SkillError {
            override val message get() = "skill has no phrases, regexes or examples to trigger on"
        }

        data class BadRegex(val pattern: String) : SkillError {
            override val message get() = "trigger regex does not compile: $pattern"
        }

        data class BadSha256(val value: String) : SkillError {
            override val message get() = "sha256 must be 64 lowercase hex chars"
        }

        data class DuplicateParameter(val name: String) : SkillError {
            override val message get() = "duplicate parameter: $name"
        }

        data class RequiredParamWithDefault(val name: String) : SkillError {
            override val message get() = "required parameter must not declare a default: $name"
        }
    }

    /**
     * Validate [manifest] against the known capability registry.
     *
     * @param knownTools contract ActionType names the interpreter may invoke.
     * @param knownCapabilities capabilities/permissions the app can grant.
     * @return every problem found; empty means the manifest is executable.
     */
    fun validate(
        manifest: SkillManifest,
        knownTools: Set<String>,
        knownCapabilities: Set<String>,
    ): List<SkillError> {
        val errors = mutableListOf<SkillError>()

        if (manifest.id.isBlank()) errors += SkillError.BlankField("id")
        if (manifest.name.isBlank()) errors += SkillError.BlankField("name")
        if (manifest.version < 1) errors += SkillError.BadVersion(manifest.version)
        if (manifest.triggers.phrases.isEmpty() &&
            manifest.triggers.regexes.isEmpty() &&
            manifest.triggers.examples.isEmpty()
        ) {
            errors += SkillError.EmptyTriggers
        }
        for (pattern in manifest.triggers.regexes) {
            try {
                Regex(pattern)
            } catch (_: Exception) {
                errors += SkillError.BadRegex(pattern)
            }
        }
        manifest.sha256?.let { hash ->
            if (!hash.matches(Regex("[0-9a-f]{64}"))) errors += SkillError.BadSha256(hash)
        }

        val seenParams = mutableSetOf<String>()
        for (param in manifest.parameters) {
            if (param.name.isBlank()) errors += SkillError.BlankField("parameter name")
            if (!seenParams.add(param.name)) errors += SkillError.DuplicateParameter(param.name)
            if (param.required && param.default != null) {
                errors += SkillError.RequiredParamWithDefault(param.name)
            }
        }

        for (capability in manifest.requires) {
            if (capability !in knownCapabilities) errors += SkillError.UnknownCapability(capability)
        }

        if (manifest.steps.isEmpty()) errors += SkillError.EmptySteps
        var stepCount = 0
        validateSteps(manifest.steps, knownTools, errors, depth = 1, counter = { stepCount = it })
        if (stepCount > MAX_STEPS_TOTAL) errors += SkillError.TooManySteps

        return errors
    }

    private fun validateSteps(
        steps: List<SkillStep>,
        knownTools: Set<String>,
        errors: MutableList<SkillError>,
        depth: Int,
        counter: (Int) -> Unit,
    ) {
        if (depth > MAX_NESTING_DEPTH) {
            errors += SkillError.TooDeep
            return
        }
        var count = 0
        for (step in steps) {
            count++
            when (step) {
                is SkillStep.CallTool ->
                    if (step.tool !in knownTools) errors += SkillError.UnknownTool(step.tool)

                is SkillStep.AskUser -> {
                    if (step.prompt.isBlank()) errors += SkillError.BlankField("ask prompt")
                    if (step.storeAs.isBlank()) errors += SkillError.BlankField("ask storeAs")
                }

                is SkillStep.Speak ->
                    if (step.text.isBlank()) errors += SkillError.BlankField("speak text")

                is SkillStep.IfElse -> {
                    validateSteps(step.then, knownTools, errors, depth + 1) { count += it }
                    validateSteps(step.otherwise, knownTools, errors, depth + 1) { count += it }
                }

                is SkillStep.Repeat -> {
                    if (step.times !in 1..MAX_REPEAT_TIMES) {
                        errors += SkillError.RepeatOutOfBounds(step.times)
                    }
                    validateSteps(step.steps, knownTools, errors, depth + 1) { count += it }
                }

                is SkillStep.WaitMillis ->
                    if (step.millis !in 0..MAX_WAIT_MILLIS) {
                        errors += SkillError.WaitOutOfBounds(step.millis)
                    }
            }
        }
        counter(count)
    }
}
