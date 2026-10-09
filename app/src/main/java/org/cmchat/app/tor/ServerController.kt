package org.cmchat.app.tor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.cmchat.app.transport.Transport
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

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
    @Volatile private var currentServiceId: String? = null
    /** The onion private key currently published, so we don't re-add the same one. */
    @Volatile private var activeKey: String? = null

    /** Single-flight guard: only one publish/rotate/stop runs at a time. */
    private val publishMutex = Mutex()
    /** Debounce rotation so it can never fire in a tight loop. */
    @Volatile private var lastRotateMs = 0L
    private const val MIN_ROTATE_INTERVAL_MS = 60_000L
    /** Collapse debounced-rotation logging so a tight loop can't flood the log. */
    private val rotateLock = Any()
    private var debouncedCount = 0L
    private var lastDebounceLogMs = 0L
    private const val DEBOUNCE_LOG_INTERVAL_MS = 2_000L

    // ---- anti-mashing for the My Server buttons ------------------------------
    /** Restart can't be mashed into a storm of re-publishes. */
    @Volatile private var lastRestartMs = 0L
    private const val MIN_RESTART_INTERVAL_MS = 10_000L
    /** Self-test: one at a time, and at most one per [SELF_TEST_MIN_INTERVAL_MS]
     * (each can hold a Tor connection attempt for up to 90 s). */
    private val selfTestRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var lastSelfTestMs = 0L
    private const val SELF_TEST_MIN_INTERVAL_MS = 20_000L

    /** ms until Restart is accepted again (0 = now). */
    fun restartCooldownMs(now: Long = System.currentTimeMillis()): Long =
        (lastRestartMs + MIN_RESTART_INTERVAL_MS - now).coerceAtLeast(0)
    /** ms until "Request new address" is accepted again (0 = now). */
    fun rotateCooldownMs(now: Long = System.currentTimeMillis()): Long =
        (lastRotateMs + MIN_ROTATE_INTERVAL_MS - now).coerceAtLeast(0)
    /** ms until a self-test is accepted again (0 = now). */
    fun selfTestCooldownMs(now: Long = System.currentTimeMillis()): Long =
        (lastSelfTestMs + SELF_TEST_MIN_INTERVAL_MS - now).coerceAtLeast(0)
    fun selfTestRunning(): Boolean = selfTestRunning.get()

    // ---- incoming-connection DoS limits ------------------------------------
    /** Max simultaneous incoming onion connections; extras are dropped. */
    private const val MAX_CONCURRENT_CONN = 8
    private val activeConns = AtomicInteger(0)
    /** Accept-rate token bucket (global; peers are indistinguishable pre-auth). */
    private const val ACCEPT_BURST = 12
    private const val ACCEPT_REFILL_PER_SEC = 6.0
    @Volatile private var acceptTokens = ACCEPT_BURST.toDouble()
    @Volatile private var acceptRefillAt = System.currentTimeMillis()
    /** A connected peer that sends nothing must not hold a slot forever. */
    private const val CONN_READ_TIMEOUT_MS = 15_000

    /** Set by MessageService: handles each accepted connection synchronously. */
    @Volatile
    var onIncoming: ((java.net.Socket) -> Unit)? = null

    @Synchronized
    private fun acceptAllowed(): Boolean {
        val now = System.currentTimeMillis()
        acceptTokens = (acceptTokens + (now - acceptRefillAt) / 1000.0 * ACCEPT_REFILL_PER_SEC)
            .coerceAtMost(ACCEPT_BURST.toDouble())
        acceptRefillAt = now
        if (acceptTokens < 1.0) return false
        acceptTokens -= 1.0
        return true
    }

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
        // Fast path: already online for THIS exact key -> nothing to do. Prevents
        // the re-publish storm when the start effect re-fires on recomposition.
        if (_status.value is ServerStatus.Online && activeKey == existingOnionKey) return
        // A publish is already in flight: never queue another one behind it.
        if (_status.value is ServerStatus.Starting) return
        scope.launch {
            // Single-flight: never run two publishes concurrently.
            publishMutex.withLock {
                if (_status.value is ServerStatus.Online && activeKey == existingOnionKey) return@withLock
                _status.value = ServerStatus.Starting
                val control = TorService.controlConnection()
                if (control == null) {
                    _status.value = ServerStatus.Failed("Tor not connected"); return@withLock
                }
                val result = runCatching {
                    // DEL any previous service, and (defensively) the stored address
                    // we're about to re-add, so re-adding can never collide.
                    currentServiceId?.let { runCatching { control.delOnion(it) } }
                    existingOnionAddress?.removeSuffix(".onion")?.let {
                        runCatching { control.delOnion(it) }
                    }
                    val server = Transport.openServer(0)
                    serverSocket?.let { old -> runCatching { old.close() } }
                    serverSocket = server
                    val ports = mapOf(80 to "127.0.0.1:${server.localPort}")
                    val keyArg = existingOnionKey ?: "NEW:ED25519-V3"
                    org.cmchat.app.diag.Diag.i(
                        "onion",
                        "ADD_ONION ${if (existingOnionKey != null) "ED25519-V3:<stored>" else "NEW:ED25519-V3"} " +
                            "Port=80,127.0.0.1:${server.localPort}",
                    )
                    val reply = control.addOnion(keyArg, ports)
                    org.cmchat.app.diag.Diag.i("onion", "ADD_ONION reply keys=${reply.keys}")
                    fun v(name: String) = reply.entries.firstOrNull { it.key.equals(name, true) }?.value
                    val addr = v("onionAddress")
                        ?: existingOnionAddress?.removeSuffix(".onion")
                        ?: throw IllegalStateException("ADD_ONION returned no onionAddress")
                    val priv = v("onionPrivKey") ?: existingOnionKey
                    currentServiceId = addr
                    activeKey = priv ?: existingOnionKey
                    acceptLoop(server)
                    OnionPublish("$addr.onion", priv)
                }
                result.onSuccess { pub ->
                    onPublished(pub)
                    org.cmchat.app.diag.Diag.i("onion", "published ${org.cmchat.app.diag.Redact.onionShort(pub.onion)}")
                    _status.value = ServerStatus.Online(pub.onion, faceName, System.currentTimeMillis())
                }.onFailure { e ->
                    org.cmchat.app.diag.Diag.e("onion", "publish failed", e)
                    _status.value = ServerStatus.Failed(e.message ?: "publish failed")
                }
            }
        }
    }

    /**
     * Rotate to a NEW onion address while keeping the OLD one registered for a
     * ~24h overlap so no contact drops mid-switch. Both map to the same local
     * server. The caller persists the new key/address and sends the signed
     * address-update to contacts. Manual (triggered from My Server).
     */
    fun requestNewAddress(urgent: Boolean = false, onNew: (OnionPublish) -> Unit) {
        // Debounce at the GATE: claim the 60s window SYNCHRONOUSLY, so a burst of
        // calls (rapid taps, or the adversarial self-test) collapses to ONE real
        // rotation instead of launching a coroutine per call. Debounced calls are
        // only counted here and logged at most once per DEBOUNCE_LOG_INTERVAL_MS
        // as "rotation debounced x<n>", so a tight loop can never flood the log.
        // [urgent] (the decoy — at most once, since it locks the app) skips the
        // window and waits for a running publish instead of being dropped.
        val now = System.currentTimeMillis()
        synchronized(rotateLock) {
            if (!urgent && now - lastRotateMs < MIN_ROTATE_INTERVAL_MS) {
                debouncedCount++
                if (now - lastDebounceLogMs >= DEBOUNCE_LOG_INTERVAL_MS) {
                    lastDebounceLogMs = now
                    org.cmchat.app.diag.Diag.i("onion", "rotation debounced x$debouncedCount")
                }
                return
            }
            // Passing the gate: claim the window now, and flush any pending count.
            lastRotateMs = now
            if (debouncedCount > 0) {
                org.cmchat.app.diag.Diag.i("onion", "rotation debounced x$debouncedCount (suppressed)")
                debouncedCount = 0
            }
        }
        scope.launch {
            // Single-flight: skip if a publish/rotate is already running (an
            // urgent one waits for it instead).
            if (urgent) publishMutex.lock()
            else if (!publishMutex.tryLock()) {
                org.cmchat.app.diag.Diag.i("onion", "rotation skipped (publish in flight)"); return@launch
            }
            try {
                val control = TorService.controlConnection() ?: run {
                    lastRotateMs = 0L  // genuine failure — allow an immediate retry
                    _status.value = ServerStatus.Failed("Tor not connected"); return@launch
                }
                val server = serverSocket ?: run {
                    lastRotateMs = 0L
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
                    activeKey = priv
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
                    lastRotateMs = 0L  // genuine failure — allow an immediate retry
                    org.cmchat.app.diag.Diag.e("onion", "address rotation failed", it)
                }
            } finally {
                publishMutex.unlock()
            }
        }
    }

    fun stop() {
        // Flip state synchronously so a following start()/restart() re-publishes.
        _status.value = ServerStatus.Off
        val id = currentServiceId
        currentServiceId = null
        activeKey = null
        val sock = serverSocket
        serverSocket = null
        scope.launch {
            publishMutex.withLock {
                runCatching { id?.let { TorService.controlConnection()?.delOnion(it) } }
                runCatching { sock?.close() }
            }
        }
    }

    /** Stop + start. Ignored while a publish is in flight or within 10 s of the
     * last restart, so mashing can't queue a re-publish storm. */
    fun restart(
        faceName: String,
        existingOnionKey: String?,
        existingOnionAddress: String? = null,
        onPublished: (OnionPublish) -> Unit,
    ): Boolean {
        val now = System.currentTimeMillis()
        synchronized(rotateLock) {
            if (_status.value is ServerStatus.Starting || now - lastRestartMs < MIN_RESTART_INTERVAL_MS) return false
            lastRestartMs = now
        }
        stop()
        start(faceName, existingOnionKey, existingOnionAddress, onPublished)
        return true
    }

    /**
     * Connect to my own onion through Tor; report OK/FAIL and elapsed ms. A
     * freshly published descriptor needs ~30-90s to upload, so this retries with
     * backoff rather than hard-failing, and reports progress via [onProgress].
     */
    suspend fun selfTest(onProgress: (Long) -> Unit = {}): Pair<Boolean, Long>? = withContext(Dispatchers.IO) {
        // One at a time + rate-limited: mashing returns null (refused) instead of
        // stacking up 90-second Tor probes.
        if (selfTestCooldownMs() > 0 || !selfTestRunning.compareAndSet(false, true)) return@withContext null
        lastSelfTestMs = System.currentTimeMillis()
        try {
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
            org.cmchat.app.diag.ConnDiag.recordSelfTest(ok, ms)
            ok to ms
        } finally {
            lastSelfTestMs = System.currentTimeMillis()   // the cooldown counts from the END
            selfTestRunning.set(false)
        }
    }

    private fun acceptLoop(server: ServerSocket) {
        scope.launch {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                // DoS defense: drop beyond the accept-rate bucket or the concurrent
                // cap, before doing any work or allocating buffers. Peers are
                // indistinguishable pre-auth over Tor, so this is a global limit;
                // a per-contact limit applies post-auth in MessageService.
                if (!acceptAllowed() || activeConns.get() >= MAX_CONCURRENT_CONN) {
                    runCatching { socket.close() }
                    org.cmchat.app.diag.ConnDiag.inc("incoming dropped (rate-limited or over concurrency cap)")
                    org.cmchat.app.diag.Diag.droppedFrame()
                    continue
                }
                val handler = onIncoming
                if (handler == null) { runCatching { socket.close() }; continue }
                org.cmchat.app.diag.ConnDiag.inc("incoming connection accepted")
                activeConns.incrementAndGet()
                scope.launch {
                    try {
                        runCatching { socket.soTimeout = CONN_READ_TIMEOUT_MS }
                        handler(socket)   // synchronous: reads + opens + dispatches
                    } catch (_: Exception) {
                    } finally {
                        activeConns.decrementAndGet()
                        runCatching { socket.close() }
                    }
                }
            }
        }
    }
}
