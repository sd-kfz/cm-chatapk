package org.cmchat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.cmchat.app.tor.ServerController
import org.cmchat.app.tor.ServerStatus
import org.cmchat.app.tor.TorService
import org.cmchat.app.tor.TorStatus
import org.cmchat.app.ui.theme.*

@Composable
fun MyServerScreen(
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onBack: () -> Unit,
    onRequestNewAddress: () -> Unit = {},
) {
    val tor by TorService.status.collectAsState()
    val server by ServerController.status.collectAsState()
    val scope = rememberCoroutineScope()
    var selfTest by remember { mutableStateOf<String?>(null) }

    // Ticks every second: uptime, and the cooldowns that keep the buttons from
    // being mashed into a storm of publishes / rotations / Tor probes.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { now = System.currentTimeMillis(); delay(1000) }
    }
    val starting = server is ServerStatus.Starting
    val online = server is ServerStatus.Online
    val restartWait = secs(ServerController.restartCooldownMs(now))
    val rotateWait = secs(ServerController.rotateCooldownMs(now))
    val testWait = secs(ServerController.selfTestCooldownMs(now))
    val testing = ServerController.selfTestRunning()

    Column(Modifier.fillMaxSize().background(CmBackground)) {
        Box(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("‹ Back", color = CmBlue, fontFamily = Nunito, fontSize = 15.sp,
                modifier = Modifier.align(Alignment.CenterStart).clickable { onBack() })
            Text("My Server", color = CmText, fontFamily = Nunito, fontSize = 17.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
        }

        val torPct = (tor as? TorStatus.Connecting)?.percent
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Step("Tor starting", tor !is TorStatus.Offline)
            Step("Tor connected" + if (torPct != null) " ($torPct%)" else "", tor is TorStatus.Online)
            Step("Creating my server", server is ServerStatus.Starting || server is ServerStatus.Online)
            Step("Published & reachable", server is ServerStatus.Online)
        }

        Spacer(Modifier.height(16.dp))
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(16.dp))
                .background(CmCard).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val live = server as? ServerStatus.Online
            Text("Onion address", color = CmTextDim, fontFamily = Nunito, fontSize = 12.sp)
            Text(live?.onion ?: "—", color = CmText, fontFamily = Nunito, fontSize = 13.sp)
            Text("Tag: ${live?.faceName ?: "—"}", color = CmTextDim, fontFamily = Nunito, fontSize = 12.sp)
            val uptime = live?.let { formatUptime(now - it.sinceMs) } ?: "—"
            Text("Uptime: $uptime", color = CmTextDim, fontFamily = Nunito, fontSize = 12.sp)
            (server as? ServerStatus.Failed)?.let {
                Text("Error: ${it.reason}", color = CmRed, fontFamily = Nunito, fontSize = 12.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // Start only when nothing is running; Stop only when something is;
            // Restart never while a publish is in flight (and 10 s apart).
            ServerButton(if (starting) "Starting…" else "Start", CmGreen, Modifier.weight(1f),
                enabled = !starting && !online) { onStart(); now = System.currentTimeMillis() }
            ServerButton("Stop", CmRed, Modifier.weight(1f),
                enabled = server !is ServerStatus.Off) { onStop(); now = System.currentTimeMillis() }
            ServerButton(if (restartWait > 0) "Restart ${restartWait}s" else "Restart", CmBlue, Modifier.weight(1f),
                enabled = !starting && restartWait == 0) { onRestart(); now = System.currentTimeMillis() }
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.padding(horizontal = 16.dp)) {
            ServerButton(
                when {
                    testing -> "Testing…"
                    testWait > 0 -> "Self-test (again in ${testWait}s)"
                    else -> "Self-test (reach my own server)"
                },
                CmCard, Modifier.fillMaxWidth(), textColor = CmText,
                enabled = online && !testing && testWait == 0,
            ) {
                selfTest = "Testing…"
                scope.launch {
                    val r = ServerController.selfTest()
                    selfTest = when {
                        r == null -> "Already ran just now — try again in a moment"
                        r.first -> "Reachable — ${r.second}ms"
                        else -> "Failed"
                    }
                }
            }
        }
        selfTest?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = CmTextDim, fontFamily = Nunito, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp))
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.padding(horizontal = 16.dp)) {
            ServerButton(
                if (rotateWait > 0) "Request new address (again in ${rotateWait}s)" else "Request new address",
                CmCard, Modifier.fillMaxWidth(), textColor = CmOrange,
                enabled = online && rotateWait == 0,
            ) {
                onRequestNewAddress()
                now = System.currentTimeMillis()
            }
        }
        Text(
            "Rotates to a fresh onion and tells your contacts; the old address " +
                "stays alive ~24h so no one drops.",
            color = CmTextFaint, fontFamily = Nunito, fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun Step(label: String, done: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (done) CmGreen else CmTextFaint))
        Spacer(Modifier.width(10.dp))
        Text(label, color = if (done) CmText else CmTextDim, fontFamily = Nunito, fontSize = 14.sp)
    }
}

/** Whole seconds left in a cooldown (0 = ready). */
private fun secs(ms: Long): Int = ((ms + 999) / 1000).toInt()

@Composable
private fun ServerButton(
    label: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier,
    textColor: androidx.compose.ui.graphics.Color = CmBackground, enabled: Boolean = true, onClick: () -> Unit,
) {
    // Disabled = dimmed and not tappable (no queued taps).
    Box(
        modifier.clip(RoundedCornerShape(14.dp)).background(if (enabled) color else CmCard)
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) textColor else CmTextFaint, fontFamily = Nunito, fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun formatUptime(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return buildString {
        if (h > 0) append("${h}h ")
        if (h > 0 || m > 0) append("${m}m ")
        append("${sec}s")
    }
}
