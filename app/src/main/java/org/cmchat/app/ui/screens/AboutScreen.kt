package org.cmchat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.cmchat.app.ui.theme.*

/** About / Version: the serious CMC safety welcome. */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(CmBackground)) {
        Box(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("‹ Back", color = CmBlue, fontFamily = Nunito, fontSize = 15.sp,
                modifier = Modifier.align(Alignment.CenterStart).clickable { onBack() })
            Text("About", color = CmText, fontFamily = Nunito, fontSize = 17.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("CM-Chat v0.1", color = CmText, fontFamily = Nunito, fontSize = 18.sp,
                fontWeight = FontWeight.Bold)
            Text(WELCOME, color = CmTextDim, fontFamily = Nunito, fontSize = 13.sp)
            Text(
                "⚠ v0.1 is unaudited test software. It has not had a security review " +
                    "and is for trying things out with friends — not for real-life " +
                    "safety or high-risk use.",
                color = CmRed, fontFamily = Nunito, fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

private const val WELCOME = """Please read this before you rely on CM-Chat.

1. CM-Chat is peer-to-peer over Tor. There is no server, no account, and no one who can recover your data or your passcode for you. If you forget your passcode, your data is gone — by design.

2. Both people must be online at the same time to exchange messages. There is no mailbox holding messages for you.

3. Messages live only in memory. They are erased when the app is closed, wiped, or the process ends. Only the encrypted vault (your identity, friends and settings) is ever written to disk.

4. Your safety depends on your device. If your phone is unlocked, compromised, or taken while open, CM-Chat cannot protect you. Use the screen lock, Cerberus idle-wipe, the Kill Timer, and the reverse-PIN shredder.

5. Metadata still matters. Tor hides the network path, but who you talk to, when, and how often can still be inferred by someone watching you in person or holding your device. Invisible mode, buzz throttling and coarse last-seen reduce — not eliminate — this.

6. This is test software. Do not use it where being discovered would put you or anyone else at risk. Treat every build as experimental until a real security audit says otherwise.

Stay careful. Trust your own judgement over any app."""
