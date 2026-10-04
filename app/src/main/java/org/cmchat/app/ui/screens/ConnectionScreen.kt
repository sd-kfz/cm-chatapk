package org.cmchat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.cmchat.app.diag.ConnDiag
import org.cmchat.app.tor.Bridges
import org.cmchat.app.tor.ServerController
import org.cmchat.app.tor.ServerStatus
import org.cmchat.app.tor.TorService
import org.cmchat.app.tor.TorStatus
import org.cmchat.app.ui.theme.*

/**
 * Connection diagnostic / "Link Test" — its own section, separate from the Tor
 * log and Self-Test log. Shows live engine/onion/bridge state, streams every
 * connect stage (outgoing + incoming), and can run a real probe to a contact.
 */
@Composable
fun ConnectionScreen(
    contacts: List<Pair<String, String>>,   // name, cmId
    onLinkTest: (String) -> Unit,
    onBack: () -> Unit,
) {
    val lines by ConnDiag.lines.collectAsState()
    val engine by TorService.status.collectAsState()
    val server by ServerController.status.collectAsState()
    val bridgeMode by Bridges.mode.collectAsState()
    val lastSelf by ConnDiag.lastSelfTest.collectAsState()
    val clipboard = LocalClipboardManager.current
    var pickerOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(CmBackground)) {
        Box(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("‹ Back", color = CmBlue, fontFamily = Nunito, fontSize = 15.sp,
                modifier = Modifier.align(Alignment.CenterStart).clickable { onBack() })
            Text("Connection", color = CmText, fontFamily = Nunito, fontSize = 17.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
        }

        // State summary.
        val engineLabel = when (val e = engine) {
            is TorStatus.Online -> "Online"
            is TorStatus.Connecting -> "Connecting ${e.percent}%"
            is TorStatus.Starting -> "Starting"
            is TorStatus.Offline -> "Offline"
            is TorStatus.Failed -> if (e.reason == "bridges") "Failed (bridges)" else "Failed"
        }
        val published = server is ServerStatus.Online
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(14.dp)).background(CmCard).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            StateRow("Engine", engineLabel, if (engine is TorStatus.Online) CmGreen else CmOrange)
            StateRow("My onion", if (published) "published" else "not published",
                if (published) CmGreen else CmTextDim)
            StateRow("Last self-test", lastSelf ?: "—", CmTextDim)
            StateRow("Bridges", bridgeMode.wire, if (bridgeMode == Bridges.Mode.OFF) CmTextDim else CmBlue)
            val starts = TorService.serviceStarts
            val upMin = if (TorService.serviceStartedAtMs > 0)
                (System.currentTimeMillis() - TorService.serviceStartedAtMs) / 60000 else 0
            StateRow("Engine service", "started ${starts}× · up ${upMin}m", CmTextDim)
        }

        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Run Link Test", CmBlue, Modifier.weight(1f)) { pickerOpen = true }
            Pill("Copy log", CmCard, Modifier.weight(1f), CmText) {
                clipboard.setText(AnnotatedString(ConnDiag.dump()))
            }
            Pill("Clear", CmCard, Modifier.weight(1f), CmRed) { ConnDiag.clear() }
        }

        if (pickerOpen) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(12.dp)).background(CmCard).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Pick a contact to test:", color = CmTextDim, fontFamily = Nunito, fontSize = 12.sp)
                if (contacts.isEmpty()) {
                    Text("No contacts yet — add one first.", color = CmTextFaint,
                        fontFamily = Nunito, fontSize = 12.sp)
                }
                contacts.forEach { (name, cmId) ->
                    Text(name, color = CmBlue, fontFamily = Nunito, fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth().clickable {
                            pickerOpen = false; onLinkTest(cmId)
                        }.padding(vertical = 6.dp))
                }
                Text("Cancel", color = CmTextDim, fontFamily = Nunito, fontSize = 13.sp,
                    modifier = Modifier.clickable { pickerOpen = false }.padding(top = 2.dp))
            }
        }

        Spacer(Modifier.height(6.dp))
        if (lines.isEmpty()) {
            Text("No connection activity yet. Run a Link Test, or send/receive a message.",
                color = CmTextFaint, fontFamily = Nunito, fontSize = 12.sp,
                modifier = Modifier.padding(16.dp))
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp)) {
            items(lines.asReversed()) { l ->
                val (tag, color) = when (l.dir) {
                    ConnDiag.Dir.OUT -> "OUT" to CmBlue
                    ConnDiag.Dir.IN -> "IN " to CmGreen
                    ConnDiag.Dir.SYS -> "SYS" to CmOrange
                }
                Text("$tag  ${l.text}", color = color, fontFamily = Nunito, fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }
}

@Composable
private fun StateRow(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = CmTextDim, fontFamily = Nunito, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(value, color = valueColor, fontFamily = Nunito, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Pill(label: String, bg: androidx.compose.ui.graphics.Color, modifier: Modifier,
                 fg: androidx.compose.ui.graphics.Color = CmBackground, onClick: () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(bg).clickable { onClick() }
        .padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(label, color = fg, fontFamily = Nunito, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
