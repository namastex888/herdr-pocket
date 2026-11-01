package ai.namastex.automagik

import com.jcraft.jsch.*
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64

class HostTrustRequired(val profile: HostProfile, val presented: String) : RuntimeException("Verify SSH host key")
data class SshTransport(val input: InputStream, val output: OutputStream, val session: Session, val channel: ChannelExec) : Closeable {
    override fun close() { channel.disconnect(); session.disconnect() }
}

class SshKeys(private val identityRoot: File) {
    private val folder get() = File(identityRoot, "ssh").apply { mkdirs() }
    private val privateKey get() = File(folder, "id_ed25519")
    init {
        JSch.setConfig("keypairgen.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
        JSch.setConfig("keypairgen_fromprivate.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
        JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
        JSch.setConfig("xdh", "com.jcraft.jsch.bc.XDH")
    }
    @Synchronized fun publicKey(): String {
        val public = File(folder, "id_ed25519.pub")
        if (!privateKey.exists() || !public.exists()) {
            val key = KeyPair.genKeyPair(JSch(), KeyPair.ED25519)
            try {
                val temp = File(folder, "id_ed25519.tmp")
                key.writeOpenSSHv1PrivateKey(temp.absolutePath)
                temp.setReadable(false, false); temp.setWritable(false, false)
                temp.setReadable(true, true); temp.setWritable(true, true)
                check(temp.renameTo(privateKey)) { "Could not save SSH identity" }
                key.writePublicKey(public.absolutePath, "herdr-pocket@android")
            } finally { key.dispose() }
        }
        return public.readText().trim()
    }

    fun open(profile: HostProfile, password: String?): SshTransport {
        publicKey()
        val jsch = JSch()
        jsch.addIdentity(privateKey.absolutePath)
        jsch.hostKeyRepository = object : HostKeyRepository {
            override fun check(host: String?, key: ByteArray?): Int {
                check(key != null) { "SSH host presented no key" }
                val fp = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key))
                if (fp != profile.fingerprint) throw HostTrustRequired(profile, fp)
                return HostKeyRepository.OK
            }
            override fun add(hostkey: HostKey?, ui: UserInfo?) { }
            override fun remove(host: String?, type: String?) { }
            override fun remove(host: String?, type: String?, key: ByteArray?) { }
            override fun getKnownHostsRepositoryID() = "Automagik verified hosts"
            override fun getHostKey(): Array<HostKey> = emptyArray()
            override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
        }
        val session = jsch.getSession(profile.user, profile.host, profile.port)
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig("PreferredAuthentications", if (password.isNullOrBlank()) "publickey" else "publickey,password")
        if (!password.isNullOrBlank()) session.setPassword(password)
        session.setServerAliveInterval(15_000); session.serverAliveCountMax = 3
        try {
            session.connect(20_000)
            val channel = session.openChannel("exec") as ChannelExec
            val command = "export HC_SESSION=${shellQuote(profile.session)}; " +
                "if command -v hc >/dev/null 2>&1; then exec hc connect; " +
                "elif command -v herdr-core >/dev/null 2>&1; then exec herdr-core connect; " +
                "elif [ -x \"\$HOME/.local/bin/hc\" ]; then exec \"\$HOME/.local/bin/hc\" connect; " +
                "else echo 'hc not found: install herdr-core on this host' >&2; exit 127; fi"
            channel.setCommand("sh -lc " + shellQuote(command))
            val input = channel.inputStream; val output = channel.outputStream
            // Drain stderr, but never accumulate unbounded command output or credentials.
            channel.setErrStream(object : OutputStream() { override fun write(b: Int) {} })
            channel.connect(15_000)
            return SshTransport(input, output, session, channel)
        } catch (e: Throwable) { session.disconnect(); throw e }
    }
}

/** JSch reports rejected credentials as "Auth fail…" or "Auth cancel…", possibly wrapped. */
fun Throwable.isAuthFailure(): Boolean = generateSequence(this) { it.cause }.take(8)
    .any { it is JSchException && (it.message.orEmpty().startsWith("Auth fail") || it.message.orEmpty().startsWith("Auth cancel")) }

fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"
