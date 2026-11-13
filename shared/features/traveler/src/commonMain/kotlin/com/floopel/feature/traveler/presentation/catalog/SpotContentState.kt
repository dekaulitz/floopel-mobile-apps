package com.floopel.feature.traveler.presentation.catalog

import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.presentation.ReadState
import com.floopel.feature.traveler.presentation.ReadFailure

internal data class SpotContentState(val id: String = "", val location: ReadState<CatalogEntry> = ReadState(),
    val quests: ReadState<LocationQuestPage> = ReadState(), val trips: ReadState<OpenTripPage> = ReadState(),
    val articles: ReadState<CatalogPage> = ReadState(), val comments: SpotCommentsState = SpotCommentsState(),
    val report: SpotReportState = SpotReportState())

internal data class SpotReportState(val open: Boolean = false, val reason: CatalogReportReason? = null,
    val details: String = "", val result: ReadState<ReportReceipt> = ReadState(), val uncertain: Boolean = false)

/** Drafts remain in memory across sheet dismissal and never enter the persisted route stack. */
internal data class SpotCommentsState(val open: Boolean = false, val page: ReadState<CommentPage> = ReadState(),
    val draft: String = "", val replyTo: TravelerComment? = null, val uncertainDraft: Pair<String?, String>? = null,
    val replyPages: Map<String, ReadState<CommentPage>> = emptyMap(), val writeFailure: ReadFailure? = null)
