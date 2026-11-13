package com.floopel.feature.traveler.presentation.home

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.floopel.core.designsystem.component.FloopelEditorialCard
import com.floopel.feature.traveler.domain.model.CatalogEntry
import com.floopel.feature.traveler.presentation.Media
import com.floopel.feature.traveler.presentation.s
import com.floopel.feature.traveler.presentation.catalog.catalogCount
import com.floopel.feature.traveler.resources.*

internal const val HOME_ARTICLE_LIMIT = 10

@Composable
internal fun HomeArticleSlider(entries: List<CatalogEntry>, onOpen: (CatalogEntry) -> Unit) {
    LazyRow(Modifier.fillMaxWidth().testTag("home-article-slider"), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(entries.distinctBy { it.id }.take(HOME_ARTICLE_LIMIT), key = { it.id }) { entry ->
            val category = entry.story?.tags?.firstOrNull { it.isNotBlank() } ?: when (entry.category?.lowercase()) {
                "article" -> s(Res.string.home_content_article)
                "news" -> s(Res.string.home_content_news)
                "editorial" -> s(Res.string.home_content_editorial)
                else -> entry.category?.takeIf { it.isNotBlank() } ?: s(Res.string.stories)
            }
            val metadata = buildList {
                (entry.locationName?.takeIf { it.isNotBlank() }
                    ?: entry.locations.map { it.name }.filter { it.isNotBlank() }.joinToString(", ").takeIf { it.isNotBlank() })?.let(::add)
                entry.story?.readingMinutes?.takeIf { it > 0 }?.let { add(s(Res.string.location_reading_minutes, it)) }
                add(entry.commentCount?.takeIf { it >= 0 }?.let { s(Res.string.catalog_comments_label,
                    catalogCount(it.toLong(), s(Res.string.catalog_decimal_separator))) } ?: s(Res.string.home_article_comments_unknown))
            }.joinToString(" · ")
            FloopelEditorialCard(entry.title, { onOpen(entry) },
                media = { Media(entry.coverUrl, it.testTag("home-article-media-${entry.id}")) },
                modifier = Modifier.width(304.dp).testTag("home-article-${entry.id}"), header = category,
                subtitle = entry.subtitle?.takeIf { it.isNotBlank() }, readingLabel = metadata)
        }
    }
}
