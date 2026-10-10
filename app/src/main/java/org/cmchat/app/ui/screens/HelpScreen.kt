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
 * Plain-language "How to use" guide, in the order a new user needs it: setup →
 * adding friends → messaging → presence → privacy & panic → diagnostics.
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
            .padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

            Section("1 · Setting up")
            Entry("Your PIN (very important)", "It's the only key — there is NO reset and NO recovery. " +
                "Use 4 to 56 characters: numbers, letters or symbols; 8 or more is much stronger. " +
                "It can't read the same backwards, because your PIN typed backwards is the Shredder.")
            Entry("The Engine", "Connects you over the Tor network. The first start can take 1–3 minutes. " +
                "If your Wi-Fi or mobile data changes, it reconnects by itself.")
            Entry("Your nickname and ID", "Your nickname is what friends see when you add them. Your address " +
                "(CMC-ID) and QR code are in Settings → My identity.")

            Section("2 · Adding friends")
            Entry("Add a friend", "Tap the cyan + and choose Add friend. Scan their QR (or paste their " +
                "CMC-ID), pick a nickname and tap Add friend. They show as \"Waiting for them to accept\" until " +
                "they do — tap Cancel there to take the request back. Only ONE of you needs to add the other. " +
                "It keeps trying quietly in the background (and again each time your Engine comes online), " +
                "so it lands once both Engines are online.")
            Entry("Knocks", "A friend request arrives as a \"Knock\" card on the Friends screen — Accept or " +
                "Decline. A first knock gets through even while you're Invisible. A declined person can't " +
                "knock again for an hour.")
            Entry("QR codes", "Everything happens on the phone. The camera is only used while you're scanning.")

            Section("3 · Messaging")
            Entry("Sending", "Every message gets brand-new one-time keys, so even someone who later steals " +
                "the phone and its keys can't read messages they recorded earlier (forward secrecy). There are " +
                "never delivery or read receipts. If your friend is offline, the message waits quietly and goes " +
                "out when they're back (while your app is running) — nothing on screen shows whether they're online. " +
                "Each message shows the time it was sent or received (like 4:05 PM).")
            Entry("Disappear", "Under \"Disappear\" pick: Off, Single Message (view once — gone the moment " +
                "it's read), or 30s / 5m / 30m / 1h after it's seen. The small timer pill next to the Cerberus " +
                "eye shows the timer your next message gets. A timer for ALL messages is in Settings → Chats.")
            Entry("Team Clock", "A shared clock for one chat — handy for agreeing on a time. Tap \"Set Team " +
                "Clock\" in the chat and set the time like a phone alarm; both of you see it tick, live.")
            Entry("Buzz", "A nudge with no text: long-press a friend, or ⚡ Buzz in the chat. Their screen " +
                "shakes, a notification pops up, and a BLUE dot stays on your name until they open your chat. " +
                "It can reach them even when their app is closed (if they allow it).")
            Entry("The red X in a chat", "Wipe conversation: erases it on BOTH phones (theirs as soon as it " +
                "reaches them). Delete friend: removes them from your phone only. Terminate: removes them AND " +
                "removes you from their list when it reaches their phone (kept and retried until it does). " +
                "Each one asks you twice.")

            Section("4 · Presence")
            Entry("Online and Invisible", "Every time the app starts (and after Exit) you're Invisible. Tap " +
                "\"Me:\" on the Friends screen to switch — only your tap changes it; minimising keeps your choice. " +
                "Invisible = you still receive, but look offline: no notifications for messages (a Buzz or a " +
                "friend request still notifies), and they wait as \"Missed Message\". Go Online and they're " +
                "delivered as new messages. Senders can't tell.")
            Entry("New messages", "A blue dot on a friend means a new message is waiting. It goes away when " +
                "you open that chat while Online. There is no \"online\" dot — nobody's status is shown.")
            Entry("Last seen", "Friends only ever see \"last seen recently\" — for a day after you last sent " +
                "them something, even if you then go Invisible or close the app.")
            Entry("Minimise or Exit", "The white line minimises: the Engine keeps running. The red power " +
                "symbol exits: it stops everything, clears memory (your keys too) and logs you out.")

            Section("5 · Privacy and panic buttons")
            Entry("Privacy & Safety", "Sensitive settings (server, stealth, guardians, PIN, wipe) sit " +
                "behind a separate Privacy PIN (digits, on the number pad). It's mandatory: you can change it, " +
                "but never turn it off.")
            Entry("Cerberus", "Idle auto-wipe: if the app goes untouched for the time you choose (15 min–3 h), " +
                "it clears memory, stops the Engine and closes. Your vault stays.")
            Entry("Kill Timer", "Set it like an alarm (the time it goes off); then it does the same as Cerberus.")
            Entry("Decoy chat", "A fake friend. Tapping it instantly wipes your chats from memory, moves you " +
                "to a new address and locks the app. Each friend sees \"Decoy chat triggered — chat erased.\" " +
                "in your chat; their copy is erased once they leave it.")
            Entry("Shredder", "Type your PIN BACKWARDS at the lock screen: everything is silently erased " +
                "and a small red \"Error. Please restart the app.\" appears under \"Welcome back\". Close and " +
                "reopen the app and it starts fresh (no reinstall needed).")
            Entry("Wipe Everything", "Settings → Privacy & Safety. Erases all app data, then opens Android's " +
                "uninstall prompt so the app itself can be removed too.")
            Entry("Bridges and cover traffic", "Bridges hide from your network that you use Tor (slower). " +
                "Cover traffic sends decoy traffic so nobody can tell when you really message (more battery).")

            Section("6 · Diagnostics")
            Entry("Connection test", "Settings → Connection test runs a Link Test to one friend and shows " +
                "every step live, on both phones — the best way to see where a connection fails.")
            Entry("\"Update both apps\"", "If you see this, you and a friend run different app versions. " +
                "Install the same version on both phones.")
            Entry("Diagnostics, self-test, integrity", "Diagnostics shows what the app is doing; the self-test " +
                "checks its own defences; Verify App Integrity shows the app's signing fingerprint to compare " +
                "with a friend.")
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, color = CmBlue, fontFamily = Nunito, fontSize = 13.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp, start = 4.dp))
}

@Composable
private fun Entry(title: String, body: String) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CmCard).padding(14.dp)) {
        Text(title, color = CmText, fontFamily = Nunito, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(body, color = CmTextDim, fontFamily = Nunito, fontSize = 13.sp, lineHeight = 19.sp)
    }
}
