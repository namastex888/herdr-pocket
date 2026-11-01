package ai.namastex.automagik

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class LinkedMachineTest {
    @Test fun listsEveryMachineAndCountsItsTerminalsByAddressPrefix() {
        val response = JSONObject("""{"machines":[{"name":"g9","state":"connected"},{"name":"g10","state":"disconnected"},{"name":""}],
            "terminals":[{"address":"t1"},{"address":"g9/t1"},{"address":"g9/t2"},{"address":"g10/t8"}]}""")
        val terminals = response.getJSONArray("terminals").objects().map(CoreTerminal::parse)
        assertEquals(listOf(LinkedMachine("g9", "connected", 2), LinkedMachine("g10", "disconnected", 1)), LinkedMachine.list(response, terminals))
    }
    @Test fun hostProfileKeepsLinkedMachinesAndReadsOlderSavedHosts() {
        val host = HostProfile("id", "Mac", "10.0.0.2", "me", linked = listOf("g9", "hx470"))
        assertEquals(host, HostProfile.parse(host.json()))
        val old = JSONObject("""{"id":"id","name":"Mac","host":"10.0.0.2","user":"me"}""")
        assertEquals(emptyList<String>(), HostProfile.parse(old).linked)
    }
}
