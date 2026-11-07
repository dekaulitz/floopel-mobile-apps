package com.floopel.feature.traveler.feature.auth

/** Both optional device cleanup and refresh-family revocation have a bounded sign-out budget. */
internal object LogoutLifecyclePolicy {
    const val CleanupMillis = 7_000L
    const val RevokeMillis = 7_000L
}
