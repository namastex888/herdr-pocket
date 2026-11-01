package ai.namastex.automagik

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.math.max

/**
 * Spend: tokens and API-equivalent $ per project, per task and in total, from the usage meter's `herdr-meter` doc that
 * each machine publishes in its own herdr-core (meter/README.md, "Summary doc schema"). The phone only reads it.
 *
 * Each machine's doc becomes one [Report], whatever its version; [View] sums them. A machine that is missing, stale or
 * on a doc version this app can't read is named in [View.coverage], so totals never shrink silently. $ is never made
 * up: tokens the meter couldn't price carry no $, and a sum that misses some of its data (unpriced tokens, detail the
 * meter cut to keep the doc small, a meter that started counting inside the window) is a lower bound, "≥ $x".
 */
object Spend {
    /** Doc versions this app reads. Another one shows that machine as "update", never as numbers. */
    val SCHEMAS = setOf(1, 2)
    const val DOC = "herdr-meter"
    /** A doc older than this still counts, but the coverage line says how old it is. */
    const val STALE_MS = 2 * 3_600_000L
    private const val HOUR_MS = 3_600_000L
    private const val DAY_MS = 24 * HOUR_MS

    /** The meter's rolling windows, ending at the doc's hour: the last 24 hours, 7 days, 30 days. */
    enum class Period(val label: String, val key: String, val hours: Int) { D1("24h", "d1", 24), D7("7d", "d7", 168), D30("30d", "d30", 720) }

    /** Tokens as the meter splits them. [unsplit]: known only as a total. Output includes reasoning, never added again. */
    data class Tokens(val input: Long = 0, val cacheRead: Long = 0, val cacheWrite: Long = 0, val output: Long = 0, val unsplit: Long = 0) {
        val total get() = input + cacheRead + cacheWrite + output + unsplit
        /** Share of the prompt side read from cache, 0..1; null with no prompt tokens. */
        val cached get() = (input + cacheRead + cacheWrite).takeIf { it > 0 }?.let { cacheRead.toDouble() / it }
        operator fun plus(o: Tokens) = Tokens(input + o.input, cacheRead + o.cacheRead, cacheWrite + o.cacheWrite, output + o.output, unsplit + o.unsplit)
    }

    /**
     * A sum of usage. [usd] is null when none of it could be priced; [unpriced] counts the tokens [usd] doesn't cover;
     * [gap]: some of the window's data is missing. [atLeast]: the real $ is higher than shown, or unknown.
     */
    data class Sum(val tokens: Tokens = Tokens(), val priced: Double = 0.0, val hasPrice: Boolean = false, val unpriced: Long = 0,
                   val reported: Double = 0.0, val gap: Boolean = false) {
        val usd get() = if (hasPrice) priced else null
        val atLeast get() = unpriced > 0 || gap
        operator fun plus(o: Sum) = Sum(tokens + o.tokens, priced + o.priced, hasPrice || o.hasPrice, unpriced + o.unpriced,
            reported + o.reported, gap || o.gap)
    }

    /** [tasks] and [usdPerTask] are the meter's own 30-day figures for this project on one machine (v2). */
    data class ProjectData(val windows: Map<Period, Sum>, val tasks: Int?, val hours: Int?, val usdPerTask: Double? = null)
    data class TaskData(val project: String, val branch: String, val agents: List<String>, val hours: Int?, val windows: Map<Period, Sum>)

    /** What one machine's doc said, or why it can't be used. [name] is how the phone names that machine. */
    sealed interface Reading { val name: String }
    data class Unsupported(override val name: String, val version: String) : Reading
    data class Report(
        override val name: String,
        /** The doc's `machine` (the core id): the same doc reached through two connections counts once. */
        val id: String, val version: Int, val generatedMs: Long, val pricesDate: String, val complete: Boolean,
        val totals: Map<Period, Sum>,
        /** By [projectKey]; work in no repository is in [totals] only. */
        val projects: Map<String, ProjectData>,
        val tasks: List<TaskData>,
        /** $ by UTC hour start, all harnesses together. */
        val hourly: Map<Long, Sum>,
        val hourlyFromMs: Long?,
        /** Windows whose totals miss data: the meter started counting inside them, or cut rows. */
        val totalGaps: Set<Period>,
        /** Windows where some project lost its figures to fit the doc (cut, or rows without their repo). */
        val projectGaps: Set<Period>,
        /**
         * Windows where the doc dropped task detail (rows lost their branch, or days were cut), so tasks can't be
         * counted. A meter that only started inside a window is in [totalGaps] instead: a lower bound, still countable.
         */
        val taskGaps: Set<Period>,
        /** Harnesses whose usage the meter couldn't fully read: "codex degraded". */
        val issues: List<String> = emptyList(),
        val reportedUsd: Boolean = false,
        /** Projects cut from the doc to fit: a project missing here may still have usage on this machine. */
        val projectsCut: Boolean = false,
        /** Tasks cut from the doc to fit; then only the projects in [tasksWhole] still list all of theirs. */
        val tasksCut: Boolean = false,
        val tasksWhole: Set<String> = emptySet(),
    ) : Reading {
        /** Some of [key]'s tasks on this machine are missing from the doc in window [p]. */
        fun tasksMissing(key: String, p: Period) = p in taskGaps || (tasksCut && key !in tasksWhole && (key in projects || projectsCut))
    }

    /**
     * A doc as `doc.get` returns its value: null when the machine has none (no meter there). [name] names the machine
     * on screen. Anything not shaped like a meter doc of a known version is [Unsupported], never a crash.
     */
    fun parse(name: String, value: JSONObject?): Reading? {
        value ?: return null
        val version = value.opt("schema_version")
        val v = (version as? Number)?.toInt()
        if (value.optString("kind") != "herdr-meter" || v == null || v !in SCHEMAS) return Unsupported(name, version?.toString() ?: "?")
        return runCatching { if (v == 1) v1(name, value) else v2(name, value) }.getOrElse { Unsupported(name, v.toString()) }
    }

    private fun ms(s: String?): Long? = s?.takeIf { it.isNotBlank() }?.let { try { Instant.parse(it).toEpochMilli() } catch (_: DateTimeParseException) { null } }
    private fun dayMs(s: String?): Long? = s?.let {
        try { LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() } catch (_: DateTimeParseException) { null }
    }
    private fun strings(a: JSONArray?) = a?.let { (0 until it.length()).map { i -> it.optString(i) } }.orEmpty()
    /** Where a window ending at [generated]'s hour would start had the meter counted all of it. */
    private fun nominal(generated: Long, p: Period) = generated - generated.mod(HOUR_MS) - (p.hours - 1) * HOUR_MS

    /**
     * The project a meter repository belongs to: the meter writes "host/owner/repo", [Projects] keys a repository by
     * "repo:" and its remote path after the host, lowercased.
     */
    fun projectKey(repo: String?): String? = repo?.trim()?.trim('/')?.substringAfter('/', "")?.takeIf { it.isNotBlank() }?.let { "repo:${it.lowercase()}" }

    // Version 2: windows per project, task and model, worked out by the meter.

    /** A v2 token object; null when absent (a window with no usage). */
    private fun t2(o: JSONObject?): Sum? = o?.let {
        val usd = if (o.has("usd") && !o.isNull("usd")) o.optDouble("usd").takeIf { u -> !u.isNaN() } else null
        Sum(Tokens(o.optLong("in"), o.optLong("cr"), o.optLong("cw"), o.optLong("out"), o.optLong("un")), usd ?: 0.0, usd != null,
            o.optLong("unpriced"), o.optDouble("usd_rep", 0.0).takeIf { r -> !r.isNaN() } ?: 0.0)
    }
    private fun windows2(o: JSONObject) = Period.entries.mapNotNull { p -> t2(o.optJSONObject(p.key))?.let { p to it } }.toMap()

    private fun v2(name: String, v: JSONObject): Report {
        val generated = ms(v.optString("generatedAt")) ?: error("no generatedAt")
        val starts = v.optJSONObject("windows")
        // A window that starts later than its length says began when the meter started counting: it misses data.
        val late = Period.entries.filter { p -> (ms(starts?.optString(p.key)) ?: 0L) > nominal(generated, p) }.toSet()
        val projects = HashMap<String, ProjectData>()
        val totals = HashMap<Period, Sum>()
        fun add(p: Period, s: Sum) { totals[p] = (totals[p] ?: Sum()) + s }
        for (o in v.optJSONArray("projects")?.objects().orEmpty()) {
            val w = windows2(o)
            w.forEach { (p, s) -> add(p, s) }
            val key = projectKey(o.clean("repo")) ?: continue
            projects[key] = ProjectData(w, o.optInt("tasks", -1).takeIf { it >= 0 }, o.optInt("hours", -1).takeIf { it >= 0 },
                o.optDouble("usd_per_task").takeIf { !it.isNaN() })
        }
        v.optJSONObject("projects_other")?.let { o -> windows2(o).forEach { (p, s) -> add(p, s) } }
        val tasks = v.optJSONArray("tasks")?.objects().orEmpty().mapNotNull { o ->
            val key = projectKey(o.clean("repo")) ?: return@mapNotNull null
            TaskData(key, o.clean("branch").ifBlank { return@mapNotNull null }, strings(o.optJSONArray("agents")),
                o.optInt("hours", -1).takeIf { it >= 0 }, windows2(o))
        }
        val hourly = HashMap<Long, Sum>()
        val h = v.optJSONObject("hourly")
        val hourlyFrom = h?.let { ms(it.optString("from")) }
        if (hourlyFrom != null) for (s in h.optJSONArray("series")?.objects().orEmpty()) {
            val usd = s.optJSONArray("usd")
            val n = listOf("in", "cr", "cw", "out", "usd").maxOf { s.optJSONArray(it)?.length() ?: 0 }
            for (i in 0 until n) {
                fun at(k: String) = s.optJSONArray(k)?.optLong(i) ?: 0L
                val u = usd?.optDouble(i)?.takeIf { !it.isNaN() }
                val sum = Sum(Tokens(at("in"), at("cr"), at("cw"), at("out")), u ?: 0.0, u != null)
                val hour = hourlyFrom + i * HOUR_MS
                hourly[hour] = (hourly[hour] ?: Sum()) + sum
            }
        }
        val truncated = strings(v.optJSONArray("truncated"))
        val projectsCut = v.has("projects_other") || truncated.any { "project" in it }
        val tasksCut = v.has("tasks_other") || truncated.any { "task" in it }
        val issues = v.optJSONObject("sources")?.let { s ->
            s.keys().asSequence().toList().sorted().mapNotNull { k -> s.optJSONObject(k)?.clean("status")?.takeIf { it == "degraded" || it == "unsupported" }?.let { "$k $it" } }
        }.orEmpty()
        // Busy machines keep every project but cut tasks: a project whose kept tasks match its count still lists them all.
        val whole = projects.filter { (key, d) -> d.tasks != null && tasks.count { it.project == key } == d.tasks }.keys
        val prices = v.optJSONObject("prices")
        return Report(name, v.clean("machine").ifBlank { name }, 2, generated, prices?.clean("date").orEmpty(), v.optBoolean("complete", true),
            totals, projects, tasks, hourly, hourlyFrom, late,
            if (projectsCut) Period.entries.toSet() else late, emptySet(), issues,
            totals.values.any { it.reported > 0 }, projectsCut, tasksCut, whole)
    }

    // Version 1 (meter 0.7): hourly rows for 48 hours, then daily rows, by harness, model, repo and branch.

    private data class Row(val atMs: Long, val hourly: Boolean, val repo: String?, val branch: String?, val sum: Sum)

    private fun v1(name: String, v: JSONObject): Report {
        val generated = ms(v.optString("generatedAt")) ?: error("no generatedAt")
        val counted = ms(v.optString("counted_from")) ?: 0L
        val truncated = strings(v.optJSONArray("truncated"))
        fun rows(key: String, time: String, hourly: Boolean) = v.optJSONArray(key)?.objects().orEmpty().mapNotNull { r ->
            val at = (if (hourly) ms(r.optString(time)) else dayMs(r.optString(time))) ?: return@mapNotNull null
            val tokens = Tokens(r.optLong("in"), r.optLong("cr"), r.optLong("cw5m") + r.optLong("cw1h"), r.optLong("out"))
            val usd = if (r.optBoolean("priced", true) && r.has("usd") && !r.isNull("usd")) r.optDouble("usd").takeIf { !it.isNaN() } else null
            val reported = r.optString("usd_src") == "reported"
            Row(at, hourly, r.clean("repo").ifBlank { null }, r.clean("branch").ifBlank { null },
                Sum(tokens, usd ?: 0.0, usd != null, if (usd == null) tokens.total else 0, if (reported) usd ?: 0.0 else 0.0))
        }
        val all = rows("hourly", "hour", true) + rows("daily", "day", false)
        val hours = truncated.firstNotNullOfOrNull { Regex("""hourly rows for the last (\d+) hours only""").find(it)?.groupValues?.get(1)?.toInt() } ?: 48
        val hourlyFrom = generated - generated.mod(HOUR_MS) - (hours - 1) * HOUR_MS
        // With days cut the meter kept the last N days that had rows: nothing before the oldest one is known.
        val covered = max(counted, if (truncated.none { it.startsWith("daily rows for the last") }) 0L
            else all.filter { !it.hourly }.minOfOrNull { it.atMs } ?: hourlyFrom)
        val repoFrom = if ("daily rows without repo" in truncated) max(covered, hourlyFrom) else covered
        val branchFrom = when {
            "hourly rows without branch" in truncated -> Long.MAX_VALUE
            "daily rows without branch" in truncated || "daily rows without repo" in truncated -> max(covered, hourlyFrom)
            else -> covered
        }
        // A daily row is a whole UTC day: it counts in a window when the day ends inside it.
        fun start(p: Period) = nominal(generated, p)
        fun inside(r: Row, p: Period) = (if (r.hourly) r.atMs else r.atMs + DAY_MS - 1) >= start(p)
        fun windows(rows: List<Row>) = Period.entries.mapNotNull { p ->
            rows.filter { inside(it, p) }.takeIf { it.isNotEmpty() }?.map { it.sum }?.reduce(Sum::plus)?.let { p to it }
        }.toMap()
        val projects = all.mapNotNull { r -> projectKey(r.repo)?.let { it to r } }.groupBy({ it.first }, { it.second })
            .mapValues { (_, rows) -> ProjectData(windows(rows), null, null) }
        val tasks = all.mapNotNull { r -> val k = projectKey(r.repo); val b = Projects.task(r.branch); if (k != null && b != null) (k to b) to r else null }
            .groupBy({ it.first }, { it.second }).map { (k, rows) -> TaskData(k.first, k.second, emptyList(), null, windows(rows)) }
        val hourly = all.filter { it.hourly }.groupBy { it.atMs }.mapValues { (_, rows) -> rows.map { it.sum }.reduce(Sum::plus) }
        fun gaps(from: Long) = Period.entries.filter { from > start(it) }.toSet()
        val prices = v.optJSONObject("prices")
        return Report(name, v.clean("machine").ifBlank { name }, 1, generated, prices?.clean("date").orEmpty(), v.optBoolean("complete", true),
            windows(all), projects, tasks, hourly, hourlyFrom, gaps(covered), gaps(repoFrom),
            // Only detail the doc dropped hides tasks; a meter that started counting late just makes their sums partial.
            if (branchFrom == counted) emptySet() else gaps(branchFrom),
            reportedUsd = all.any { it.sum.reported > 0 })
    }

    /** One machine in the coverage line. [ageMs]: how old its doc is. */
    data class Machine(val name: String, val state: State, val ageMs: Long? = null, val version: String = "",
                       val counting: Boolean = false, val issues: List<String> = emptyList())
    enum class State { FRESH, STALE, UNSUPPORTED, MISSING }

    /** Which machines report, which don't, and so how far the totals can be trusted. */
    data class Coverage(val machines: List<Machine>) {
        val reporting get() = machines.count { it.state == State.FRESH || it.state == State.STALE }
        /** No machine has a meter at all: the screens show one hint and no numbers. */
        val none get() = machines.all { it.state == State.MISSING }
        val complete get() = machines.all { it.state == State.FRESH && !it.counting && it.issues.isEmpty() }
    }

    data class TaskView(val project: String, val branch: String, val sum: Sum, val hours: Int?, val agents: List<String>)
    data class PerTask(val usd: Double, val tasks: Int, val atLeast: Boolean)

    /**
     * Readings merged for display. [machines]: every machine the phone sees now, by name, so one without a meter shows
     * as missing. [readings]: the docs read, also from machines that have dropped since (their last doc still counts, as
     * stale). [zone] is where "today" starts.
     */
    class View(readings: List<Reading>, machines: List<String>, val nowMs: Long, val zone: ZoneId = ZoneId.systemDefault()) {
        val reports: List<Report> = readings.filterIsInstance<Report>().groupBy { it.id }.values.map { same -> same.maxBy { it.generatedMs } }
        val coverage: Coverage = Coverage(buildList {
            val seen = HashSet<String>()
            for (r in reports.sortedBy { it.name.lowercase() }) if (seen.add(r.name)) {
                val age = (nowMs - r.generatedMs).coerceAtLeast(0)
                add(Machine(r.name, if (age > STALE_MS) State.STALE else State.FRESH, age, r.version.toString(), !r.complete, r.issues))
            }
            for (u in readings.filterIsInstance<Unsupported>()) if (seen.add(u.name)) add(Machine(u.name, State.UNSUPPORTED, version = u.version))
            for (m in machines.sortedBy { it.lowercase() }) if (seen.add(m)) add(Machine(m, State.MISSING))
        })
        val pricesDate: String get() = reports.map { it.pricesDate }.filter { it.isNotBlank() }.maxOrNull().orEmpty()
        val reported get() = reports.any { it.reportedUsd }
        val versions get() = reports.map { it.version }.toSortedSet()

        /** Every machine's usage in the window, also work in no repository. */
        fun total(p: Period): Sum? = reports.mapNotNull { r -> r.totals[p]?.copy(gap = p in r.totalGaps) }.reduceOrNull(Sum::plus)
            ?.let { s -> s.copy(gap = s.gap || reports.any { p in it.totalGaps }) }

        /** A project's sum, null when no machine reported any of its usage in the window: a "—", never a "$0". */
        fun project(key: String, p: Period): Sum? {
            val sums = reports.mapNotNull { it.projects[key]?.windows?.get(p) }
            if (sums.isEmpty()) return null
            return sums.reduce(Sum::plus).let { s -> s.copy(gap = s.gap || reports.any { p in it.projectGaps || p in it.totalGaps }) }
        }

        /** The project's tasks with usage in the window, merged across machines, most spent first. */
        fun tasks(key: String, p: Period): List<TaskView> = reports.flatMap { it.tasks }.filter { it.project == key && p in it.windows }
            .groupBy { it.branch }.map { (branch, parts) ->
                // A kept task is exact on its machine; it undercounts only where another machine may have cut it.
                val gap = reports.any { r -> r.tasksMissing(key, p) && r.tasks.none { it.project == key && it.branch == branch } } ||
                    reports.any { r -> p in r.totalGaps && key in r.projects }
                TaskView(key, branch, parts.map { it.windows.getValue(p) }.reduce(Sum::plus).let { it.copy(gap = it.gap || gap) },
                    parts.map { it.hours }.takeIf { h -> h.none { it == null } }?.sumOf { it ?: 0 }, parts.flatMap { it.agents }.distinct())
            }.sortedWith(compareByDescending<TaskView> { it.sum.usd ?: -1.0 }.thenByDescending { it.sum.tokens.total })

        fun task(key: String, branch: String?, p: Period): TaskView? = branch?.let { b -> tasks(key, p).find { it.branch == b } }

        /** Every machine lists all of [key]'s tasks for this window: counts and averages over its tasks are whole. */
        fun tasksKnown(key: String, p: Period) = reports.none { it.tasksMissing(key, p) }

        /**
         * With the task list cut, the meter's own 30-day count and $ per task for the project, when one machine alone
         * has it (two machines' counts can't be added: the same branch may be on both).
         */
        private fun meterFigures(key: String, p: Period): ProjectData? =
            if (p != Period.D30) null else reports.filter { key in it.projects }.singleOrNull()?.projects?.get(key)

        /** Tasks with usage in the window, or null when some were cut and can't be counted. */
        fun taskCount(key: String, p: Period): Int? = if (tasksKnown(key, p)) tasks(key, p).size else meterFigures(key, p)?.tasks

        /** The mean $ of the window's tasks (untasked work excluded); null with no tasks, none priced, or some cut. */
        fun costPerTask(key: String, p: Period): PerTask? {
            if (!tasksKnown(key, p)) return meterFigures(key, p)?.let { d ->
                val usd = d.usdPerTask ?: return null
                val tasks = d.tasks?.takeIf { it > 0 } ?: return null
                PerTask(usd, tasks, project(key, p)?.let { it.unpriced > 0 || reports.any { r -> p in r.totalGaps && key in r.projects } } ?: false)
            }
            val tasks = tasks(key, p).ifEmpty { return null }
            val priced = tasks.filter { it.sum.usd != null }.ifEmpty { return null }
            return PerTask(priced.sumOf { it.sum.priced } / tasks.size, tasks.size, priced.size < tasks.size || tasks.any { it.sum.atLeast })
        }

        /** Mean active hours per task over 30 days (hours with usage, not wall time); null when the doc has none. */
        fun hoursPerTask(key: String): Double? {
            if (!tasksKnown(key, Period.D30)) return null
            val tasks = tasks(key, Period.D30).ifEmpty { return null }
            if (tasks.any { it.hours == null }) return null
            return tasks.sumOf { it.hours ?: 0 }.toDouble() / tasks.size
        }

        /** Local midnight today. */
        val midnight: Long get() = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

        /** Every machine's usage since local midnight, from the hourly series; null when no machine has one. */
        fun today(): Sum? {
            val from = midnight
            val with = reports.filter { it.hourlyFromMs != null }.ifEmpty { return null }
            val s = with.flatMap { r -> r.hourly.filterKeys { it >= from }.values }.fold(Sum()) { a, b -> a + b }
            // The series has no unpriced count: a model with unpriced tokens in the last 24 h makes today a lower bound.
            val unpriced = with.any { (it.totals[Period.D1]?.unpriced ?: 0) > 0 }
            val gap = with.any { (it.hourlyFromMs ?: 0) > from || it.complete.not() } || with.size < reports.size
            return s.copy(gap = s.gap || gap || unpriced, hasPrice = s.hasPrice || s.tokens.total == 0L)
        }

        /** Today's $ by local hour (index 0 is midnight), up to the current hour; null without hourly data. */
        fun hourly(): List<Double>? {
            val from = midnight
            if (reports.none { it.hourlyFromMs != null }) return null
            val n = ((nowMs - from) / HOUR_MS).toInt().coerceIn(0, 23) + 1
            val out = DoubleArray(n)
            for (r in reports) for ((at, s) in r.hourly) if (at >= from) out[((at - from) / HOUR_MS).toInt().coerceIn(0, n - 1)] += s.priced
            return out.toList()
        }
    }

    // Display. Every $ is an API-equivalent estimate at list price; the footnote says so, with the price table's date.

    fun usd(v: Double): String = when {
        v >= 1_000 -> String.format(Locale.US, "$%.1fk", v / 1_000)
        v >= 10 -> String.format(Locale.US, "$%.0f", v)
        else -> String.format(Locale.US, "$%.2f", v)
    }
    /** "$3.20", "≥ $3.20" when it is a lower bound, "$ —" when none of it could be priced, "—" with no usage. */
    fun money(s: Sum?): String = when {
        s == null -> "—"
        s.usd == null -> "$ —"
        s.atLeast -> "≥ ${usd(s.priced)}"
        else -> usd(s.priced)
    }
    fun money(t: PerTask?): String = t?.let { (if (it.atLeast) "≥ " else "") + usd(it.usd) } ?: "—"
    fun tokens(n: Long): String = when {
        n >= 1_000_000_000 -> String.format(Locale.US, "%.1fB", n / 1e9)
        n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1e6)
        n >= 1_000 -> String.format(Locale.US, "%.0fk", n / 1e3)
        else -> n.toString()
    }
    /** "4.1M tokens · 82% cached". */
    fun detail(s: Sum): String = listOfNotNull("${tokens(s.tokens.total)} tokens", s.tokens.cached?.let { "${(it * 100).toInt()}% cached" }).joinToString(" · ")
    fun hours(h: Double): String = if (h < 1) "under 1 h" else String.format(Locale.US, if (h < 10) "%.1f h" else "%.0f h", h)
    fun age(ms: Long): String = when {
        ms < HOUR_MS -> "${max(1, ms / 60_000)} min"
        ms < DAY_MS -> "${ms / HOUR_MS} h"
        else -> "${ms / DAY_MS} d"
    }

    /** "2 of 6 machines reporting · g9 data 3 h old · vps: update the meter · no meter on ryzen". */
    fun coverageLine(c: Coverage): String = buildList {
        add("${c.reporting} of ${c.machines.size} ${if (c.machines.size == 1) "machine" else "machines"} reporting")
        c.machines.filter { it.state == State.STALE }.forEach { add("${it.name} data ${age(it.ageMs ?: 0)} old") }
        c.machines.filter { it.state == State.UNSUPPORTED }.forEach { add("${it.name}: update the app or the meter (doc v${it.version})") }
        c.machines.filter { it.counting }.forEach { add("${it.name} still counting") }
        c.machines.forEach { m -> m.issues.forEach { add("${m.name}: $it") } }
        c.machines.filter { it.state == State.MISSING }.takeIf { it.isNotEmpty() }?.let { m -> add("no meter on " + m.joinToString(", ") { it.name }) }
    }.joinToString(" · ")

    /** The footnote under every $: what it is, and the price table it came from. */
    fun footnote(v: View): String = buildString {
        append("API-equivalent estimate at list price, not an amount billed")
        if (v.pricesDate.isNotBlank()) append(" (prices of ${v.pricesDate})")
        if (v.reported) append("; omp's own reported $ where it has one")
        append(". ≥: some tokens have no price, or some data is missing. 24h, 7d and 30d end at each machine's last update.")
    }
}
