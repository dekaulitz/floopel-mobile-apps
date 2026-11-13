package com.floopel.feature.traveler.testing

import com.floopel.feature.traveler.TravelerRuntime
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.*
import java.util.concurrent.CopyOnWriteArrayList

/** Only synthetic state is mutated. No HTTP client, account or backend is involved. */
class TravelerPendingInputFixture {
    val base = TravelerFixture()
    @Volatile var textQuestion = false
    @Volatile var answerOutcome = "incorrect"
    @Volatile var completeOnCorrect = false
    val answers = CopyOnWriteArrayList<String>()
    var beforeAnswer: suspend (String) -> Unit = {}
    val identity = object : TravelerIdentity by base.identity {
        override suspend fun activity(id: String) {
            if (base.identityState.value.question?.id == id) return
            base.identityState.value = base.identityState.value.copy(question = QuestQuestion(id, "Pelajari cerita Spot", QuestActivityKind.QUESTION,
                "Pilih jawaban.", if (textQuestion) emptyList() else listOf("a" to "Jawaban A", "b" to "Jawaban B"), if (textQuestion) "text" else "choice"),
                answerOutcome = null, explanation = null, submittedAnswer = null)
        }
        override suspend fun answer(value: String) {
            val start = base.identityState.value
            if (start.busy) return
            answers += value
            base.identityState.value = start.copy(busy = true, answerOutcome = null, explanation = null, submittedAnswer = value)
            beforeAnswer(value)
            val current = base.identityState.value
            if (current.generation != start.generation || current.question?.id != start.question?.id) return
            base.identityState.value = current.copy(busy = false, answerOutcome = answerOutcome, explanation = "Hasil untuk $value",
                completed = completeOnCorrect && answerOutcome == "correct")
        }
    }
    data class TitleWrite(val itineraryId: String, val day: Int, val value: String)
    val titleWrites = CopyOnWriteArrayList<TitleWrite>()
    val detailReads = CopyOnWriteArrayList<String>()
    val titles = mutableMapOf((base.planId to 1) to "Hari awal")
    @Volatile var titleResult: CatalogResult<OwnedItineraryDetail>? = null
    @Volatile var legalResult: CatalogResult<List<LegalDocument>> = CatalogResult.Ready(emptyList())
    var beforeLegal: suspend () -> Unit = {}
    var beforeTitle: suspend () -> Unit = {}
    private fun projection(id: String) = base.plan.copy(itinerary = base.plan.itinerary.copy(id = id),
        dayTitles = titles.filterKeys { it.first == id }.mapKeys { it.key.second })
    val itineraries = object : TravelerItineraryRepository by base.itineraries {
        override suspend fun detail(id: String): CatalogResult<OwnedItineraryDetail> {
            detailReads += id
            return CatalogResult.Ready(projection(id))
        }
        override suspend fun titleDay(id: String, day: Int, title: String): CatalogResult<OwnedItineraryDetail> {
            titleWrites += TitleWrite(id, day, title); beforeTitle()
            titleResult?.let { return it }
            titles[id to day] = title
            return CatalogResult.Ready(projection(id))
        }
    }
    val legal = object : TravelerLegalRepository by base.runtime.legal {
        override suspend fun pending(locale: String): CatalogResult<List<LegalDocument>> { beforeLegal(); return legalResult }
    }
    val runtime = base.runtime.let { r -> TravelerRuntime(identity, r.catalog, itineraries, r.distribution, r.trips,
        r.notifications, r.account, legal, r.registration, r.publicContent, r.savedSpots, r.questGroups, r.newOperationKey, r.startup) }
}
