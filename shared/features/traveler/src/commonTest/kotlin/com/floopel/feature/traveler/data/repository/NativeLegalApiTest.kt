package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.*
import com.floopel.feature.traveler.core.network.*
import com.floopel.feature.traveler.data.dto.*
import com.floopel.feature.traveler.data.remote.*
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.*
import com.floopel.feature.traveler.domain.repository.*
import com.floopel.feature.traveler.feature.auth.*
import com.floopel.feature.traveler.feature.legal.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class NativeLegalApiTest {
    @Test fun exactPendingDocumentHashIsAcceptedOnlyInItsOriginalIdentityGeneration() = runTest {
        var pending = true
        var wrongVersion = false
        var writes = 0
        var status = HttpStatusCode.OK
        val client = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            val data = when {
                path.endsWith("/login") -> """{"tokens":{"token_type":"Bearer","access_token":"fixture","access_expires_at":"2099-01-01T00:00:00Z","refresh_token":"protected","refresh_expires_at":"2099-02-01T00:00:00Z"}}"""
                path.endsWith("/progress") -> """{"xp_total":0,"level":{"level":1,"code":"explorer","name":"Explorer","xp_floor":0,"xp_to_next_level":1000,"policy_revision":1},"badges":[]}"""
                path.endsWith("/me") -> """{"pending_reconsent":${if(pending) "[{\"doc_type\":\"terms\",\"version\":\"2.0\"}]" else "[]"}}"""
                path.contains("/legal/") -> {
                    assertEquals("/api/apps/v1/legal/terms/2.0", path)
                    """{"doc_type":"terms","version":"${if(wrongVersion) "3.0" else "2.0"}","locale_served":"id","content_html":"<p>Terms</p>","content_hash":"served-id-hash"}"""
                }
                else -> {
                    writes++
                    assertEquals("Bearer fixture", request.headers[HttpHeaders.Authorization])
                    assertEquals("intent", request.headers["Idempotency-Key"])
                    val body = (request.body as TextContent).text
                    assertTrue(body.contains("\"locale\":\"id\"")); assertTrue(body.contains("served-id-hash"))
                    pending = false
                    return@MockEngine respond("""{"meta":{"code":"OK"},"error":null,"data":null}""", status, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
            respond("""{"meta":{"code":"OK"},"error":null,"data":$data}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val api = NativeApi(client, "https://fixture.invalid", AppPlatform.ANDROID)
            val session = TravelerSession(api, object : RefreshCredentialStore {
                var credential: String? = null
                override fun read() = credential
                override fun write(credential: String) { this.credential = credential }
                override fun clear() { credential = null }
            }, "en")
            session.login("fixture@example.invalid", "fixture-password")
            val legal = NativeLegalApi(api.catalogTransport(), session)
            val document = assertIs<CatalogResult.Ready<List<LegalDocument>>>(legal.pending("en")).value.single()
            assertEquals("id", document.locale)
            assertEquals(CatalogResult.HttpError(401), legal.accept(document, "intent", session.identityGeneration - 1))
            assertEquals(0, writes); assertTrue(session.view.value.signedIn)
            status = HttpStatusCode.ServiceUnavailable
            assertEquals(CatalogResult.Maintenance(), legal.accept(document, "intent", session.identityGeneration))
            assertTrue(session.view.value.signedIn)
            status = HttpStatusCode.OK
            assertEquals(CatalogResult.Ready(Unit), legal.accept(document, "intent", session.identityGeneration))
            assertEquals(CatalogResult.Ready(emptyList()), legal.pending("en"))
            pending = true; wrongVersion = true
            assertEquals(CatalogResult.InvalidResponse, legal.pending("en"))
            assertEquals(CatalogResult.HttpError(422), legal.pending("xx"))
        } finally { client.close() }
    }
}
