package dev.herdr.pocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.json.JSONObject
import org.junit.Test

class PairingLinkTest {
    private val ticket = "endpoint" + "abcdefghijklmnopqrstuvwxyz234567".repeat(4)
    private val code = "Zx9_-aB3cD4eF5gH6iJ7kQ"

    @Test fun readsTheDesktopsLink() {
        assertEquals(PairingLink("MacBook Pro", code, ticket), PairingLink.parse("herdr-pocket://pair?v=1&n=MacBook%20Pro&c=$code&t=$ticket"))
        assertEquals("Herdr desktop", PairingLink.parse(" herdr-pocket://pair?v=1&c=$code&t=$ticket\n")?.name)
    }
    @Test fun refusesAnythingElse() {
        for (bad in listOf("https://pair?v=1&c=$code&t=$ticket", "herdr-pocket://other?v=1&c=$code&t=$ticket",
            "herdr-pocket://pair?v=2&c=$code&t=$ticket", "herdr-pocket://pair?v=1&c=short&t=$ticket",
            "herdr-pocket://pair?v=1&c=$code&t=notaticket", "herdr-pocket://pair?v=1&c=$code", "herdr-pocket://pair?v=1&t=$ticket", "", "garbage"))
            assertNull(bad, PairingLink.parse(bad))
    }
    @Test fun keepsNamesShortAndPrintable() {
        assertEquals("ab", PairingLink.parse("herdr-pocket://pair?v=1&n=a%0Ab&c=$code&t=$ticket")?.name)
        assertEquals(60, PairingLink.parse("herdr-pocket://pair?v=1&n=${"x".repeat(200)}&c=$code&t=$ticket")?.name?.length)
    }
    @Test fun pairedHostsRoundTripAndOldOnesAreSsh() {
        val host = HostProfile("id", "Mac", "0123456789ab", "", 0, transport = "iroh", ticket = ticket)
        assertEquals(host, HostProfile.parse(host.json()))
        assertEquals("ssh", HostProfile.parse(JSONObject("""{"id":"a","name":"b","host":"c","user":"d"}""")).transport)
    }

    @Test fun aPairedDesktopsTerminalSaysPocketLinkNotSsh() {
        assertEquals("Pocket Link encrypted", HostProfile("id", "Mac", "0123456789ab", "", 0, transport = "iroh", ticket = ticket).encryption)
        assertEquals("SSH encrypted", HostProfile("id", "Mac", "mac.local", "me").encryption)
    }
}
