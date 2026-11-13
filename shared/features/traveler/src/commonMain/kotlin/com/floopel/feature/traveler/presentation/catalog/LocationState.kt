package com.floopel.feature.traveler.presentation.catalog

import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.presentation.ReadState

internal data class LocationBrowserPage(val countries: List<CatalogEntry>, val country: CatalogEntry?,
    val region: CatalogEntry?, val entries: List<CatalogEntry>, val query: String, val page: Int, val totalPages: Int)

internal data class LocationContentState(val id: String = "",
    val spots: ReadState<CatalogPage> = ReadState(), val quests: ReadState<LocationQuestPage> = ReadState(),
    val trips: ReadState<OpenTripPage> = ReadState(), val articles: ReadState<CatalogPage> = ReadState())
