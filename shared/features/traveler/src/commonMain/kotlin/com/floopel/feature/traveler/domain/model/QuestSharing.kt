package com.floopel.feature.traveler.domain.model

import kotlin.time.Instant

/** Snapshot context comes from fresh server authority, never from a button or cached reward. */
enum class QuestShareContext { INVITATION, PROGRESS, COMPLETION }

/** Export allowlist deliberately excludes profile IDs, proofs, answers, coordinates and group members. */
data class QuestShareFacts(
    val context: QuestShareContext, val questId: String, val slug: String, val title: String,
    val revisionId: String? = null, val completed: Int? = null, val total: Int? = null,
    val completedAt: Instant? = null, val xp: Int? = null, val badgeName: String? = null,
) {
    /** A public discovery link conveys no completion verification or Hidden reveal entitlement. */
    fun canonicalLink(locale: String): String {
        require(locale in setOf("id", "en") && slug.matches(Regex("[a-z0-9]+(-[a-z0-9]+)*")))
        return "https://floopel.com/$locale/quests/$slug"
    }
}
