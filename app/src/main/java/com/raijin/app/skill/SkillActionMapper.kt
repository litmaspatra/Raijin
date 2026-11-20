package com.raijin.app.skill

/**
 * Maps a user-authored skill action description ("learn skill: when I say X,
 * <action>") to an ActionType string, or null to fail closed.
 *
 * PRD 2.2.1: an unrecognised description must never fall back to a
 * destructive action. Storage/file actions are intentionally unmapped this
 * release: file deletion is disabled (PRD ST-x is future work), so
 * clean/storage/junk descriptions refuse like any other unknown action.
 */
object SkillActionMapper {

    fun forDescription(actionDesc: String): String? = when {
        actionDesc.contains("pause") ||
            actionDesc.contains("mute") ||
            actionDesc.contains("music") ||
            actionDesc.contains("silence") -> "MUSIC_CONTROL"
        actionDesc.contains("call") -> "CALL_CONTACT"
        actionDesc.contains("reminder") || actionDesc.contains("remind") -> "CREATE_REMINDER"
        else -> null
    }
}
