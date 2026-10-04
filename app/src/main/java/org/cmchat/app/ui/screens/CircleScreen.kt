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
import androidx.compose.ui.res.stringResource
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
    onMinimise: () -> Unit = {},
    onExit: () -> Unit = {},
    onStayUnlocked: (Boolean) -> Unit = {},
) {
    val torStatus by TorService.status.collectAsState()
    val knocks by MessageService.incomingKnocks.collectAsState()
    val calcOn by ToolsState.calcEnabled.collectAsState()
    val notesOn by ToolsState.notesEnabled.collectAsState()
    val flashOn by ToolsState.flashlightEnabled.collectAsState()
    val invisible by org.cmchat.app.settings.AppSettings.invisibleMode.collectAsState()
    val stayUnlocked by org.cmchat.app.settings.AppSettings.sessionWindowEnabled.collectAsState()
    val online = torStatus is TorStatus.Online
    Column(Modifier.fillMaxSize().background(CmBackground)) {
        // Compact, LEFT-anchored header: logo + engine line on one row (knock "+"
        // on the right), then the "Me:" status + minimise/exit pill, then the
        // stay-unlocked tick. Tight spacing so more of the screen is for chats.
        Column(Modifier.fillMaxWidth().padding(top = 8.dp, start = 16.dp, end = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                CmChatLogo(size = 30, active = online)
                Spacer(Modifier.width(10.dp))
                EngineLine(torStatus)
                Spacer(Modifier.weight(1f))
                // "+" knock, top-right.
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(CmOrange)
                        .clickable { onKnock() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("+", color = Color.White, fontFamily = Nunito,
                        fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // My own status, prefixed "Me:". Tap to toggle Online/Invisible.
                Text("Me:", color = CmTextDim, fontFamily = Nunito, fontSize = 12.sp)
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier.clip(RoundedCornerShape(16.dp))
                        .background(if (invisible) CmCard else CmGreen.copy(alpha = 0.2f))
                        .clickable {
                            val nowInvisible = !invisible
                            org.cmchat.app.settings.AppSettings.invisibleMode.value = nowInvisible
                            if (!nowInvisible) org.cmchat.app.chat.ChatStore.markMissedSeen()
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(if (invisible) "Invisible" else "Online",
                        color = if (invisible) CmTextDim else CmGreen,
                        fontFamily = Nunito, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.weight(1f))
                MinimiseExitPill(onMinimise = onMinimise, onExit = onExit)
            }
            Spacer(Modifier.height(6.dp))
            StayUnlockedTick(stayUnlocked) {
                val now = !stayUnlocked
                org.cmchat.app.settings.AppSettings.sessionWindowEnabled.value = now
                onStayUnlocked(now)
            }
        }
        Spacer(Modifier.height(8.dp))

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
            val bridged = org.cmchat.app.tor.Bridges.isEnabled()
            Text(
                if (bridged) "Connecting through bridges… can take longer than normal."
                else "Connecting to Tor — the first launch can take 1–3 minutes.",
                color = CmTextDim, fontFamily = Nunito, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp),
            )
        }

        if (contacts.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 28.dp),
                contentAlignment = Alignment.Center) {
                Text(stringResource(org.cmchat.app.R.string.empty_circle),
                    color = CmTextFaint, fontFamily = Nunito, fontSize = 14.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        } else LazyColumn(
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
            val context = androidx.compose.ui.platform.LocalContext.current
            val torchOn by org.cmchat.app.tools.Flashlight.on.collectAsState()
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally)) {
                if (calcOn) DockTool("Calculator", "▦", onClick = { onOpenTool("calculator") })
                if (notesOn) DockTool("Notes", "☑", onClick = { onOpenTool("notes") })
                if (flashOn) DockTool(
                    // Flashlight toggles the torch IN PLACE (no screen); stays on
                    // while you keep using the app.
                    if (torchOn) "Torch on" else "Flashlight", "☀",
                    active = torchOn,
                    onClick = { org.cmchat.app.tools.Flashlight.toggle(context) },
                )
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
private fun DockTool(label: String, glyph: String, active: Boolean = false, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Circle, fully transparent fill (the old black square was a bug), thin
        // orange ring, symbol glyph inside. `active` (torch on) fills it.
        Box(
            Modifier.size(52.dp).clip(CircleShape)
                .then(if (active) Modifier.background(CmOrange) else Modifier)
                .border(1.5.dp, CmOrange, CircleShape)
                .clickable { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            Text(glyph, color = if (active) Color.White else CmOrange, fontFamily = Nunito, fontSize = 22.sp)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = CmTextDim, fontFamily = Nunito, fontSize = 11.sp)
    }
}

/** Engine status line under the logo: "Engine: Online / Starting / Offline …". */
@Composable
private fun EngineLine(status: TorStatus) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val (color, label) = when (status) {
        is TorStatus.Online -> CmGreen to "Online"
        is TorStatus.Connecting -> CmOrange to "Connecting ${status.percent}%"
        is TorStatus.Starting -> CmOrange to "Starting"
        is TorStatus.Offline -> CmTextDim to "Offline"
        is TorStatus.Failed -> CmRed to if (status.reason == "bridges") "Failed (bridges)" else "Failed"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text("Engine: $label", color = color, fontFamily = Nunito, fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold)
        // Manual retry once the watchdog has given up.
        if (status is TorStatus.Failed) {
            Spacer(Modifier.width(10.dp))
            Box(Modifier.clip(RoundedCornerShape(10.dp)).background(CmCard)
                .clickable { TorService.retry(ctx) }
                .padding(horizontal = 10.dp, vertical = 3.dp)) {
                Text("Retry", color = CmBlue, fontFamily = Nunito, fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * Minimal segmented pill: [–] minimise | [⏻] exit. Dark aesthetic, 1px border,
 * dim icons, the exit power icon in red. Subtle, not a big button bar.
 */
@Composable
private fun MinimiseExitPill(onMinimise: () -> Unit, onExit: () -> Unit) {
    val border = Color(0xFF2B3340)
    val red = Color(0xFFFF3B3B)
    Row(
        Modifier.clip(RoundedCornerShape(16.dp)).border(1.dp, border, RoundedCornerShape(16.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.clickable { onMinimise() }.padding(horizontal = 20.dp, vertical = 7.dp),
            contentAlignment = Alignment.Center) {
            Text("–", color = CmTextDim, fontFamily = Nunito, fontSize = 18.sp,
                fontWeight = FontWeight.Bold)
        }
        Box(Modifier.width(1.dp).height(22.dp).background(border))
        Box(Modifier.clickable { onExit() }.padding(horizontal = 20.dp, vertical = 7.dp),
            contentAlignment = Alignment.Center) {
            Text("⏻", color = red, fontFamily = Nunito, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** "Stay unlocked" tick — small, centred, symmetric. */
@Composable
private fun StayUnlockedTick(checked: Boolean, onToggle: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onToggle() }
            .padding(horizontal = 8.dp, vertical = 4.dp)) {
        Box(Modifier.size(16.dp).clip(RoundedCornerShape(4.dp))
            .background(if (checked) CmGreen else CmCard)
            .border(1.dp, if (checked) CmGreen else CmTextFaint, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center) {
            if (checked) Text("✓", color = CmBackground, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(7.dp))
        Text("Stay unlocked", color = CmTextDim, fontFamily = Nunito, fontSize = 12.sp)
    }
}
