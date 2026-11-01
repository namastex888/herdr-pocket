package ai.namastex.automagik

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.Closeable
import java.nio.file.Files
import java.util.UUID

class CoreIntegrationTest {
    private fun withCore(test: suspend (CoreClient) -> Unit) = runBlocking {
        val executable = System.getenv("HERDR_TEST_HC")
        assumeTrue("Set HERDR_TEST_HC to run against a real core", !executable.isNullOrBlank())
        val root = Files.createTempDirectory("pocket-core-").toFile()
        val session = "pocket-" + UUID.randomUUID().toString().take(8)
        fun builder(vararg args: String) = ProcessBuilder(listOf(executable!!) + args).apply {
            environment()["HC_SESSION"] = session
            environment()["XDG_STATE_HOME"] = root.resolve("state").path
            environment()["XDG_CONFIG_HOME"] = root.resolve("config").path
            environment()["XDG_RUNTIME_DIR"] = root.resolve("run").apply { mkdirs() }.path
            environment().remove("HC_SOCKET")
        }
        val process = builder("connect").start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = CoreClient(process.inputStream, process.outputStream, Closeable { process.destroy() }, scope) { }
        try { withTimeout(25_000) { client.start(); test(client) } }
        finally {
            client.close(); scope.cancel()
            val stop = builder("server", "stop").redirectErrorStream(true).start()
            stop.inputStream.readBytes(); stop.waitFor()
            root.deleteRecursively()
        }
    }

    @Test fun realCoreStreamsInputResizesAndDetachPreservesTheProgram() = withCore { c ->
        assertEquals(1, c.hello.getInt("generation"))
        val changes = Channel<Unit>(Channel.CONFLATED)
        c.subscribe { changes.trySend(Unit) }
        val run = c.call("terminal.run", JSONObject().put("argv", JSONArray(listOf("sh", "-c", "printf 'POCKET_READY\\n'; exec cat"))))
        val terminal = run
        val address = terminal.getString("address")
        val list = c.call("terminal.list").getJSONArray("terminals").objects()
        assertTrue(list.any { it.getString("address") == address })
        val output = Channel<ByteArray>(Channel.UNLIMITED)
        val channel = c.attach(address, 62, 23, { ch, bytes -> output.trySend(bytes); c.send(ch, Wire.CREDIT, Wire.credit(bytes.size)) }, {})
        suspend fun untilContains(needle: String) {
            val bytes = java.io.ByteArrayOutputStream()
            withTimeout(10_000) { while (!bytes.toString("UTF-8").contains(needle)) bytes.write(output.receive()) }
        }
        untilContains("POCKET_READY")
        c.send(channel, Wire.DATA, "pocket-écho-你好\n".toByteArray())
        untilContains("pocket-écho-你好")
        c.send(channel, Wire.RESIZE, Wire.resize(73, 28))
        withTimeout(5_000) {
            while (c.call("terminal.get", JSONObject().put("terminal", address)).getInt("cols") != 73) delay(50)
        }
        c.detach(channel)
        assertTrue(c.call("terminal.list").getJSONArray("terminals").objects().any { it.getString("address") == address && it.getString("state") == "live" })
        c.call("terminal.kill", JSONObject().put("terminal", address))
    }

    @Test fun documentReadsMatchDesktopStructureAndConflictingWritesAreRejected() = withCore { c ->
        val value = JSONObject("""{"spaces":[{"id":"sp1","name":"Mobile","folder":"/tmp","tabs":[{"id":"tb1","root":{"type":"block","content":{"terminal":"t7"}}}]}]}""")
        c.call("doc.set", JSONObject().put("name", "herdr-app").put("value", value).put("version", 0))
        val reply = c.call("doc.get", JSONObject().put("name", "herdr-app"))
        val space = LayoutReader.spaces(reply.getJSONObject("value")).single()
        assertEquals("Mobile", space.name); assertEquals(listOf("t7"), space.addresses)
        try {
            c.call("doc.set", JSONObject().put("name", "herdr-app").put("value", value).put("version", 0))
            fail("Stale document writes must fail")
        } catch (e: IllegalStateException) { assertTrue(e.message.orEmpty().contains("version") || e.message.orEmpty().contains("conflict")) }
    }
}
