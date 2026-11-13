package com.floopel.feature.traveler.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

internal data class RichSpan(val text: String, val bold: Boolean, val italic: Boolean, val link: String?)
internal sealed interface RichBlock {
    data class Paragraph(val spans: List<RichSpan>, val headingLevel: Int? = null) : RichBlock
    data class Image(val url: String, val alt: String?) : RichBlock
}

/** Inert subset of server-sanitized rich text. No WebView, script, form, embed or CSS execution. */
internal fun richBlocks(html: String): List<RichBlock> {
    val blocks = mutableListOf<RichBlock>(); val spans = mutableListOf<RichSpan>()
    val styles = mutableListOf<Pair<String, String?>>()
    fun append(text: String) {
        if (text.isNotEmpty()) spans += RichSpan(text, styles.any { it.first in setOf("b", "strong", "h1", "h2", "h3", "h4") },
            styles.any { it.first in setOf("em", "i") }, styles.lastOrNull { it.first == "a" }?.second)
    }
    fun flush() {
        if (spans.any { it.text.isNotBlank() }) blocks += RichBlock.Paragraph(spans.toList(),
            styles.lastOrNull { it.first in setOf("h1", "h2", "h3", "h4") }?.first?.drop(1)?.toIntOrNull())
        spans.clear()
    }
    val inert = html.replace(Regex("<(script|style|iframe|object|template)\\b[^>]*>[\\s\\S]*?</\\1\\s*>", RegexOption.IGNORE_CASE), "")
    Regex("<!--[\\s\\S]*?-->|<[^>]*>|[^<]+").findAll(inert).forEach { match ->
        val token = match.value
        if (!token.startsWith("<")) append(decodeEntities(token))
        else if (!token.startsWith("<!")) {
            val tag = Regex("^</?\\s*([a-zA-Z0-9]+)").find(token)?.groupValues?.get(1)?.lowercase().orEmpty()
            val closing = token.startsWith("</")
            fun attribute(name: String): String? = Regex("\\b$name\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", RegexOption.IGNORE_CASE)
                .find(token)?.groupValues?.drop(1)?.firstOrNull(String::isNotEmpty)?.let(::decodeEntities)
            when {
                tag == "img" && !closing -> { flush(); safeRichUrl(attribute("src"))?.let { blocks += RichBlock.Image(it, attribute("alt")) } }
                tag == "br" -> append("\n")
                tag in setOf("p", "div", "li", "h1", "h2", "h3", "h4", "blockquote") -> {
                    flush()
                    if (tag == "li" && !closing) append("• ")
                    if (tag.startsWith("h")) {
                        if (!closing) styles += tag to null else styles.removeAll { it.first == tag }
                    }
                }
                tag in setOf("b", "strong", "i", "em", "a") -> {
                    if (!closing) styles += tag to if (tag == "a") safeRichLink(attribute("href")) else null
                    else { val index = styles.indexOfLast { it.first == tag }; if (index >= 0) styles.removeAt(index) }
                }
            }
        }
    }
    flush(); return blocks
}

private fun safeRichUrl(value: String?): String? = value?.takeIf {
    Regex("https://[A-Za-z0-9.-]+(?::[0-9]{1,5})?(?:[/?#][^\\s\\p{Cntrl}]*)?").matches(it)
}
private fun safeRichLink(value: String?): String? = value?.takeIf {
    Regex("https?://[A-Za-z0-9.-]+(?::[0-9]{1,5})?(?:[/?#][^\\s\\p{Cntrl}]*)?").matches(it) ||
        Regex("mailto:[A-Za-z0-9_.+%-]+@[A-Za-z0-9.-]+").matches(it)
}
private fun decodeEntities(text: String): String = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos|nbsp);").replace(text) { match ->
    when (val value = match.groupValues[1]) {
        "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"; "nbsp" -> "\u00a0"
        else -> {
            val code = if (value.startsWith("#x")) value.drop(2).toIntOrNull(16) else value.drop(1).toIntOrNull()
            when { code == null || code !in 1..0x10ffff || code in 0xd800..0xdfff -> "\uFFFD"
                code < 0x10000 -> code.toChar().toString()
                else -> "${((code - 0x10000) / 0x400 + 0xd800).toChar()}${((code - 0x10000) % 0x400 + 0xdc00).toChar()}" }
        }
    }
}

@Composable
internal fun RichBody(html: String, textStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium, legalHeadings: Boolean = false) {
    val blocks = remember(html) { richBlocks(html) }; val uri = LocalUriHandler.current
    val linkStyle = SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)
    Column(verticalArrangement = Arrangement.spacedBy(if (legalHeadings) 16.dp else 12.dp)) {
        blocks.forEachIndexed { index, block -> when (block) {
            is RichBlock.Image -> Media(block.url, Modifier.fillMaxWidth().aspectRatio(1.6f), block.alt)
            is RichBlock.Paragraph -> Text(buildAnnotatedString {
                block.spans.forEach { span ->
                    withStyle(SpanStyle(fontWeight = if (span.bold) FontWeight.Bold else FontWeight.Normal,
                        fontStyle = if (span.italic) FontStyle.Italic else FontStyle.Normal)) {
                        if (span.link != null) withLink(LinkAnnotation.Url(span.link, TextLinkStyles(linkStyle)) { runCatching { uri.openUri(span.link) } }) { append(span.text) }
                        else append(span.text)
                    }
                }
            }, modifier = if (legalHeadings && block.headingLevel != null) Modifier.semantics { heading() } else Modifier,
                style = if (legalHeadings && block.headingLevel != null) textStyle.copy(
                    fontSize = if (index == 0 || block.headingLevel == 1) 26.sp else 20.sp,
                    lineHeight = if (index == 0 || block.headingLevel == 1) 35.1.sp else 27.sp, fontWeight = FontWeight.Bold) else textStyle)
        } }
    }
}
