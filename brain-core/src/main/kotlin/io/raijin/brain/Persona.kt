package io.raijin.brain

/**
 * BR-10: the persona prompt is a versioned file in the repo
 * (resources/persona/system-prompt.vN.md), not a scattered inline string.
 * Any wording change ships as a new file version plus a [VERSION] bump, so
 * persona changes are reviewable in git like any other behavior change.
 */
object Persona {

    const val VERSION = 1

    private const val RESOURCE = "/persona/system-prompt.v$VERSION.md"
    private const val VERSION_MARKER = "persona_version: $VERSION"

    /** The current persona system prompt. Fails hard when the file is missing or stale. */
    val systemPrompt: String by lazy {
        val text = Persona::class.java.getResource(RESOURCE)?.readText()
            ?: error("Persona resource $RESOURCE missing from brain-core")
        require(text.contains(VERSION_MARKER)) {
            "Persona resource $RESOURCE lacks version marker '$VERSION_MARKER'"
        }
        text.substringAfter(VERSION_MARKER).trim().also {
            require(it.isNotBlank()) { "Persona resource $RESOURCE has an empty prompt body" }
        }
    }
}
