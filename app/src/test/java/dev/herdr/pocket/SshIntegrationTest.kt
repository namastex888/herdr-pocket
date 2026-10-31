package dev.herdr.pocket

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SshIntegrationTest {
    @Test fun verifiesHostKeyAndAuthenticatesWithThePhonesEd25519Identity() = runBlocking {
        val port = System.getenv("HERDR_TEST_SSH_PORT")?.toIntOrNull()
        val allowedKeyFile = System.getenv("HERDR_TEST_ALLOWED_KEY")
        assumeTrue("Set HERDR_TEST_SSH_PORT and HERDR_TEST_ALLOWED_KEY for the local SSH fixture", port != null && allowedKeyFile != null)
        val root = Files.createTempDirectory("pocket-identity-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val keys = SshKeys(root)
            val pub = keys.publicKey()
            assertTrue(pub.startsWith("ssh-ed25519 "))
            assertEquals(pub, keys.publicKey())
            File(allowedKeyFile!!).writeText(pub)
            val profile = HostProfile("fixture", "Test fixture", "127.0.0.1", "pocket", port!!)
            fun challenge(p: HostProfile): HostTrustRequired {
                try { keys.open(p, null).close(); fail("An untrusted host must not authenticate") } catch (e: Throwable) {
                    var cause: Throwable? = e
                    while (cause != null && cause !is HostTrustRequired) cause = cause.cause
                    assertTrue("Expected host fingerprint challenge, got $e", cause is HostTrustRequired)
                    return cause as HostTrustRequired
                }
                error("unreachable")
            }
            val fingerprint = challenge(profile).presented
            assertTrue(fingerprint.startsWith("SHA256:"))
            val transport = keys.open(profile.copy(fingerprint = fingerprint), null)
            val core = CoreClient(transport.input, transport.output, transport, scope) {}
            try {
                core.start()
                assertEquals(1, core.hello.getInt("generation"))
                assertTrue(core.call("terminal.list").getJSONArray("terminals").length() > 0)
            } finally { core.close() }
            assertEquals(fingerprint, challenge(profile.copy(fingerprint = "SHA256:wrong-key")).presented)
        } finally { scope.cancel(); root.deleteRecursively() }
    }
}
