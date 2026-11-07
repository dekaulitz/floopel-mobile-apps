package com.floopel.feature.traveler.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class CatalogPhotoTest {
    private val photo = CatalogPhoto("fallback", "Travel", listOf(
        CatalogPhotoVariant("large", 1280, 720), CatalogPhotoVariant("thumb", 160, 90),
        CatalogPhotoVariant("medium", 640, 360), CatalogPhotoVariant("small", 320, 180),
    ))

    @Test fun fullWidthHighDensityFeedSelectsLarge() {
        assertEquals("large", photo.adaptiveUrl(984, 554, crop = false))
    }
    @Test fun thumbnailDoesNotDownloadTheFeedVariant() {
        assertEquals("thumb", photo.adaptiveUrl(120, 67, crop = false))
    }
    @Test fun squareCropRequiresEnoughPixelsForTheCoveredHeight() {
        assertEquals("medium", photo.adaptiveUrl(240, 240, crop = true))
        assertEquals("small", photo.adaptiveUrl(240, 240, crop = false))
    }
    @Test fun missingOrInvalidDimensionsKeepTheAuthorizedFallback() {
        assertEquals("fallback", CatalogPhoto("fallback", "", listOf(CatalogPhotoVariant("invalid", 0, 90))).adaptiveUrl(120, 90, true))
        assertEquals("fallback", photo.adaptiveUrl(0, 90, true))
    }
    @Test fun unavailableResolutionUsesLargestWithoutInventingAUrl() {
        assertEquals("large", photo.adaptiveUrl(2560, 1440, true))
    }
    @Test fun unboundedHeightUsesTheImageRatio() {
        assertEquals("medium", photo.adaptiveUrl(640, 0, false))
    }
}
