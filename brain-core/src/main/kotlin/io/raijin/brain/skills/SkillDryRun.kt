package io.raijin.brain.skills

/**
 * SK-3: dry-run preview. Runs a manifest through the interpreter with a
 * recording runtime that never touches a real tool, never waits and never
 * asks a real question, so the owner sees exactly what WOULD happen at
 * run time before approving an install.
 */
object SkillDryRun {

    private class RecordingRuntime : SkillRuntime {
        override suspend fun callTool(tool: String, args: Map<String, String>): SkillToolResult =
            SkillToolResult(ok = true, detail = "(dry run)")

        // Affirmative so the preview walks the full path, including steps
        // behind a consent question; the ask itself appears in the log.
        override suspend fun askUser(prompt: String): String = "yes"

        override suspend fun speak(text: String) = Unit

        override suspend fun waitMillis(millis: Long) = Unit
    }

    /**
     * The interpreter's own log lines are the preview: one line per step,
     * in execution order, including branch and repeat expansion.
     */
    suspend fun preview(manifest: SkillManifest): SkillRunResult =
        SkillInterpreter.run(manifest, RecordingRuntime())
}
