package com.floopel.feature.traveler.domain.model

import kotlinx.serialization.json.*

/** Plain public summary; never a credential, account authorization or verified reward receipt. */
internal fun passportSummaryJson(username: String, level: Int, totalXP: Long): String {
    require(username.isNotBlank() && level in 1..10 && totalXP >= 0)
    return buildJsonObject {
        put("username", username)
        put("level", level)
        put("total_xp", totalXP)
    }.toString()
}
