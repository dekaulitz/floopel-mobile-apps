package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.domain.model.LocationReference

internal val coverage = """[{"id":"11111111-1111-4111-8111-111111111111","name":"Bali","slug":"bali","level":"region"},{"id":"22222222-2222-4222-8222-222222222222","name":"Bandung","slug":"bandung","level":"city"}]"""
internal val coverageLocations = listOf(
    LocationReference("11111111-1111-4111-8111-111111111111", "Bali", "bali", "region"),
    LocationReference("22222222-2222-4222-8222-222222222222", "Bandung", "bandung", "city"),
)
