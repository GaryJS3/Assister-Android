package net.thegary.assister

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import net.thegary.assister.ui.markdownInline
import net.thegary.assister.ui.markdownParser
import org.junit.Assert.*
import org.junit.Test

class MarkdownTest {
    @Test fun strong_emphasis_and_literal_code_render_as_styled_text() {
        val result = markdownInline(markdownParser.parse("**On** and `**literal**`").firstChild)
        assertEquals("On and **literal**", result.text)
        assertTrue(result.spanStyles.any { it.item.fontWeight == FontWeight.Bold && it.start == 0 && it.end == 2 })
        assertTrue(result.spanStyles.any { it.item.fontFamily == FontFamily.Monospace })
    }

    @Test fun unsafe_links_remain_plain_text_and_https_links_are_clickable() {
        val unsafe = markdownInline(markdownParser.parse("[Label](javascript:alert)").firstChild)
        assertEquals("Label", unsafe.text)
        assertTrue(unsafe.getLinkAnnotations(0, unsafe.length).isEmpty())
        val safe = markdownInline(markdownParser.parse("[Docs](https://example.com)").firstChild)
        assertEquals(1, safe.getLinkAnnotations(0, safe.length).size)
    }

    @Test fun streaming_incomplete_markup_stays_readable_then_formats_on_completion() {
        val partial = markdownInline(markdownParser.parse("**Office").firstChild)
        assertEquals("**Office", partial.text)
        val complete = markdownInline(markdownParser.parse("**Office**").firstChild)
        assertEquals("Office", complete.text)
        assertTrue(complete.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
    }
}
