package com.floopel.feature.traveler.presentation.quest

import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.presentation.ReadState

internal data class QuestFilterDraft(val minimum: String = "", val maximum: String = "", val location: LocationReference? = null) {
    val valid get() = (minimum.isBlank() || minimum.all { it in '0'..'9' } && minimum.toIntOrNull() != null) &&
        (maximum.isBlank() || maximum.all { it in '0'..'9' } && maximum.toIntOrNull() != null) &&
        (minimum.toIntOrNull() == null || maximum.toIntOrNull() == null || minimum.toInt() <= maximum.toInt())
}
internal data class QuestBrowseState(val scope: String? = null, val taken: Boolean = false,
    val filter: QuestDiscoveryFilter = QuestDiscoveryFilter(), val revision: Long = 0, val requestedPage: Int = 1,
    val takenRows: ReadState<List<QuestRunning>> = ReadState(), val draft: QuestFilterDraft? = null,
    val pickerOpen: Boolean = false, val pickerQuery: String = "", val pickerRows: ReadState<List<CatalogEntry>> = ReadState())

internal fun QuestReader.boundOwner() = owner?.takeIf { it.questId == card.id }
internal fun QuestReader.canTake(identity: TravelerIdentityView) = identity.signedIn && identity.executionConfigured && !identity.busy &&
    boundOwner()?.status == "not_completed" && card.mode == "personal" && availability == "active" && executionStatus == "available" && remaining != 0
internal fun QuestRunning.validProgress() = total > 0 && completed in 0..total
