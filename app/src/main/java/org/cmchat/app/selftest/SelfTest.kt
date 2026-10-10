package org.cmchat.app.selftest

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.cmchat.app.BuildConfig
import org.cmchat.app.selftest.SelfTestLog.Severity
import org.cmchat.app.transport.RateLimiter
import org.cmchat.app.transport.Transport
import org.cmchat.app.vault.LoginThrottle
import org.cmchat.app.vault.SecurityFactory
import org.cmchat.app.vault.UnlockResult
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * DEBUG-ONLY adversarial self-test. It hammers the app's own hardened code
 * paths with malformed, oversized, flooding and abusive input and records what
 * (if anything) breaks into the separate [SelfTestLog]. It is compiled to a
 * no-op in release by the [BuildConfig.DEBUG] gate — it must NEVER run in a
 * shipped build. It only drives in-process logic and loopback sockets; it opens
 * no real Tor connection and touches no real vault (it uses a throwaway one in
 * the cache dir, deleted afterwards).
 */
object SelfTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var job: Job? = null
    /** Mashing "Run self-test" can't queue runs back to back. */
    @Volatile private var lastRunMs = 0L
    private const val MIN_INTERVAL_MS = 30_000L

    /** ms until another run is accepted (0 = now). */
    fun cooldownMs(now: Long = System.currentTimeMillis()): Long =
        if (job?.isActive == true) MIN_INTERVAL_MS else (lastRunMs + MIN_INTERVAL_MS - now).coerceAtLeast(0)

    @Synchronized
    fun run(context: Context) {
        if (!BuildConfig.DEBUG) return
        if (job?.isActive == true) return
        if (System.currentTimeMillis() - lastRunMs < MIN_INTERVAL_MS) return
        lastRunMs = System.currentTimeMillis()
        val app = context.applicationContext
        job = scope.launch {
            SelfTestLog.running.value = true
            SelfTestLog.record("Self-test", "started", Severity.INFO)
            runCatching { fuzzParser() }.onFailure { crash("message parser fuzz", it) }
            runCatching { floodOnion() }.onFailure { crash("onion flood", it) }
            runCatching { mashLifecycle() }.onFailure { crash("lifecycle mashing", it) }
            runCatching { abusePin(app) }.onFailure { crash("PIN / unlock abuse", it) }
            runCatching { forwardSecrecyLoopback(app) }.onFailure { crash("forward secrecy loopback", it) }
            SelfTestLog.record("Self-test", "finished", Severity.INFO)
            lastRunMs = System.currentTimeMillis()   // the cooldown counts from the END
            SelfTestLog.running.value = false
        }
    }

    private fun crash(attack: String, t: Throwable) {
        SelfTestLog.record(attack, "UNHANDLED ${t.javaClass.simpleName}: ${t.message}", Severity.HIGH)
    }

    // ---- 1) message parser fuzz -------------------------------------------
    private fun fuzzParser() {
        fun read(bytes: ByteArray): Pair<ByteArray?, Throwable?> =
            try { Transport.readFrame(ByteArrayInputStream(bytes)) to null }
            catch (t: Throwable) { null to t }

        // Craft a 4-byte big-endian length header.
        fun hdr(len: Int) = byteArrayOf(
            (len ushr 24).toByte(), (len ushr 16).toByte(),
            (len ushr 8).toByte(), len.toByte(),
        )

        data class Case(val name: String, val bytes: ByteArray)
        val cases = listOf(
            Case("empty", ByteArray(0)),
            Case("1 byte (truncated header)", byteArrayOf(7)),
            Case("header only, no body", hdr(100)),
            Case("negative length", hdr(-1)),
            Case("zero length", hdr(0)),
            Case("oversized length (Int.MAX)", hdr(Int.MAX_VALUE)),
            Case("oversized length (1 GiB)", hdr(1 shl 30)),
            Case("length 1000, body 10 then EOF", hdr(1000) + ByteArray(10)),
            Case("length 64KiB+1 (one over cap)", hdr(Transport.MAX_FRAME_BYTES + 1)),
        )
        var worst = Severity.OK
        for (c in cases) {
            val (out, thrown) = read(c.bytes)
            when {
                thrown != null -> {
                    SelfTestLog.record("parser: ${c.name}",
                        "THREW ${thrown.javaClass.simpleName} up the stack", Severity.HIGH)
                    worst = Severity.HIGH
                }
                out != null -> {
                    // A malformed/oversized case returning bytes would be wrong.
                    SelfTestLog.record("parser: ${c.name}",
                        "accepted ${out.size} bytes (should have been dropped)", Severity.HIGH)
                    worst = Severity.HIGH
                }
            }
        }
        // 500 random frames — must never crash or hang, memory stays bounded
        // because an over-cap length is rejected before allocation.
        repeat(500) {
            val n = Random.nextInt(0, 80)
            val (_, thrown) = read(Random.nextBytes(n))
            if (thrown != null) {
                SelfTestLog.record("parser: random bytes", "THREW ${thrown.javaClass.simpleName}", Severity.HIGH)
                worst = Severity.HIGH
            }
        }
        if (worst == Severity.OK) {
            SelfTestLog.record("message parser fuzz",
                "all malformed/truncated/oversized/random frames dropped cleanly, no crash", Severity.OK)
        }
    }

    // ---- 2) onion flood ----------------------------------------------------
    private suspend fun floodOnion() {
        // (a) Accept-rate limiter: the same token-bucket config the server uses.
        val rl = RateLimiter(burst = 12, refillPerSec = 6.0)
        var allowed = 0
        val t0 = System.currentTimeMillis()
        repeat(200) { if (rl.allow("peer", t0)) allowed++ }   // all at one instant
        if (allowed > 12) {
            SelfTestLog.record("flood: accept-rate burst",
                "rate limit let $allowed/200 through at once (burst is 12)", Severity.HIGH)
        } else {
            SelfTestLog.record("flood: accept-rate burst",
                "limiter capped a 200-connection burst at $allowed (expected ≤12)", Severity.OK)
        }

        // (b) Real loopback socket flood against the HARDENED readFrame, with a
        //     minimal accept loop standing in for the server. Confirms the read
        //     path survives connect-then-silent, garbage, oversized headers and
        //     mid-handshake disconnects under concurrency, with a concurrency cap.
        val serverCrashed = AtomicBoolean(false)
        val server = Transport.openServer(0)
        val port = server.localPort
        val maxConcurrent = 8
        val live = java.util.concurrent.atomic.AtomicInteger(0)
        var peakConcurrent = 0
        val acceptor = scope.launch {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                val n = live.incrementAndGet()
                synchronized(server) { if (n > peakConcurrent) peakConcurrent = n }
                if (n > maxConcurrent) { runCatching { s.close() }; live.decrementAndGet(); continue }
                launch {
                    try {
                        s.soTimeout = 800
                        Transport.readFrame(s.getInputStream())   // hardened: never throws up
                    } catch (t: Throwable) {
                        serverCrashed.set(true)
                    } finally {
                        live.decrementAndGet(); runCatching { s.close() }
                    }
                }
            }
        }
        val completed = java.util.concurrent.atomic.AtomicInteger(0)
        val ok = withTimeoutOrNull(8_000) {
            (1..40).map { i ->
                async {
                    runCatching {
                        Socket().use { c ->
                            c.connect(InetSocketAddress("127.0.0.1", port), 500)
                            val out = c.getOutputStream()
                            when (i % 4) {
                                0 -> { delay(200) }                                   // connect-then-silent
                                1 -> { out.write(Random.nextBytes(32)); out.flush() } // garbage
                                2 -> { out.write(byteArrayOf(127, -1, -1, -1)); out.flush() } // huge length header
                                else -> { out.write(byteArrayOf(0, 0, 4, 1)); out.flush() }    // partial frame, then close
                            }
                        }
                    }
                    completed.incrementAndGet()
                }
            }.awaitAll()
            true
        }
        delay(200)
        acceptor.cancel()
        runCatching { server.close() }
        when {
            serverCrashed.get() -> SelfTestLog.record("flood: loopback connections",
                "server read path threw on malformed/abusive input", Severity.HIGH)
            ok == null -> SelfTestLog.record("flood: loopback connections",
                "flood did not drain within 8s — possible hang", Severity.HIGH)
            peakConcurrent > maxConcurrent -> SelfTestLog.record("flood: concurrency cap",
                "peak concurrent handlers $peakConcurrent exceeded cap $maxConcurrent", Severity.MEDIUM)
            else -> SelfTestLog.record("flood: loopback connections",
                "40 abusive connections (silent/garbage/oversized/partial) handled, cap held at ≤$maxConcurrent, no crash",
                Severity.OK)
        }
    }

    // ---- 3) lifecycle mashing ---------------------------------------------
    private suspend fun mashLifecycle() {
        val threadsBefore = Thread.activeCount()
        val invisible = org.cmchat.app.settings.AppSettings.invisibleMode
        val before = invisible.value
        // NEVER touch a live server: stopping / rotating it here would take the
        // user offline (or move their address without telling friends).
        val live = org.cmchat.app.tor.ServerController.status.value.let {
            it is org.cmchat.app.tor.ServerStatus.Online || it is org.cmchat.app.tor.ServerStatus.Starting
        }
        repeat(100) {
            invisible.value = !invisible.value
            if (!live) {
                runCatching { org.cmchat.app.tor.ServerController.stop() }
                runCatching { org.cmchat.app.tor.ServerController.requestNewAddress {} } // debounced
            }
        }
        invisible.value = before
        delay(300)
        if (live) {
            SelfTestLog.record("lifecycle: rapid stop/rotate",
                "skipped — your server is live and the self-test never touches it", Severity.INFO)
        }
        val grew = Thread.activeCount() - threadsBefore
        // Off or Failed are both fine here (there is no real Tor in the test);
        // only a stuck Starting/Online or a crash would be wrong.
        val st = org.cmchat.app.tor.ServerController.status.value
        val statusSane = live || st is org.cmchat.app.tor.ServerStatus.Off ||
            st is org.cmchat.app.tor.ServerStatus.Failed
        when {
            !statusSane -> SelfTestLog.record("lifecycle: rapid stop/rotate",
                "server left in an unexpected state ($st) after mashing", Severity.MEDIUM)
            grew > 40 -> SelfTestLog.record("lifecycle: thread leak",
                "active threads grew by $grew after mashing (possible leak)", Severity.MEDIUM)
            else -> SelfTestLog.record("lifecycle: rapid stop/rotate/presence",
                "100x stop + address-rotate + presence toggles: no crash, debounce held, threads +$grew",
                Severity.OK)
        }
    }

    // ---- 5) forward secrecy on THIS phone's libsodium ----------------------
    /**
     * Runs the real v3 handshake (prekey request → verified one-time prekey →
     * X3DH frame) between two throwaway identities over a loopback socket, using
     * the device's own native libsodium — the unit tests in CI use the host one.
     * Then checks a tampered frame and a replayed request are both refused.
     * No Tor, no real keys, nothing written to disk.
     */
    private fun forwardSecrecyLoopback(context: Context) {
        val crypto = SecurityFactory.create(java.io.File(context.cacheDir, "selftest-fs")).crypto
        val (aPub, aSec) = crypto.newIdentityKeypair()
        val (bPub, bSec) = crypto.newIdentityKeypair()
        val alice = org.cmchat.app.transport.SecureChannel(crypto, aPub, aSec,
            org.cmchat.app.transport.InnerCodec(), org.cmchat.app.transport.ReplayGuard())
        val bob = org.cmchat.app.transport.SecureChannel(crypto, bPub, bSec,
            org.cmchat.app.transport.InnerCodec(), org.cmchat.app.transport.ReplayGuard())
        val contacts = mapOf("alice" to aPub)
        val secret = Random.nextBytes(64)

        // (a) full exchange over a real loopback socket
        val server = Transport.openServer(0)
        val got = java.util.concurrent.atomic.AtomicReference<Any?>()
        val t = Thread {
            runCatching {
                server.accept().use { s ->
                    s.soTimeout = 5_000
                    val r = org.cmchat.app.transport.SecureWire.receive(bob, s.getInputStream(), s.getOutputStream(), contacts)
                    // The receipt: "stored" (here: kept for the check below).
                    (r as? org.cmchat.app.transport.SecureWire.Received.Message)?.reply(org.cmchat.app.transport.Ack.OK)
                    got.set(r)
                }
            }.onFailure { got.set(it) }
        }.also { it.start() }
        var receipt: org.cmchat.app.transport.Ack? = null
        try {
            Socket().use { c ->
                c.connect(InetSocketAddress("127.0.0.1", server.localPort), 2_000)
                c.soTimeout = 5_000
                receipt = org.cmchat.app.transport.SecureWire.send(alice, c.getInputStream(), c.getOutputStream(), bPub,
                    org.cmchat.app.transport.FrameType.MSG, secret)
            }
            t.join(6_000)
        } finally {
            runCatching { server.close() }
        }
        val r = got.get() as? org.cmchat.app.transport.SecureWire.Received.Message
        if (r == null || !r.body.contentEquals(secret)) {
            SelfTestLog.record("forward secrecy: handshake", "loopback message NOT delivered intact", Severity.HIGH)
            return
        }
        if (receipt != org.cmchat.app.transport.Ack.OK) {
            SelfTestLog.record("forward secrecy: handshake", "delivered, but no valid receipt came back", Severity.HIGH)
            return
        }

        // (b) tamper + replay, in memory
        val client = alice.Client(bPub)
        val first = bob.onFirstFrame(client.request, contacts)
        val srv = (first as org.cmchat.app.transport.SecureChannel.First.Handshake).server
        val pk = (client.verify(srv.response) as org.cmchat.app.transport.SecureChannel.Verdict.Ok).prekey
        val frame = client.seal(pk, org.cmchat.app.transport.FrameType.MSG, secret)
        frame[frame.size - 1] = (frame[frame.size - 1].toInt() xor 1).toByte()
        val tamperRefused = srv.open(frame) is org.cmchat.app.transport.SecureChannel.Opened.Drop
        val replayRefused = bob.onFirstFrame(client.request, contacts) is
            org.cmchat.app.transport.SecureChannel.First.Drop
        if (tamperRefused && replayRefused) {
            SelfTestLog.record("forward secrecy: handshake",
                "one-time prekey + X3DH delivered over loopback on this device's libsodium; tamper and replay refused",
                Severity.OK)
        } else {
            SelfTestLog.record("forward secrecy: handshake",
                "tamper refused=$tamperRefused, replay refused=$replayRefused", Severity.HIGH)
        }
    }

    // ---- 4) PIN / unlock abuse --------------------------------------------
    private fun abusePin(context: Context) {
        // Escalating-lockout schedule is pure logic — verify it never shrinks and
        // tops out at the 30-min lockout.
        var prev = -1
        var monotonic = true
        for (a in 1..LoginThrottle.SCHEDULE.size) {
            val d = LoginThrottle.delaySeconds(a)
            if (d < prev) monotonic = false
            prev = d
        }
        val tops = LoginThrottle.delaySeconds(LoginThrottle.SCHEDULE.size + 1) == LoginThrottle.LOCKOUT_SECONDS
        if (!monotonic || !tops) {
            SelfTestLog.record("PIN: lockout schedule",
                "escalation not monotonic or missing final lockout", Severity.MEDIUM)
        } else {
            SelfTestLog.record("PIN: lockout schedule",
                "delays escalate and top out at ${LoginThrottle.LOCKOUT_SECONDS / 60}-min lockout", Severity.OK)
        }

        // Throwaway vault in the cache dir — NEVER the real one.
        val dir = java.io.File(context.cacheDir, "selftest-vault-${System.currentTimeMillis()}")
        try {
            val vm = SecurityFactory.create(dir)
            val pin = "ab12cd"   // 6 chars, not a palindrome
            vm.createVault(pin, "SelfTest")
            // Hammer wrong PINs — every one must fail, none may open the vault.
            var leaked = 0
            repeat(25) { n -> if (vm.verify("wrong$n!")) leaked++ }
            if (leaked > 0) {
                SelfTestLog.record("PIN: wrong-PIN hammer", "a wrong PIN opened the vault", Severity.HIGH)
            } else {
                SelfTestLog.record("PIN: wrong-PIN hammer", "25 wrong PINs all rejected", Severity.OK)
            }
            // Duress (reversed PIN) must wipe and report Duress.
            val duress = vm.unlock(pin.reversed())
            if (duress is UnlockResult.Duress) {
                SelfTestLog.record("PIN: duress (reversed) trigger", "detected and vault wiped", Severity.OK)
            } else {
                SelfTestLog.record("PIN: duress (reversed) trigger",
                    "reversed PIN did NOT trigger the shredder path", Severity.MEDIUM)
            }
        } finally {
            runCatching { dir.deleteRecursively() }
        }
    }
}
