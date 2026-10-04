package org.cmchat.app.ui.screens

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.cmchat.app.tools.ToolsState
import org.cmchat.app.tor.TorService
import org.cmchat.app.tor.TorStatus
import org.cmchat.app.transport.MessageService
import org.cmchat.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Contact(
    val name: String,
    val color: Color,
    val unread: Boolean,
    val cmId: String? = null,
    val lastSeenMs: Long? = null,
    val missed: Boolean = false,
)

// ---- HUD palette (matches the server-panel look) --------------------------
private val HudWordBlue = Color(0xFF35C6F2)
private val HudWordRed = Color(0xFFFF3B3B)
private val HudCyan = Color(0xFF4FD4FF)
private val HudTeal = Color(0xFF35D6A6)
private val HudPurpleA = Color(0xFF8B5CF6)
private val HudPurpleB = Color(0xFF6D28D9)
private val HudLed = Color(0xFFFF6A44)
private val HudGrey = Color(0xFF8B94A3)
private val HudRed = Color(0xFFFF3B3B)
private val HudFrame = Color(0xFF2B3340)

@Composable
fun CircleScreen(
    contacts: List<Contact>,
    myTag: String,
    onOpenChat: (Contact) -> Unit,
    onOpenSettings: () -> Unit,
    onKnock: () -> Unit = {},
    onOpenTool: (String) -> Unit = {},
    onMinimise: () -> Unit = {},
    onExit: () -> Unit = {},
    onStayUnlocked: (Boolean) -> Unit = {},
) {
    val ctx = LocalContext.current
    val torStatus by TorService.status.collectAsState()
    val knocks by MessageService.incomingKnocks.collectAsState()
    val calcOn by ToolsState.calcEnabled.collectAsState()
    val notesOn by ToolsState.notesEnabled.collectAsState()
    val flashOn by ToolsState.flashlightEnabled.collectAsState()
    val invisible by org.cmchat.app.settings.AppSettings.invisibleMode.collectAsState()
    val stayUnlocked by org.cmchat.app.settings.AppSettings.sessionWindowEnabled.collectAsState()
    val online = torStatus is TorStatus.Online

    // Ticking LED clock.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { nowMs = System.currentTimeMillis(); delay(1000) } }

    Box(Modifier.fillMaxSize().background(CmBackground).hudGridAndRadar()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {

            Spacer(Modifier.height(10.dp))
            // Row 1: reticle + wordmark .......... LED clock.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ReticleDot()
                Spacer(Modifier.width(10.dp))
                HudWordmark(online)
                Spacer(Modifier.weight(1f))
                LedClock(online, nowMs)
            }

            Spacer(Modifier.height(6.dp))
            // Row 2: "CONTROL PANEL · tag" .......... minimise/exit pill.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "CONTROL PANEL · ${myTag.uppercase()}",
                    color = if (online) HudCyan.copy(alpha = 0.75f) else HudGrey,
                    fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 1.sp,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.weight(1f))
                MinimiseExitPill(onMinimise = onMinimise, onExit = onExit)
            }

            // Centred status block — the focal element.
            Spacer(Modifier.height(22.dp))
            CentredStatus(online, torStatus, invisible) {
                val nowInvisible = !invisible
                org.cmchat.app.settings.AppSettings.invisibleMode.value = nowInvisible
                if (!nowInvisible) org.cmchat.app.chat.ChatStore.markMissedSeen()
            }
            Spacer(Modifier.height(22.dp))

            if (torStatus is TorStatus.Starting || torStatus is TorStatus.Connecting) {
                val bridged = org.cmchat.app.tor.Bridges.isEnabled()
                Text(
                    if (bridged) "Connecting through bridges… can take longer than normal."
                    else "Connecting to Tor — the first launch can take 1–3 minutes.",
                    color = CmTextDim, fontFamily = Nunito, fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    textAlign = TextAlign.Center,
                )
            }

            // Incoming knock requests.
            for (k in knocks) {
                Column(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(12.dp)).background(CmCard)
                        .cornerTicks(HudCyan.copy(alpha = 0.5f)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Knock from ${k.displayName}", color = CmText, fontFamily = Nunito,
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(HudTeal)
                            .clickable { MessageService.acceptKnock(k) }.padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center) {
                            Text("Accept", color = CmBackground, fontFamily = Nunito, fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold)
                        }
                        Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(CmBackground)
                            .border(1.dp, HudFrame, RoundedCornerShape(10.dp))
                            .clickable { MessageService.declineKnock(k) }.padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center) {
                            Text("Decline", color = CmTextDim, fontFamily = Nunito, fontSize = 14.sp)
                        }
                    }
                }
            }

            // Section header: 01 | ● CONTACTS .......... N recent.
            val recent = contacts.count { it.lastSeenMs != null && nowMs - it.lastSeenMs!! <= 24 * 3_600_000L }
            SectionHeader(online, "01", "CONTACTS", "$recent recent")
            Spacer(Modifier.height(6.dp))

            if (contacts.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center) {
                    Text(stringResource(org.cmchat.app.R.string.empty_circle),
                        color = CmTextFaint, fontFamily = Nunito, fontSize = 14.sp,
                        textAlign = TextAlign.Center)
                }
            } else LazyColumn(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(contacts) { c -> HudContactRow(c, online, nowMs, onOpenChat) }
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

            // Stay-unlocked tick (left) + Settings (right).
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                StayUnlockedTick(stayUnlocked) {
                    val now = !stayUnlocked
                    org.cmchat.app.settings.AppSettings.sessionWindowEnabled.value = now
                    onStayUnlocked(now)
                }
                Spacer(Modifier.weight(1f))
                Text("Settings", color = HudCyan.copy(alpha = 0.8f), fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp, letterSpacing = 1.sp,
                    modifier = Modifier.clickable { onOpenSettings() }.padding(8.dp))
            }

            // Bottom button: online = "+ Add contact"; offline = "Reconnect".
            PurpleButton(if (online) "+  Add contact" else "Reconnect") {
                if (online) onKnock() else TorService.retry(ctx)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

// ---- pieces ----------------------------------------------------------------

@Composable
private fun HudWordmark(online: Boolean) {
    if (!online) {
        Text("CM-Chat", color = HudGrey, fontFamily = Nunito, fontSize = 22.sp,
            fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp)
        return
    }
    fun glow(c: Color) = TextStyle(
        color = c, fontFamily = Nunito, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold,
        letterSpacing = 0.5.sp,
        shadow = Shadow(color = c.copy(alpha = 0.9f), offset = Offset(0f, 0f), blurRadius = 3f),
    )
    Row {
        Text("CM-C", style = glow(HudWordBlue))
        Text("hat", style = glow(HudWordRed))
    }
}

@Composable
private fun LedClock(online: Boolean, nowMs: Long) {
    val time = if (online) SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(nowMs)) else "--:--:--"
    val date = if (online) SimpleDateFormat("EEE dd MMM", Locale.US).format(Date(nowMs)).uppercase() else "----"
    val c = if (online) HudLed else HudLed.copy(alpha = 0.35f)
    Column(horizontalAlignment = Alignment.End) {
        Text(time, color = c, fontFamily = FontFamily.Monospace, fontSize = 15.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text(date, color = c.copy(alpha = 0.7f), fontFamily = FontFamily.Monospace, fontSize = 9.sp,
            letterSpacing = 1.sp)
    }
}

@Composable
private fun ReticleDot() {
    Canvas(Modifier.size(14.dp)) {
        val r = size.minDimension / 2
        drawCircle(HudPurpleA, radius = r, style = Stroke(width = 1.5.dp.toPx()))
        drawCircle(HudPurpleA, radius = r / 3f)
    }
}

@Composable
private fun CentredStatus(online: Boolean, status: TorStatus, invisible: Boolean, onToggleMe: () -> Unit) {
    val engineLabel = when (status) {
        is TorStatus.Online -> "Online"
        is TorStatus.Connecting -> "Connecting ${status.percent}%"
        is TorStatus.Starting -> "Starting"
        is TorStatus.Offline -> "Offline"
        is TorStatus.Failed -> if (status.reason == "bridges") "Failed (bridges)" else "Failed"
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .cornerTicks(if (online) HudCyan.copy(alpha = 0.6f) else HudFrame)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Engine: ", color = CmTextDim, fontFamily = Nunito, fontSize = 13.sp)
            Text(engineLabel,
                color = if (online) HudTeal else HudGrey.copy(alpha = 0.7f),
                fontFamily = Nunito, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(8.dp))
        // "Me: Invisible" — focal, tappable to toggle.
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { onToggleMe() }
                .padding(horizontal = 12.dp, vertical = 4.dp)) {
            Text("Me: ", color = CmTextDim, fontFamily = Nunito, fontSize = 18.sp)
            Text(if (invisible) "Invisible" else "Online",
                color = when {
                    !online -> HudGrey
                    invisible -> HudCyan
                    else -> HudTeal
                },
                fontFamily = Nunito, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SectionHeader(online: Boolean, number: String, label: String, meta: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(number, color = CmTextFaint, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        Spacer(Modifier.width(8.dp))
        Text("|", color = CmTextFaint, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        Spacer(Modifier.width(8.dp))
        GlowDot(if (online) HudTeal else HudGrey, 7.dp, glow = online)
        Spacer(Modifier.width(6.dp))
        Text(label, color = if (online) HudCyan else HudGrey, fontFamily = FontFamily.Monospace,
            fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
            modifier = Modifier.weight(1f))
        Text(meta, color = CmTextFaint, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
    }
}

@Composable
private fun HudContactRow(c: Contact, online: Boolean, nowMs: Long, onOpenChat: (Contact) -> Unit) {
    val recent = c.lastSeenMs != null && nowMs - c.lastSeenMs <= 24 * 3_600_000L
    val rowAlpha = if (online) 1f else 0.5f
    @OptIn(ExperimentalFoundationApi::class)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CmCard)
            .cornerTicks(HudFrame)
            .combinedClickable(
                onClick = { onOpenChat(c) },
                onLongClick = { c.cmId?.let { MessageService.sendBuzz(it) } },
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(c.color.copy(alpha = rowAlpha)),
            contentAlignment = Alignment.Center) {
            Text(c.name.take(1).uppercase(), color = CmBackground, fontFamily = Nunito,
                fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(c.name, color = CmText.copy(alpha = rowAlpha), fontFamily = Nunito, fontSize = 16.sp)
            when {
                c.missed -> Text("Missed message", color = HudRed, fontFamily = Nunito, fontSize = 11.sp,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                recent -> Text("last seen recently", color = CmTextFaint, fontFamily = Nunito, fontSize = 11.sp)
            }
        }
        if (c.unread || c.missed) {
            GlowDot(CmOrange, 10.dp, glow = false)
        } else {
            GlowDot(if (recent && online) HudTeal else HudFrame, 8.dp, glow = recent && online)
        }
    }
}

@Composable
private fun GlowDot(color: Color, size: Dp, glow: Boolean) {
    Canvas(Modifier.size(size + if (glow) 6.dp else 0.dp)) {
        val c = this.size.minDimension / 2
        if (glow) drawCircle(color.copy(alpha = 0.3f), radius = c)
        drawCircle(color, radius = size.toPx() / 2, center = Offset(this.size.width / 2, this.size.height / 2))
    }
}

@Composable
private fun PurpleButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(Brush.horizontalGradient(listOf(HudPurpleA, HudPurpleB)))
            .clickable { onClick() }.padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontFamily = Nunito, fontSize = 15.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
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

@Composable
private fun MinimiseExitPill(onMinimise: () -> Unit, onExit: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(14.dp)).border(1.dp, HudFrame, RoundedCornerShape(14.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.clickable { onMinimise() }.padding(horizontal = 16.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center) {
            Text("–", color = CmTextDim, fontFamily = Nunito, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.width(1.dp).height(20.dp).background(HudFrame))
        Box(Modifier.clickable { onExit() }.padding(horizontal = 16.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center) {
            Text("⏻", color = HudRed, fontFamily = Nunito, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StayUnlockedTick(checked: Boolean, onToggle: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onToggle() }
            .padding(horizontal = 8.dp, vertical = 4.dp)) {
        Box(Modifier.size(15.dp).clip(RoundedCornerShape(4.dp))
            .background(if (checked) HudTeal else CmCard)
            .border(1.dp, if (checked) HudTeal else CmTextFaint, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center) {
            if (checked) Text("✓", color = CmBackground, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(7.dp))
        Text("Stay unlocked", color = CmTextDim, fontFamily = Nunito, fontSize = 11.sp)
    }
}

// ---- modifiers -------------------------------------------------------------

/** Faint grid + a red dashed radar arc behind the whole screen. */
private fun Modifier.hudGridAndRadar(): Modifier = drawBehind {
    val grid = HudCyan.copy(alpha = 0.045f)
    val step = 42.dp.toPx()
    var x = 0f
    while (x < size.width) { drawLine(grid, Offset(x, 0f), Offset(x, size.height), 1f); x += step }
    var y = 0f
    while (y < size.height) { drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f); y += step }
    // Red dashed radar arc, lower area.
    val r = size.minDimension * 0.7f
    drawArc(
        color = HudRed.copy(alpha = 0.16f),
        startAngle = 200f, sweepAngle = 140f, useCenter = false,
        topLeft = Offset(size.width / 2 - r, size.height - r),
        size = Size(r * 2, r * 2),
        style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 12f))),
    )
}

/** L-shaped ticks at each corner (server-panel card frame). */
private fun Modifier.cornerTicks(color: Color, len: Dp = 9.dp, stroke: Dp = 1.5.dp): Modifier = drawBehind {
    val l = len.toPx(); val s = stroke.toPx(); val w = size.width; val h = size.height
    drawLine(color, Offset(0f, 0f), Offset(l, 0f), s)
    drawLine(color, Offset(0f, 0f), Offset(0f, l), s)
    drawLine(color, Offset(w, 0f), Offset(w - l, 0f), s)
    drawLine(color, Offset(w, 0f), Offset(w, l), s)
    drawLine(color, Offset(0f, h), Offset(l, h), s)
    drawLine(color, Offset(0f, h), Offset(0f, h - l), s)
    drawLine(color, Offset(w, h), Offset(w - l, h), s)
    drawLine(color, Offset(w, h), Offset(w, h - l), s)
}
