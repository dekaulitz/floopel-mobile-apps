package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.data.dto.*
import kotlin.test.*

class QuestGroupMapperTest {
    private val id = "11111111-1111-4111-8111-111111111111"
    private val timestamp = "2026-10-25T00:00:00Z"

    @Test fun profileIdentityIsTextWhileBusinessIdentityIsUuid() {
        val group = QuestGroupDto(id, id, null, "traveler-profile-text", "active", 1, 4, timestamp, null).toDomain()
        assertEquals("traveler-profile-text", group.managerProfileId)
        assertNull(group.questTitle)
        val member = QuestGroupMemberDto(id, "profile-text", "Deka", "manager", timestamp, null).toDomain()
        assertTrue(member.manager)
        assertNull(member.progress)
        assertFails { QuestGroupDto("path/foreign", id, null, "profile", "active", 1, 4, timestamp, null).toDomain() }
    }

    @Test fun capacityAndUnknownStatusCannotBecomeAReadyGroup() {
        val dto = QuestGroupDto(id, id, "Kopi", "profile", "active", 1, 4, timestamp, null)
        assertFails { dto.copy(memberCount = 5).toDomain() }
        assertFails { dto.copy(sizeLimit = 1).toDomain() }
        assertFails { dto.copy(status = "deleted").toDomain() }
        assertFalse(dto.copy(status = "unavailable", questTitle = null).toDomain().available)
        assertFails { dto.copy(status = "unavailable").toDomain() }
    }

    @Test fun boundedPaginationAcceptsPartialServerBatchesButRejectsDriftAndDuplicates() {
        val page = QuestGroupPaginationDto(1, 20, 21, 2)
        assertEquals(2, page.toBatch(listOf("one"), 1, listOf(id)).totalPages)
        assertFails { page.copy(page = 2).toBatch(listOf("one"), 1, listOf(id)) }
        assertFails { page.copy(totalPages = 1).toBatch(listOf("one"), 1, listOf(id)) }
        assertFails { page.toBatch(listOf("one", "two"), 1, listOf(id, id)) }
        assertEquals(0, QuestGroupPaginationDto(1, 20, 0, 0).toBatch(emptyList<String>(), 1, emptyList()).total)
    }

    @Test fun membershipProgressNeverTreatsMalformedNumbersAsZero() {
        val dto = QuestGroupMemberDto(id, "profile", "Deka", "member", timestamp,
            QuestGroupProgressDto("active", 2, 4, null))
        assertEquals(2, dto.toDomain().progress?.completed)
        assertFails { dto.copy(progress = dto.progress!!.copy(completed = -1)).toDomain() }
        assertFails { dto.copy(progress = dto.progress!!.copy(completed = 5)).toDomain() }
        assertFails { dto.copy(role = "organizer").toDomain() }
    }

    @Test fun legacyPersonalRemainsCompatibleButUnknownOrIncompleteGroupModeFailsClosed() {
        val personal = QuestSummary(id, "kopi", "Kopi", "active", "available", 1, QuestReward(100, null), null, locations = emptyList())
        assertEquals("personal", personal.participationMode)
        assertNull(personal.groupSizeLimit)
        assertEquals(2, personal.copy(participationMode = "group", groupSizeLimit = 2).groupSizeLimit)
        assertFails { personal.copy(participationMode = "future") }
        assertFails { personal.copy(participationMode = "group") }
        assertFails { personal.copy(groupSizeLimit = 2) }
    }
}
