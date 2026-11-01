package ai.namastex.automagik

/** What git says about a space's folder, read the way Herdr desktop's spaces-agents sidebar reads it. */
data class GitInfo(val branch: String?, val ahead: Int, val behind: Int, val top: String, val common: String,
                   /** The origin remote as [Projects.remoteKey] writes it ("owner/repo"), or "" when there is none. */
                   val remote: String = "") {
    /** The folder that holds `.git`, or a bare repository's name without `.git`. */
    val repo: String get() = if (common.endsWith("/.git")) common.removeSuffix("/.git").substringAfterLast('/')
        else common.trimEnd('/').substringAfterLast('/').removeSuffix(".git")
    /** A worktree: its common git dir belongs to another checkout. */
    val linked: Boolean get() = common != "$top/.git"

    /** The row's tail: the repo unless nested or the space's own name, and the branch unless main, master or the name. */
    fun tail(name: String, nested: Boolean): String {
        fun same(a: String) = a.equals(name, ignoreCase = true)
        val raw = branch ?: "detached"
        val shown = if (same(raw) || raw == "master" || raw == "main") "" else raw
        val repo = if (nested || same(repo)) "" else repo
        return listOf(repo, shown).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    companion object {
        /** The fixed git commands the phone runs, each with `--no-optional-locks`, never through a shell. */
        val STATUS = listOf("status", "--porcelain=v2", "--branch")
        val REV_PARSE = listOf("rev-parse", "--path-format=absolute", "--show-toplevel", "--git-common-dir")

        /** Reads the origin's URL; exit 1 with no output means the repository has no origin. */
        val REMOTE = listOf("config", "--get", "remote.origin.url")

        /** From [STATUS], [REV_PARSE] and [REMOTE] output, all run in [cwd]; null if rev-parse said too little. */
        fun parse(status: String, revParse: String, cwd: String, remote: String = ""): GitInfo? {
            // git before 2.31 doesn't know --path-format: rev-parse echoes it, and the common dir may come back relative.
            val lines = revParse.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("--") }
            val top = lines.getOrNull(0)?.trimEnd('/')?.takeIf { it.startsWith("/") } ?: return null
            val common = lines.getOrNull(1)?.let { absolute(it, cwd) } ?: return null
            var branch: String? = null; var ahead = 0; var behind = 0
            for (line in status.lineSequence()) {
                if (line.startsWith("# branch.head ")) branch = line.removePrefix("# branch.head ").trim()
                if (line.startsWith("# branch.ab ")) {
                    val (a, b) = line.removePrefix("# branch.ab ").trim().split(' ').map { it.drop(1).toIntOrNull() ?: 0 } + listOf(0, 0)
                    ahead = a; behind = b
                }
            }
            return GitInfo(branch, ahead, behind, top, common, Projects.remoteKey(remote).orEmpty())
        }

        fun absolute(path: String, cwd: String): String {
            val parts = ArrayDeque<String>()
            for (part in (if (path.startsWith("/")) path else "$cwd/$path").split('/')) when (part) {
                "", "." -> {}
                ".." -> parts.removeLastOrNull()
                else -> parts.addLast(part)
            }
            return "/" + parts.joinToString("/")
        }

        /** Spaces in sidebar order: each worktree moves under the first main checkout of its repo; true marks a nested one.
         *  A worktree whose main checkout has no space stays where it is. */
        fun <S> ordered(spaces: List<S>, info: (S) -> GitInfo?): List<Pair<S, Boolean>> {
            val mains = spaces.mapNotNull { s -> info(s)?.takeIf { !it.linked }?.common }.toSet()
            fun nested(s: S) = info(s)?.let { it.linked && it.common in mains } == true
            val placed = HashSet<String>()
            val out = mutableListOf<Pair<S, Boolean>>()
            for (s in spaces) {
                if (nested(s)) continue
                out += s to false
                val g = info(s)?.takeIf { !it.linked } ?: continue
                if (placed.add(g.common)) spaces.filter { nested(it) && info(it)?.common == g.common }.forEach { out += it to true }
            }
            return out
        }
    }
}
