package dev.herdr.pocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinksTest {
    private fun links(text: String) = Links.find(text).map { text.substring(it) }

    @Test fun findsLinksAndLeavesTrailingPunctuationOut() {
        assertEquals(listOf("https://github.com/namastex888/herdr-pocket/pull/2"),
            links("Opened https://github.com/namastex888/herdr-pocket/pull/2."))
        assertEquals(listOf("http://localhost:8080/a?b=1&c=2#x"), links("serving at http://localhost:8080/a?b=1&c=2#x, ready"))
        assertEquals(listOf("https://a.dev", "https://b.dev/x"), links("see https://a.dev and https://b.dev/x!"))
    }
    @Test fun keepsBalancedBracketsAndDropsUnmatchedClosers() {
        assertEquals(listOf("https://en.wikipedia.org/wiki/Rust_(programming_language)"),
            links("(https://en.wikipedia.org/wiki/Rust_(programming_language))"))
        assertEquals(listOf("https://example.com/docs"), links("[docs](https://example.com/docs)"))
        assertEquals(listOf("https://example.com"), links("\"https://example.com\""))
    }
    @Test fun ignoresOtherSchemesAndBareSchemes() {
        assertEquals(emptyList<String>(), links("file:///etc/passwd javascript:alert(1) ftp://x.org https:// http://."))
    }
    @Test fun onlyHttpLinksWithAHostAreBrowsable() {
        assertEquals("https://example.com/a", Links.browsable("https://example.com/a"))
        assertEquals("HTTP://EXAMPLE.COM", Links.browsable("HTTP://EXAMPLE.COM"))
        for (bad in listOf("javascript:alert(1)", "intent://x#Intent;end", "file:///sdcard/x", "content://x/y", "https:///nohost",
            "https://exa mple.com", "https://example.com/\n", "data:text/html,hi", "https://" + "a".repeat(5000) + ".com"))
            assertNull(bad, Links.browsable(bad))
    }
}
