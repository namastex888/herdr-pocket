package ai.namastex.automagik

/** Tracks only the VT modes the phone controls need (scrolling, cursor keys, paste), across arbitrary frame boundaries. */
class TerminalModes {
    @Volatile var alternate = false; private set
    @Volatile var mouseTracking = false; private set
    @Volatile var sgrMouse = false; private set
    @Volatile var applicationCursor = false; private set
    @Volatile var bracketedPaste = false; private set
    private val mouseModes = mutableSetOf<Int>()
    private var phase = 0
    private val parameters = StringBuilder()

    fun feed(bytes: ByteArray) {
        for (byte in bytes) {
            val c = (byte.toInt() and 255).toChar()
            if (c == '\u001b') { phase = 1; parameters.clear(); continue }
            when (phase) {
                1 -> {
                    phase = if (c == '[') 2 else 0
                    if (c == 'c') { mouseModes.clear(); alternate = false; mouseTracking = false; sgrMouse = false; applicationCursor = false; bracketedPaste = false }
                }
                2 -> {
                    if (c in '@'..'~') {
                        if ((c == 'h' || c == 'l') && parameters.startsWith("?")) {
                            val enabled = c == 'h'
                            parameters.substring(1).split(';').mapNotNull(String::toIntOrNull).forEach { mode ->
                                when (mode) {
                                    47, 1047, 1049 -> alternate = enabled
                                    1000, 1002, 1003 -> if (enabled) mouseModes.add(mode) else mouseModes.remove(mode)
                                    1006 -> sgrMouse = enabled
                                    1 -> applicationCursor = enabled
                                    2004 -> bracketedPaste = enabled
                                }
                            }
                            mouseTracking = mouseModes.isNotEmpty()
                        }
                        phase = 0; parameters.clear()
                    } else if (parameters.length < 128) parameters.append(c)
                    else { phase = 0; parameters.clear() }
                }
            }
        }
    }
}
