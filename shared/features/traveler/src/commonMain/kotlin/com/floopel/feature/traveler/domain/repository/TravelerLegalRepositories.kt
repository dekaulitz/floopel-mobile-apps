package com.floopel.feature.traveler.domain.repository

import com.floopel.feature.traveler.domain.model.*

interface TravelerLegalRepository {
    suspend fun pending(locale: String): CatalogResult<List<LegalDocument>>
    suspend fun accept(document: LegalDocument, key: String, identityGeneration: Long): CatalogResult<Unit>
}

/** Gated writes pause before submission; dismissing consent keeps browsing available. */
interface TravelerConsentGate {
    suspend fun ensure(): CatalogResult<Unit>
}

/** Read-only access to the current published legal text; never records consent. */
interface TravelerLegalDocumentRepository {
 suspend fun current(type: String, locale: String): CatalogResult<LegalDocument>
}
