package ai.namastex.automagik

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * The preview's usage meter: two synthetic `herdr-meter` docs (schema 2) for the demo's repositories, written relative
 * to now so Today always has hours to show. Made-up numbers, no real machine: the preview machine reports fresh, a
 * second one ("build-box") last reported three hours ago, and one model has no price.
 */
object SpendDemo {
    private const val HOUR = 3_600_000L
    private fun t(input: Long, cr: Long, cw: Long, out: Long, usd: Double?, unpriced: Long = 0) = JSONObject().apply {
        put("in", input); put("cr", cr); put("cw", cw); put("out", out)
        if (usd != null) put("usd", usd)
        if (unpriced > 0) put("unpriced", unpriced)
    }
    private fun windows(o: JSONObject, d1: JSONObject?, d7: JSONObject, d30: JSONObject) = o.apply {
        d1?.let { put("d1", it) }; put("d7", d7); put("d30", d30)
    }
    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()

    /** One doc. [scale] sizes the numbers, [ageMs] how long ago it was written. */
    fun doc(machine: String, now: Long, ageMs: Long, scale: Double, withUnpriced: Boolean): JSONObject {
        val generated = now - ageMs
        val hour = generated - generated.mod(HOUR)
        fun s(v: Double) = Math.round(v * scale * 100) / 100.0
        fun n(v: Long) = (v * scale).toLong()
        val tasks = JSONArray()
            .put(windows(JSONObject().put("repo", "github.com/example/herdr").put("branch", "feat/auth-flow").put("agents", JSONArray().put("claude-code"))
                .put("first", iso(hour - 30 * HOUR)).put("last", iso(hour)).put("hours", 6),
                t(n(900), n(1_400_000), n(60_000), n(21_000), s(4.10)), t(n(2_100), n(3_900_000), n(160_000), n(52_000), s(11.30)),
                t(n(2_100), n(3_900_000), n(160_000), n(52_000), s(11.30))))
            .put(windows(JSONObject().put("repo", "github.com/example/herdr").put("branch", "feat/release-notes").put("agents", JSONArray().put("codex"))
                .put("first", iso(hour - 5 * HOUR)).put("last", iso(hour)).put("hours", 3),
                t(n(400), n(600_000), n(20_000), n(9_000), s(1.60)), t(n(400), n(600_000), n(20_000), n(9_000), s(1.60)),
                t(n(400), n(600_000), n(20_000), n(9_000), s(1.60))))
        if (withUnpriced) tasks.put(windows(JSONObject().put("repo", "github.com/example/herdr").put("branch", "fix/api-review")
            .put("agents", JSONArray().put("codex")).put("first", iso(hour - 2 * HOUR)).put("last", iso(hour - HOUR)).put("hours", 2),
            t(300, 410_000, 0, 7_000, null, 417_300), t(300, 410_000, 0, 7_000, null, 417_300), t(300, 410_000, 0, 7_000, null, 417_300)))
        val herdr = windows(JSONObject().put("repo", "github.com/example/herdr").put("tasks", tasks.length()).put("hours", 11).put("last", iso(hour)),
            t(n(1_700), n(2_400_000), n(90_000), n(38_000), s(6.90), if (withUnpriced) 417_300 else 0),
            t(n(3_400), n(5_600_000), n(210_000), n(78_000), s(15.40), if (withUnpriced) 417_300 else 0),
            t(n(9_800), n(14_500_000), n(540_000), n(190_000), s(38.20), if (withUnpriced) 417_300 else 0))
        val website = windows(JSONObject().put("repo", "github.com/example/website").put("tasks", 0).put("hours", 4).put("last", iso(hour - 2 * HOUR)),
            t(n(200), n(300_000), n(10_000), n(4_000), s(0.90)), t(n(900), n(1_100_000), n(40_000), n(16_000), s(3.40)),
            t(n(2_600), n(3_000_000), n(120_000), n(41_000), s(9.80)))
        website.put("untasked", website.getJSONObject("d30"))
        // 48 hours of $ for the hourly chart: quiet nights, a working day that peaks in the afternoon.
        val usd = JSONArray()
        val from = hour - 47 * HOUR
        for (i in 0 until 48) {
            val h = Instant.ofEpochMilli(from + i * HOUR).atZone(java.time.ZoneId.systemDefault()).hour
            usd.put(if (h in 8..20) s(0.15 + 0.6 * (1 - Math.abs(h - 14) / 7.0)) else 0.0)
        }
        return JSONObject().put("schema_version", 2).put("kind", "herdr-meter").put("meter_version", "0.0.0-preview")
            .put("generatedAt", iso(generated)).put("machine", machine).put("counted_from", iso(hour - 40 * 24 * HOUR))
            .put("complete", true).put("pending", JSONObject().put("bytes", 0).put("files", 0))
            .put("prices", JSONObject().put("date", "2026-10-06").put("source", "bundled"))
            .put("windows", JSONObject().put("d1", iso(hour - 23 * HOUR)).put("d7", iso(hour - 167 * HOUR)).put("d30", iso(hour - 719 * HOUR)))
            .put("projects", JSONArray().put(herdr).put(website)).put("tasks", tasks)
            .put("hourly", JSONObject().put("from", iso(from)).put("hours", 48).put("series", JSONArray().put(JSONObject().put("harness", "claude-code").put("usd", usd))))
            .put("truncated", JSONArray())
    }

    /** The preview's readings, keyed like [MachineState.meter]. */
    fun readings(now: Long): Map<String, Spend.Reading> = mapOfNotNull(
        "" to Spend.parse("Preview machine", doc("preview-core", now, 4 * 60_000L, 1.0, withUnpriced = true)),
        "build-box" to Spend.parse("build-box", doc("build-core", now, 3 * HOUR, 0.4, withUnpriced = false)))
    private fun mapOfNotNull(vararg pairs: Pair<String, Spend.Reading?>) = pairs.mapNotNull { (k, v) -> v?.let { k to it } }.toMap()
}
