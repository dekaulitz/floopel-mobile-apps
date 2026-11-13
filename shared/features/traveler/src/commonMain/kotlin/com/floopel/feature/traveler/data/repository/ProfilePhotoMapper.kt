package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.data.dto.ProfilePhotoUploadDto
import com.floopel.feature.traveler.data.dto.ProfilePhotoStatusDto
import com.floopel.feature.traveler.data.remote.validAppsId
import com.floopel.feature.traveler.domain.model.ProfilePhotoStatus
import com.floopel.feature.traveler.domain.model.ProfilePhotoUpload

/** Unknown protocol states fail safely rather than falsely displaying a completed upload. */
internal fun ProfilePhotoUploadDto.toDomain(): ProfilePhotoUpload {
    require(validAppsId(id))
    val state = when (status) {
        ProfilePhotoStatusDto.PROCESSING -> ProfilePhotoStatus.PROCESSING
        ProfilePhotoStatusDto.READY -> ProfilePhotoStatus.READY
        ProfilePhotoStatusDto.FAILED -> ProfilePhotoStatus.FAILED
    }
    return ProfilePhotoUpload(id, state)
}
