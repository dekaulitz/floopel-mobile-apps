package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.core.network.FloopelRestClient
import com.floopel.feature.traveler.domain.model.CatalogPhoto
import com.floopel.feature.traveler.domain.model.CatalogPhotoVariant
import kotlinx.serialization.json.*

/** Keep every authorized variant and its real dimensions until the renderer knows the slot. */
internal fun catalogMediaPhoto(asset: JsonElement?, rest: FloopelRestClient, alt: String): CatalogPhoto? {
    val descriptor = asset as? JsonObject ?: return null
    val variants = descriptor["variants"] as? JsonObject ?: return null
    val resolved = variants.mapNotNull { (name, element) ->
        val value = element as? JsonObject ?: return@mapNotNull null
        val url = rest.resolveMediaUrl((value["url"] as? JsonPrimitive)?.contentOrNull) ?: return@mapNotNull null
        Triple(name, url, value)
    }
    val fallback = listOf("medium", "small", "large", "thumb").firstNotNullOfOrNull { name -> resolved.firstOrNull { it.first == name }?.second }
        ?: return null
    val sizes = resolved.mapNotNull { (_, url, value) ->
        val width = (value["width"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
        val height = (value["height"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
        if (width > 0 && height > 0) CatalogPhotoVariant(url, width, height) else null
    }.distinctBy { it.url }
    return CatalogPhoto(fallback, (descriptor["alt"] as? JsonPrimitive)?.contentOrNull?.takeUnless { it == "null" } ?: alt, sizes)
}
