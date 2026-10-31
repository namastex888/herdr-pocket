package dev.herdr.pocket

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class HostProfile(
    val id: String, val name: String, val host: String, val user: String,
    val port: Int = 22, val session: String = "default", val fingerprint: String = ""
) {
    fun json() = JSONObject().put("id", id).put("name", name).put("host", host)
        .put("user", user).put("port", port).put("session", session).put("fingerprint", fingerprint)
    companion object {
        fun parse(j: JSONObject) = HostProfile(j.getString("id"), j.getString("name"), j.getString("host"),
            j.getString("user"), j.optInt("port", 22), j.optString("session", "default"), j.optString("fingerprint"))
    }
}

data class CoreTerminal(
    val address: String, val title: String, val cwd: String, val state: String,
    val status: String, val agent: String, val message: String, val cols: Int, val rows: Int,
    val labels: Map<String, String> = emptyMap()
) {
    val live get() = state == "live"
    val attention get() = status == "blocked" || status == "error"
    companion object {
        fun parse(j: JSONObject): CoreTerminal {
            val status = j.optJSONObject("status")
            val foreground = j.optJSONObject("foreground")
            val argv = foreground?.optJSONArray("argv") ?: j.optJSONArray("argv")
            val agent = status?.clean("app").orEmpty().ifBlank { foreground?.clean("name").orEmpty() }
                .ifBlank { argv?.optString(0).orEmpty().substringAfterLast('/') }.ifBlank { "Terminal" }
            return CoreTerminal(j.getString("address"), j.clean("name").ifBlank { j.clean("title") }.ifBlank { agent },
                foreground?.clean("cwd").orEmpty().ifBlank { j.clean("cwd") }, j.clean("state"),
                status?.clean("state").orEmpty(), agent, status?.clean("msg").orEmpty(),
                j.optInt("cols", 80), j.optInt("rows", 24), j.optJSONObject("labels")?.let { o -> o.keys().asSequence().associateWith { o.getString(it) } } ?: emptyMap())
        }
    }
}

fun JSONObject.clean(key: String): String = if (isNull(key)) "" else optString(key, "")
fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

data class PocketTab(val id: String, val name: String, val terminals: List<String>)
data class PocketSpace(val id: String, val name: String, val folder: String, val machine: String, val tabs: List<PocketTab>) {
    val addresses get() = tabs.flatMap { it.terminals }
}

object LayoutReader {
    fun spaces(document: JSONObject?, machine: String = ""): List<PocketSpace> {
        fun qualify(s: String) = if (machine.isBlank()) s else "$machine/$s"
        return document?.optJSONArray("spaces")?.objects()?.map { s ->
            val tabs = s.optJSONArray("tabs")?.objects()?.map { t ->
                val addresses = mutableListOf<String>()
                fun walk(n: JSONObject) {
                    n.optJSONObject("content")?.clean("terminal")?.takeIf { it.isNotBlank() }?.let { addresses.add(qualify(it)) }
                    n.optJSONArray("children")?.objects()?.forEach(::walk)
                    n.optJSONArray("floating")?.objects()?.forEach { f ->
                        f.optJSONObject("content")?.clean("terminal")?.takeIf { it.isNotBlank() }?.let { addresses.add(qualify(it)) }
                    }
                }
                t.optJSONObject("root")?.let(::walk)
                PocketTab(qualify(t.getString("id")), t.clean("name"), addresses.distinct())
            }.orEmpty()
            PocketSpace(qualify(s.getString("id")), s.clean("name").ifBlank { s.clean("folder").trimEnd('/').substringAfterLast('/').ifBlank { "Untitled space" } },
                s.clean("folder"), machine, tabs)
        }.orEmpty()
    }
}

data class Frame(val channel: Int, val kind: Int, val payload: ByteArray)
object Wire {
    const val MAX_PAYLOAD = 16 * 1024 * 1024
    const val DATA = 0
    const val CREDIT = 1
    const val CLOSE = 2
    const val RESIZE = 3
    const val FOCUS = 4
    fun encode(channel: Int, kind: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        require(payload.size <= MAX_PAYLOAD)
        return ByteBuffer.allocate(9 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(channel).put(kind.toByte()).putInt(payload.size).put(payload).array()
    }
    fun credit(size: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(size).array()
    fun resize(cols: Int, rows: Int): ByteArray {
        require(cols in 1..65535 && rows in 1..65535)
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putShort(cols.toShort()).putShort(rows.toShort()).array()
    }
    fun header(bytes: ByteArray): Triple<Int, Int, Int> {
        require(bytes.size == 9)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val channel = b.int; val kind = b.get().toInt() and 255; val length = b.int
        require(length in 0..MAX_PAYLOAD) { "Invalid core frame size" }
        return Triple(channel, kind, length)
    }
}
