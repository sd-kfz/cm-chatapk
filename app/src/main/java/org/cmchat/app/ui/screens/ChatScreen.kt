package org.cmchat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.Animatable
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import org.cmchat.app.chat.ChatMessage
import org.cmchat.app.chat.ChatStore
import org.cmchat.app.chat.LastSeen
import org.cmchat.app.chat.SelfTimer
import org.cmchat.app.chat.displayLabel
import org.cmchat.app.transport.MessageService
import org.cmchat.app.ui.components.CerberusMark
import org.cmchat.app.ui.theme.*

@Composable
fun ChatScreen(
    contactName: String,
    chatCmId: String?,
    onBack: () -> Unit,
    onRename: (String) -> Unit = {},
    teamHour: String? = null,
    onSetTeamHour: (String) -> Unit = {},
) {
    val chatId = chatCmId ?: contactName
    var renaming by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf(contactName) }
    var editingTeam by remember { mutableStateOf(false) }

    // Load the persisted Team clock into this thread when the chat opens.
    LaunchedEffect(chatId, teamHour) { ChatStore.setTeamHourValue(chatId, teamHour) }
    val threads by ChatStore.threads.collectAsState()
    val thread = threads[chatId] ?: org.cmchat.app.chat.ChatThread()

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
        // Viewing the chat while Online clears the orange unread dot.
        if (chatCmId != null && !org.cmchat.app.settings.AppSettings.invisibleMode.value) {
            ChatStore.markRead(chatCmId)
        }
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

    // Screen-shake when a BUZZ for this chat arrives (optional vibration too).
    val shakeX = remember { Animatable(0f) }
    LaunchedEffect(chatCmId) {
        org.cmchat.app.buzz.BuzzPolicy.shakes.collect { s ->
            if (s.chatCmId == chatCmId) {
                runCatching {
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

    Column(Modifier.fillMaxSize().background(CmBackground)
        .offset { IntOffset(shakeX.value.roundToInt(), 0) }) {
        Box(Modifier.fillMaxWidth().padding(14.dp)) {
            Text("‹ Friends", color = CmBlue, fontFamily = Nunito, fontSize = 15.sp,
                modifier = Modifier.align(Alignment.CenterStart).clickable { onBack() })
            if (renaming && chatCmId != null) {
                BasicTextField(
                    value = newName, onValueChange = { newName = it.take(24) }, singleLine = true,
                    textStyle = TextStyle(color = CmText, fontFamily = Nunito, fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                    cursorBrush = SolidColor(CmBlue),
                    modifier = Modifier.align(Alignment.Center).widthIn(max = 200.dp),
                )
                Text("Save", color = CmGreen, fontFamily = Nunito, fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 52.dp).clickable {
                        val n = newName.trim(); if (n.isNotEmpty()) onRename(n); renaming = false
                    })
            } else {
                Text(contactName, color = CmText, fontFamily = Nunito, fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center).clickable {
                        if (chatCmId != null) { newName = contactName; renaming = true }
                    })
            }
            Text("Erase", color = CmRed, fontFamily = Nunito, fontSize = 14.sp,
                modifier = Modifier.align(Alignment.CenterEnd).clickable {
                    if (chatCmId != null) MessageService.sendErase(chatCmId) else ChatStore.erase(chatId)
                })
        }

        val generalTimer by org.cmchat.app.settings.AppSettings.generalTimer.collectAsState()

        // Last seen only — there is NO online indicator on friends, ever.
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            LastSeen.bucket(thread.peerLastSeen, now)?.let {
                Text(it, color = CmTextFaint, fontFamily = Nunito, fontSize = 12.sp)
            }
        }

        // Guardian bar — DISPLAY ONLY (Cerberus + Kill Timer are set in Settings).
        // Left: the Cerberus eye with the message self-destruct TIMER pill beside
        // it (the timer the next message will get). Right: the Kill Timer.
        val killDeadline by org.cmchat.app.guard.GuardController.killDeadline.collectAsState()
        val cerberusMin by org.cmchat.app.guard.GuardController.cerberusMinutes.collectAsState()
        val nextTimer = if (selfTimer != SelfTimer.OFF) selfTimer else generalTimer
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp).clip(RoundedCornerShape(14.dp))
            .border(1.dp, CmTextFaint.copy(alpha = 0.6f), RoundedCornerShape(14.dp)),
            verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1.4f).padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
                CerberusMark(on = cerberusOn, sizeDp = 34)
                Spacer(Modifier.width(8.dp))
                Text(if (cerberusOn) "Cerberus ${cerberusMin}m" else "Cerberus off",
                    color = if (cerberusOn) CmBlue else CmRed, fontFamily = Nunito,
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                TimerPill(nextTimer)
            }
            Box(Modifier.width(1.dp).height(34.dp).background(CmTextFaint.copy(alpha = 0.6f)))
            Box(Modifier.weight(1f).padding(9.dp), contentAlignment = Alignment.Center) {
                val killLabel = killDeadline?.let { "Kill " + countdown(it - now) } ?: "Kill off"
                Text(killLabel,
                    color = if (killDeadline != null) CmRed else CmTextDim,
                    fontFamily = Nunito, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        // Team Clock: a shared private clock for THIS conversation, ticking live.
        // Setting it updates both phones (sent inside the encrypted chat).
        val teamOffset = org.cmchat.app.chat.TeamClock.decode(thread.teamHour)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, start = 16.dp, end = 16.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = chatCmId != null) { editingTeam = true }
            .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (teamOffset != null) {
                Text("Team Clock", color = CmTextDim, fontFamily = Nunito, fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                Text(org.cmchat.app.chat.TeamClock.timeAt(now, teamOffset), color = CmBlue, fontFamily = Nunito,
                    fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(6.dp))
                Text(org.cmchat.app.chat.TeamClock.label(teamOffset), color = CmTextFaint, fontFamily = Nunito,
                    fontSize = 11.sp)
            } else {
                Text("Set a Team Clock", color = CmTextDim, fontFamily = Nunito, fontSize = 12.sp)
            }
        }
        if (editingTeam && chatCmId != null) {
            TeamClockDialog(
                current = teamOffset,
                now = now,
                onSave = { off ->
                    val v = org.cmchat.app.chat.TeamClock.encode(off)
                    onSetTeamHour(v)
                    ChatStore.setTeamHour(chatId, v, "You")
                    MessageService.sendTeamClock(chatCmId, v)
                    editingTeam = false
                },
                onTurnOff = {
                    onSetTeamHour("")
                    ChatStore.setTeamHour(chatId, null, "You")
                    MessageService.sendTeamClock(chatCmId, "")
                    editingTeam = false
                },
                onDismiss = { editingTeam = false },
            )
        }

        val invisible by org.cmchat.app.settings.AppSettings.invisibleMode.collectAsState()
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (thread.messages.isEmpty()) {
                Text("No messages yet.", color = CmTextFaint, fontFamily = Nunito, fontSize = 13.sp)
            }
            for (m in thread.messages) {
                // While Invisible, messages that arrived are held back (shown as a
                // prompt below); nothing of them is revealed yet.
                if (invisible && m.missed) continue
                when {
                    // Small italic-bold alert line where the next message would be.
                    m.alert -> Text(m.text, color = CmRedGlow, fontFamily = Nunito,
                        fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    m.system -> Text(m.text, color = CmTextFaint, fontFamily = Nunito, fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    else -> Bubble(m)
                }
            }
        }
        // Invisible + something waiting: the bottom prompt (sender learns nothing).
        if (invisible && thread.messages.any { it.missed }) {
            Text("Change status to Online to receive messages",
                color = CmOrange, fontFamily = Nunito, fontSize = 12.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp))
        }

        // Disappear SELECTOR — scoped to the ONE message being sent only (resets to
        // OFF after send), NEVER the whole conversation. A general timer set in
        // Settings still applies to every message; a per-message pick overrides it
        // just once. "Single Message (view once)" is true burn-after-first-view;
        // the rest are timed self-destructs.
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Disappear:", color = CmTextDim, fontFamily = Nunito, fontSize = 12.sp)
            Spacer(Modifier.width(6.dp))
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (t in TIMER_CHOICES) {
                    val sel = t == selfTimer
                    Box(Modifier.clip(RoundedCornerShape(10.dp))
                        .background(if (sel) CmBlue else CmCard).clickable { selfTimer = t }
                        .padding(horizontal = 10.dp, vertical = 5.dp)) {
                        Text(t.displayLabel(), color = if (sel) CmBackground else CmTextDim,
                            fontFamily = Nunito, fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.width(6.dp))
            val buzzLeft = chatCmId?.let { org.cmchat.app.buzz.BuzzPolicy.sendCooldownRemaining(it, now) } ?: 0L
            Box(Modifier.clip(RoundedCornerShape(10.dp))
                .background(if (buzzLeft > 0) CmCard else CmOrange)
                .clickable(enabled = buzzLeft <= 0 && chatCmId != null) {
                    if (chatCmId != null) MessageService.sendBuzz(chatCmId)
                }
                .padding(horizontal = 12.dp, vertical = 5.dp)) {
                Text(if (buzzLeft > 0) "Buzz ${buzzLeft}s" else "⚡ Buzz",
                    color = if (buzzLeft > 0) CmTextDim else CmBackground,
                    fontFamily = Nunito, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        // Scope note: this applies to THIS message only, never the whole chat.
        if (selfTimer != SelfTimer.OFF) {
            Text(
                if (selfTimer == SelfTimer.VIEW_ONCE)
                    "burns the moment it's read — this message only"
                else "applies to this message only",
                color = CmTextFaint, fontFamily = Nunito, fontSize = 10.sp,
                modifier = Modifier.padding(start = 14.dp, top = 2.dp))
        }

        // Live counter once the body gets long (past ~9,000 of the 10,000 cap).
        if (input.length > 9_000) {
            Text("%,d / %,d".format(input.length, MAX_BODY_CHARS),
                color = if (input.length >= MAX_BODY_CHARS) CmRed else CmTextDim,
                fontFamily = Nunito, fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).background(CmCard)
                .padding(horizontal = 16.dp, vertical = 12.dp)) {
                if (input.isEmpty()) Text("Message…", color = CmTextDim, fontFamily = Nunito, fontSize = 15.sp)
                BasicTextField(
                    // Enter = newline; send only via the button. Body max 10,000.
                    value = input, onValueChange = { if (it.length <= MAX_BODY_CHARS) input = it },
                    singleLine = false, maxLines = 6,
                    textStyle = TextStyle(color = CmText, fontFamily = Nunito, fontSize = 15.sp),
                    cursorBrush = SolidColor(CmBlue),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 140.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            // Minimum body = 1 char: send is disabled while the body is blank.
            val canSend = input.isNotBlank()
            Box(Modifier.size(44.dp).clip(CircleShape)
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

/** "Kill 1h23m" style countdown for the guardian bar. */
private fun countdown(ms: Long): String {
    val secs = (ms / 1000).coerceAtLeast(0)
    return if (secs >= 3600) "${secs / 3600}h${(secs % 3600) / 60}m" else "${secs / 60}m${secs % 60}s"
}

/** The message self-destruct timer, shown as a small pill beside the Cerberus eye. */
@Composable
private fun TimerPill(t: SelfTimer) {
    val on = t != SelfTimer.OFF
    val label = when (t) {
        SelfTimer.OFF -> "timer off"
        SelfTimer.VIEW_ONCE -> "view once"
        else -> "timer ${t.label}"
    }
    Box(Modifier.clip(RoundedCornerShape(50))
        .border(1.dp, if (on) CmRed else CmTextFaint, RoundedCornerShape(50))
        .padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(label, color = if (on) CmRed else CmTextDim, fontFamily = Nunito, fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Pick the conversation's Team Clock: a UTC offset, in 1-hour steps (−/+) plus
 * a :00/:15/:30/:45 chip, or "Use my time zone". Shows the resulting team time
 * live. Save shares it with the friend; Turn off clears it on both phones.
 */
@Composable
private fun TeamClockDialog(
    current: Int?, now: Long,
    onSave: (Int) -> Unit, onTurnOff: () -> Unit, onDismiss: () -> Unit,
) {
    val tc = org.cmchat.app.chat.TeamClock
    var offset by remember { mutableStateOf(current ?: tc.deviceOffset(now)) }
    val hours = Math.floorDiv(offset, 60)
    val mins = Math.floorMod(offset, 60)
    fun set(h: Int, m: Int) {
        val v = h * 60 + m
        if (tc.isValid(v)) offset = v
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Team Clock") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text("A shared clock for this chat. Both of you will see it.",
                    color = CmTextDim, fontFamily = Nunito, fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                Text(tc.timeAt(now, offset), color = CmBlue, fontFamily = Nunito, fontSize = 34.sp,
                    fontWeight = FontWeight.Bold)
                Text(tc.label(offset), color = CmTextDim, fontFamily = Nunito, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StepBtn("−") { set(hours - 1, mins) }
                    Text("hour", color = CmTextDim, fontFamily = Nunito, fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 14.dp))
                    StepBtn("+") { set(hours + 1, mins) }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (m in listOf(0, 15, 30, 45)) {
                        val sel = m == mins
                        Box(Modifier.clip(RoundedCornerShape(10.dp)).background(if (sel) CmBlue else CmCard)
                            .clickable { set(hours, m) }.padding(horizontal = 10.dp, vertical = 6.dp)) {
                            Text(":%02d".format(m), color = if (sel) CmBackground else CmTextDim,
                                fontFamily = Nunito, fontSize = 13.sp)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("Use my time zone", color = CmBlue, fontFamily = Nunito, fontSize = 13.sp,
                    modifier = Modifier.clickable { offset = tc.deviceOffset(now) }.padding(6.dp))
                if (current != null) {
                    Text("Turn off", color = CmRed, fontFamily = Nunito, fontSize = 13.sp,
                        modifier = Modifier.clickable { onTurnOff() }.padding(6.dp))
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { onSave(offset) }) { Text("Save") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun StepBtn(label: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(CmCard).clickable { onClick() },
        contentAlignment = Alignment.Center) {
        Text(label, color = CmText, fontFamily = Nunito, fontSize = 20.sp, fontWeight = FontWeight.Bold)
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
private fun Bubble(m: ChatMessage) {
    Row(Modifier.fillMaxWidth(),
        horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start) {
        Column(horizontalAlignment = if (m.mine) Alignment.End else Alignment.Start) {
            if (m.missed) {
                Text("Missed Message", color = CmRed, fontFamily = Nunito, fontSize = 11.sp,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    modifier = Modifier.padding(bottom = 2.dp))
            }
            Box(Modifier.widthIn(max = 260.dp).clip(RoundedCornerShape(16.dp))
                .background(if (m.mine) CmBlue.copy(alpha = 0.85f) else CmCard.copy(alpha = 0.85f))
                .padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(m.text, color = if (m.mine) CmBackground else CmText, fontFamily = Nunito, fontSize = 15.sp,
                    fontStyle = if (m.missed) androidx.compose.ui.text.font.FontStyle.Italic else null)
            }
            // No delivery/read receipts. Only a small RED self-timer duration
            // (no countdown) under a timed message; it vanishes when it expires.
            if (m.selfTimer != SelfTimer.OFF) {
                Text(if (m.selfTimer == SelfTimer.VIEW_ONCE) "👁 view once" else m.selfTimer.label,
                    color = CmRed, fontFamily = Nunito, fontSize = 10.sp,
                    modifier = Modifier.padding(top = 2.dp, end = 4.dp))
            }
        }
    }
}

