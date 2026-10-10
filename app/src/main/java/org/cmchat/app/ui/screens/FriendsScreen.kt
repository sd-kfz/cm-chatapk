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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.cmchat.app.R
import org.cmchat.app.tools.ToolsState
import org.cmchat.app.tor.TorService
import org.cmchat.app.tor.TorStatus
import org.cmchat.app.transport.MessageService
import org.cmchat.app.ui.theme.*
import org.cmchat.app.i18n.Tr

/** One friend row on the Friends screen. */
data class Contact(
    val name: String,
    val color: Color,
    val unread: Boolean,
    val cmId: String? = null,
    val lastSeenMs: Long? = null,
    val missed: Boolean = false,
    /** A Buzz arrived and the conversation hasn't been opened since (blue dot). */
    val buzzed: Boolean = false,
    /** I added them; waiting for them to accept. */
    val pending: Boolean = false,
)

// The crisp blue/red wordmark (kept exactly as approved) + offline grey.
private val WordBlue = Color(0xFF35C6F2)
private val WordRed = Color(0xFFFF3B3B)
private val Grey = Color(0xFF8B94A3)
private val Frame = Color(0xFF232A35)

/**
 * The Friends screen (main screen). v1.2 final look: true-black OLED background,
 * letters-only "CM-Chat" wordmark top-left, exactly two DRAWN vector icons
 * top-right (minimise, exit), dense friend cards, and a cyan-gradient "+" that
 * holds Add friend + Settings. No logo image and no gear in the top bar.
 */
@Composable
fun FriendsScreen(
    contacts: List<Contact>,
    onOpenChat: (Contact) -> Unit,
    onOpenSettings: () -> Unit,
    onAddFriend: () -> Unit = {},
    /** Cancel a still-pending add (removes them; withdraws the request card). */
    onCancelPending: (Contact) -> Unit = {},
    /** Remove a pending contact from MY list only (nothing is sent). */
    onRemovePending: (Contact) -> Unit = {},
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
    // A pending friend: tapping the row opens what you can do about it — never a
    // dead end ("stuck on Pending").
    var pendingFor by remember { mutableStateOf<Contact?>(null) }
    pendingFor?.let { c ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingFor = null },
            title = { Text(Tr.s(R.string.friends_waiting_for, c.name)) },
            text = { Text(Tr.s(R.string.friends_pending_explainer, c.name, c.name)) },
            confirmButton = {
                Row {
                    androidx.compose.material3.TextButton(onClick = { pendingFor = null; onRemovePending(c) }) {
                        Text(Tr.s(R.string.friends_remove), color = CmRed)
                    }
                    androidx.compose.material3.TextButton(onClick = { pendingFor = null; onCancelPending(c) }) {
                        Text(Tr.s(R.string.friends_cancel_request), color = CmRed)
                    }
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingFor = null }) { Text(Tr.s(R.string.friends_keep_waiting)) }
            },
        )
    }

    Box(Modifier.fillMaxSize().background(CmBackground)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {

            // ---- top bar: wordmark (letters only, left) .......... minimise / exit
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Wordmark(online)
                Spacer(Modifier.weight(1f))
                IconBtn(onClick = onMinimise) { MinimiseIcon() }
                Spacer(Modifier.width(6.dp))
                IconBtn(onClick = onExit) { PowerIcon() }
            }

            // ---- thin status line: Engine (left) .......... Me (right)
            Spacer(Modifier.height(6.dp))
            val serverOff by org.cmchat.app.tor.ServerController.stoppedByUser.collectAsState()
            StatusLine(online, torStatus, invisible, serverOff) {
                val nowInvisible = !invisible
                org.cmchat.app.settings.AppSettings.invisibleMode.value = nowInvisible
                // Going Online delivers what was held: "Missed" clears, the
                // messages show as new (blue dot until viewed).
                if (!nowInvisible) org.cmchat.app.chat.ChatStore.deliverMissed()
            }
            Spacer(Modifier.height(8.dp))

            if (versionMismatch) {
                Text(Tr.s(R.string.friends_version_mismatch),
                    color = WordRed, fontFamily = Nunito, fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(WordRed.copy(alpha = 0.12f)).padding(10.dp),
                    textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
            }

            if (torStatus is TorStatus.Starting || torStatus is TorStatus.Connecting) {
                val bridged = org.cmchat.app.tor.Bridges.isEnabled()
                Text(
                    if (bridged) Tr.s(R.string.friends_connecting_bridges)
                    else Tr.s(R.string.friends_connecting_tor),
                    color = CmTextDim, fontFamily = Nunito, fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp), textAlign = TextAlign.Center,
                )
            }

            // Incoming knock requests (only when present).
            for (k in knocks) {
                Column(
                    Modifier.fillMaxWidth().padding(bottom = 6.dp)
                        .clip(RoundedCornerShape(10.dp)).background(CmCard).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(Tr.s(R.string.friends_request_from, k.displayName), color = CmText, fontFamily = Nunito,
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(CmTeal)
                            .clickable { MessageService.acceptKnock(k) }.padding(vertical = 9.dp),
                            contentAlignment = Alignment.Center) {
                            Text(Tr.s(R.string.friends_accept), color = CmBackground, fontFamily = Nunito, fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold)
                        }
                        Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(CmBackground)
                            .border(1.dp, Frame, RoundedCornerShape(10.dp))
                            .clickable { MessageService.declineKnock(k) }.padding(vertical = 9.dp),
                            contentAlignment = Alignment.Center) {
                            Text(Tr.s(R.string.friends_decline), color = CmTextDim, fontFamily = Nunito, fontSize = 14.sp)
                        }
                    }
                }
            }

            // ---- friends list (dense cards)
            if (contacts.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.empty_friends), color = CmTextFaint,
                        fontFamily = Nunito, fontSize = 14.sp, textAlign = TextAlign.Center)
                }
            } else LazyColumn(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
                contentPadding = PaddingValues(bottom = 80.dp),  // clear the "+"
            ) {
                items(contacts) { c -> FriendRow(c, onOpenChat = { if (it.pending) pendingFor = it else onOpenChat(it) }) }
            }

            // Tools dock (only when a tool is enabled) — behaviour unchanged.
            if (calcOn || notesOn || flashOn) {
                val torchOn by org.cmchat.app.tools.Flashlight.on.collectAsState()
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally)) {
                    if (calcOn) DockTool(Tr.s(R.string.friends_calculator), org.cmchat.app.ui.components.ToolIcon.CALCULATOR) { onOpenTool("calculator") }
                    if (notesOn) DockTool(Tr.s(R.string.friends_notes), org.cmchat.app.ui.components.ToolIcon.NOTES) { onOpenTool("notes") }
                    if (flashOn) DockTool(if (torchOn) Tr.s(R.string.friends_torch_on) else Tr.s(R.string.friends_flashlight), org.cmchat.app.ui.components.ToolIcon.FLASHLIGHT,
                        active = torchOn) { org.cmchat.app.tools.Flashlight.toggle(ctx) }
                }
            }
        }

        // ---- cyan-gradient "+", bottom-right: Add friend + Settings live here.
        var menuOpen by remember { mutableStateOf(false) }
        Box(Modifier.align(Alignment.BottomEnd).padding(20.dp)) {
            Box(
                Modifier.size(56.dp).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(CmBlueGlow, CmBlue, CmCyanDeep)))
                    .clickable { menuOpen = true },
                contentAlignment = Alignment.Center,
            ) { PlusIcon() }
            androidx.compose.material3.DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(Tr.s(R.string.add_add_friend), fontFamily = Nunito) },
                    onClick = { menuOpen = false; onAddFriend() },
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(Tr.s(R.string.friends_settings), fontFamily = Nunito) },
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
        Text("CM-Chat", color = Grey, fontFamily = Nunito, fontSize = 24.sp,
            fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
        return
    }
    fun glow(c: Color) = TextStyle(
        color = c, fontFamily = Nunito, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold,
        letterSpacing = 1.sp,
        shadow = Shadow(color = c.copy(alpha = 0.9f), offset = Offset(0f, 0f), blurRadius = 3f),
    )
    Row {
        Text("CM-C", style = glow(WordBlue))
        Text("hat", style = glow(WordRed))
    }
}

/**
 * A ≥40dp circular touch target wrapping a vector icon. The icons are DRAWN
 * with Canvas lines/arcs — never a font or emoji glyph — so they render the same
 * on every Android version (a missing glyph is what showed up as an empty box).
 */
@Composable
private fun IconBtn(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).clickable { onClick() }, contentAlignment = Alignment.Center) {
        content()
    }
}

/** MINIMISE — a single horizontal white line (SVG "M5 12h14", stroke 2.2). */
@Composable
private fun MinimiseIcon() {
    androidx.compose.foundation.Canvas(Modifier.size(24.dp)) {
        val s = size.minDimension / 24f
        drawLine(Color.White, Offset(5f * s, 12f * s), Offset(19f * s, 12f * s),
            strokeWidth = 2.2f * s, cap = StrokeCap.Round)
    }
}

/** EXIT — the red power symbol (drawn; shared with the chat's Kill pill). */
@Composable
private fun PowerIcon() = org.cmchat.app.ui.components.PowerGlyph(WordRed, 24)

/** The "+" on the cyan button, drawn (crisp at any size). */
@Composable
private fun PlusIcon() {
    androidx.compose.foundation.Canvas(Modifier.size(24.dp)) {
        val s = size.minDimension / 24f
        drawLine(CmBackground, Offset(12f * s, 4f * s), Offset(12f * s, 20f * s), strokeWidth = 3f * s, cap = StrokeCap.Round)
        drawLine(CmBackground, Offset(4f * s, 12f * s), Offset(20f * s, 12f * s), strokeWidth = 3f * s, cap = StrokeCap.Round)
    }
}

@Composable
private fun StatusLine(online: Boolean, status: TorStatus, invisible: Boolean, serverOff: Boolean,
                       onToggleMe: () -> Unit) {
    val engineLabel = when (status) {
        // You stopped My Server: nothing can reach you until you tap Start there.
        is TorStatus.Online -> if (serverOff) Tr.s(R.string.friends_online_server_off) else Tr.s(R.string.status_online)
        is TorStatus.Connecting -> Tr.s(R.string.status_connecting, status.percent)
        is TorStatus.Starting -> Tr.s(R.string.status_starting)
        is TorStatus.Offline -> Tr.s(R.string.status_offline)
        is TorStatus.Failed -> if (status.reason == "bridges") Tr.s(R.string.status_failed_bridges) else Tr.s(R.string.status_failed)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Tr.s(R.string.friends_engine_label) + " ", color = CmTextDim, fontFamily = Nunito, fontSize = 13.sp)
            Text(engineLabel, color = if (online) CmTeal else Grey.copy(alpha = 0.7f),
                fontFamily = Nunito, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.weight(1f))
        // My own status: "● Online" / "● Invisible", the dot in the status colour.
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { onToggleMe() }
                .padding(horizontal = 6.dp, vertical = 4.dp)) {
            val c = if (!online) Grey else if (invisible) CmBlue else CmTeal
            Dot(c)
            Spacer(Modifier.width(6.dp))
            Text(if (invisible) Tr.s(R.string.status_invisible) else Tr.s(R.string.status_online), color = c,
                fontFamily = Nunito, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * A dense friend card: small round avatar, name + one sub-line, and markers on
 * the right — a BLUE dot means a new message is waiting (nothing else: there is
 * no "online" dot). Presence is only the coarse "last seen recently" line.
 */
@Composable
private fun FriendRow(c: Contact, onOpenChat: (Contact) -> Unit) {
    val recent = c.lastSeenMs != null && System.currentTimeMillis() - c.lastSeenMs <= 24 * 3_600_000L
    @OptIn(ExperimentalFoundationApi::class)
    Row(
        // Every row the same height — with or without a second line (so the
        // decoy row can't be told apart).
        Modifier.fillMaxWidth().heightIn(min = 54.dp).clip(RoundedCornerShape(10.dp)).background(CmCard)
            .combinedClickable(
                onClick = { onOpenChat(c) },
                onLongClick = { c.cmId?.let { MessageService.sendBuzz(it) } },
            )
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(c.color), contentAlignment = Alignment.Center) {
            Text(c.name.take(1).uppercase(), color = CmBackground, fontFamily = Nunito,
                fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(c.name, color = CmText, fontFamily = Nunito, fontSize = 15.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            when {
                c.pending -> Text(Tr.s(R.string.friends_pending_line), color = CmTextDim,
                    fontFamily = Nunito, fontSize = 11.sp, fontStyle = FontStyle.Italic)
                c.missed -> Text(Tr.s(R.string.missed_message), color = WordRed, fontFamily = Nunito, fontSize = 11.sp,
                    fontStyle = FontStyle.Italic)
                c.buzzed -> Text(Tr.s(R.string.friends_buzzed_you), color = CmBuzzBlue, fontFamily = Nunito, fontSize = 11.sp)
                recent -> Text(Tr.s(R.string.last_seen_recently), color = CmTextFaint, fontFamily = Nunito, fontSize = 11.sp)
            }
        }
        if (c.pending) {
            // Visible on every row that's waiting: tap → Cancel request / Remove.
            Text(Tr.s(R.string.friends_pending), color = CmRed, fontFamily = Nunito, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
        }
        // The only dot: BLUE = a new message is waiting (incl. one held while
        // Invisible). A Buzz shows as its "Buzzed you" line.
        if (c.unread || c.missed) Dot(CmUnreadBlue)
    }
}

@Composable
private fun Dot(color: Color) = Box(Modifier.size(9.dp).clip(CircleShape).background(color))

@Composable
private fun DockTool(label: String, icon: org.cmchat.app.ui.components.ToolIcon, active: Boolean = false, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(46.dp).clip(CircleShape)
                .then(if (active) Modifier.background(CmOrange) else Modifier)
                .border(1.5.dp, CmOrange, CircleShape)
                .clickable { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            org.cmchat.app.ui.components.ToolGlyph(icon, if (active) Color.White else CmOrange)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = CmTextDim, fontFamily = Nunito, fontSize = 11.sp)
    }
}
