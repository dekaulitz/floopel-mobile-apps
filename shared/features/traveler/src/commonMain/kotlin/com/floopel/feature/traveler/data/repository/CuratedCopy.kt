package com.floopel.feature.traveler.data.repository

/** Recognizes only the legacy curated ID/EN block format; unmarked author text is preserved. */
internal fun localizedCuratedCopy(title: String, description: String, locale: String): Pair<String, String> {
    val lines = description.replace("\r\n", "\n").trim().lines()
    val markers = lines.mapIndexedNotNull { index, line -> line.trim().takeIf { it == "ID" || it == "EN" }?.let { index to it } }
    if (markers.size != 2 || markers[0] != (0 to "ID") || markers[1].second != "EN") return title to description
    val split = markers[1].first
    val id = lines.subList(1, split).joinToString("\n").trim()
    val en = lines.drop(split + 1).joinToString("\n").trim()
    if (id.isBlank() || en.isBlank()) return title to description
    val titles = title.split(" / ")
    val localizedTitle = if (titles.size == 2 && titles.all(String::isNotBlank)) titles[if (locale == "en") 1 else 0].trim() else title
    return localizedTitle to if (locale == "en") en else id
}
