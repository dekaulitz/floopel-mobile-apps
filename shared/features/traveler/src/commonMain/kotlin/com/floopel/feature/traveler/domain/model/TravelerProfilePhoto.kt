package com.floopel.feature.traveler.domain.model

/** Server-owned processing outcome; accepting bytes is never completion. */
enum class ProfilePhotoStatus { PROCESSING, READY, FAILED }

/** Owner-scoped request identity, independent of internal Media asset IDs. */
data class ProfilePhotoUpload(val id: String, val status: ProfilePhotoStatus)

/** One in-memory native selection; original paths and image bytes are never logged. */
class ProfilePhotoImage(val bytes: ByteArray, val mimeType: String) {
    override fun toString(): String = "ProfilePhotoImage([REDACTED])"
}

/** Platform selection is resolved before upload without exposing platform paths to the server. */
sealed interface ProfilePhotoSelection {
    data class Loaded(val image: ProfilePhotoImage) : ProfilePhotoSelection
    data object Invalid : ProfilePhotoSelection
    data object TooLarge : ProfilePhotoSelection
    data object Unavailable : ProfilePhotoSelection
    data object ResourceLimited : ProfilePhotoSelection
}

/** Local import is bounded separately; only cropped/encoded output uses the server admission budget. */
object ProfilePhotoPolicy {
    const val MaxBytes = 5 * 1024 * 1024
    const val MaxSourceMiB = 32
    const val MaxSourceBytes = MaxSourceMiB * 1024 * 1024
    val MimeTypes: Set<String> = setOf("image/jpeg", "image/png", "image/webp")
}

/** Existing canonical business codes consumed by the photo journey; unknown future values remain strings with safe fallback. */
object ProfilePhotoCodes {
    const val Validation = "VALIDATION"
    const val PayloadTooLarge = "PAYLOAD_TOO_LARGE"
    const val Unauthorized = "UNAUTHORIZED"
    const val Forbidden = "FORBIDDEN"
    const val NotFound = "NOT_FOUND"
    const val RateLimited = "RATE_LIMITED"
    const val ChangeLimited = "PROFILE_PHOTO_CHANGE_LIMITED"
    const val Conflict = "CONFLICT"
    const val Internal = "INTERNAL"
}
