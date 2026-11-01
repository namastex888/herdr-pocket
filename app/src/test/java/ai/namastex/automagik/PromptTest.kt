package ai.namastex.automagik

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading an agent's menu off its screen, on synthetic screens shaped like Claude Code's prompts. */
class PromptTest {
    private val question = """
❯ Ask me which color I like.
────────────────────────────────────────
 ☐ Color
Which color do you like?
❯ 1. Red
     The warm one
  2. Green
     The calm one
  3. Blue
     The cool one
  4. Type something.
────────────────────────────────────────
  5. Chat about this
Enter to select · ↑/↓ to navigate · Esc to cancel
""".trimIndent()

    private val permission = """
⏺ Bash(make build)
────────────────────────────────────────
 Bash command
 make build
╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌
 Do you want to proceed?
   1. Yes
 ❯ 2. Yes, and always allow access to /work/example from this project
   3. Yes, and switch to auto mode · auto mode handles these prompts for you
   4. No
 Esc to cancel · Tab to amend
""".trimIndent()

    @Test fun readsAQuestionsMenuAcrossItsSeparator() {
        val m = Prompt.menu(question)!!
        assertEquals(listOf("Red", "Green", "Blue", "Type something.", "Chat about this"), m.options)
        assertEquals(0, m.cursor)
    }

    @Test fun aQuestionOffersItsOwnAnswersOnly() {
        assertEquals(listOf("Red", "Green", "Blue"), Prompt.choices("question", Prompt.menu(question)).map { it.label })
        // Two answers: the free-text entries never fill the third button.
        val two = Prompt.Menu(listOf("Yes", "No", "Type something.", "Chat about this"), 0)
        assertEquals(listOf("Yes", "No"), Prompt.choices("question", two).map { it.label })
    }

    @Test fun aPermissionOffersYesAndNoNeverAlwaysOrAutoMode() {
        val m = Prompt.menu(permission)!!
        assertEquals(1, m.cursor)
        assertEquals(listOf(0 to "Yes", 3 to "No"), Prompt.choices("permission", m).map { it.index to it.label })
    }

    @Test fun keysMoveFromTheCursorThenPick() {
        val m = Prompt.menu(permission)!!
        assertEquals(listOf("up", "enter"), Prompt.keys(m, 0))
        assertEquals(listOf("down", "down", "enter"), Prompt.keys(m, 3))
        assertEquals(listOf("enter"), Prompt.keys(m, 1))
    }

    @Test fun noMenuMeansNoButtons() {
        assertNull(Prompt.menu("⏺ Done.\n❯ \n"))
        assertTrue(Prompt.choices("question", Prompt.menu("⏺ Done.\n❯ ")).isEmpty())
        // A numbered list in the agent's own output has no highlighted option: not a menu.
        assertNull(Prompt.menu("Plan:\n  1. Read the code\n  2. Fix the bug\n❯ "))
        // A permission whose No isn't there (an unknown layout) gets no buttons rather than a guess.
        assertTrue(Prompt.choices("permission", Prompt.Menu(listOf("Yes", "Yes, always"), 0)).isEmpty())
        assertTrue(Prompt.choices("auth", Prompt.menu(permission)).isEmpty())
    }

    @Test fun aChangedMenuIsNotPressed() {
        val then = Prompt.menu(question)!!.options
        assertTrue(Prompt.unchanged(then, Prompt.menu(question)))
        assertFalse(Prompt.unchanged(then, Prompt.menu(permission)))
        assertFalse(Prompt.unchanged(then, null))
    }
}
