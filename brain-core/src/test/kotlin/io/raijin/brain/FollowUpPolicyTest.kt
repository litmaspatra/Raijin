package io.raijin.brain

import io.raijin.contract.InputKind
import io.raijin.contract.InputOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FollowUpPolicyTest {
    @Test fun onlyClarificationsOpenTheMic() {
        assertTrue(FollowUpPolicy.shouldListen(InputKind.CLARIFICATION))
        assertFalse(FollowUpPolicy.shouldListen(InputKind.CONFIRMATION))
        assertFalse(FollowUpPolicy.shouldListen(InputKind.CLOUD_CONSENT))
        assertFalse(FollowUpPolicy.shouldListen(InputKind.PERMISSION))
        assertFalse(FollowUpPolicy.shouldListen(InputKind.COUNTDOWN))
    }

    @Test fun windowIsSixSeconds() = assertEquals(6_000L, FollowUpPolicy.WINDOW_MS)

    @Test fun spokenPromptListsChoices() {
        val s = FollowUpPolicy.spokenPrompt(
            "Which Rahul?",
            listOf(InputOption("1", "Rahul Sharma"), InputOption("2", "Rahul Verma")),
        )
        assertEquals("Which Rahul? Rahul Sharma, Rahul Verma?", s)
    }

    @Test fun spokenPromptWithoutChoicesIsUnchanged() =
        assertEquals("Say the country.", FollowUpPolicy.spokenPrompt("Say the country.", emptyList()))
}
