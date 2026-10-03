package org.cmchat.app.tor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cmchat.app.transport.Transport
import java.net.ServerSocket

/** Onion service (my "server") state for the active Face. */
sealed interface ServerStatus {
    data object Off : ServerStatus
    data object Starting : ServerStatus
    data class Online(val onion: String, val faceName: String, val sinceMs: Long) : ServerStatus
    data class Failed(val reason: String) : ServerStatus
}

/** Result of publishing an onion, so the caller can persist a newly-made key. */
data class OnionPublish(val onion: String, val newPrivateKey: String?)

/**
 * Publishes a v3 onion service (virtual port 80 -> a random loopback
 * ServerSocket) for the active Face via the Tor control port, and tracks its
 * state. If the Face has no onion key yet, Tor generates one (ADD_ONION
 * NEW:ED25519-V3) and it is returned so the caller can store it in the vault.
 *
 * Runtime behaviour (actual onion publishing) requires Tor ONLINE on a device;
 * this is compile-verified only in CI.
 */
object ServerController {

    private val _status = MutableStateFlow<ServerStatus>(ServerStatus.Off)
    val status: StateFlow<ServerStatus> = _status.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    /** onion address without the ".onion" suffix, for DEL_ONION on stop. */
    private var currentServiceId: String? = null

    /** Set by MessageService: handles each accepted incoming connection. */
    @Volatile
    var onIncoming: ((java.net.Socket) -> Unit)? = null

    /**
     * @param existingOnionKey the Face's stored "ED25519-V3:..." key, or null
     * @param onPublished called with the onion + any freshly-generated key to persist
     */
    fun start(
        faceName: String,
        existingOnionKey: String?,
        existingOnionAddress: String? = null,
        onPublished: (OnionPublish) -> Unit,
    ) {
        // Publish ONCE per session. If a service is already registered (Online)
        // or a publish is in flight (Starting), reuse it — never ADD_ONION the
        // same service twice (that collides on the address). restart() clears
        // the state first, so it still re-publishes.
        synchronized(this) {
            if (_status.value is ServerStatus.Online || _status.value is ServerStatus.Starting) return
            _status.value = ServerStatus.Starting
        }
        scope.launch {
            val control = TorService.controlConnection()
            if (control == null) {
                _status.value = ServerStatus.Failed("Tor not connected")
                return@launch
            }
            val result = runCatching {
                val server = Transport.openServer(0)
                serverSocket = server
                val ports = mapOf(80 to "127.0.0.1:${server.localPort}")
                val keyArg = existingOnionKey ?: "NEW:ED25519-V3"
                org.cmchat.app.diag.Diag.i(
                    "onion",
                    "ADD_ONION ${if (existingOnionKey != null) "ED25519-V3:<stored>" else "NEW:ED25519-V3"} " +
                        "Port=80,127.0.0.1:${server.localPort}",
                )
                val reply = control.addOnion(keyArg, ports)
                // jtorctl parses the ADD_ONION reply into keys "onionAddress"
                // (the base32 host, no scheme, no ".onion") and "onionPrivKey"
                // ("ED25519-V3:..."). Log presence only — the priv key is this
                // device's own onion key (already in the vault), never a blob.
                org.cmchat.app.diag.Diag.i("onion", "ADD_ONION reply keys=${reply.keys}")
                fun v(name: String) = reply.entries.firstOrNull { it.key.equals(name, true) }?.value
                // New key first; fall back to the stored address on recreate
                // (a recreate reply may omit the address).
                val addr = v("onionAddress")
                    ?: existingOnionAddress?.removeSuffix(".onion")
                    ?: throw IllegalStateException(
                        "ADD_ONION returned no onionAddress; reply=${reply.entries.joinToString { "${it.key}=${redact(it.key, it.value)}" }}"
                    )
                val priv = v("onionPrivKey") ?: existingOnionKey
                currentServiceId = addr
                acceptLoop(server)
                OnionPublish("$addr.onion", priv)
            }
            result.onSuccess { pub ->
                onPublished(pub)
                org.cmchat.app.diag.Diag.i("onion", "published ${pub.onion}")
                _status.value = ServerStatus.Online(pub.onion, faceName, System.currentTimeMillis())
            }.onFailure { e ->
                org.cmchat.app.diag.Diag.e("onion", "publish failed", e)
                _status.value = ServerStatus.Failed(e.message ?: "publish failed")
            }
        }
    }

    /**
     * Rotate to a NEW onion address while keeping the OLD one registered for a
     * ~24h overlap so no contact drops mid-switch. Both map to the same local
     * server. The caller persists the new key/address and sends the signed
     * address-update to contacts. Manual (triggered from My Server).
     */
    fun requestNewAddress(onNew: (OnionPublish) -> Unit) {
        scope.launch {
            val control = TorService.controlConnection() ?: run {
                _status.value = ServerStatus.Failed("Tor not connected"); return@launch
            }
            val server = serverSocket ?: run {
                _status.value = ServerStatus.Failed("server not running"); return@launch
            }
            val faceName = (status.value as? ServerStatus.Online)?.faceName ?: ""
            val oldId = currentServiceId
            runCatching {
                val ports = mapOf(80 to "127.0.0.1:${server.localPort}")
                val reply = control.addOnion("NEW:ED25519-V3", ports)
                fun v(name: String) = reply.entries.firstOrNull { it.key.equals(name, true) }?.value
                val addr = v("onionAddress") ?: throw IllegalStateException("no onionAddress")
                val priv = v("onionPrivKey")
                currentServiceId = addr
                OnionPublish("$addr.onion", priv)
            }.onSuccess { pub ->
                _status.value = ServerStatus.Online(pub.onion, faceName, System.currentTimeMillis())
                onNew(pub)
                org.cmchat.app.diag.Diag.i("onion", "rotated to new address")
                // Keep the old address alive ~24h, then remove it.
                if (oldId != null) scope.launch {
                    kotlinx.coroutines.delay(24 * 60 * 60_000L)
                    runCatching { TorService.controlConnection()?.delOnion(oldId) }
                }
            }.onFailure {
                org.cmchat.app.diag.Diag.e("onion", "address rotation failed", it)
            }
        }
    }

    fun stop() {
        // Flip state synchronously so a following start()/restart() re-publishes.
        _status.value = ServerStatus.Off
        val id = currentServiceId
        currentServiceId = null
        val sock = serverSocket
        serverSocket = null
        scope.launch {
            runCatching { id?.let { TorService.controlConnection()?.delOnion(it) } }
            runCatching { sock?.close() }
        }
    }

    fun restart(
        faceName: String,
        existingOnionKey: String?,
        existingOnionAddress: String? = null,
        onPublished: (OnionPublish) -> Unit,
    ) {
        stop()
        start(faceName, existingOnionKey, existingOnionAddress, onPublished)
    }

    /**
     * Connect to my own onion through Tor; report OK/FAIL and elapsed ms. A
     * freshly published descriptor needs ~30-90s to upload, so this retries with
     * backoff rather than hard-failing, and reports progress via [onProgress].
     */
    suspend fun selfTest(onProgress: (Long) -> Unit = {}): Pair<Boolean, Long> = withContext(Dispatchers.IO) {
        val onion = (status.value as? ServerStatus.Online)?.onion
            ?: return@withContext false to 0L
        val start = System.currentTimeMillis()
        val ok = runCatching {
            Transport.connectThroughTorRetry(
                TorService.socksPort(), onion.removeSuffix(".onion"), 80,
                totalMs = 90_000L, onProgress = onProgress,
            ).use { it.isConnected }
        }.getOrElse { org.cmchat.app.diag.Diag.e("onion", "self-test failed", it); false }
        val ms = System.currentTimeMillis() - start
        org.cmchat.app.diag.Diag.i("onion", "self-test ${if (ok) "OK" else "FAIL"} ${ms}ms")
        ok to ms
    }

    /** Never log the onion private-key blob; show presence only. */
    private fun redact(key: String, value: String): String =
        if (key.contains("PrivKey", true) || key.equals("PrivateKey", true))
            "<${value.substringBefore(':')}:redacted>" else value

    private fun acceptLoop(server: ServerSocket) {
        scope.launch {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                // Note: Invisible mode no longer refuses connections. Messages
                // still arrive but are held as "missed" (no receipts exist, so a
                // sender can't tell Online from Invisible). See MessageService.
                val handler = onIncoming
                if (handler != null) runCatching { handler(socket) }
                else runCatching { socket.close() }
            }
        }
    }
}
