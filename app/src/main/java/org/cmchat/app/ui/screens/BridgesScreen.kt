package org.cmchat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.cmchat.app.tor.Bridges
import org.cmchat.app.tor.TorService
import org.cmchat.app.tor.TorStatus
import org.cmchat.app.ui.theme.*

/**
 * "Stealth" — pluggable-transport bridges. Honest wording throughout: bridges
 * only hide THAT you use Tor; they do not add message secrecy.
 */
@Composable
fun BridgesScreen(
    currentMode: String,
    currentLines: String,
    onSave: (modeWire: String, lines: String) -> Unit,
    onToggleCover: (Boolean) -> Unit = {},
    onBack: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var mode by remember { mutableStateOf(Bridges.Mode.from(currentMode)) }
    var lines by remember { mutableStateOf(currentLines) }
    val torStatus by TorService.status.collectAsState()
    val coverOn by org.cmchat.app.transport.CoverTraffic.enabled.collectAsState()

    Column(Modifier.fillMaxSize().background(CmBackground)) {
        Box(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("‹ Back", color = CmBlue, fontFamily = Nunito, fontSize = 15.sp,
                modifier = Modifier.align(Alignment.CenterStart).clickable { onBack() })
            Text("Stealth (Bridges)", color = CmText, fontFamily = Nunito, fontSize = 17.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

            Text(
                "Makes your Tor traffic look like normal web traffic, so your network " +
                    "can't tell you use Tor. Can be slower. It does NOT add message secrecy — " +
                    "your messages are already end-to-end encrypted either way.",
                color = CmTextDim, fontFamily = Nunito, fontSize = 12.sp,
            )

            if (torStatus is TorStatus.Failed &&
                (torStatus as TorStatus.Failed).reason == "bridges"
            ) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(CmRed.copy(alpha = 0.15f)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Engine: Failed (bridges) — couldn't connect.",
                        color = CmRed, fontFamily = Nunito, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(CmCard)
                        .clickable { TorService.retry(ctx) }
                        .padding(horizontal = 10.dp, vertical = 5.dp)) {
                        Text("Retry", color = CmBlue, fontFamily = Nunito, fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            ModeRow("Off", "Normal Tor (default).", mode == Bridges.Mode.OFF) { mode = Bridges.Mode.OFF }
            ModeRow("obfs4", "Disguise Tor as random traffic. Most reliable bridge type.",
                mode == Bridges.Mode.OBFS4) { mode = Bridges.Mode.OBFS4 }
            ModeRow("Snowflake", "Route via volunteer browser proxies. Experimental here.",
                mode == Bridges.Mode.SNOWFLAKE) { mode = Bridges.Mode.SNOWFLAKE }

            if (mode != Bridges.Mode.OFF) {
                Text("Your own bridge lines (one per line)", color = CmText, fontFamily = Nunito,
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp))
                Text("Custom bridges are more reliable long-term than the built-in ones. " +
                    "Get them from Tor (bridges.torproject.org) or a friend. Leave blank to " +
                    "use the built-in fallback set.",
                    color = CmTextFaint, fontFamily = Nunito, fontSize = 11.sp)
                OutlinedTextField(
                    value = lines, onValueChange = { lines = it },
                    label = { Text("obfs4 <ip:port> <fingerprint> cert=… iat-mode=0", color = CmTextDim) },
                    singleLine = false, minLines = 3,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = CmCard, unfocusedContainerColor = CmCard,
                        focusedTextColor = CmText, unfocusedTextColor = CmText, cursorColor = CmBlue,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Text("If bridges are on and can't connect, the app will NOT fall back to normal " +
                "Tor — it shows \"Failed (bridges)\" instead, so your cloak is never bypassed.",
                color = CmTextFaint, fontFamily = Nunito, fontSize = 11.sp)

            // Cover traffic (decoy frames), off by default.
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CmCard)
                .clickable { onToggleCover(!coverOn) }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Cover traffic", color = CmText, fontFamily = Nunito, fontSize = 14.sp)
                    Text("Sends decoy traffic to hide when you're really messaging. " +
                        "Uses more battery and data.",
                        color = CmTextFaint, fontFamily = Nunito, fontSize = 11.sp)
                }
                Text(if (coverOn) "On" else "Off", color = if (coverOn) CmGreen else CmTextDim,
                    fontFamily = Nunito, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CmBlue)
                .clickable { onSave(mode.wire, lines) }.padding(vertical = 13.dp),
                contentAlignment = Alignment.Center) {
                Text("Save & restart engine", color = CmBackground, fontFamily = Nunito,
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ModeRow(title: String, hint: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
        .background(if (selected) CmBlue.copy(alpha = 0.18f) else CmCard)
        .clickable { onClick() }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = CmText, fontFamily = Nunito, fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold)
            Text(hint, color = CmTextFaint, fontFamily = Nunito, fontSize = 11.sp)
        }
        Text(if (selected) "●" else "○", color = if (selected) CmBlue else CmTextDim,
            fontFamily = Nunito, fontSize = 16.sp)
    }
}
