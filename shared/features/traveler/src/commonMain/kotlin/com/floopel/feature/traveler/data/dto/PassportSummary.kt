package com.floopel.feature.traveler.data.dto

import kotlinx.serialization.json.*

/** Plain summary only; contains neither credentials nor a verified reward claim. */
internal fun passportSummaryJson(username: String, level: Int, totalXP: Long): String {
    require(username.isNotBlank() && level in 1..10 && totalXP >= 0)
    return buildJsonObject {
        put("username", username)
        put("level", level)
        put("total_xp", totalXP)
    }.toString()
}
