package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.data.dto.TripReportRecoveryDto
import com.floopel.feature.traveler.data.remote.validAppsId
import com.floopel.feature.traveler.domain.model.*
import kotlinx.serialization.json.*

/** Local context never enters the HTTP evidence body; normalization happens once before freezing. */
internal fun TripReportIntent.reportBody(): String = buildJsonObject {
    require(validAppsId(tripId) && visibleCharacterCount(details.trim()) <= 1000 && validReportKey(key))
    put("target_type", "trip"); put("target_id", tripId); put("reason_code", reason.wire)
    put("details", details.trim()); put("details_format", "plain")
}.toString()

/** Restores only the known target/reason vocabulary from an exact retained request. */
internal fun TripReportRecoveryDto.intent(json: Json): TripReportIntent {
    val fields = json.parseToJsonElement(body).jsonObject
    fun text(name: String) = fields.getValue(name).jsonPrimitive.also { require(it.isString) }.content
    require(text("target_type") == "trip" && text("details_format") == "plain" && validReportKey(key))
    val value = TripReportIntent(text("target_id"), title, ItineraryReportReason.entries.single { it.wire == text("reason_code") }, text("details"), key)
    require(validAppsId(value.tripId) && visibleCharacterCount(value.details) <= 1000)
    return value
}

/** Keys use the server's bounded, trimmed, control-free operation namespace. */
internal fun validReportKey(key: String): Boolean = key.isNotBlank() && key.encodeToByteArray().size <= 128 && key == key.trim() && key.none { it.isISOControl() }
