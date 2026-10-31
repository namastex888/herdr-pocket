package dev.herdr.pocket

/** Ctrl/Alt armed from the key bar. They apply once, to the next key or typed character. */
data class KeyModifiers(val ctrl: Boolean = false, val alt: Boolean = false) {
    val any get() = ctrl || alt
}

/** Pure byte transforms for the phone key bar, kept separate from the UI so they can be unit tested. */
object KeyInput {
    private const val ESC = "\u001b"
    private val cursor = Regex("^\u001b[\\[O]([ABCDHF])$")
    private val tilde = Regex("^\u001b\\[(\\d+)~$")
    private val csiCursor = Regex("^\u001b\\[([ABCDHF])$")
    private const val PASTE_END = "\u001b[201~"

    /**
     * Applies armed modifiers using xterm conventions. Returns null when the input is not a key
     * the modifiers can apply to (for example a mouse report), so the caller can keep them armed.
     */
    fun modify(bytes: ByteArray, mods: KeyModifiers): ByteArray? {
        if (!mods.any || bytes.isEmpty()) return null
        val text = String(bytes, Charsets.UTF_8)
        val parameter = 1 + (if (mods.alt) 2 else 0) + (if (mods.ctrl) 4 else 0)
        cursor.find(text)?.let { return "$ESC[1;$parameter${it.groupValues[1]}".toByteArray() }
        tilde.find(text)?.let { return "$ESC[${it.groupValues[1]};$parameter~".toByteArray() }
        if (text.startsWith(ESC) && text.length > 1) return null
        val first = text.codePointAt(0)
        val rest = text.substring(Character.charCount(first))
        val head = (if (mods.ctrl) control(first) else null) ?: first
        return ((if (mods.alt) ESC else "") + String(Character.toChars(head)) + rest).toByteArray()
    }

    /** The C0 control a Ctrl+key produces, or null when Ctrl has no conventional meaning for it. */
    fun control(c: Int): Int? = when (c) {
        in 'a'.code..'z'.code -> c - 'a'.code + 1
        in 'A'.code..'Z'.code -> c - 'A'.code + 1
        ' '.code, '@'.code, '2'.code -> 0
        '['.code, '3'.code -> 27
        '\\'.code, '4'.code -> 28
        ']'.code, '5'.code -> 29
        '^'.code, '~'.code, '6'.code -> 30
        '_'.code, '/'.code, '-'.code, '7'.code -> 31
        '?'.code, '8'.code -> 127
        else -> null
    }

    /** Unmodified cursor keys use SS3 while a program has enabled application cursor mode (DECCKM). */
    fun cursorKeys(bytes: ByteArray, application: Boolean): ByteArray {
        if (!application) return bytes
        val match = csiCursor.find(String(bytes, Charsets.UTF_8)) ?: return bytes
        return "${ESC}O${match.groupValues[1]}".toByteArray()
    }

    /** Matches xterm.js paste: newlines become CR, and bracketed paste is wrapped and cannot be closed early. */
    fun paste(text: String, bracketed: Boolean): ByteArray {
        var body = text.replace("\r\n", "\r").replace('\n', '\r')
        if (!bracketed) return body.toByteArray()
        // Remove until stable: one pass could reassemble a terminator from nested pieces.
        while (PASTE_END in body) body = body.replace(PASTE_END, "")
        return "$ESC[200~$body$PASTE_END".toByteArray()
    }
}
