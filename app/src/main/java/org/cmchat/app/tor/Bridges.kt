package org.cmchat.app.tor

import IPtProxy.Controller
import IPtProxy.OnTransportEvents
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.cmchat.app.diag.Diag
import java.io.File
import org.torproject.jni.TorService as GpTorService

/**
 * Pluggable-transport bridges — hide THAT Tor is being used from a network
 * observer. This does NOT add any message secrecy (messages are already
 * end-to-end encrypted); it only makes the Tor traffic look like something
 * else so an ISP can't fingerprint it.
 *
 * Mechanism (fail-closed): we run the obfs4/snowflake client in-process via
 * IPtProxy, then write `UseBridges 1` + `ClientTransportPlugin <t> socks5
 * 127.0.0.1:<port>` + `Bridge` lines into tor-android's user torrc
 * (`TorService.getTorrc`) BEFORE Tor is launched. Because the config is in the
 * torrc at startup, Tor makes its very first connection THROUGH the bridge —
 * there is never a direct-Tor connection to leak. If the transport can't start
 * we return false and the caller refuses to bind Tor at all (no fallback to
 * direct Tor or clearnet).
 */
object Bridges {

    enum class Mode(val wire: String) {
        OFF("off"), OBFS4("obfs4"), SNOWFLAKE("snowflake");

        companion object {
            fun from(s: String?): Mode = entries.firstOrNull { it.wire == s } ?: OFF
        }
    }

    /** Current mode + user bridge lines (loaded from the vault at unlock). */
    val mode = MutableStateFlow(Mode.OFF)
    val customLines = MutableStateFlow("")

    fun isEnabled(): Boolean = mode.value != Mode.OFF

    fun configure(modeWire: String?, lines: String?) {
        mode.value = Mode.from(modeWire)
        customLines.value = lines ?: ""
    }

    // Built-in obfs4 fallback bridges (public Tor Browser built-ins; these can
    // rotate/expire — the UI tells the user their own bridges are more reliable).
    private val DEFAULT_OBFS4 = listOf(
        "obfs4 193.11.166.194:27015 2D82C2E354D531A68469ADF7F878FA6060C6BACA cert=4TLQPJrTSaDffMK7Nbao6LC7G9OW/NHkUwIdjLSS3KYf0Nv4/nQiiI8dY2TcsQx01NniOg iat-mode=0",
        "obfs4 193.11.166.194:27020 86AC7B8D430DAC4117E9F42C9EAED18133863AAF cert=0LDeJH4JzMDtkJJrFphJCiPqKx7loozKN7VNfuukMGfHO0Z8OGdzHVkhVAOfo1mUdv9cMg iat-mode=0",
        "obfs4 85.31.186.98:443 011F2599C0E9B27EE74B353155E244813763C3E5 cert=ayq0XzCwhpdysn5o0EyDUbmSOx3X/oTEbzDMvczHOdBJKlvIdHHLJGkZARtT4dcBFArPPg iat-mode=0",
        "obfs4 85.31.186.26:443 91A6354697E6B02A386312F68D82CF86824D3606 cert=PBwr+S8JTVZo6MPdHnkTwXJPILWADLqfMGoVvhZClMq/Urndyd42BwX9YFJHZnBB3H0XCw iat-mode=0",
    )
    // Standard snowflake rendezvous bridge fingerprint; the real rendezvous is
    // driven by the IPtProxy snowflake defaults below.
    private const val SNOWFLAKE_FP = "2B280B23E1107BB62ABFC40DDCC8824814F80A72"

    private const val BEGIN = "# >>> cmchat-bridges (managed)"
    private const val END = "# <<< cmchat-bridges"

    @Volatile private var controller: Controller? = null
    @Volatile private var startedTransport: String? = null

    /**
     * Configure bridges and write the torrc. MUST be called before Tor binds.
     * Returns true to proceed (OFF, or transport started OK); false if an
     * ENABLED transport failed — the caller then fails closed (no bind).
     */
    @Synchronized
    fun prepare(context: Context): Boolean {
        val m = mode.value
        if (m == Mode.OFF) {
            stop()
            writeTorrc(context, null)   // strip any previous managed block
            return true
        }
        val transport = m.wire
        val port: Long = try {
            val c = ensureController(context)
            if (m == Mode.SNOWFLAKE) applySnowflakeDefaults(c)
            if (startedTransport != null && startedTransport != transport) {
                runCatching { c.stop(startedTransport) }
                startedTransport = null
            }
            if (startedTransport != transport) {
                c.start(transport, "")   // no upstream proxy
                startedTransport = transport
            }
            c.port(transport)
        } catch (t: Throwable) {
            Diag.e("bridges", "failed to start $transport", t)
            return false
        }
        if (port <= 0L) { Diag.w("bridges", "no local SOCKS port for $transport"); return false }
        val lines = bridgeLinesFor(m)
        if (lines.isEmpty()) { Diag.w("bridges", "no bridge lines for $transport"); return false }
        val block = buildString {
            appendLine(BEGIN)
            appendLine("UseBridges 1")
            appendLine("ClientTransportPlugin $transport socks5 127.0.0.1:$port")
            lines.forEach { appendLine("Bridge $it") }
            append(END)
        }
        // FAIL CLOSED: if we can't write the bridge config, do NOT let Tor bind
        // without it (that would connect directly and leak around the cloak).
        if (!writeTorrc(context, block)) {
            Diag.e("bridges", "could not write bridge torrc; failing closed")
            return false
        }
        Diag.i("bridges", "configured $transport via loopback:$port (${lines.size} bridge lines)")
        return true
    }

    /** Stop the running transport (safe to call repeatedly). */
    @Synchronized
    fun stop() {
        val c = controller
        val t = startedTransport
        if (c != null && t != null) runCatching { c.stop(t) }
        startedTransport = null
    }

    private fun ensureController(context: Context): Controller {
        controller?.let { return it }
        val stateDir = File(context.cacheDir, "pt").apply { mkdirs() }.absolutePath
        // enableLogging=false, unsafeLogging=false: no PT logs written to disk.
        val c = Controller(stateDir, false, false, "ERROR", object : OnTransportEvents {
            override fun connected(name: String?) { Diag.i("bridges", "transport up: ${name ?: "?"}") }
            override fun error(name: String?, e: Exception?) { Diag.w("bridges", "transport error: ${name ?: "?"}") }
            override fun stopped(name: String?, e: Exception?) { Diag.i("bridges", "transport stopped: ${name ?: "?"}") }
        })
        controller = c
        return c
    }

    private fun applySnowflakeDefaults(c: Controller) {
        runCatching {
            c.snowflakeBrokerUrl = "https://1098762253.rsc.cdn77.org/"
            c.snowflakeFrontDomains = "www.cdn77.com,www.phpmyadmin.net"
            c.snowflakeIceServers =
                "stun:stun.l.google.com:19302,stun:stun.antisip.com:3478,stun:stun.dus.net:3478"
        }
    }

    /** Custom lines win; otherwise the built-in fallback for the mode. */
    private fun bridgeLinesFor(m: Mode): List<String> {
        val custom = customLines.value.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { it.removePrefix("Bridge ").trim() }   // accept with or without "Bridge " prefix
        return when (m) {
            Mode.OBFS4 -> custom.filter { it.startsWith("obfs4") }.ifEmpty { DEFAULT_OBFS4 }
            Mode.SNOWFLAKE -> custom.filter { it.startsWith("snowflake") }
                .ifEmpty { listOf("snowflake 192.0.2.3:80 $SNOWFLAKE_FP") }
            Mode.OFF -> emptyList()
        }
    }

    /**
     * Insert/replace/remove our managed block in tor-android's user torrc.
     * Returns true on success. A false return while bridges are enabled makes
     * the caller fail closed (Tor is never bound without the bridge config).
     */
    private fun writeTorrc(context: Context, block: String?): Boolean =
        runCatching {
            val f = GpTorService.getTorrc(context)
            f.parentFile?.mkdirs()
            val existing = if (f.exists()) f.readText() else ""
            val stripped = stripBlock(existing)
            val out = when {
                block == null -> stripped
                stripped.isBlank() -> block + "\n"
                else -> stripped.trimEnd() + "\n" + block + "\n"
            }
            f.writeText(out)
            true
        }.getOrElse { Diag.e("bridges", "torrc write failed", it); false }

    private fun stripBlock(s: String): String {
        val b = s.indexOf(BEGIN)
        if (b < 0) return s
        val e = s.indexOf(END, b)
        return if (e < 0) s.substring(0, b).trim()
        else (s.substring(0, b) + s.substring(e + END.length)).trim()
    }
}
