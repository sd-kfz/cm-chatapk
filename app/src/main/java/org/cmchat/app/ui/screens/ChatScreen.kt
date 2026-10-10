package org.cmchat.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import org.cmchat.app.chat.ChatMessage
import org.cmchat.app.chat.ChatStore
import org.cmchat.app.chat.LastSeen
import org.cmchat.app.chat.SelfTimer
import org.cmchat.app.chat.TeamClock
import org.cmchat.app.chat.displayLabel
import org.cmchat.app.chat.formatTimestamp
import org.cmchat.app.transport.MessageService
import org.cmchat.app.ui.components.AlarmTimeDialog
import org.cmchat.app.ui.components.CerberusMark
import org.cmchat.app.ui.theme.*

/** The three actions behind the red X — each needs TWO confirmations. */
private enum class ChatAction(val title: String, val confirm: String) {
    WIPE("Wipe conversation", "Wipe"),
    DELETE("Delete friend", "Delete"),
    TERMINATE("Terminate", "Terminate"),
}

/**
 * One conversation. Built as a plain top-to-bottom stack — top bar, a
 * one-line status strip, the messages (which take whatever room is left), and
 * the composer — so on any screen height nothing can overlap: the messages
 * shrink first, and on a very short window (e.g. keyboard up on a small phone)
 * the status strip steps aside.
 */
@Composable
fun ChatScreen(
    contactName: String,
    chatCmId: String?,
    onBack: () -> Unit,
    onRename: (String) -> Unit = {},
    teamHour: String? = null,
    onSetTeamHour: (String) -> Unit = {},
    /** Persisted (vault) "last seen", so it survives restarts and erases. */
    lastSeenSaved: Long? = null,
    onDeleteFriend: () -> Unit = {},
    onTerminate: () -> Unit = {},
) {
    val chatId = chatCmId ?: contactName
    var renaming by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf(contactName) }
    var editingTeam by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var action by remember { mutableStateOf<ChatAction?>(null) }
    var confirmStep by remember { mutableStateOf(1) }

    // Load the persisted Team clock into this thread when the chat opens.
    LaunchedEffect(chatId, teamHour) { ChatStore.setTeamHourValue(chatId, teamHour) }
    val threads by ChatStore.threads.collectAsState()
    val thread = threads[chatId] ?: org.cmchat.app.chat.ChatThread()
    val invisible by org.cmchat.app.settings.AppSettings.invisibleMode.collectAsState()

    val cerberusOn by org.cmchat.app.guard.GuardController.cerberusArmed.collectAsState()
    var input by remember { mutableStateOf("") }
    var selfTimer by remember { mutableStateOf(SelfTimer.OFF) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        while (true) { now = System.currentTimeMillis(); ChatStore.purgeExpired(now); delay(1000) }
    }

    // Mark this chat as the one on screen (so a message here doesn't also notify).
    DisposableEffect(chatCmId) {
        MessageService.activeChatCmId = chatCmId
        // Opening the conversation clears the one-time blue Buzz marker (and
        // re-arms "Once only" buzzes from this friend).
        if (chatCmId != null) {
            ChatStore.clearBuzzed(chatCmId)
            org.cmchat.app.buzz.BuzzPolicy.onOpenedConversation(chatCmId)
        }
        onDispose {
            if (MessageService.activeChatCmId == chatCmId) MessageService.activeChatCmId = null
            // Leaving the chat burns any view-once message that has been seen, and
            // erases the chat if a friend's decoy notice was shown in it.
            ChatStore.leaveChat(chatId)
        }
    }
    // Online with the chat on screen = everything here is SEEN: the orange dot
    // and every "Missed Message" mark clear (also for anything arriving now).
    LaunchedEffect(chatId, invisible, thread.messages.size) {
        if (!invisible) ChatStore.markSeen(chatId)
    }

    // Screen-shake when a BUZZ for this chat arrives (optional vibration too).
    val shakeX = remember { Animatable(0f) }
    LaunchedEffect(chatCmId) {
        org.cmchat.app.buzz.BuzzPolicy.shakes.collect { s ->
            if (s.chatCmId == chatCmId) {
                runCatching {
                    @Suppress("DEPRECATION")
                    val vib = context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                        vib?.vibrate(android.os.VibrationEffect.createOneShot(120, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                    else @Suppress("DEPRECATION") vib?.vibrate(120)
                }
                repeat(4) {
                    shakeX.animateTo(16f, androidx.compose.animation.core.tween(50))
                    shakeX.animateTo(-16f, androidx.compose.animation.core.tween(50))
                }
                shakeX.animateTo(0f, androidx.compose.animation.core.tween(50))
            }
        }
    }

    // ---- the X menu's two-step confirmation ---------------------------------
    action?.let { a ->
        val first = when (a) {
            ChatAction.WIPE -> "Delete this whole conversation on BOTH phones, right now?"
            ChatAction.DELETE -> "Remove $contactName from your friends? (Only on your phone.)"
            ChatAction.TERMINATE -> "Remove $contactName AND remove yourself from their friend list?"
        }
        val second = when (a) {
            ChatAction.WIPE -> "Are you sure? Both copies are erased and can't be brought back."
            ChatAction.DELETE -> "Are you sure? To talk again, one of you has to add the other again."
            ChatAction.TERMINATE -> "Are you sure? It applies on their phone as soon as it receives it."
        }
        AlertDialog(
            onDismissRequest = { action = null },
            title = { Text(a.title) },
            text = { Text(if (confirmStep == 1) first else second) },
            confirmButton = {
                TextButton(onClick = {
                    if (confirmStep == 1) { confirmStep = 2; return@TextButton }
                    action = null
                    when (a) {
                        ChatAction.WIPE ->
                            if (chatCmId != null) MessageService.sendErase(chatCmId) else ChatStore.erase(chatId)
                        ChatAction.DELETE -> onDeleteFriend()
                        ChatAction.TERMINATE -> onTerminate()
                    }
                }) { Text(if (confirmStep == 1) "Continue" else a.confirm, color = CmRed) }
            },
            dismissButton = { TextButton(onClick = { action = null }) { Text("Cancel") } },
        )
    }

    val teamOffset = TeamClock.decode(thread.teamHour)
    if (editingTeam && chatCmId != null) {
        val (h, m) = if (teamOffset != null) TeamClock.hourMinuteAt(now, teamOffset)
            else java.util.Calendar.getInstance().let {
                it.get(java.util.Calendar.HOUR_OF_DAY) to it.get(java.util.Calendar.MINUTE)
            }
        AlarmTimeDialog(
            title = "Team Clock",
            initialHour = h, initialMinute = m,
            note = "Set what time your shared clock shows right now. You both see it tick.",
            confirmLabel = "Set",
            extra = if (teamOffset != null) { {
                TextButton(onClick = {
                    onSetTeamHour("")
                    ChatStore.setTeamHour(chatId, null, "You")
                    MessageService.sendTeamClock(chatCmId, "")
                    editingTeam = false
                }) { Text("Turn the Team Clock off", color = CmRed) }
            } } else null,
            onConfirm = { hh, mm ->
                val v = TeamClock.encode(TeamClock.offsetFor(hh, mm, System.currentTimeMillis()))
                onSetTeamHour(v)
                ChatStore.setTeamHour(chatId, v, "You")
                MessageService.sendTeamClock(chatCmId, v)
                editingTeam = false
            },
            onDismiss = { editingTeam = false },
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(CmBackground)
        .offset { IntOffset(shakeX.value.roundToInt(), 0) }) {
        // Very short window (small phone with the keyboard up): drop the status
        // strip so the messages and the composer keep their room.
        val roomy = maxHeight >= 380.dp
        val bubbleMax = maxWidth * 0.78f
        Column(Modifier.fillMaxSize()) {

            // ---- top bar: back · name (+ last seen) · red X ------------------
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(CircleShape).clickable { onBack() },
                    contentAlignment = Alignment.Center) {
                    Text("‹", color = CmBlue, fontFamily = Nunito, fontSize = 30.sp)
                }
                Column(Modifier.weight(1f).padding(horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    if (renaming && chatCmId != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BasicTextField(
                                value = newName, onValueChange = { newName = it.take(24) }, singleLine = true,
                                textStyle = TextStyle(color = CmText, fontFamily = Nunito, fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
                                cursorBrush = SolidColor(CmBlue),
                                modifier = Modifier.weight(1f, fill = false).widthIn(min = 60.dp, max = 180.dp),
                            )
                            Text("Save", color = CmGreen, fontFamily = Nunito, fontSize = 13.sp,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                                    val n = newName.trim(); if (n.isNotEmpty()) onRename(n); renaming = false
                                }.padding(8.dp))
                        }
                    } else {
                        Text(contactName, color = CmText, fontFamily = Nunito, fontSize = 18.sp,
                            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable(enabled = chatCmId != null) {
                                newName = contactName; renaming = true
                            })
                    }
                    // Last seen only — there is NO online indicator on friends, ever.
                    val seen = listOfNotNull(thread.peerLastSeen, lastSeenSaved).maxOrNull()
                    LastSeen.bucket(seen, now)?.let {
                        Text(it, color = CmTextFaint, fontFamily = Nunito, fontSize = 11.sp, maxLines = 1)
                    }
                }
                Box {
                    Box(Modifier.size(48.dp).clip(CircleShape).clickable { menuOpen = true },
                        contentAlignment = Alignment.Center) { RedX() }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Wipe conversation", fontFamily = Nunito) },
                            onClick = { menuOpen = false; confirmStep = 1; action = ChatAction.WIPE })
                        DropdownMenuItem(text = { Text("Delete friend", fontFamily = Nunito) },
                            onClick = { menuOpen = false; confirmStep = 1; action = ChatAction.DELETE })
                        DropdownMenuItem(text = { Text("Terminate", color = CmRed, fontFamily = Nunito) },
                            enabled = chatCmId != null,
                            onClick = { menuOpen = false; confirmStep = 1; action = ChatAction.TERMINATE })
                    }
                }
            }

            // ---- status strip: each item its own pill, one scrollable line ----
            if (roomy) {
                val generalTimer by org.cmchat.app.settings.AppSettings.generalTimer.collectAsState()
                val killDeadline by org.cmchat.app.guard.GuardController.killDeadline.collectAsState()
                val cerberusMin by org.cmchat.app.guard.GuardController.cerberusMinutes.collectAsState()
                val nextTimer = if (selfTimer != SelfTimer.OFF) selfTimer else generalTimer
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Pill(outline = if (cerberusOn) CmBlue else CmRed) {
                        CerberusMark(on = cerberusOn, sizeDp = 18)
                        Spacer(Modifier.width(5.dp))
                        Text(if (cerberusOn) "${cerberusMin}m" else "off",
                            color = if (cerberusOn) CmBlue else CmRed, fontFamily = Nunito,
                            fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    TimerPill(nextTimer)
                    Pill(outline = if (killDeadline != null) CmRed else CmTextFaint) {
                        Text(killDeadline?.let { "Kill " + countdown(it - now) } ?: "Kill off",
                            color = if (killDeadline != null) CmRed else CmTextDim,
                            fontFamily = Nunito, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    // Team Clock: a shared clock for THIS chat, set like an alarm.
                    Pill(outline = CmBlue, onClick = if (chatCmId != null) ({ editingTeam = true }) else null) {
                        if (teamOffset != null) {
                            Text("Team ", color = CmTextDim, fontFamily = Nunito, fontSize = 12.sp)
                            Text(TeamClock.time12(now, teamOffset), color = CmBlue, fontFamily = Nunito,
                                fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        } else {
                            Text("Set Team Clock", color = CmBlue, fontFamily = Nunito, fontSize = 12.sp)
                        }
                    }
                }
            }

            // ---- messages: take whatever room is left ------------------------
            val shown = thread.messages.filterNot { invisible && it.missed }
            val listState = rememberLazyListState()
            LaunchedEffect(shown.size) { if (shown.isNotEmpty()) listState.animateScrollToItem(shown.size - 1) }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (shown.isEmpty()) item {
                    Text("No messages yet.", color = CmTextFaint, fontFamily = Nunito, fontSize = 13.sp)
                }
                // No item keys: a friend's message id can equal one of mine, and
                // duplicate keys would crash the list.
                items(shown) { m ->
                    when {
                        // Small italic-bold alert line where the next message would be.
                        m.alert -> Text(m.text, color = CmRedGlow, fontFamily = Nunito,
                            fontSize = 12.sp, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic,
                            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                        m.system -> Text(m.text, color = CmTextFaint, fontFamily = Nunito, fontSize = 12.sp,
                            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                        else -> Bubble(m, bubbleMax)
                    }
                }
            }
            // Invisible + something waiting: the bottom prompt (sender learns nothing).
            if (invisible && thread.messages.any { it.missed }) {
                Text("Change status to Online to receive messages",
                    color = CmOrange, fontFamily = Nunito, fontSize = 12.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp))
            }

            // ---- composer -----------------------------------------------------
            // Disappear SELECTOR — this ONE message only (resets after send).
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Disappear:", color = CmTextDim, fontFamily = Nunito, fontSize = 12.sp)
                    for (t in TIMER_CHOICES) {
                        val sel = t == selfTimer
                        Box(Modifier.clip(RoundedCornerShape(10.dp))
                            .background(if (sel) CmBlue else CmCard).clickable { selfTimer = t }
                            .padding(horizontal = 10.dp, vertical = 5.dp)) {
                            Text(t.displayLabel(), color = if (sel) CmBackground else CmTextDim,
                                fontFamily = Nunito, fontSize = 12.sp, maxLines = 1)
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                val buzzLeft = chatCmId?.let { org.cmchat.app.buzz.BuzzPolicy.sendCooldownRemaining(it, now) } ?: 0L
                Box(Modifier.clip(RoundedCornerShape(10.dp))
                    .background(if (buzzLeft > 0) CmCard else CmOrange)
                    .clickable(enabled = buzzLeft <= 0 && chatCmId != null) {
                        if (chatCmId != null) MessageService.sendBuzz(chatCmId)
                    }
                    .padding(horizontal = 12.dp, vertical = 5.dp)) {
                    Text(if (buzzLeft > 0) "Buzz ${buzzLeft}s" else "⚡ Buzz",
                        color = if (buzzLeft > 0) CmTextDim else CmBackground,
                        fontFamily = Nunito, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
            if (selfTimer != SelfTimer.OFF) {
                Text(if (selfTimer == SelfTimer.VIEW_ONCE) "burns the moment it's read — this message only"
                    else "applies to this message only",
                    color = CmTextFaint, fontFamily = Nunito, fontSize = 10.sp,
                    modifier = Modifier.padding(start = 14.dp, top = 2.dp))
            }
            // Live counter once the body gets long (past ~9,000 of the 10,000 cap).
            if (input.length > 9_000) {
                Text("%,d / %,d".format(input.length, MAX_BODY_CHARS),
                    color = if (input.length >= MAX_BODY_CHARS) CmRed else CmTextDim,
                    fontFamily = Nunito, fontSize = 11.sp, textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp))
            }
            // Message box (grows to ~5 lines, then scrolls) + its own send button.
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom) {
                Box(Modifier.weight(1f).heightIn(min = 46.dp).clip(RoundedCornerShape(22.dp)).background(CmCard)
                    .padding(horizontal = 16.dp, vertical = 12.dp)) {
                    if (input.isEmpty()) Text("Message…", color = CmTextDim, fontFamily = Nunito, fontSize = 15.sp)
                    BasicTextField(
                        // Enter = newline; send only via the button. Body max 10,000.
                        value = input, onValueChange = { if (it.length <= MAX_BODY_CHARS) input = it },
                        singleLine = false, maxLines = 5,
                        textStyle = TextStyle(color = CmText, fontFamily = Nunito, fontSize = 15.sp),
                        cursorBrush = SolidColor(CmBlue),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 120.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                // Minimum body = 1 char: send is disabled while the body is blank.
                val canSend = input.isNotBlank()
                Box(Modifier.size(48.dp).clip(CircleShape)
                    .background(if (canSend) CmBlue else CmCard)
                    .clickable(enabled = canSend) {
                        val text = input.trimEnd()
                        if (text.isNotEmpty()) {
                            if (chatCmId != null) MessageService.sendText(chatCmId, text, selfTimer)
                            else ChatStore.addMine(chatId, text, selfTimer)
                            input = ""
                            selfTimer = SelfTimer.OFF   // per-message timer resets
                        }
                    }, contentAlignment = Alignment.Center) {
                    Text("➤", color = if (canSend) CmBackground else CmTextDim, fontSize = 18.sp)
                }
            }
        }
    }
}

/** The red X (drawn, so it looks the same on every Android version). */
@Composable
private fun RedX() {
    Canvas(Modifier.size(20.dp)) {
        val s = size.minDimension
        drawLine(CmRedGlow, Offset(s * 0.18f, s * 0.18f), Offset(s * 0.82f, s * 0.82f),
            strokeWidth = s * 0.13f, cap = StrokeCap.Round)
        drawLine(CmRedGlow, Offset(s * 0.82f, s * 0.18f), Offset(s * 0.18f, s * 0.82f),
            strokeWidth = s * 0.13f, cap = StrokeCap.Round)
    }
}

/** A rounded, outlined item on the status strip. */
@Composable
private fun Pill(
    outline: androidx.compose.ui.graphics.Color,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Row(Modifier.clip(RoundedCornerShape(50))
        .border(1.dp, outline.copy(alpha = 0.7f), RoundedCornerShape(50))
        .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
        .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, content = content)
}

/** "Kill 1h23m" style countdown for the status strip. */
private fun countdown(ms: Long): String {
    val secs = (ms / 1000).coerceAtLeast(0)
    return if (secs >= 3600) "${secs / 3600}h${(secs % 3600) / 60}m" else "${secs / 60}m${secs % 60}s"
}

/** The timer the NEXT message gets, as a small pill. */
@Composable
private fun TimerPill(t: SelfTimer) {
    val on = t != SelfTimer.OFF
    val label = when (t) {
        SelfTimer.OFF -> "timer off"
        SelfTimer.VIEW_ONCE -> "view once"
        else -> "timer ${t.label}"
    }
    Pill(outline = if (on) CmRed else CmTextFaint) {
        Text(label, color = if (on) CmRed else CmTextDim, fontFamily = Nunito, fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Chat body hard cap (min 1 enforced by disabling send when blank). */
private const val MAX_BODY_CHARS = 10_000

/**
 * The curated per-message disappear options, in order: never, true view-once,
 * then a few timed self-destructs. (The full [SelfTimer] set still backs the
 * general timer in Settings; this is just the composer's short list.)
 */
private val TIMER_CHOICES = listOf(
    SelfTimer.OFF, SelfTimer.VIEW_ONCE, SelfTimer.S30, SelfTimer.M5, SelfTimer.M30, SelfTimer.M60,
)

@Composable
private fun Bubble(m: ChatMessage, maxBubble: androidx.compose.ui.unit.Dp) {
    Row(Modifier.fillMaxWidth(),
        horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start) {
        Column(horizontalAlignment = if (m.mine) Alignment.End else Alignment.Start) {
            if (m.missed) {
                Text("Missed Message", color = CmRed, fontFamily = Nunito, fontSize = 11.sp,
                    fontStyle = FontStyle.Italic, modifier = Modifier.padding(bottom = 2.dp))
            }
            Box(Modifier.widthIn(max = maxBubble).clip(RoundedCornerShape(16.dp))
                .background(if (m.mine) CmBlue.copy(alpha = 0.85f) else CmCard.copy(alpha = 0.85f))
                .padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(m.text, color = if (m.mine) CmBackground else CmText, fontFamily = Nunito, fontSize = 15.sp,
                    fontStyle = if (m.missed) FontStyle.Italic else null)
            }
            // Time only (h:mm AM/PM) — no delivery/read receipts. A timed message
            // also shows its small RED self-timer (no countdown).
            Row(Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(formatTimestamp(m.createdAt), color = CmTextFaint, fontFamily = Nunito, fontSize = 10.sp)
                if (m.selfTimer != SelfTimer.OFF) {
                    Text(if (m.selfTimer == SelfTimer.VIEW_ONCE) "👁 view once" else m.selfTimer.label,
                        color = CmRed, fontFamily = Nunito, fontSize = 10.sp)
                }
            }
        }
    }
}
