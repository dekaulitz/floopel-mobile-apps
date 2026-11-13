package com.floopel.feature.traveler.core.network

import io.ktor.http.HeadersBuilder

/** Build metadata is descriptive; server credentials remain the authority for session channels. */
internal class AppRequestMetadata(private val platform: AppPlatform, private val version: String?) {
    init {
        require(version == null || version.isNotBlank() && version.none { it == '\r' || it == '\n' })
    }

    /** Replace adapter values so JSON, uploads and maintenance probes carry exactly one value. */
    fun applyTo(headers: HeadersBuilder) {
        headers.remove("X-App-Platform")
        headers.remove("X-App-Version")
        headers.append("X-App-Platform", platform.name)
        version?.let { headers.append("X-App-Version", it) }
    }
}
