package ai.namastex.automagik

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading an agent's menu off its screen, on synthetic screens shaped like Claude Code's and Codex's prompts. */
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

    @Test fun aPermissionOffersYesAlwaysAndNoNeverAutoMode() {
        val m = Prompt.menu(permission)!!
        assertEquals(1, m.cursor)
        val c = Prompt.choices("permission", m)
        assertEquals(listOf(0 to "Yes", 1 to "Yes, and always allow access to /work/example from this project", 3 to "No"), c.map { it.index to it.label })
        assertEquals(listOf("Yes", "Always allow", "No"), c.map { it.button })
        assertEquals(listOf(false, true, false), c.map { it.always })
    }

    /** Claude Code's Bash permission: "don't ask again" for that command, with a straight or a curly apostrophe. */
    private val bash = """
⏺ Bash(make build)
────────────────────────────────────────
 Bash command
   make build
   Build the project
 Do you want to proceed?
 ❯ 1. Yes
   2. Yes, and don't ask again for make build commands in /work/example
   3. No, and tell Claude what to do differently (esc)
 Esc to cancel
""".trimIndent()

    /** Codex's command approval: a › cursor and a key hint after each option. */
    private val codex = """
  Would you like to run the following command?

  $ cargo test -p example

› 1. Yes, proceed (y)
  2. Yes, and don't ask again for commands that start with `cargo test` (p)
  3. No, and tell Codex what to do differently (esc)

  Press enter to confirm or esc to cancel
""".trimIndent()

    @Test fun claudeCodesDontAskAgainIsTheAlwaysButton() {
        for (screen in listOf(bash, bash.replace("don't", "don’t"))) {
            val m = Prompt.menu(screen)!!
            val c = Prompt.choices("permission", m)
            assertEquals(listOf(0, 1, 2), c.map { it.index })
            assertEquals(listOf("Yes", "Always allow", "No, and tell Claude what to do differently (esc)"), c.map { it.button })
            // The always button sends that option and no other: one down from the cursor on 1. Yes, then Enter.
            assertEquals(listOf("down", "enter"), Prompt.keys(m, c.single { it.always }.index))
        }
    }

    @Test fun codexsDontAskAgainIsTheAlwaysButton() {
        val m = Prompt.menu(codex)!!
        assertEquals(0, m.cursor)
        val c = Prompt.choices("permission", m)
        assertEquals(listOf(0 to "Yes, proceed (y)", 1 to "Always allow", 2 to "No, and tell Codex what to do differently (esc)"), c.map { it.index to it.button })
        assertEquals(listOf("down", "enter"), Prompt.keys(m, 1))
        val session = Prompt.menu(codex.replace("commands that start with `cargo test` (p)", "this command in this session (a)"))!!
        assertEquals(1, Prompt.choices("permission", session).single { it.always }.index)
        // Codex's network approval: a one-time Yes, a Yes for this host in the future, and a No.
        val host = Prompt.Menu(listOf("Yes, just this once (y)", "Yes, and allow this host in the future (a)", "No, and block this host in the future (esc)"), 0)
        assertEquals(listOf("Yes, just this once (y)", "Always allow", "No, and block this host in the future (esc)"), Prompt.choices("permission", host).map { it.button })
    }

    @Test fun aModeChangeIsNeverTheAlwaysButton() {
        // Claude Code's file edit: "allow all edits" switches the session's mode, so it gets Yes and No only.
        val edit = Prompt.Menu(listOf("Yes", "Yes, allow all edits during this session (shift+tab)", "No, and tell Claude what to do differently (esc)"), 0)
        assertEquals(listOf("Yes", "No, and tell Claude what to do differently (esc)"), Prompt.choices("permission", edit).map { it.button })
        assertFalse(Prompt.isAlways("Yes, and switch to auto mode · auto mode handles these prompts for you"))
        assertFalse(Prompt.isAlways("Yes, and don't ask again: switch to bypass permissions mode"))
        assertFalse(Prompt.isAlways("Yes"))
        assertFalse(Prompt.isAlways("No, and don't ask again"))
        assertFalse(Prompt.isAlways("Yes, and allow this host for this conversation"))
        assertTrue(Prompt.isAlways("Yes, and allow this host in the future"))
        assertTrue(Prompt.isAlways("Yes, and don’t ask again for npm test commands in this project"))
    }

    @Test fun twoAlwaysOptionsOfferTheFirst() {
        val m = Prompt.Menu(listOf("Yes", "Yes, and don't ask again for this command", "Yes, and don't ask again for these files", "No"), 0)
        assertEquals(listOf(0, 1, 3), Prompt.choices("permission", m).map { it.index })
    }

    @Test fun aQuestionNeverHasAnAlwaysButton() {
        val m = Prompt.Menu(listOf("Yes", "Yes, and don't ask again", "No"), 0)
        assertTrue(Prompt.choices("question", m).none { it.always })
        assertEquals(listOf("Yes", "Yes, and don't ask again", "No"), Prompt.choices("question", m).map { it.button })
    }

    @Test fun whatAlwaysCoversIsSaidInFull() {
        assertEquals("always allow access to /work/example from this project", Prompt.scope("Yes, and always allow access to /work/example from this project"))
        assertEquals("don't ask again for commands that start with `cargo test`", Prompt.scope("Yes, and don't ask again for commands that start with `cargo test` (p)"))
    }

    @Test fun theNotificationOrdersYesAlwaysNoAndDropsReplyOnlyThen() {
        val always = Prompt.choices("permission", Prompt.menu(bash))
        assertEquals(listOf("Yes", "Always", "No, and tell Claude what to do differently (esc)".take(40)), Notify.titles(always))
        val plain = Prompt.choices("permission", Prompt.Menu(listOf("Yes", "No"), 0))
        assertEquals(listOf("Yes", "No", "Reply"), Notify.titles(plain))
        assertEquals(listOf("Reply"), Notify.titles(emptyList()))
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

    /** The same permission with the cursor where Claude Code puts it first: on 1. Yes. */
    private val permissionAtYes = permission.replace(" ❯ 2.", "   2.").replace("   1. Yes", " ❯ 1. Yes")

    @Test fun keysFollowTheCursorWhereverItIs() {
        val atYes = Prompt.menu(permissionAtYes)!!
        assertEquals(0, atYes.cursor)
        assertEquals(listOf("enter"), Prompt.keys(atYes, 0))
        assertEquals(listOf("down", "down", "down", "enter"), Prompt.keys(atYes, 3))
        val atNo = Prompt.menu(permission.replace(" ❯ 2.", "   2.").replace("   4. No", " ❯ 4. No"))!!
        assertEquals(3, atNo.cursor)
        assertEquals(listOf("up", "up", "up", "enter"), Prompt.keys(atNo, 0))
        // A question with its cursor in the middle moves both ways.
        val middle = Prompt.menu(question.replace("❯ 1. Red", "  1. Red").replace("  2. Green", "❯ 2. Green"))!!
        assertEquals(1, middle.cursor)
        assertEquals(listOf("up", "enter"), Prompt.keys(middle, 0))
        assertEquals(listOf("down", "enter"), Prompt.keys(middle, 2))
    }

    @Test fun theButtonsAreTheSameWhereverTheCursorIs() {
        assertEquals(Prompt.choices("permission", Prompt.menu(permission)), Prompt.choices("permission", Prompt.menu(permissionAtYes)))
    }

    @Test fun onTheTerminalScreenAQuestionOffersAllItsOwnAnswers() {
        val five = Prompt.Menu(listOf("Red", "Green", "Blue", "Black", "Type something.", "Chat about this"), 0)
        assertEquals(listOf("Red", "Green", "Blue"), Prompt.choices("question", five).map { it.label })
        assertEquals(listOf(0, 1, 2, 3), Prompt.choices("question", five, limit = Prompt.ALL).map { it.index })
        // A permission offers its Yes, its always option and its No there, never its auto mode.
        assertEquals(listOf("Yes", "Always allow", "No"), Prompt.choices("permission", Prompt.menu(permission), limit = Prompt.ALL).map { it.button })
    }

    @Test fun aMenuThatChangedBetweenTapAndSendIsNotPressed() {
        val tapped = Prompt.menu(permission)!!.options
        // The agent moved on to a question, or redrew its permission with other options, before the keys went out.
        assertFalse(Prompt.unchanged(tapped, Prompt.menu(question)))
        val other = permission.replace("   3. Yes, and switch to auto mode · auto mode handles these prompts for you\n", "").replace("   4. No", "   3. No")
        assertEquals(listOf("Yes", "Yes, and always allow access to /work/example from this project", "No"), Prompt.menu(other)!!.options)
        assertFalse(Prompt.unchanged(tapped, Prompt.menu(other)))
        assertFalse(Prompt.unchanged(tapped, Prompt.menu("⏺ Running the tests…\n")))
        // Only the cursor moved: the same menu, so the keys are worked out from where it is now.
        val now = Prompt.menu(permissionAtYes)
        assertTrue(Prompt.unchanged(tapped, now))
        assertEquals(listOf("down", "down", "down", "enter"), Prompt.keys(now!!, 3))
    }

    @Test fun thePreviewAgentAsksAPermissionTheButtonsCanAnswer() {
        val asking = CoreTerminal("t1", "Auth", "~/work", "live", "blocked", "Claude Code", "Waiting", 80, 24, kind = "permission")
        val menu = Prompt.menu(previewScreen(asking))!!
        assertEquals(0, menu.cursor)
        assertEquals(listOf(0 to "Yes", 1 to "Always allow", 2 to "No, and tell Claude what to do differently (esc)"),
            Prompt.choices("permission", menu).map { it.index to it.button })
        // Once it moves on, its screen has no menu and the buttons go.
        assertNull(Prompt.menu(previewScreen(asking.copy(status = "working", kind = ""))))
    }
}
