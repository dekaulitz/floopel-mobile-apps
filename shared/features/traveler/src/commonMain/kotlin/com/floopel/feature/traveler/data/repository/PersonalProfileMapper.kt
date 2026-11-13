package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.data.dto.PersonalProfileDto
import com.floopel.feature.traveler.domain.model.PersonalProfile

/** Optional image delivery is resolved by the existing transport policy; profile text remains owner-only. */
internal fun PersonalProfileDto.toDomain(resolveUrl: (String?) -> String?): PersonalProfile = PersonalProfile(
    firstName = firstName,
    lastName = lastName,
    username = username,
    nationalityCode = nationalityCode,
    avatarUrl = resolveUrl(avatarUrl),
    profileId = id?.takeIf { it.isNotBlank() },
)
