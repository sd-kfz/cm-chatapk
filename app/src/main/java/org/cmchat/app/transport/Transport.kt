package org.cmchat.app.transport

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket


object Transport {





    const val MAX_FRAME_BYTES = 64 * 1024

    fun writeFrame(out: OutputStream, sealed: ByteArray) {
        val d = DataOutputStream(out)
        d.writeInt(sealed.size)
        d.write(sealed)
        d.flush()
    }


    fun readFrame(input: InputStream): ByteArray? {
        val d = DataInputStream(input)
        val len = try {
            d.readInt()
        } catch (_: Exception) {
            return null
        }
        if (len <= 0 || len > MAX_FRAME_BYTES) return null
        return try {
            val buf = ByteArray(len)
            d.readFully(buf)
            buf
        } catch (_: Exception) {
            null
        }
    }


    fun connectThroughTor(socksPort: Int, onion: String, port: Int, timeoutMs: Int = 60_000): Socket {
        require(isOnionHost(onion)) { "refusing non-onion destination" }
        val host = if (onion.endsWith(".onion")) onion else "$onion.onion"
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
        val socket = Socket(proxy)
        socket.connect(InetSocketAddress.createUnresolved(host, port), timeoutMs)
        return socket
    }


    fun connectThroughTorRetry(
        socksPort: Int,
        onion: String,
        port: Int,
        totalMs: Long = 90_000L,
        onProgress: (Long) -> Unit = {},
        onStage: (String) -> Unit = {},
    ): Socket {
        val deadline = System.currentTimeMillis() + totalMs
        var wait = 2_000L
        var last: Exception? = null
        var attempt = 0
        while (System.currentTimeMillis() < deadline) {
            attempt++
            onStage("SOCKS connect attempt $attempt")
            try {
                val s = connectThroughTor(socksPort, onion, port, timeoutMs = 30_000)
                onStage("TCP/SOCKS connection established")
                return s
            } catch (e: Exception) {
                last = e
                onProgress(System.currentTimeMillis() - (deadline - totalMs))
                onStage("not reachable yet (${failureReason(e)}) — descriptor may still be uploading; retry in ${wait}ms")
                Thread.sleep(wait)
                wait = (wait * 2).coerceAtMost(15_000L)
            }
        }
        throw last ?: java.io.IOException("onion unreachable after ${totalMs}ms")
    }


    fun failureReason(e: Throwable?): String {
        val m = (e?.message ?: "").lowercase()
        return when {
            e is java.net.SocketTimeoutException || "timed out" in m || "timeout" in m -> "timeout"
            "ttl expired" in m -> "descriptor not found"
            "host unreachable" in m || "unreachable" in m -> "host unreachable"
            "connection refused" in m || "refused" in m -> "refused"
            "socks" in m -> "SOCKS error"
            e is java.io.IOException && m.isNotBlank() -> m.take(40)
            else -> e?.javaClass?.simpleName ?: "unknown"
        }
    }


    fun isOnionHost(host: String): Boolean {
        val h = host.removeSuffix(".onion")
        return h.length == 56 &&
            h.all { it in 'a'..'z' || it in 'A'..'Z' || it in '2'..'7' }
    }


    fun openServer(port: Int): ServerSocket =
        ServerSocket(port, 50, java.net.InetAddress.getByName("127.0.0.1"))
}
