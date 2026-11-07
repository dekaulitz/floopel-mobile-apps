package com.floopel.feature.traveler.presentation

import kotlin.test.*

class RichBodyTest {
    @Test fun activeContentAndUnsafeUrlsAreInert() {
        val blocks = richBlocks("<script>alert('secret')</script><p>Text <a href='javascript:alert(1)'>link</a></p><img src='file:///secret'><iframe src='https://example.com'>embed</iframe>")
        val text = blocks.filterIsInstance<RichBlock.Paragraph>().flatMap { it.spans }
        assertEquals("Text link", text.joinToString("") { it.text })
        assertTrue(text.all { it.link == null }); assertTrue(blocks.none { it is RichBlock.Image })
    }
    @Test fun richTextKeepsFormattingLinksAndAltTextWithoutExecutingMarkup() {
        val blocks = richBlocks("<p><strong>Budaya</strong> &amp; <em>cerita</em> <a href='https://example.com/a?x=1&amp;y=2'>sumber</a></p><img src='https://example.com/photo.png' alt='Candi &amp; taman'>")
        val spans = assertIs<RichBlock.Paragraph>(blocks[0]).spans
        assertTrue(spans.first().bold); assertTrue(spans.single { it.text == "cerita" }.italic)
        assertEquals("https://example.com/a?x=1&y=2", spans.last().link)
        assertEquals("Candi & taman", assertIs<RichBlock.Image>(blocks[1]).alt)
    }
    @Test fun malformedFormattingAndNumericEntitiesRemainReadable() {
        val spans = richBlocks("<p><b>Jejak</i> &#x1F30F; &#1114112; &lt;tag&gt;</p>").filterIsInstance<RichBlock.Paragraph>().flatMap { it.spans }
        assertEquals("Jejak 🌏 � <tag>", spans.joinToString("") { it.text })
    }
    @Test fun documentedWebAndMailLinksSurviveWithoutAllowingActiveSchemes() {
        val spans = richBlocks("<p><a href='http://example.com/help'>help</a><a href='mailto:hello@example.com'>email</a><a href='data:text/html,script'>inert</a></p>")
            .filterIsInstance<RichBlock.Paragraph>().flatMap { it.spans }
        assertEquals(listOf("http://example.com/help", "mailto:hello@example.com", null), spans.map { it.link })
    }
}
