package com.floopel.feature.traveler.data.repository
import com.floopel.feature.traveler.core.network.*
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.*
/** Optional public stored counters; unavailable values remain absent, not fabricated zero. */
internal class AppsCommentTotals(private val rest: FloopelRestClient) {
 suspend fun read(type: String, targetIDs: List<String>): Map<String,Long> {
  require(type in setOf("spot","location","itinerary","content"))
  val totals = mutableMapOf<String,Long>()
  for (ids in targetIDs.filter(String::isNotBlank).distinct().chunked(100)) {
   val query = ids.joinToString("&") { "target_ids=" + it.encodeURLParameter() }
   val response = rest.execute(RestMethod.GET,"/api/apps/v1/comment-totals/targets/$type?$query")
   if (response !is ApiOutcome.Success) continue
   val batch = runCatching {
    val envelope = Json.parseToJsonElement(response.responseBody).jsonObject
    require(envelope["error"] is JsonNull && envelope.getValue("meta").jsonObject.getValue("code").jsonPrimitive.content.isNotBlank())
    envelope.getValue("data").jsonObject.getValue("items").jsonArray.mapNotNull { item ->
     val value = item.jsonObject
     val id = value.getValue("target_id").jsonPrimitive.also { require(it.isString) }.content
     val count = value.getValue("total_comments").jsonPrimitive.also { require(!it.isString) }.long
     if(id in ids && count >= 0) id to count else null
    }.toMap()
   }.getOrNull()
   if(batch != null) totals.putAll(batch)
  }
  return totals
 }
}
