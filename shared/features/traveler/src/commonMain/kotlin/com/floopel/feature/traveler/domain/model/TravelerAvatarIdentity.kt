package com.floopel.feature.traveler.domain.model

/** Publicly eligible appearance metadata never grants access to a private profile. */
data class TravelerAvatarIdentity(val level: Int, val avatarUrl: String?)
