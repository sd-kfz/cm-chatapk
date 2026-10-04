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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.cmchat.app.R
import org.cmchat.app.tools.ToolsState
import org.cmchat.app.tor.TorService
import org.cmchat.app.tor.TorStatus
import org.cmchat.app.transport.MessageService
import org.cmchat.app.ui.theme.*

data class Contact(
    val name: String,
    val color: Color,
    val unread: Boolean,
    val cmId: String? = null,
    val lastSeenMs: Long? = null,
    val missed: Boolean = false,
)

// Brand accents for the wordmark + status line (kept local to this screen).
private val WordBlue = Color(0xFF35C6F2)
private val WordRed = Color(0xFFFF3B3B)
private val Teal = Color(0xFF35D6A6)
private val Cyan = Color(0xFF35C6F2)
private val Purple = Color(0xFF8B5CF6)
private val Grey = Color(0xFF8B94A3)
private val Frame = Color(0xFF2B3340)

@Composable
fun CircleScreen(
    contacts: List<Contact>,
    onOpenChat: (Contact) -> Unit,
    onOpenSettings: () -> Unit,
    onKnock: () -> Unit = {},
    onOpenTool: (String) -> Unit = {},
    onMinimise: () -> Unit = {},
    onExit: () -> Unit = {},
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val torStatus by TorService.status.collectAsState()
    val knocks by MessageService.incomingKnocks.collectAsState()
    val calcOn by ToolsState.calcEnabled.collectAsState()
    val notesOn by ToolsState.notesEnabled.collectAsState()
    val flashOn by ToolsState.flashlightEnabled.collectAsState()
    val invisible by org.cmchat.app.settings.AppSettings.invisibleMode.collectAsState()
    val versionMismatch by MessageService.versionMismatch.collectAsState()
    val online = torStatus is TorStatus.Online

    Box(Modifier.fillMaxSize().background(CmBackground)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {

            // ---- top bar: wordmark (letters only, left) .......... minimise / exit
            // No logo image and no settings gear here — Settings lives under the "+".
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Wordmark(online)
                Spacer(Modifier.weight(1f))
                IconBtn(onClick = onMinimise) { MinimiseIcon() }
                Spacer(Modifier.width(8.dp))
                IconBtn(onClick = onExit) { PowerIcon() }
            }

            // ---- thin status line: Engine (left) .......... Me (right)
            Spacer(Modifier.height(10.dp))
            StatusLine(online, torStatus, invisible) {
                val nowInvisible = !invisible
                org.cmchat.app.settings.AppSettings.invisibleMode.value = nowInvisible
                if (!nowInvisible) org.cmchat.app.chat.ChatStore.markMissedSeen()
            }
            Spacer(Modifier.height(10.dp))

            if (versionMismatch) {
                Text("A contact is on a different version — update both apps to the same version.",
                    color = WordRed, fontFamily = Nunito, fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(WordRed.copy(alpha = 0.12f)).padding(10.dp),
                    textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
            }

            if (torStatus is TorStatus.Starting || torStatus is TorStatus.Connecting) {
                val bridged = org.cmchat.app.tor.Bridges.isEnabled()
                Text(
                    if (bridged) "Connecting through bridges… can take longer than normal."
                    else "Connecting to Tor — the first launch can take 1–3 minutes.",
                    color = CmTextDim, fontFamily = Nunito, fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp), textAlign = TextAlign.Center,
                )
            }

            // Incoming knock requests (only when present).
            for (k in knocks) {
                Column(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(12.dp)).background(CmCard).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Knock from ${k.displayName}", color = CmText, fontFamily = Nunito,
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(Teal)
                            .clickable { MessageService.acceptKnock(k) }.padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center) {
                            Text("Accept", color = CmBackground, fontFamily = Nunito, fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold)
                        }
                        Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(CmBackground)
                            .border(1.dp, Frame, RoundedCornerShape(10.dp))
                            .clickable { MessageService.declineKnock(k) }.padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center) {
                            Text("Decline", color = CmTextDim, fontFamily = Nunito, fontSize = 14.sp)
                        }
                    }
                }
            }

            // ---- contact list
            if (contacts.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.empty_circle), color = CmTextFaint,
                        fontFamily = Nunito, fontSize = 14.sp, textAlign = TextAlign.Center)
                }
            } else LazyColumn(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 84.dp),  // clear the FAB
            ) {
                items(contacts) { c -> ContactRow(c, online, onOpenChat) }
            }

            // Tools dock (only when a tool is enabled) — behaviour unchanged.
            if (calcOn || notesOn || flashOn) {
                val torchOn by org.cmchat.app.tools.Flashlight.on.collectAsState()
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally)) {
                    if (calcOn) DockTool("Calculator", "▦") { onOpenTool("calculator") }
                    if (notesOn) DockTool("Notes", "☑") { onOpenTool("notes") }
                    if (flashOn) DockTool(if (torchOn) "Torch on" else "Flashlight", "☀",
                        active = torchOn) { org.cmchat.app.tools.Flashlight.toggle(ctx) }
                }
            }
        }

        // ---- purple "+" FAB, bottom-right — opens the actions menu (Add contact,
        // Settings). Settings is NOT in the top bar; it lives here under the "+".
        var menuOpen by remember { mutableStateOf(false) }
        Box(Modifier.align(Alignment.BottomEnd).padding(20.dp)) {
            Box(
                Modifier.size(56.dp).clip(CircleShape).background(Purple)
                    .clickable { menuOpen = true },
                contentAlignment = Alignment.Center,
            ) {
                Text("+", color = Color.White, fontFamily = Nunito, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            }
            androidx.compose.material3.DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Add contact", fontFamily = Nunito) },
                    onClick = { menuOpen = false; onKnock() },
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Settings", fontFamily = Nunito) },
                    onClick = { menuOpen = false; onOpenSettings() },
                )
            }
        }
    }
}

// ---- pieces ----------------------------------------------------------------

@Composable
private fun Wordmark(online: Boolean) {
    if (!online) {
        Text("CM-Chat", color = Grey, fontFamily = Nunito, fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp)
        return
    }
    fun glow(c: Color) = TextStyle(
        color = c, fontFamily = Nunito, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold,
        letterSpacing = 0.5.sp,
        shadow = Shadow(color = c.copy(alpha = 0.9f), offset = Offset(0f, 0f), blurRadius = 3f),
    )
    Row {
        Text("CM-C", style = glow(WordBlue))
        Text("hat", style = glow(WordRed))
    }
}

/** A ≥40dp circular touch target wrapping a vector icon (not a font glyph). */
@Composable
private fun IconBtn(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.size(40.dp).clip(CircleShape).clickable { onClick() }, contentAlignment = Alignment.Center) {
        content()
    }
}

/** MINIMISE — a single horizontal white line (SVG "M5 12h14"). */
@Composable
private fun MinimiseIcon() {
    androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
        val s = size.minDimension / 24f
        drawLine(
            color = Color.White,
            start = Offset(5f * s, 12f * s),
            end = Offset(19f * s, 12f * s),
            strokeWidth = 2.2f * s,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
    }
}

/** EXIT — a red power/off symbol: open ring + vertical stem through the top. */
@Composable
private fun PowerIcon() {
    androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
        val s = size.minDimension / 24f
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
            width = 2.2f * s, cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        // Ring: a circle open at the top (gap centred on the vertical stem).
        val r = 7.5f * s
        val cx = 12f * s; val cy = 12.5f * s
        drawArc(
            color = WordRed,
            startAngle = -60f, sweepAngle = 300f, useCenter = false,
            topLeft = Offset(cx - r, cy - r),
            size = androidx.compose.ui.geometry.Size(2 * r, 2 * r),
            style = stroke,
        )
        // Vertical stem (SVG "M12 3.5v8").
        drawLine(
            color = WordRed,
            start = Offset(12f * s, 3.5f * s),
            end = Offset(12f * s, 11.5f * s),
            strokeWidth = 2.2f * s,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
    }
}

@Composable
private fun StatusLine(online: Boolean, status: TorStatus, invisible: Boolean, onToggleMe: () -> Unit) {
    val engineLabel = when (status) {
        is TorStatus.Online -> "Online"
        is TorStatus.Connecting -> "Connecting ${status.percent}%"
        is TorStatus.Starting -> "Starting"
        is TorStatus.Offline -> "Offline"
        is TorStatus.Failed -> if (status.reason == "bridges") "Failed (bridges)" else "Failed"
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Engine: ", color = CmTextDim, fontFamily = Nunito, fontSize = 13.sp)
            Text(engineLabel, color = if (online) Teal else Grey.copy(alpha = 0.7f),
                fontFamily = Nunito, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { onToggleMe() }
                .padding(horizontal = 6.dp, vertical = 4.dp)) {
            Text("Me: ", color = CmTextDim, fontFamily = Nunito, fontSize = 13.sp)
            Text(if (invisible) "Invisible" else "Online",
                color = if (!online) Grey else if (invisible) Cyan else Teal,
                fontFamily = Nunito, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ContactRow(c: Contact, online: Boolean, onOpenChat: (Contact) -> Unit) {
    val recent = c.lastSeenMs != null && System.currentTimeMillis() - c.lastSeenMs <= 24 * 3_600_000L
    @OptIn(ExperimentalFoundationApi::class)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CmCard)
            .combinedClickable(
                onClick = { onOpenChat(c) },
                onLongClick = { c.cmId?.let { MessageService.sendBuzz(it) } },
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(c.color), contentAlignment = Alignment.Center) {
            Text(c.name.take(1).uppercase(), color = CmBackground, fontFamily = Nunito,
                fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(c.name, color = CmText, fontFamily = Nunito, fontSize = 16.sp)
            when {
                c.missed -> Text("Missed Message", color = WordRed, fontFamily = Nunito, fontSize = 11.sp,
                    fontStyle = FontStyle.Italic)
                recent -> Text("last seen recently", color = CmTextFaint, fontFamily = Nunito, fontSize = 11.sp)
            }
        }
        // Status dot: teal = seen recently (online-ish), dim = otherwise; orange
        // marker when there's an unread/missed message.
        val dot = when {
            c.unread || c.missed -> CmOrange
            recent && online -> Teal
            else -> Frame
        }
        Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
    }
}

@Composable
private fun DockTool(label: String, glyph: String, active: Boolean = false, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(48.dp).clip(CircleShape)
                .then(if (active) Modifier.background(CmOrange) else Modifier)
                .border(1.5.dp, CmOrange, CircleShape)
                .clickable { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            Text(glyph, color = if (active) Color.White else CmOrange, fontFamily = Nunito, fontSize = 20.sp)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = CmTextDim, fontFamily = Nunito, fontSize = 11.sp)
    }
}
