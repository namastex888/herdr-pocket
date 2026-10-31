package dev.herdr.pocket

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SpaceNameTest {
    private fun space(id: String, name: String, folder: String, terminal: String) =
        """{"id":"$id","name":"$name","folder":"$folder","tabs":[{"id":"tb$id","name":"","root":{"content":{"terminal":"$terminal"}}}]}"""
    private fun terminal(address: String, cwd: String, live: String) =
        CoreTerminal.parse(JSONObject("""{"address":"$address","cwd":"$cwd","foreground":{"cwd":"$live"}}"""))

    @Test fun unnamedSpacesTakeTheirFirstTerminalsLiveFolderLikeTheDesktop() {
        val doc = JSONObject("""{"spaces":[${space("a", "", "/Users/me/workspace", "t1m")},${space("b", "", "/Users/me/workspace/repos/brain-v3", "t16r")},
            ${space("c", "T1 · components", "/Users/me/x", "t20")},${space("d", "", "/Users/me", "gone")},${space("e", "", "/home/me", "t9")}]}""")
        val terminals = listOf(terminal("t1m", "/Users/me/workspace", "/Users/me/workspace/repos/brain-v3"),
            terminal("t16r", "/Users/me/workspace/repos/brain-v3", "/Users/me/workspace/repos/herdr-pocket"),
            terminal("t20", "/Users/me/y", "/Users/me/y"), terminal("t9", "/home/me", "/home/me/"))
        assertEquals(listOf("brain-v3", "herdr-pocket", "T1 · components", "~", "me"), LayoutReader.spaces(doc, terminals = terminals, home = "/Users/me").map { it.name })
    }
    @Test fun forwardedSpacesMatchTheirQualifiedTerminals() {
        val doc = JSONObject("""{"spaces":[${space("a", "", "/home/me", "t1")}]}""")
        assertEquals("droid", LayoutReader.spaces(doc, "hx470", listOf(terminal("hx470/t1", "/home/me", "/home/me/repos/droid"))).single().name)
        // Only the core's own home is "~": the desktop names another machine's home after its folder.
        assertEquals("me", LayoutReader.spaces(doc, "hx470", listOf(terminal("hx470/t1", "/home/me", "/home/me")), home = "/home/me").single().name)
    }
}
