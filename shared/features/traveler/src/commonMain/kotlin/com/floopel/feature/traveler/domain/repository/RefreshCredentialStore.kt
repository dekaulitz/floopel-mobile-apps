package com.floopel.feature.traveler.domain.repository

/** Platform protected storage holds only the refresh credential, with backup exclusion. */
interface RefreshCredentialStore {
    @Throws(Exception::class)
    fun read(): String?
    @Throws(Exception::class)
    fun write(credential: String)
    @Throws(Exception::class)
    fun clear()
}
