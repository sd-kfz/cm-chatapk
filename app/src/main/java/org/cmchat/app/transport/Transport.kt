package org.cmchat.app.transport

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket

/**
 * Length-prefixed frame I/O and Tor-only socket helpers.
 *
 * Outgoing connections go through Tor's SOCKS5 proxy to <onion>:port, with the
 * hostname left UNRESOLVED so Tor performs the .onion lookup (never the local
 * resolver). Incoming connections are accepted on a local ServerSocket that
 * sits behind the Face's onion service. The app opens no direct socket.
 *
 * Wire format per frame: 4-byte big-endian length, then that many bytes of
 * crypto_box-sealed data (see FrameCodec).
 */
object Transport {

    // Bounded BEFORE allocation (input hardening): a text frame is a ~10,000-char
    // body + small JSON/crypto overhead, so 64 KiB is ample. A sender claiming a
    // larger length is rejected without allocating the buffer. (File transfer,
    // when added, will negotiate its own chunked path, not a giant frame.)
    const val MAX_FRAME_BYTES = 64 * 1024

    fun writeFrame(out: OutputStream, sealed: ByteArray) {
        val d = DataOutputStream(out)
        d.writeInt(sealed.size)
        d.write(sealed)
        d.flush()
    }

    /** Reads one length-prefixed frame, or null on clean EOF / oversized frame. */
    fun readFrame(input: InputStream): ByteArray? {
        val d = DataInputStream(input)
        val len = try {
            d.readInt()
        } catch (_: Exception) {
            return null
        }
        if (len <= 0 || len > MAX_FRAME_BYTES) return null
        val buf = ByteArray(len)
        d.readFully(buf)
        return buf
    }

    /**
     * SOCKS5 through Tor to <onion>:port; hostname stays unresolved for Tor.
     * Onion-only guard: refuses any host that is not a valid v3 onion (fail
     * closed — the app never touches clearnet). [onion] may be given with or
     * without the ".onion" suffix.
     */
    fun connectThroughTor(socksPort: Int, onion: String, port: Int, timeoutMs: Int = 60_000): Socket {
        require(isOnionHost(onion)) { "refusing non-onion destination" }
        val host = if (onion.endsWith(".onion")) onion else "$onion.onion"
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
        val socket = Socket(proxy)
        socket.connect(InetSocketAddress.createUnresolved(host, port), timeoutMs)
        return socket
    }

    /**
     * A freshly published v3 onion descriptor takes ~30-90s to upload before
     * anyone (even self) can reach it, surfacing as "SOCKS: Host unreachable".
     * Retry with backoff up to [totalMs] instead of hard-failing. [onProgress]
     * reports elapsed ms so the UI can show "connecting…". Throws the last error
     * if it never connects.
     */
    fun connectThroughTorRetry(
        socksPort: Int,
        onion: String,
        port: Int,
        totalMs: Long = 90_000L,
        onProgress: (Long) -> Unit = {},
    ): Socket {
        val deadline = System.currentTimeMillis() + totalMs
        var wait = 2_000L
        var last: Exception? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                return connectThroughTor(socksPort, onion, port, timeoutMs = 30_000)
            } catch (e: Exception) {
                last = e
                onProgress(System.currentTimeMillis() - (deadline - totalMs))
                Thread.sleep(wait)
                wait = (wait * 2).coerceAtMost(15_000L)
            }
        }
        throw last ?: java.io.IOException("onion unreachable after ${totalMs}ms")
    }

    /** v3 onion host: 56 base32 chars, with or without the ".onion" suffix. */
    fun isOnionHost(host: String): Boolean {
        val h = host.removeSuffix(".onion")
        return h.length == 56 &&
            h.all { it in 'a'..'z' || it in 'A'..'Z' || it in '2'..'7' }
    }

    /** Local server behind the onion service; bound to loopback only. */
    fun openServer(port: Int): ServerSocket =
        ServerSocket(port, 50, java.net.InetAddress.getByName("127.0.0.1"))
}
