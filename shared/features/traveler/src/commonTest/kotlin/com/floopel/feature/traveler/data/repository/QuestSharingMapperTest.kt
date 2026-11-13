package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.data.dto.*
import com.floopel.feature.traveler.domain.model.QuestShareContext
import kotlin.test.*

class QuestSharingMapperTest {
    private val quest = "11111111-1111-4111-8111-111111111111"
    private val revision = "22222222-2222-4222-8222-222222222222"
    private fun progress(hidden: Boolean? = false) = QuestShareOwnerDto(quest, "kopi", "active", hidden,
        QuestShareParticipationDto(quest, "kopi", revision, "Bound title", "active", 2, 5))
    private fun completion(hidden: Boolean? = true) = QuestShareOwnerDto(quest, "kopi", "completed", hidden,
        completion = QuestShareCompletionDto(quest, "kopi", revision, "Historical title", "2026-10-25T01:00:00Z", 250, true, "granted"))

    @Test fun hiddenAndMissingPolicyBlockPreCompletionSnapshots() {
        assertNull(progress(true).ownerFacts())
        assertNull(progress(null).ownerFacts())
        assertEquals(QuestShareContext.PROGRESS, progress().ownerFacts()?.context)
        assertEquals(2, progress().ownerFacts()?.completed)
        assertNull(progress().ownerFacts()?.xp)
    }

    @Test fun hiddenCompletionUsesBoundRevisionAndConfirmedGrant() {
        val facts = assertNotNull(completion().ownerFacts())
        assertEquals(QuestShareContext.COMPLETION, facts.context)
        assertEquals("Historical title", facts.title)
        assertEquals(revision, facts.revisionId)
        assertEquals(250, facts.xp)
        assertEquals("https://floopel.com/en/quests/kopi", facts.canonicalLink("en"))
        assertFalse(facts.canonicalLink("id").contains(revision))
        assertFalse(facts.canonicalLink("id").contains(quest))
    }

    @Test fun revokedAndInconsistentRewardCannotOpenAnotherShare() {
        val owner = completion()
        val original = requireNotNull(owner.completion)
        for (reward in listOf(original.copy(available = false), original.copy(rewardStatus = "revoked"),
            original.copy(revokedAt = "2026-10-25T02:00:00Z"))) {
            assertNull(owner.copy(completion = reward).ownerFacts())
        }
        assertFails { owner.copy(completion = original.copy(xp = -1)).ownerFacts() }
    }

    @Test fun malformedOrCrossQuestFactsAreRejected() {
        assertFails { progress().copy(active = progress().active!!.copy(questId = revision)).ownerFacts() }
        assertFails { progress().copy(active = progress().active!!.copy(completed = 6)).ownerFacts() }
        assertFails { completion().copy(completion = null).ownerFacts() }
        assertFails { completion().copy(completion = completion().completion!!.copy(completedAt = "invalid")).ownerFacts() }
    }

    @Test fun invitationRequiresCurrentPublicAvailabilityWithoutProgressOrRewardClaim() {
        val owner = QuestShareOwnerDto(quest, "kopi", "not_completed", false)
        val current = QuestSharePublicDto(quest, "kopi", "Current title", "active", "available")
        val facts = assertNotNull(current.invitationFacts(owner))
        assertEquals(QuestShareContext.INVITATION, facts.context)
        assertNull(facts.xp)
        assertNull(facts.completed)
        assertNull(facts.completedAt)
        assertNull(current.copy(availability = "ended").invitationFacts(owner))
        assertNull(current.copy(executionStatus = "suspended").invitationFacts(owner))
        assertNull(current.invitationFacts(owner.copy(hidden = true)))
        assertFails { facts.canonicalLink("en/foreign") }
    }
}
