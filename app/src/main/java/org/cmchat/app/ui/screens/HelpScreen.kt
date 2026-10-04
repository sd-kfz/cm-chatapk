package org.cmchat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.cmchat.app.R
import org.cmchat.app.ui.theme.*

/**
 * Plain-language "How to use (A–Z)" help for new users. Short, concrete entries
 * in everyday words — no jargon, no error codes.
 */
@Composable
fun HelpScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(CmBackground)) {
        Box(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(stringResource(R.string.back), color = CmBlue, fontFamily = Nunito, fontSize = 15.sp,
                modifier = Modifier.align(Alignment.CenterStart).clickable { onBack() })
            Text(stringResource(R.string.help_title), color = CmText, fontFamily = Nunito, fontSize = 17.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Entry("Adding a friend", "Tap the orange + on the main screen. Show your QR code or scan theirs. " +
                "You each add the other once. Both of you need the Engine Online for the first hello to arrive.")
            Entry("Bridges / Stealth", "In Settings → Stealth, turn on bridges if your network blocks Tor or you " +
                "don't want it to see that you use Tor. It can be slower. It does not change message secrecy.")
            Entry("Buzz", "A quick nudge to get someone's attention. No text, just a shake + notification.")
            Entry("Connection test", "Settings → Connection test shows, step by step, how reaching a contact goes. " +
                "Use it with a friend to see exactly where a connection succeeds or fails.")
            Entry("Cover traffic", "In Settings → Stealth. Sends decoy traffic so an observer can't tell when you " +
                "really message. Uses more battery and data. Off by default.")
            Entry("Engine / Online / Invisible", "The Engine connects you over Tor. Online = friends can reach you. " +
                "Invisible = you still receive but look offline. You always start Invisible.")
            Entry("Exit", "Stops the Engine, clears everything from memory, and logs you out. Opening again needs your PIN.")
            Entry("Invisible mode", "Receive messages while appearing offline to everyone. Messages are held and " +
                "shown when you go Online. Senders can't tell.")
            Entry("Messages vanish", "Each message can self-destruct after it's seen — pick a timer under \"Single " +
                "Message\" in a chat. There are no delivery or read receipts, ever.")
            Entry("Erase a chat (remote burn)", "\"Erase\" clears the conversation on your phone instantly and asks " +
                "the other phone to erase it too. The remote wipe is best-effort: it only works if they're online on " +
                "the real app and can't be guaranteed. The decoy chat erases ALL conversations at once the same way.")
            Entry("PIN (very important)", "Your PIN is the only key. There is NO reset and NO recovery — forget it " +
                "and your data is gone. Keep it safe.")
            Entry("QR codes", "Everything is on-device. Showing or scanning a QR never goes through another app, and " +
                "the camera is only used while you're scanning.")
            Entry("Reconnecting", "If your WiFi/data changes or signal drops, the Engine reconnects by itself and " +
                "shows its progress. Give it a moment on a new network.")
            Entry("Shredder (panic wipe)", "Type your PIN BACKWARDS at the lock screen to instantly and silently " +
                "erase everything back to a fresh install. No confirmation, no trace.")
            Entry("Wipe everything", "Settings → Wipe Everything deletes all app data on this phone and asks Android " +
                "to uninstall. Use it when you want nothing left.")
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun Entry(title: String, body: String) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CmCard).padding(14.dp)) {
        Text(title, color = CmText, fontFamily = Nunito, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(body, color = CmTextDim, fontFamily = Nunito, fontSize = 13.sp, lineHeight = 19.sp)
    }
}
