package com.floopel.feature.traveler.presentation

import com.floopel.feature.traveler.presentation.catalog.*
import kotlin.test.*

class CatalogMetadataFormatTest {
    @Test fun figmaScoreUsesTheLocalizedDecimalAndKeepsZeroAsARealScore() {
        assertEquals("4,8", catalogScore(4.8, ","))
        assertEquals("0,0", catalogScore(0.0, ","))
        assertEquals("4.8", catalogScore(4.8, "."))
        assertEquals("5,0", catalogScore(4.99, ","))
    }
    @Test fun commentsUseActualCountsAndTheCompactLabelFromTheDesign() {
        assertEquals("0", catalogCount(0, ","))
        assertEquals("1", catalogCount(1, ","))
        assertEquals("245", catalogCount(245, ","))
        assertEquals("1.000", catalogCount(1_000, ","))
        assertEquals("1K", catalogCount(1_001, ","))
        assertEquals("1,2K", catalogCount(1_200, ","))
        assertEquals("1.2K", catalogCount(1_200, "."))
    }
    @Test fun largeCountsDoNotOverflowAnAndroidInt() {
        assertEquals("1M", catalogCount(1_000_000, ","))
        assertEquals("2,1B", catalogCount(2_147_483_648, ","))
        assertFalse(catalogCount(Long.MAX_VALUE, ",").startsWith("-"))
    }
}
