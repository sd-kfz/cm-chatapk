package org.cmchat.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.cmchat.app.tools.ToolsState
import org.cmchat.app.tor.TorService
import org.cmchat.app.tor.TorStatus
import org.cmchat.app.transport.MessageService
import org.cmchat.app.ui.components.CmChatLogo
import org.cmchat.app.ui.theme.*

data class Contact(val name: String, val color: Color, val unread: Boolean, val cmId: String? = null)

/** Sample Circle shown only when the vault has no contacts yet. */
val sampleCircle = listOf(
    Contact("Nightingale", CmBlue, true),
    Contact("Quartz", CmOrange, false),
    Contact("Driftwood", CmGreen, false),
    Contact("Cipher", CmTextDim, false),
    Contact("Halcyon", CmRed, false),
)

@Composable
fun CircleScreen(
    contacts: List<Contact>,
    onOpenChat: (Contact) -> Unit,
    onOpenSettings: () -> Unit,
    onKnock: () -> Unit = {},
    onOpenTool: (String) -> Unit = {},
) {
    val torStatus by TorService.status.collectAsState()
    val knocks by MessageService.incomingKnocks.collectAsState()
    val calcOn by ToolsState.calcEnabled.collectAsState()
    val notesOn by ToolsState.notesEnabled.collectAsState()
    val flashOn by ToolsState.flashlightEnabled.collectAsState()
    val invisible by org.cmchat.app.settings.AppSettings.invisibleMode.collectAsState()
    Column(Modifier.fillMaxSize().background(CmBackground)) {
        Row(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Bigger logo, smaller status text.
            CmChatLogo(size = 32)
            Spacer(Modifier.width(10.dp))
            TorIndicator(torStatus)
            Spacer(Modifier.weight(1f))
            // My status: Online / Invisible. Tap to toggle; going Online starts
            // self-timers on any messages that arrived while Invisible.
            Box(
                Modifier.clip(RoundedCornerShape(16.dp))
                    .background(if (invisible) CmCard else CmGreen.copy(alpha = 0.2f))
                    .clickable {
                        val nowInvisible = !invisible
                        org.cmchat.app.settings.AppSettings.invisibleMode.value = nowInvisible
                        if (!nowInvisible) org.cmchat.app.chat.ChatStore.markMissedSeen()
                    }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Text(if (invisible) "Invisible" else "Online",
                    color = if (invisible) CmTextDim else CmGreen,
                    fontFamily = Nunito, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(10.dp))
            // Suggestive tappable orange "+" (the "Knock" word/bubble is gone).
            Box(
                Modifier.size(38.dp).clip(CircleShape).background(CmOrange)
                    .clickable { onKnock() },
                contentAlignment = Alignment.Center,
            ) {
                Text("+", color = Color.White, fontFamily = Nunito,
                    fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }

        for (k in knocks) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(16.dp)).background(CmCard).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Knock from ${k.displayName}", color = CmText, fontFamily = Nunito,
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(CmGreen)
                        .clickable { MessageService.acceptKnock(k) }.padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center) {
                        Text("Accept", color = CmBackground, fontFamily = Nunito, fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold)
                    }
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(CmBackground)
                        .clickable { MessageService.declineKnock(k) }.padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center) {
                        Text("Decline", color = CmTextDim, fontFamily = Nunito, fontSize = 14.sp)
                    }
                }
            }
        }
        if (torStatus is TorStatus.Starting || torStatus is TorStatus.Connecting) {
            Text(
                "Connecting to Tor — the first launch can take 1–3 minutes.",
                color = CmTextDim, fontFamily = Nunito, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp),
            )
        }

        LazyColumn(
            Modifier.weight(1f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(contacts) { c ->
                @OptIn(ExperimentalFoundationApi::class)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(CmCard).combinedClickable(
                            onClick = { onOpenChat(c) },
                            // Long-press to Buzz (fire-and-forget, cooldown-limited).
                            onLongClick = { c.cmId?.let { MessageService.sendBuzz(it) } },
                        )
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(c.color))
                    Spacer(Modifier.width(12.dp))
                    Text(c.name, color = CmText, fontFamily = Nunito, fontSize = 16.sp,
                        modifier = Modifier.weight(1f))
                    if (c.unread)
                        Box(Modifier.size(10.dp).clip(CircleShape).background(CmOrange))
                }
            }
        }

        // Active tools: transparent circles with a symbol glyph, centred and
        // evenly spaced regardless of count.
        if (calcOn || notesOn || flashOn) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally)) {
                if (calcOn) DockTool("Calculator", "calculator", "▦", onOpenTool)
                if (notesOn) DockTool("Notes", "notes", "☑", onOpenTool)
                if (flashOn) DockTool("Flashlight", "flashlight", "☀", onOpenTool)
            }
        }

        Box(
            Modifier.fillMaxWidth().clickable { onOpenSettings() }.padding(18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Settings", color = CmText, fontFamily = Nunito, fontSize = 15.sp)
        }
    }
}

@Composable
private fun DockTool(label: String, key: String, glyph: String, onOpen: (String) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Circle, fully transparent fill (the old black square was a bug), thin
        // orange ring, symbol glyph inside.
        Box(
            Modifier.size(52.dp).clip(CircleShape)
                .border(1.5.dp, CmOrange, CircleShape)
                .clickable { onOpen(key) },
            contentAlignment = Alignment.Center,
        ) {
            Text(glyph, color = CmOrange, fontFamily = Nunito, fontSize = 22.sp)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = CmTextDim, fontFamily = Nunito, fontSize = 11.sp)
    }
}

@Composable
private fun TorIndicator(status: TorStatus) {
    val (color, label) = when (status) {
        is TorStatus.Online -> CmGreen to "Online"
        is TorStatus.Connecting -> CmOrange to "Connecting ${status.percent}%"
        is TorStatus.Starting -> CmOrange to "Connecting"
        is TorStatus.Offline -> CmTextDim to "Offline"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text(label, color = color, fontFamily = Nunito, fontSize = 11.sp)
    }
}
