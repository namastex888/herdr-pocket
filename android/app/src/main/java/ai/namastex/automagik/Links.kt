package ai.namastex.automagik

import java.net.URI

/**
 * Web links in terminal text. The terminal page (assets/terminal/index.html) applies the same
 * rules in JavaScript; keep the two in step.
 */
object Links {
    private val pattern = Regex("""https?://[^\s"'<>`\u0000-\u001f]+""")
    private val trailing = Regex("""[.,;:!?'"*]+$""")
    private const val MAX_LENGTH = 4096

    /** Where each http(s) link sits in [text]; punctuation after a link and unmatched closing brackets stay out. */
    fun find(text: String): List<IntRange> = pattern.findAll(text).mapNotNull { m ->
        val url = trim(m.value)
        if (url.substringAfter("://").isNotEmpty()) m.range.first until m.range.first + url.length else null
    }.toList()

    fun trim(candidate: String): String {
        var url = candidate
        do {
            val before = url
            url = url.replace(trailing, "")
            for ((open, close) in listOf('(' to ')', '[' to ']', '{' to '}'))
                if (url.endsWith(close) && url.count { it == open } < url.count { it == close }) url = url.dropLast(1)
        } while (url != before)
        return url
    }

    /** The link as something safe to hand to a browser: http or https with a host, else null. */
    fun browsable(url: String): String? {
        if (url.length > MAX_LENGTH || url.any { it.isWhitespace() || it.isISOControl() }) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) return null
        return url
    }
}
