package com.floopel.feature.traveler.domain.model

/** Structured field identity is retained separately from the primary failure code and raw server messages. */
data class CatalogFieldIssue(val field: String, val code: String)
