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


sealed interface ServerStatus {
    data object Off : ServerStatus
    data object Starting : ServerStatus
    data class Online(val onion: String, val faceName: String, val sinceMs: Long) : ServerStatus
    data class Failed(val reason: String) : ServerStatus
}


data class OnionPublish(val onion: String, val newPrivateKey: String?)


object ServerController {

    private val _status = MutableStateFlow<ServerStatus>(ServerStatus.Off)
    val status: StateFlow<ServerStatus> = _status.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null

    @Volatile private var currentServiceId: String? = null

    @Volatile private var activeKey: String? = null


    private val publishMutex = Mutex()

    @Volatile private var lastRotateMs = 0L
    private const val MIN_ROTATE_INTERVAL_MS = 60_000L

    private val rotateLock = Any()
    private var debouncedCount = 0L
    private var lastDebounceLogMs = 0L
    private const val DEBOUNCE_LOG_INTERVAL_MS = 2_000L



    @Volatile private var lastRestartMs = 0L
    private const val MIN_RESTART_INTERVAL_MS = 10_000L

    private val selfTestRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var lastSelfTestMs = 0L
    private const val SELF_TEST_MIN_INTERVAL_MS = 20_000L


    fun restartCooldownMs(now: Long = System.currentTimeMillis()): Long =
        (lastRestartMs + MIN_RESTART_INTERVAL_MS - now).coerceAtLeast(0)

    fun rotateCooldownMs(now: Long = System.currentTimeMillis()): Long =
        (lastRotateMs + MIN_ROTATE_INTERVAL_MS - now).coerceAtLeast(0)

    fun selfTestCooldownMs(now: Long = System.currentTimeMillis()): Long =
        (lastSelfTestMs + SELF_TEST_MIN_INTERVAL_MS - now).coerceAtLeast(0)
    fun selfTestRunning(): Boolean = selfTestRunning.get()



    private const val MAX_CONCURRENT_CONN = 8
    private val activeConns = AtomicInteger(0)

    private const val ACCEPT_BURST = 12
    private const val ACCEPT_REFILL_PER_SEC = 6.0
    @Volatile private var acceptTokens = ACCEPT_BURST.toDouble()
    @Volatile private var acceptRefillAt = System.currentTimeMillis()

    private const val CONN_READ_TIMEOUT_MS = 15_000


    val stoppedByUser = MutableStateFlow(false)


    fun userStop() {
        stoppedByUser.value = true
        stop()
        org.cmchat.app.diag.ConnDiag.sys("My server: stopped by you — stays off until you tap Start")
    }


    fun userStart(faceName: String, existingOnionKey: String?, existingOnionAddress: String?,
                  onPublished: (OnionPublish) -> Unit) {
        stoppedByUser.value = false
        start(faceName, existingOnionKey, existingOnionAddress, onPublished)
    }


    @Volatile
    var onIncoming: ((java.net.Socket) -> Unit)? = null



    private data class Published(val faceName: String, val key: String?, val onion: String?)
    @Volatile private var lastPublished: Published? = null

    @Volatile private var resumeOnOnline: Published? = null
    @Volatile private var torWatch: kotlinx.coroutines.Job? = null


    fun pauseForTorRestart() {
        val again = lastPublished?.takeIf {
            _status.value is ServerStatus.Online || _status.value is ServerStatus.Starting
        }
        stop()
        resumeOnOnline = again
        if (again != null) {
            org.cmchat.app.diag.ConnDiag.sys("server paused for a Tor restart — republishes when Tor is back")
        }
    }

    private fun ensureTorWatch() {
        if (torWatch != null) return
        torWatch = scope.launch {
            TorService.status.collect { st ->
                val again = resumeOnOnline
                if (st is TorStatus.Online && again != null) {
                    resumeOnOnline = null
                    org.cmchat.app.diag.ConnDiag.sys("Tor back — republishing my server")
                    start(again.faceName, again.key, again.onion) {}
                }
            }
        }
    }

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


    fun start(
        faceName: String,
        existingOnionKey: String?,
        existingOnionAddress: String? = null,
        onPublished: (OnionPublish) -> Unit,
    ) {

        if (stoppedByUser.value) return





        val running = _status.value as? ServerStatus.Online
        val runningKey = activeKey
        if (running != null && runningKey != null) {
            if (runningKey != existingOnionKey) onPublished(OnionPublish(running.onion, runningKey))
            return
        }

        if (_status.value is ServerStatus.Starting) return
        ensureTorWatch()
        scope.launch {

            publishMutex.withLock {
                if (_status.value is ServerStatus.Online && activeKey != null) return@withLock
                _status.value = ServerStatus.Starting
                val control = TorService.controlConnection()
                if (control == null) {
                    _status.value = ServerStatus.Failed("Tor not connected"); return@withLock
                }
                val result = runCatching {


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
                    lastPublished = Published(faceName, activeKey, pub.onion)
                    onPublished(pub)
                    org.cmchat.app.diag.Diag.i("onion", "published ${org.cmchat.app.diag.Redact.onionShort(pub.onion)}")

                    org.cmchat.app.diag.ConnDiag.sys("My server is up at " +
                        "${org.cmchat.app.diag.Redact.onionShort(pub.onion)} — friends must reach me at this address " +
                        "(${if (existingOnionKey != null) "stored" else "NEW"} key)")
                    _status.value = ServerStatus.Online(pub.onion, faceName, System.currentTimeMillis())
                }.onFailure { e ->
                    org.cmchat.app.diag.Diag.e("onion", "publish failed", e)
                    org.cmchat.app.diag.ConnDiag.sys("My server: publish FAILED (${e.javaClass.simpleName}) — " +
                        "nothing can reach me until it's up")
                    _status.value = ServerStatus.Failed(e.message ?: "publish failed")
                }
            }
        }
    }


    fun requestNewAddress(urgent: Boolean = false, onNew: (OnionPublish) -> Unit) {
        if (stoppedByUser.value) return







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

            lastRotateMs = now
            if (debouncedCount > 0) {
                org.cmchat.app.diag.Diag.i("onion", "rotation debounced x$debouncedCount (suppressed)")
                debouncedCount = 0
            }
        }
        scope.launch {


            if (urgent) publishMutex.lock()
            else if (!publishMutex.tryLock()) {
                org.cmchat.app.diag.Diag.i("onion", "rotation skipped (publish in flight)"); return@launch
            }
            try {
                val control = TorService.controlConnection() ?: run {
                    lastRotateMs = 0L
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
                    lastPublished = Published(faceName, pub.newPrivateKey, pub.onion)
                    org.cmchat.app.diag.ConnDiag.sys("My server: NEW address " +
                        "${org.cmchat.app.diag.Redact.onionShort(pub.onion)} (the old one answers only until Tor restarts)")
                    onNew(pub)
                    org.cmchat.app.diag.Diag.i("onion", "rotated to new address")

                    if (oldId != null) scope.launch {
                        kotlinx.coroutines.delay(24 * 60 * 60_000L)
                        runCatching { TorService.controlConnection()?.delOnion(oldId) }
                    }
                }.onFailure {
                    lastRotateMs = 0L
                    org.cmchat.app.diag.Diag.e("onion", "address rotation failed", it)
                }
            } finally {
                publishMutex.unlock()
            }
        }
    }

    fun stop() {

        resumeOnOnline = null


        lastPublished = null

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


    fun restart(
        faceName: String,
        existingOnionKey: String?,
        existingOnionAddress: String? = null,
        onPublished: (OnionPublish) -> Unit,
    ): Boolean {
        if (stoppedByUser.value) return false
        val now = System.currentTimeMillis()
        synchronized(rotateLock) {
            if (_status.value is ServerStatus.Starting || now - lastRestartMs < MIN_RESTART_INTERVAL_MS) return false
            lastRestartMs = now
        }
        stop()
        start(faceName, existingOnionKey, existingOnionAddress, onPublished)
        return true
    }


    suspend fun selfTest(onProgress: (Long) -> Unit = {}): Pair<Boolean, Long>? = withContext(Dispatchers.IO) {


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
            lastSelfTestMs = System.currentTimeMillis()
            selfTestRunning.set(false)
        }
    }

    private fun acceptLoop(server: ServerSocket) {
        scope.launch {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (e: Exception) {


                    if (!server.isClosed) {
                        org.cmchat.app.diag.ConnDiag.sys("My server: listener STOPPED unexpectedly " +
                            "(${e.javaClass.simpleName}) — incoming messages can't be answered")
                    }
                    break
                }




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
                        handler(socket)
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
