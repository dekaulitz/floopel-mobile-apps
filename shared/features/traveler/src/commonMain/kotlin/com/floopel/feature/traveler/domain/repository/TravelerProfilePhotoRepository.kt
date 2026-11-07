package com.floopel.feature.traveler.domain.repository

import com.floopel.feature.traveler.domain.model.CatalogResult
import com.floopel.feature.traveler.domain.model.ProfilePhotoImage
import com.floopel.feature.traveler.domain.model.ProfilePhotoUpload
import com.floopel.feature.traveler.domain.model.ProfilePhotoSelection

/** Uses the existing identity owner; manual selection is a native-only mutation without automatic replay. */
interface TravelerProfilePhotoRepository {
    /** Accepts one bounded selection without claiming that its processed image is installed. */
    suspend fun uploadProfilePhoto(image: ProfilePhotoImage): CatalogResult<ProfilePhotoUpload>

    /** Reads only the current owner's request and never rewrites its state. */
    suspend fun profilePhotoUpload(id: String): CatalogResult<ProfilePhotoUpload>
}

/** Host boundary for a system-picker selection token; implementations read bounded bytes off the main thread. */
interface ProfilePhotoSelectionReader {
    /** Cancellation propagates; invalid/unavailable content is a recoverable selection outcome. */
    suspend fun readSelection(token: String): ProfilePhotoSelection
}
