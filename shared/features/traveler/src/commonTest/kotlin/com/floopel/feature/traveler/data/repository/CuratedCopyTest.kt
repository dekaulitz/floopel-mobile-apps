package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.core.network.FloopelRestClient
import com.floopel.feature.traveler.domain.model.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class CuratedCopyTest {
    private val bilingual = "ID\r\n\r\nCerita Indonesia.\r\n\r\nParagraf kedua.\r\n\r\nEN\r\n\r\nEnglish story."
    @Test fun recognizedCuratedBlocksSelectOneLanguageWithoutDiscardingItsParagraphs() {
        assertEquals("Ruang hijau" to "Cerita Indonesia.\n\nParagraf kedua.", localizedCuratedCopy("Ruang hijau / Green spaces", bilingual, "id"))
        assertEquals("Green spaces" to "English story.", localizedCuratedCopy("Ruang hijau / Green spaces", bilingual, "en"))
    }
    @Test fun unmarkedOrAmbiguousCopyAndLegitimateSlashesArePreserved() {
        for (text in listOf("Deskripsi biasa.", "ID\n\nBelum diterjemahkan.", "ID\n\nSatu\nEN\n\nTwo\nEN\n\nThree"))
            assertEquals("Pagi / Sore" to text, localizedCuratedCopy("Pagi / Sore", text, "id"))
        assertEquals("A / B / C", localizedCuratedCopy("A / B / C", bilingual, "id").first)
    }
    @Test fun localizationOnlyAppliesToCuratedSourceAndHonorsTheRequestedLocale() = runTest {
        for (source in listOf("curated", "traveler")) for (locale in listOf("id", "en")) {
            val client = FloopelRestClient("https://fixture.invalid", HttpClient(MockEngine { request ->
                if (request.url.encodedPath.contains("/comment-totals/"))
                    return@MockEngine respond("""{"meta":{"code":"OK"},"error":null,"data":{"items":[]}}""")
                assertEquals(locale, request.url.parameters["locale"])
                val row = buildJsonObject { put("id","plan");put("title","Ruang hijau / Green spaces");put("description",bilingual)
                    put("source",source);put("locations",JsonArray(emptyList())) }
                respond("""{"meta":{"code":"OK","pagination":{"page":1,"total_pages":1}},"error":null,"data":[$row]}""")
            }))
            try {
                val entry = assertIs<CatalogResult.Ready<CatalogPage>>(AppsTravelerCatalog(client).curatedItineraries("",null,locale,1)).value.items.single()
                if (source == "curated") assertEquals(if (locale == "id") "Ruang hijau" else "Green spaces",entry.title)
                else { assertEquals("Ruang hijau / Green spaces",entry.title); assertEquals(bilingual,entry.subtitle) }
            } finally { client.close() }
        }
    }
}
