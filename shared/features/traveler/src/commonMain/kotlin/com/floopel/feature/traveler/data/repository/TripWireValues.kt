package com.floopel.feature.traveler.data.repository

import kotlinx.serialization.json.*

/** Wire booleans must be unquoted JSON literals; serializer coercion cannot grant a capability. */
internal fun JsonObject.tripBoolean(key: String): Boolean = getValue(key).jsonPrimitive.also { require(!it.isString) }.boolean

/** Capacity and position use JSON integers, never numeric strings. */
internal fun JsonObject.tripInt(key: String): Int = getValue(key).jsonPrimitive.also { require(!it.isString) }.int

/** Visible comment totals retain their full integral range and known zero. */
internal fun JsonObject.tripLong(key: String): Long = getValue(key).jsonPrimitive.also { require(!it.isString) }.long

/** Explicit null is different from an omitted field. */
internal fun JsonObject.tripNullableText(key: String): String? =
    if (getValue(key) is JsonNull) null else wireText(key)

/** Coordinates and reference values remain numeric or explicitly unavailable. */
internal fun JsonObject.tripNullableDouble(key: String): Double? =
    if (getValue(key) is JsonNull) null else getValue(key).jsonPrimitive.also { require(!it.isString) }.double

/** An absent image/object must be explicit null; malformed containers are contract failures. */
internal fun JsonObject.tripNullableObject(key: String): JsonObject? =
    if (getValue(key) is JsonNull) null else getValue(key).jsonObject
