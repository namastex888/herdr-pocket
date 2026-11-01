package ai.namastex.automagik

/**
 * The options an agent waits on, read off its screen, for the notification's buttons and the terminal screen's answer
 * row. Pressing one sends the arrow
 * keys and Enter that pick it, but only after the screen is read again and still shows the same menu.
 */
object Prompt {
    /** A numbered menu at the bottom of the screen; [cursor] is the highlighted option's index. */
    data class Menu(val options: List<String>, val cursor: Int)
    /** A button: the option's index in its menu and the label shown. */
    data class Choice(val index: Int, val label: String)

    private val OPTION = Regex("""^\s*(❯|›|>)?\s*(\d{1,2})\.\s+(\S.*?)\s*$""")
    /** A drawn separator inside a menu (Claude Code puts one before "Chat about this"). */
    private val RULE = Regex("""^[\s─━╌┄-]+$""")
    private val FREE_TEXT = listOf("type something", "chat about this")

    /** The last numbered menu on [screen] (1., 2., … in order, one highlighted), or null if there isn't one. */
    fun menu(screen: String): Menu? {
        val lines = screen.lines()
        val end = lines.indexOfLast { OPTION.matches(it) }
        if (end < 0) return null
        val found = mutableListOf<Pair<Int, MatchResult>>()
        // Walk up from the last option: other options, or their indented descriptions and wrapped lines.
        var i = end
        while (i >= 0) {
            val m = OPTION.matchEntire(lines[i])
            when {
                m != null -> found.add(0, i to m)
                RULE.matches(lines[i]) -> {}
                lines[i].isBlank() || !lines[i].startsWith(" ") -> break
            }
            if (m != null && m.groupValues[2] == "1") break
            i--
        }
        val numbers = found.map { it.second.groupValues[2].toInt() }
        if (numbers != (1..numbers.size).toList() || numbers.size < 2) return null
        val cursor = found.indexOfFirst { it.second.groupValues[1].isNotEmpty() }
        if (cursor < 0 || found.count { it.second.groupValues[1].isNotEmpty() } != 1) return null
        return Menu(found.map { it.second.groupValues[3] }, cursor)
    }

    /** The kinds of blocked agent that wait on a numbered menu. */
    val KINDS = setOf("permission", "question")
    /** No cap on a question's answers: the terminal screen has room for all of them, unlike a notification. */
    const val ALL = Int.MAX_VALUE

    /**
     * The buttons for a [kind] of prompt: a permission gets its Yes and its No (never the "always" or mode-changing
     * ones); a question gets up to [limit] of its own answers (not "Type something" or "Chat about this").
     */
    fun choices(kind: String, menu: Menu?, limit: Int = 3): List<Choice> {
        menu ?: return emptyList()
        val all = menu.options.mapIndexed { i, label -> Choice(i, label) }
        return when (kind) {
            "permission" -> listOfNotNull(all.firstOrNull { it.label.equals("Yes", true) }, all.lastOrNull { it.label.equals("No", true) || it.label.startsWith("No,", true) || it.label.startsWith("No ", true) })
                .takeIf { it.size == 2 }.orEmpty()
            "question" -> all.filter { c -> FREE_TEXT.none { c.label.lowercase().startsWith(it) } }.take(limit)
            else -> emptyList()
        }
    }

    /** The keys that move from the menu's cursor to [index] and pick it. */
    fun keys(menu: Menu, index: Int): List<String> {
        require(index in menu.options.indices)
        val step = index - menu.cursor
        return List(kotlin.math.abs(step)) { if (step > 0) "down" else "up" } + "enter"
    }

    /** The menu still on screen is the one the button was made from (same options in the same order). */
    fun unchanged(then: List<String>, now: Menu?) = now != null && now.options == then
}
