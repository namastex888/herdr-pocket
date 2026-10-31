package dev.herdr.pocket

import org.junit.Assert.*
import org.junit.Test

class TerminalModesTest {
    @Test fun followsSplitAlternateAndMouseSequences() {
        val modes = TerminalModes()
        for (c in "hello\u001b[?1049h\u001b[?1002;1006h") modes.feed(c.toString().toByteArray())
        assertTrue(modes.alternate); assertTrue(modes.mouseTracking); assertTrue(modes.sgrMouse)
        modes.feed("\u001b[?1002l".toByteArray())
        assertFalse(modes.mouseTracking); assertTrue(modes.alternate)
        modes.feed("\u001b[?1049l".toByteArray()); assertFalse(modes.alternate)
    }
    @Test fun ignoresOtherEscapesAndRecoversFromOversizedSequence() {
        val modes = TerminalModes()
        modes.feed(("\u001b[" + "1".repeat(500) + "h\u001b[31m1049h\u001b[?47h").toByteArray())
        assertTrue(modes.alternate); assertFalse(modes.mouseTracking)
        modes.feed("\u001bc".toByteArray())
        assertFalse(modes.alternate); assertFalse(modes.sgrMouse)
    }
    @Test fun alternateScreenAliasesSelectTheSameBuffer() {
        val modes = TerminalModes()
        modes.feed("\u001b[?47h\u001b[?1049h\u001b[?1049l".toByteArray())
        assertFalse(modes.alternate)
    }
    @Test fun retainsOtherEnabledMouseModes() {
        val modes = TerminalModes()
        modes.feed("\u001b[?1000h\u001b[?1003h\u001b[?1000l".toByteArray())
        assertTrue(modes.mouseTracking)
        modes.feed("\u001b[?1003l".toByteArray()); assertFalse(modes.mouseTracking)
    }
}
