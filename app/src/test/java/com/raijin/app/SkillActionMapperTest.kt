package com.raijin.app

import com.raijin.app.skill.SkillActionMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * PRD 2.2.1 fail-closed skill authoring: an unrecognised action description
 * must never map to a destructive action. Storage/file descriptions in
 * particular must NOT map to DELETE_FILES this release (deletion disabled).
 */
class SkillActionMapperTest {

    @Test
    fun storageDescriptionsFailClosedNeverDeleteFiles() {
        assertNull(SkillActionMapper.forDescription("clean my storage"))
        assertNull(SkillActionMapper.forDescription("clean junk files"))
        assertNull(SkillActionMapper.forDescription("storage cleanup"))
    }

    @Test
    fun unknownDescriptionsFailClosed() {
        assertNull(SkillActionMapper.forDescription("pay my electricity bill"))
        assertNull(SkillActionMapper.forDescription("order groceries"))
    }

    @Test
    fun knownSafeMappingsStillWork() {
        assertEquals("MUSIC_CONTROL", SkillActionMapper.forDescription("pause the music"))
        assertEquals("MUSIC_CONTROL", SkillActionMapper.forDescription("silence everything"))
        assertEquals("CALL_CONTACT", SkillActionMapper.forDescription("call mom"))
        assertEquals("CREATE_REMINDER", SkillActionMapper.forDescription("remind me to call"))
    }
}
