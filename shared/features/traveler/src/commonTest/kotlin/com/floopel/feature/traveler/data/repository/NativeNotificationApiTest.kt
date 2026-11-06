package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.core.network.*
import com.floopel.feature.traveler.data.remote.NativeApi
import com.floopel.feature.traveler.domain.repository.RefreshCredentialStore
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.feature.auth.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class NativeNotificationApiTest {
    @Test fun nativeInboxUsesCanonicalPagesAndSeparateReadMutation() = runTest {
        val fixture = NotificationFixture()
        try {
            fixture.session.login("fixture@example.invalid", "fixture-password")
            fixture.pagination = ""","pagination":{"page":1,"per_page":20,"total":1,"total_pages":1}"""
            fixture.data = "[${fixture.notice}]"
            val page = assertIs<CatalogResult.Ready<NotificationPage>>(fixture.repository.notifications(1, NotificationClass.SYSTEM, "en")).value
            assertEquals(listOf(fixture.id), page.items.map { it.id })
            assertEquals("/api/apps/v1/notifications", fixture.lastPath)
            assertEquals("system", fixture.query["class"])
            assertEquals("20", fixture.query["per_page"])
            assertEquals(0, fixture.writes)
            fixture.pagination = ""
            fixture.data = fixture.notice
            assertIs<CatalogResult.Ready<TravelerNotification>>(fixture.repository.notification(fixture.id, "en"))
            assertEquals(0, fixture.writes)
            fixture.data = """{"notification_id":"${fixture.id}","read_at":"2026-10-25T00:01:00Z"}"""
            assertIs<CatalogResult.Ready<*>>(fixture.repository.markNotificationRead(fixture.id))
            assertEquals(1, fixture.writes)
            assertEquals("/api/apps/v1/notifications/${fixture.id}/read", fixture.lastPath)
        } finally { fixture.client.close() }
    }

    @Test fun invalidPaginationAndNullableFieldsDoNotBecomeEmptySuccess() = runTest {
        val f = NotificationFixture()
        try {
            f.session.login("fixture@example.invalid", "fixture-password")
            f.pagination = ""","pagination":{"page":1,"per_page":20,"total":1,"total_pages":0}"""
            f.data = "[${f.notice}]"
            assertEquals(CatalogResult.InvalidResponse, f.repository.notifications(1, null, "id"))
            f.pagination = ""
            f.data = f.notice.replace(",\"read_at\":null", "")
            assertEquals(CatalogResult.InvalidResponse, f.repository.notification(f.id, "en"))
            f.data = "{\"count\":-1}"
            assertEquals(CatalogResult.InvalidResponse, f.repository.unreadNotifications())
            assertEquals(CatalogResult.HttpError(422), f.repository.notification("foreign/path", "en"))
        } finally { f.client.close() }
    }

    @Test fun canonicalFailureCodesRetainNullAndArrayDetailsOutsideTheAuthRetryPath() = runTest {
        val f = NotificationFixture()
        try {
            f.session.login("fixture@example.invalid", "fixture-password")
            f.status = HttpStatusCode.NotFound; f.code = "NOT_FOUND"; f.data = "null"
            for (details in listOf("null", "[]")) {
                f.error = """{"message":"Changed","details":$details}"""
                assertEquals("NOT_FOUND", assertIs<CatalogResult.HttpError>(f.repository.markNotificationRead(f.id)).code)
            }
            assertEquals(2, f.writes)
        } finally { f.client.close() }
    }

    @Test fun bindingIdIsReturnedAndDisableUsesItWithoutLeakingOrReplayingToken() = runTest {
        val f = NotificationFixture()
        try {
            f.session.login("fixture@example.invalid", "fixture-password")
            val installation = "22222222-2222-4222-8222-222222222222"
            f.data = """{"id":"${f.id}","installation_id":"$installation","platform":"android","disabled_at":null}"""
            val binding = assertIs<CatalogResult.Ready<PushDeviceBinding>>(f.repository.registerPushDevice(installation, "android", "fixture-provider-token")).value
            assertEquals(f.id, binding.id)
            assertEquals("/api/apps/v1/mobile/installations/$installation/push-device", f.lastPath)
            assertTrue(f.lastBody!!.contains("fixture-provider-token"))
            f.status = HttpStatusCode.NoContent
            assertEquals(CatalogResult.Ready(Unit), f.repository.disablePushDevice(binding.id))
            assertEquals("/api/apps/v1/mobile/push-devices/${f.id}", f.lastPath)
            f.status = HttpStatusCode.ServiceUnavailable
            val before = f.writes
            assertIs<CatalogResult.Maintenance>(f.repository.registerPushDevice(installation, "android", "fixture-provider-token"))
            assertEquals(before + 1, f.writes)
            assertTrue(f.session.view.value.signedIn)
        } finally { f.client.close() }
    }
}

private class NotificationFixture {
    val id = "11111111-1111-4111-8111-111111111111"
    val notice = """{"id":"$id","kind":"fixture","class":"system","title":"Title","body":"Body","target_url":null,"occurred_at":"2026-10-25T00:00:00Z","created_at":"2026-10-25T00:00:01Z","read_at":null}"""
    var pagination = ""
    var data = "{}"
    var status = HttpStatusCode.OK
    var code = "OK"
    var error = "null"
    var lastPath = ""
    var lastBody: String? = null
    var query: Map<String, String> = emptyMap()
    var writes = 0
    private var credential: String? = null
    val client = HttpClient(MockEngine { request ->
        val path = request.url.encodedPath
        val content = when {
            path.endsWith("/login") -> """{"tokens":{"token_type":"Bearer","access_token":"fixture","access_expires_at":"2099-01-01T00:00:00Z","refresh_token":"protected","refresh_expires_at":"2099-02-01T00:00:00Z"}}"""
            path.endsWith("/progress") -> """{"xp_total":0,"level":{"level":1,"code":"explorer","name":"Explorer","xp_floor":0,"xp_to_next_level":1000,"policy_revision":1},"badges":[]}"""
            else -> {
                assertEquals("Bearer fixture", request.headers[HttpHeaders.Authorization])
                assertNull(request.headers[HttpHeaders.Cookie])
                assertEquals("ANDROID", request.headers["X-App-Platform"])
                assertEquals("0.1.0-dev", request.headers["X-App-Version"])
                lastPath = path
                lastBody = (request.body as? TextContent)?.text
                query = request.url.parameters.entries().associate { it.key to it.value.single() }
                if (request.method != HttpMethod.Get) writes++
                return@MockEngine respond(if (status == HttpStatusCode.NoContent) "" else """{"meta":{"code":"$code","request_id":"33333333-3333-4333-8333-333333333333","timestamp":1792922400000$pagination},"error":$error,"data":$data}""",
                    status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        respond("""{"meta":{"code":"OK"},"error":null,"data":$content}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
    })
    private val api = NativeApi(client, "https://fixture.invalid", AppPlatform.ANDROID, appVersion = "0.1.0-dev")
    val session = TravelerSession(api, object : RefreshCredentialStore {
        override fun read() = credential
        override fun write(credential: String) { this@NotificationFixture.credential = credential }
        override fun clear() { credential = null }
    }, "en")
    val repository = NativeNotificationApi(api.catalogTransport(), session)
}
