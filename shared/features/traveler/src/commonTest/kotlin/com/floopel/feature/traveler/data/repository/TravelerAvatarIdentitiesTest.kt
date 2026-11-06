package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.core.network.*
import com.floopel.feature.traveler.domain.model.TravelerAvatarIdentity
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Batch appearance projection covers privacy omission, bounds, validation and cancellation. */
class TravelerAvatarIdentitiesTest {
    @Test fun uniqueNamesShareOneBatchAndIneligibleProfilesStayUnknown() = runTest {
        val paths = mutableListOf<String>()
        val reader = TravelerAvatarIdentities({ path -> paths += path
            success("""{"alice":{"level":4,"avatar_url":"/media/alice.webp"}}""") }, { it?.let { path -> "https://fixture.invalid$path" } })
        assertEquals(mapOf("alice" to TravelerAvatarIdentity(4, "https://fixture.invalid/media/alice.webp")),
            reader.read(listOf("Alice", "alice", "private", "")))
        assertEquals(1, paths.size)
        assertEquals("/api/apps/v1/profile-identities", Url("https://fixture.invalid" + paths.single()).encodedPath)
        assertEquals("alice,private", Url("https://fixture.invalid" + paths.single()).parameters["usernames"])
        assertTrue(reader.read(emptyList()).isEmpty())
        assertEquals(1, paths.size)
    }

    @Test fun largePagesUseBoundedRequestsRatherThanOneRequestPerAvatar() = runTest {
        val sizes = mutableListOf<Int>()
        val reader = TravelerAvatarIdentities({ path ->
            sizes += Url("https://fixture.invalid$path").parameters["usernames"]!!.split(',').size
            success("{}") }, { it })
        assertTrue(reader.read((1..205).map { "traveler$it" }).isEmpty())
        assertEquals(listOf(100, 100, 5), sizes)
    }

    @Test fun missingAndBlankPhotosPreserveTheAuthoritativeLevel() = runTest {
        for (photo in listOf("", ",\"avatar_url\":null", ",\"avatar_url\":\" \"")) {
            val reader = TravelerAvatarIdentities({ success("""{"alice":{"level":2$photo}}""") }, { it })
            assertEquals(TravelerAvatarIdentity(2, null), reader.read(listOf("alice"))["alice"])
        }
    }

    @Test fun invalidOrUnexpectedMetadataCannotInventAnIdentity() = runTest {
        for (value in listOf("0", "11", "\"3\"", "null")) {
            val reader = TravelerAvatarIdentities({ success("""{"alice":{"level":$value}}""") }, { it })
            assertTrue(reader.read(listOf("alice")).isEmpty())
        }
        assertTrue(TravelerAvatarIdentities({ success("""{"other":{"level":1}}""") }, { it }).read(listOf("alice")).isEmpty())
        assertTrue(TravelerAvatarIdentities({ success("""{"alice":4}""") }, { it }).read(listOf("alice")).isEmpty())
        assertTrue(TravelerAvatarIdentities({ ApiOutcome.HttpFailure(503) }, { it }).read(listOf("alice")).isEmpty())
    }

    @Test fun cancellationIsNotConvertedIntoUnknownMetadata() = runTest {
        val reader = TravelerAvatarIdentities({ throw CancellationException("Account changed") }, { it })
        assertFailsWith<CancellationException> { reader.read(listOf("alice")) }
    }

    private fun success(data: String) = ApiOutcome.Success(200, """{"meta":{"code":"OK"},"error":null,"data":$data}""")
}
