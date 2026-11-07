package com.floopel.feature.traveler.presentation

import com.floopel.feature.traveler.presentation.trip.tripCatalogDate
import kotlin.test.Test
import kotlin.test.assertEquals

class TripCatalogDateTest {
    private val months = listOf("Jan", "Feb", "Mar", "Apr", "Mei", "Jun", "Jul", "Agu", "Sep", "Okt", "Nov", "Des")

    @Test fun localDatesKeepTheirDayAcrossSingleDayMonthAndYearBoundaries() {
        assertEquals("14 Nov 2026", tripCatalogDate("2026-11-14", "2026-11-14", months))
        assertEquals("14–16 Nov 2026", tripCatalogDate("2026-11-14", "2026-11-16", months))
        assertEquals("30 Nov–2 Des 2026", tripCatalogDate("2026-11-30", "2026-12-02", months))
        assertEquals("30 Des 2026–2 Jan 2027", tripCatalogDate("2026-12-30", "2027-01-02", months))
        assertEquals("2026-02-30 – 2026-03-01", tripCatalogDate("2026-02-30", "2026-03-01", months))
    }
}
