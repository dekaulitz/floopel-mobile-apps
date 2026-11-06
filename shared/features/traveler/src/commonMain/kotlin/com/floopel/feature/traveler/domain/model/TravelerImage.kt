package com.floopel.feature.traveler.domain.model

/** A foreground selection. Bytes and local URIs never enter saved navigation or recovery records. */
class TravelerImage(bytes: ByteArray, val mimeType: String) {
    private val content = bytes.copyOf()
    init { require(content.isNotEmpty() && content.size <= 5 * 1024 * 1024 && mimeType in setOf("image/jpeg", "image/png", "image/webp")) }
    fun bytes(): ByteArray = content.copyOf()
    override fun toString() = "TravelerImage(content=[redacted], mimeType=$mimeType)"
}
