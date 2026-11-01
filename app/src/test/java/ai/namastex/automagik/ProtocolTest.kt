package ai.namastex.automagik

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ProtocolTest {
    @Test fun frameHasLittleEndianHeaderAndBinaryPayload() {
        val payload = byteArrayOf(0, -1, 10, 127)
        val encoded = Wire.encode(0x12345678, Wire.DATA, payload)
        assertArrayEquals(byteArrayOf(0x78, 0x56, 0x34, 0x12, 0, 4, 0, 0, 0), encoded.copyOfRange(0, 9))
        assertArrayEquals(payload, encoded.copyOfRange(9, encoded.size))
        assertEquals(Triple(0x12345678, 0, 4), Wire.header(encoded.copyOfRange(0, 9)))
    }
    @Test fun rejectsOversizedFramesBeforeAllocatingPayload() {
        val header = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN).putInt(1).put(0).putInt(Wire.MAX_PAYLOAD + 1).array()
        assertThrows(IllegalArgumentException::class.java) { Wire.header(header) }
    }
    @Test fun rejectsNegativeAndTruncatedLengths() {
        val header = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN).putInt(1).put(0).putInt(-1).array()
        assertThrows(IllegalArgumentException::class.java) { Wire.header(header) }
        assertThrows(IllegalArgumentException::class.java) { Wire.header(byteArrayOf()) }
    }
    @Test fun resizeUsesUnsignedLittleEndian16BitDimensions() {
        assertArrayEquals(byteArrayOf(0x2c, 1, 0xc8.toByte(), 0), Wire.resize(300, 200))
        assertThrows(IllegalArgumentException::class.java) { Wire.resize(0, 24) }
    }
    @Test fun creditsCountBytesNotCharacters() {
        assertArrayEquals(byteArrayOf(6, 0, 0, 0), Wire.credit("你好".toByteArray().size))
    }
    @Test fun readsDesktopSpacesTabsNestedPanesAndFloatingTerminals() {
        val document = JSONObject("""{"spaces":[{"id":"sp1","name":"","folder":"/srv/my-project/","tabs":[{"id":"tb1","name":"Agent","root":{"type":"row","children":[{"type":"block","content":{"terminal":"t1"}},{"type":"column","children":[{"type":"block","content":{"terminal":"t2"}},{"type":"block","content":{"mod":"files"}}]}],"floating":[{"content":{"terminal":"t3"}}]}}]}]}""")
        val space = LayoutReader.spaces(document, "vps").single()
        assertEquals("vps/sp1", space.id)
        assertEquals("my-project", space.name)
        assertEquals("vps/tb1", space.tabs.single().id)
        assertEquals(listOf("vps/t1", "vps/t2", "vps/t3"), space.addresses)
    }
    @Test fun absentDocumentIsAnEmptyList() { assertTrue(LayoutReader.spaces(null).isEmpty()) }
    @Test fun localAddressesStayUnqualifiedAndDuplicatesAreRemoved() {
        val j = JSONObject("""{"spaces":[{"id":"s","tabs":[{"id":"b","root":{"children":[{"content":{"terminal":"t1"}},{"content":{"terminal":"t1"}}]}}]}]}""")
        assertEquals(listOf("t1"), LayoutReader.spaces(j).single().addresses)
    }
    @Test fun terminalStatusUsesProgramReportsAndNullsDoNotBecomeLabels() {
        val j = JSONObject("""{"address":"t5","name":null,"title":"Fix login","argv":["/bin/zsh"],"state":"live","cwd":"/tmp","status":{"state":"blocked","app":"Claude Code","msg":"Allow command?"},"foreground":null} """)
        val t = CoreTerminal.parse(j)
        assertEquals("Fix login", t.title); assertEquals("Claude Code", t.agent); assertTrue(t.attention); assertTrue(t.live)
    }
    @Test fun shellWithoutProgramStatusStaysUnknown() {
        val t = CoreTerminal.parse(JSONObject("""{"address":"t1","argv":["/bin/bash"],"state":"live","status":null} """))
        assertEquals("bash", t.agent); assertEquals("", t.status); assertFalse(t.attention)
    }
    @Test fun shellQuotingDoesNotExecuteSessionNames() {
        assertEquals("'a'\\''; touch /tmp/nope; '", shellQuote("a'; touch /tmp/nope; "))
        assertEquals("'default'", shellQuote("default"))
    }
    @Test fun savedProfilesPreserveVerifiedFingerprintAndSession() {
        val p = HostProfile("a", "Dev", "dev.tailnet.ts.net", "me", 2222, "work", "SHA256:abc")
        assertEquals(p, HostProfile.parse(p.json()))
        assertFalse(p.json().has("password"))
    }
}
