package dev.herdr.pocket

import org.junit.Assert.*
import org.junit.Test

class KeyInputTest {
    private fun modify(text: String, ctrl: Boolean = false, alt: Boolean = false) =
        KeyInput.modify(text.toByteArray(), KeyModifiers(ctrl, alt))?.toString(Charsets.UTF_8)

    @Test fun ctrlMakesControlCharacters() {
        assertEquals("\u0003", modify("c", ctrl = true))
        assertEquals("\u0003", modify("C", ctrl = true))
        assertEquals("\u0012", modify("r", ctrl = true))
        assertEquals("\u0000", modify(" ", ctrl = true))
        assertEquals("\u001b", modify("[", ctrl = true))
        assertEquals("\u001f", modify("/", ctrl = true))
        assertEquals("\u007f", modify("?", ctrl = true))
    }
    @Test fun ctrlAppliesOnlyToTheFirstCharacterOfAComposedWord() {
        assertEquals("\u0004one", modify("done", ctrl = true))
    }
    @Test fun altPrefixesEscape() {
        assertEquals("\u001bb", modify("b", alt = true))
        assertEquals("\u001b\r", modify("\r", alt = true))
        assertEquals("\u001b\u0018", modify("x", ctrl = true, alt = true))
    }
    @Test fun keysWithoutAControlMeaningPassThrough() {
        assertEquals("\t", modify("\t", ctrl = true))
        assertEquals("é", modify("é", ctrl = true))
    }
    @Test fun modifiedCursorAndEditingKeysUseXtermParameters() {
        assertEquals("\u001b[1;5A", modify("\u001b[A", ctrl = true))
        assertEquals("\u001b[1;3D", modify("\u001bOD", alt = true))
        assertEquals("\u001b[1;7H", modify("\u001b[H", ctrl = true, alt = true))
        assertEquals("\u001b[5;5~", modify("\u001b[5~", ctrl = true))
    }
    @Test fun otherEscapeSequencesAreNotModified() {
        assertNull(modify("\u001b[<0;10;5M", ctrl = true))
        assertNull(modify("\u001b[Z", alt = true))
        assertNull(modify("a"))
    }
    @Test fun applicationCursorModeUsesSs3ForPlainArrows() {
        assertEquals("\u001bOA", String(KeyInput.cursorKeys("\u001b[A".toByteArray(), true)))
        assertEquals("\u001b[A", String(KeyInput.cursorKeys("\u001b[A".toByteArray(), false)))
        assertEquals("\u001b[5~", String(KeyInput.cursorKeys("\u001b[5~".toByteArray(), true)))
        assertEquals("\u001b[1;5A", String(KeyInput.cursorKeys("\u001b[1;5A".toByteArray(), true)))
    }
    @Test fun pasteNormalizesNewlinesAndBrackets() {
        assertEquals("a\rb\rc", String(KeyInput.paste("a\r\nb\nc", false)))
        assertEquals("\u001b[200~ls\r\u001b[201~", String(KeyInput.paste("ls\n", true)))
        assertEquals("\u001b[200~xrm\u001b[201~", String(KeyInput.paste("x\u001b[201~rm", true)))
        // A nested terminator must not survive a single removal pass.
        val nested = String(KeyInput.paste("\u001b[20\u001b[201~1~rm -rf ~\n", true))
        assertEquals(1, Regex(Regex.escape("\u001b[201~")).findAll(nested).count())
        assertTrue(nested.endsWith("\u001b[201~"))
    }
}
