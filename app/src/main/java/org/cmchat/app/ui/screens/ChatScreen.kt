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
import androidx.compose.ui.graphics.drawscope.scale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import org.cmchat.app.R
import org.cmchat.app.i18n.Tr

/** The two actions behind the red X — each with its own confirmation. */
private enum class ChatAction(val title: Int, val confirm: Int) {
    WIPE(R.string.chat_wipe_conversation, R.string.chat_wipe),
    DELETE(R.string.chat_delete_friend, R.string.chat_delete),
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
    /** (value, when I set it) — "" = off. */
    onSetTeamHour: (String, Long) -> Unit = { _, _ -> },
    /** Persisted (vault) "last seen", so it survives restarts and erases. */
    lastSeenSaved: Long? = null,
    onDeleteFriend: () -> Unit = {},
) {
    val chatId = chatCmId ?: contactName
    var renaming by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf(contactName) }
    var editingTeam by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var action by remember { mutableStateOf<ChatAction?>(null) }

    // Load the persisted Team clock into this thread when the chat opens.
    LaunchedEffect(chatId, teamHour) { ChatStore.setTeamHourValue(chatId, teamHour) }
    val threads by ChatStore.threads.collectAsState()
    val thread = threads[chatId] ?: org.cmchat.app.chat.ChatThread()
    val invisible by org.cmchat.app.settings.AppSettings.invisibleMode.collectAsState()

    val cerberusOn by org.cmchat.app.guard.GuardController.cerberusArmed.collectAsState()
    var input by remember { mutableStateOf("") }
    var logRefused by remember { mutableStateOf(false) }
    var selfTimer by remember { mutableStateOf(SelfTimer.OFF) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ---- files: pick → (cleaned / refused) → confirm → send; received → Save ----
    var preparing by remember { mutableStateOf(false) }
    var readyFile by remember { mutableStateOf<org.cmchat.app.media.MediaPolicy.Decision.Ready?>(null) }
    var fileNote by remember { mutableStateOf<String?>(null) }
    val pickFile = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null || chatCmId == null) return@rememberLauncherForActivityResult
        preparing = true
        fileNote = null
        scope.launch {
            val d = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                org.cmchat.app.media.FilePrep.prepare(context, uri)
            }
            preparing = false
            when (d) {
                is org.cmchat.app.media.MediaPolicy.Decision.Ready -> readyFile = d
                is org.cmchat.app.media.MediaPolicy.Decision.Refused -> fileNote = noteText(d.reason)
            }
        }
    }
    var saving by remember { mutableStateOf<org.cmchat.app.chat.ChatFile?>(null) }
    val saveFile = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val f = saving
        saving = null
        if (uri == null || f == null) return@rememberLauncherForActivityResult
        scope.launch {
            // Written exactly as received — never opened or interpreted here.
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { f.writeTo(it) } != null }
                    .getOrDefault(false)
            }
            fileNote = if (ok) Tr.s(R.string.chat_saved) else Tr.s(R.string.chat_save_failed)
        }
    }
    readyFile?.let { f ->
        AlertDialog(
            onDismissRequest = { f.file.wipe(); readyFile = null },
            title = { Text(Tr.s(R.string.chat_send_file_q)) },
            text = { Text(Tr.s(R.string.chat_file_confirm, f.name, humanSize(f.file.size), noteText(f.note))) },
            confirmButton = {
                TextButton(onClick = {
                    readyFile = null
                    when (if (chatCmId != null) MessageService.sendFile(chatCmId, f.name, f.mime, f.file, selfTimer)
                          else MessageService.FileResult.NOT_READY) {
                        MessageService.FileResult.QUEUED -> selfTimer = SelfTimer.OFF
                        MessageService.FileResult.TOO_BIG -> fileNote = noteText(org.cmchat.app.media.MediaPolicy.Note.TOO_BIG)
                        MessageService.FileResult.EMPTY -> fileNote = noteText(org.cmchat.app.media.MediaPolicy.Note.EMPTY)
                        MessageService.FileResult.NOT_READY -> fileNote = Tr.s(R.string.chat_not_ready)
                    }
                }) { Text(Tr.s(R.string.chat_send)) }
            },
            dismissButton = { TextButton(onClick = { f.file.wipe(); readyFile = null }) { Text(Tr.s(R.string.cancel)) } },
        )
    }

    // Self-timers: drop expired messages once a second. No screen state is
    // touched here, so the chat is redrawn only when something actually goes
    // (the clocks on screen tick by themselves — see KillPill / TeamClockPill).
    LaunchedEffect(Unit) {
        while (true) { ChatStore.purgeExpired(); delay(1000) }
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
    // Online with the chat on screen = everything here is SEEN: the blue dot
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

    // ---- the X menu: each action has its own confirmation -------------------
    action?.let { a ->
        val question = when (a) {
            ChatAction.WIPE -> Tr.s(R.string.chat_wipe_q)
            ChatAction.DELETE -> Tr.s(R.string.chat_delete_q, contactName)
        }
        AlertDialog(
            onDismissRequest = { action = null },
            title = { Text(Tr.s(a.title)) },
            text = { Text(question) },
            confirmButton = {
                TextButton(onClick = {
                    action = null
                    when (a) {
                        ChatAction.WIPE ->
                            if (chatCmId != null) MessageService.sendErase(chatCmId) else ChatStore.erase(chatId)
                        ChatAction.DELETE -> onDeleteFriend()
                    }
                }) { Text(Tr.s(a.confirm), color = CmRed) }
            },
            dismissButton = { TextButton(onClick = { action = null }) { Text(Tr.s(R.string.cancel)) } },
        )
    }

    val teamOffset = TeamClock.decode(thread.teamHour)
    if (editingTeam && chatCmId != null) {
        val (h, m) = if (teamOffset != null) TeamClock.hourMinuteAt(System.currentTimeMillis(), teamOffset)
            else java.util.Calendar.getInstance().let {
                it.get(java.util.Calendar.HOUR_OF_DAY) to it.get(java.util.Calendar.MINUTE)
            }
        AlarmTimeDialog(
            title = Tr.s(R.string.chat_team_clock),
            initialHour = h, initialMinute = m,
            note = Tr.s(R.string.chat_tc_dialog_note),
            confirmLabel = Tr.s(R.string.set),
            extra = if (teamOffset != null) { {
                TextButton(onClick = {
                    val at = System.currentTimeMillis()
                    onSetTeamHour("", at)
                    ChatStore.setTeamHour(chatId, null, null)
                    MessageService.sendTeamClock(chatCmId, "", at)
                    editingTeam = false
                }) { Text(Tr.s(R.string.chat_tc_turn_off), color = CmRed) }
            } } else null,
            onConfirm = { hh, mm ->
                val at = System.currentTimeMillis()
                val v = TeamClock.encode(TeamClock.offsetFor(hh, mm, at))
                onSetTeamHour(v, at)
                ChatStore.setTeamHour(chatId, v, null)
                MessageService.sendTeamClock(chatCmId, v, at)
                editingTeam = false
            },
            onDismiss = { editingTeam = false },
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(CmBackground)
        .offset { IntOffset(shakeX.value.roundToInt(), 0) }) {
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
                            // Empty = no label of mine: their own nickname shows again.
                            Text(Tr.s(R.string.save), color = CmGreen, fontFamily = Nunito, fontSize = 13.sp,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                                    onRename(newName.trim()); renaming = false
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
                    LastSeenLine(listOfNotNull(thread.peerLastSeen, lastSeenSaved).maxOrNull())
                }
                Box {
                    Box(Modifier.size(48.dp).clip(CircleShape).clickable { menuOpen = true },
                        contentAlignment = Alignment.Center) { RedX() }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text(Tr.s(R.string.chat_wipe_conversation), fontFamily = Nunito) },
                            onClick = { menuOpen = false; action = ChatAction.WIPE })
                        DropdownMenuItem(text = { Text(Tr.s(R.string.chat_delete_friend), color = CmRed, fontFamily = Nunito) },
                            onClick = { menuOpen = false; action = ChatAction.DELETE })
                    }
                }
            }

            // ---- status strip: each item its own pill, one scrollable line ----
            // Always shown — also with the keyboard up on a small phone (it is
            // one short line; the messages give way instead).
            run {
                val generalTimer by org.cmchat.app.settings.AppSettings.generalTimer.collectAsState()
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
                    KillPill()
                    // Team Clock: a shared clock for THIS chat, set like an alarm.
                    TeamClockPill(teamOffset, onClick = if (chatCmId != null) ({ editingTeam = true }) else null)
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
                    Text(Tr.s(R.string.chat_no_messages), color = CmTextFaint, fontFamily = Nunito, fontSize = 13.sp)
                }
                // No item keys: a friend's message id can equal one of mine, and
                // duplicate keys would crash the list.
                items(shown) { m ->
                    when {
                        // Small italic-bold alert line where the next message would be.
                        m.alert -> Text(shownText(m), color = CmRedGlow, fontFamily = Nunito,
                            fontSize = 12.sp, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic,
                            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                        m.system -> Text(shownText(m), color = CmTextFaint, fontFamily = Nunito, fontSize = 12.sp,
                            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                        m.file != null -> FileBubble(m, m.file, bubbleMax, onSave = { f ->
                            saving = f
                            org.cmchat.app.LifecycleController.expectOwnLaunch()
                            runCatching { saveFile.launch(f.name) }
                        })
                        else -> Bubble(m, bubbleMax)
                    }
                }
            }
            // Invisible + something waiting: the bottom prompt (sender learns nothing).
            if (invisible && thread.messages.any { it.missed }) {
                Text(Tr.s(R.string.chat_go_online_hint),
                    color = CmOrange, fontFamily = Nunito, fontSize = 12.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp))
            }

            // ---- composer -----------------------------------------------------
            // Disappear SELECTOR — this ONE message only (resets after send). The
            // fire icon stays put on the left; only the choices scroll.
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("🔥", fontSize = 16.sp, modifier = Modifier.padding(end = 6.dp))
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
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
                BuzzButton(chatCmId)
            }
            if (selfTimer != SelfTimer.OFF) {
                Text(if (selfTimer == SelfTimer.VIEW_ONCE) Tr.s(R.string.chat_view_once_hint)
                    else Tr.s(R.string.chat_timer_hint),
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
            // Files: being prepared / refused (with the reason) / saved.
            if (preparing || fileNote != null) {
                Text(if (preparing) Tr.s(R.string.chat_preparing_file) else fileNote ?: "",
                    color = if (preparing) CmTextDim else CmOrange, fontFamily = Nunito,
                    fontSize = 11.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 2.dp))
            }
            // A pasted engine log is refused (logs stay in Connection/Diagnostics).
            if (logRefused) {
                Text(org.cmchat.app.chat.EngineLog.NOT_SENT_HINT, color = CmRed, fontFamily = Nunito,
                    fontSize = 11.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 2.dp))
            }
            // Attach (left) · message box (grows to ~5 lines, then scrolls) · send (right).
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom) {
                Box(Modifier.size(46.dp).clip(CircleShape).background(CmCard)
                    .clickable(enabled = chatCmId != null && !preparing) {
                        // Our own file picker: don't re-lock while it covers the app.
                        org.cmchat.app.LifecycleController.expectOwnLaunch()
                        runCatching { pickFile.launch(arrayOf("*/*")) }
                    }, contentAlignment = Alignment.Center) {
                    PaperclipIcon(if (chatCmId != null && !preparing) CmBlue else CmTextFaint)
                }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f).heightIn(min = 46.dp).clip(RoundedCornerShape(22.dp)).background(CmCard)
                    .padding(horizontal = 16.dp, vertical = 12.dp)) {
                    if (input.isEmpty()) Text(Tr.s(R.string.chat_message_hint), color = CmTextDim, fontFamily = Nunito, fontSize = 15.sp)
                    BasicTextField(
                        // Enter = newline; send only via the button. Body max 10,000.
                        value = input, onValueChange = { if (it.length <= MAX_BODY_CHARS) { input = it; logRefused = false } },
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
                        if (org.cmchat.app.chat.EngineLog.looksLikeLog(text)) {
                            logRefused = true
                        } else if (text.isNotEmpty()) {
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

/** "1.2 MB" / "340 KB" (the units the phone itself shows). */
private fun humanSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> Tr.s(R.string.size_mb, "%.1f".format(bytes / 1_000_000.0))
    bytes >= 1_000 -> Tr.s(R.string.size_kb, bytes / 1_000)
    else -> Tr.s(R.string.size_b, bytes)
}

/** A system line in the chosen language (its English [ChatMessage.text] otherwise). */
private fun shownText(m: org.cmchat.app.chat.ChatMessage): String = when (val n = m.note) {
    null -> m.text
    org.cmchat.app.chat.SystemNote.DecoyErased -> Tr.s(R.string.chat_decoy_notice)
    is org.cmchat.app.chat.SystemNote.TeamClock -> when {
        n.by == null && n.time != null -> Tr.s(R.string.chat_tc_me_set, n.time)
        n.by == null -> Tr.s(R.string.chat_tc_me_off)
        n.time != null -> Tr.s(R.string.chat_tc_they_set, n.by.ifEmpty { Tr.s(R.string.chat_your_friend) }, n.time)
        else -> Tr.s(R.string.chat_tc_they_off, n.by.ifEmpty { Tr.s(R.string.chat_your_friend) })
    }
}

/** What happened to a file (or why it wasn't sent), in the user's language. */
private fun noteText(n: org.cmchat.app.media.MediaPolicy.Note): String = Tr.s(when (n) {
    org.cmchat.app.media.MediaPolicy.Note.TOO_BIG -> R.string.file_too_big
    org.cmchat.app.media.MediaPolicy.Note.EMPTY -> R.string.file_empty
    org.cmchat.app.media.MediaPolicy.Note.CANT_CLEAN -> R.string.file_cant_clean
    org.cmchat.app.media.MediaPolicy.Note.VIDEO_TRACK -> R.string.file_video_track
    org.cmchat.app.media.MediaPolicy.Note.RAW -> R.string.file_raw
    org.cmchat.app.media.MediaPolicy.Note.VIDEO_TYPE -> R.string.file_video_type
    org.cmchat.app.media.MediaPolicy.Note.UNREADABLE -> R.string.file_unreadable
    org.cmchat.app.media.MediaPolicy.Note.NO_MEMORY -> R.string.file_no_memory
    org.cmchat.app.media.MediaPolicy.Note.PHOTO_CLEANED -> R.string.file_photo_cleaned
    org.cmchat.app.media.MediaPolicy.Note.VIDEO_CLEANED -> R.string.file_video_cleaned
    org.cmchat.app.media.MediaPolicy.Note.PHOTO_CONVERTED -> R.string.file_photo_converted
    org.cmchat.app.media.MediaPolicy.Note.AS_IS -> R.string.file_as_is
})

/** Kinds of file that can run or open something — a received one gets a warning. */
private val RISKY = setOf("apk", "exe", "bat", "cmd", "com", "msi", "scr", "js", "vbs", "jar", "sh",
    "html", "htm", "svg", "xhtml", "hta", "ps1", "dex", "so")

/**
 * A file in the chat. Received: name, size and a Save button — it is NEVER
 * opened, previewed or interpreted by the app (it's opaque bytes); Save writes
 * it exactly as it arrived to a place YOU pick.
 */
@Composable
private fun FileBubble(m: ChatMessage, f: org.cmchat.app.chat.ChatFile, maxBubble: androidx.compose.ui.unit.Dp,
                       onSave: (org.cmchat.app.chat.ChatFile) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start) {
        Column(horizontalAlignment = if (m.mine) Alignment.End else Alignment.Start) {
            if (m.missed || m.closedMiss) {
                Text(Tr.s(R.string.missed_message), color = CmRed, fontFamily = Nunito, fontSize = 11.sp,
                    fontStyle = FontStyle.Italic, modifier = Modifier.padding(bottom = 2.dp))
            }
            Column(Modifier.widthIn(max = maxBubble).clip(RoundedCornerShape(16.dp))
                .background(if (m.mine) CmBubbleMine else CmBubbleTheirs)
                .padding(horizontal = 14.dp, vertical = 10.dp)) {
                val fg = if (m.mine) CmBubbleMineText else CmBubbleText
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PaperclipIcon(fg, 16)
                    Spacer(Modifier.width(6.dp))
                    Text(f.name, color = fg, fontFamily = Nunito, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(humanSize(f.size), color = fg.copy(alpha = 0.75f), fontFamily = Nunito, fontSize = 12.sp)
                if (!m.mine) {
                    if (f.name.substringAfterLast('.', "").lowercase() in RISKY) {
                        Text(Tr.s(R.string.chat_risky_file),
                            color = CmRedGlow, fontFamily = Nunito, fontSize = 11.sp)
                    }
                    Text(Tr.s(R.string.save), color = CmBlue, fontFamily = Nunito, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(8.dp))
                            .background(CmBackground).clickable { onSave(f) }
                            .padding(horizontal = 14.dp, vertical = 6.dp))
                }
            }
            Row(Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(formatTimestamp(m.createdAt), color = CmTextFaint, fontFamily = Nunito, fontSize = 10.sp)
                if (m.selfTimer != SelfTimer.OFF) {
                    Text(if (m.selfTimer == SelfTimer.VIEW_ONCE) Tr.s(R.string.chat_view_once_mark) else m.selfTimer.displayLabel(),
                        color = CmRed, fontFamily = Nunito, fontSize = 10.sp)
                }
            }
        }
    }
}

/** The attach paperclip (Material "attach file" outline, drawn — no font glyph). */
@Composable
private fun PaperclipIcon(color: androidx.compose.ui.graphics.Color, sizeDp: Int = 22) {
    val path = remember {
        androidx.compose.ui.graphics.vector.PathParser().parsePathString(
            "M16.5,6v11.5c0,2.21 -1.79,4 -4,4s-4,-1.79 -4,-4V5c0,-1.38 1.12,-2.5 2.5,-2.5s2.5,1.12 2.5,2.5v10.5" +
                "c0,0.55 -0.45,1 -1,1s-1,-0.45 -1,-1V6H10v9.5c0,1.38 1.12,2.5 2.5,2.5s2.5,-1.12 2.5,-2.5V5" +
                "c0,-2.21 -1.79,-4 -4,-4S7,2.79 7,5v12.5c0,3.04 2.46,5.5 5.5,5.5s5.5,-2.46 5.5,-5.5V6h-1.5z"
        ).toPath()
    }
    Canvas(Modifier.size(sizeDp.dp)) {
        val k = size.minDimension / 24f
        scale(k, k, pivot = Offset.Zero) { drawPath(path, color) }
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

/** "1h23m" style countdown for the Kill pill. */
private fun countdown(ms: Long): String {
    val secs = (ms / 1000).coerceAtLeast(0)
    return if (secs >= 3600) "${secs / 3600}h${(secs % 3600) / 60}m" else "${secs / 60}m${secs % 60}s"
}

/** The current time, re-read every [periodMs] — ONLY in the composable that shows it. */
@Composable
private fun tick(periodMs: Long, active: Boolean = true): Long {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    if (active) LaunchedEffect(periodMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(periodMs - now % periodMs)      // on the boundary, not drifting
        }
    }
    return now
}

/** The timer the NEXT message gets, as a small pill (🔥 = self-destruct). */
@Composable
private fun TimerPill(t: SelfTimer) {
    val on = t != SelfTimer.OFF
    val label = when (t) {
        SelfTimer.OFF -> Tr.s(R.string.timer_off_short)
        SelfTimer.VIEW_ONCE -> Tr.s(R.string.chat_view_once_short)
        else -> t.displayLabel()
    }
    Pill(outline = if (on) CmRed else CmTextFaint) {
        Text("🔥", fontSize = 12.sp)
        Spacer(Modifier.width(4.dp))
        Text(label, color = if (on) CmRed else CmTextDim, fontFamily = Nunito, fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** The Kill Timer: a drawn power symbol + its countdown (ticks only while armed). */
@Composable
private fun KillPill() {
    val deadline by org.cmchat.app.guard.GuardController.killDeadline.collectAsState()
    val now = tick(1000, active = deadline != null)
    val armed = deadline != null
    Pill(outline = if (armed) CmRed else CmTextFaint) {
        org.cmchat.app.ui.components.PowerGlyph(if (armed) CmRed else CmTextDim, sizeDp = 14, strokeUnits = 2.6f)
        Spacer(Modifier.width(5.dp))
        Text(deadline?.let { countdown(it - now) } ?: "off",
            color = if (armed) CmRed else CmTextDim,
            fontFamily = Nunito, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** This chat's Team Clock (ticks once a minute, only while set). */
@Composable
private fun TeamClockPill(teamOffset: Int?, onClick: (() -> Unit)?) {
    val now = tick(60_000, active = teamOffset != null)
    Pill(outline = CmBlue, onClick = onClick) {
        if (teamOffset != null) {
            Text(Tr.s(R.string.chat_team) + " ", color = CmTextDim, fontFamily = Nunito, fontSize = 12.sp)
            Text(TeamClock.time12(now, teamOffset), color = CmBlue, fontFamily = Nunito,
                fontSize = 13.sp, fontWeight = FontWeight.Bold)
        } else {
            Text(Tr.s(R.string.chat_set_team_clock), color = CmBlue, fontFamily = Nunito, fontSize = 12.sp)
        }
    }
}

/** "last seen recently" (coarse: re-checked once a minute). */
@Composable
private fun LastSeenLine(seenMs: Long?) {
    val now = tick(60_000, active = seenMs != null)
    LastSeen.bucket(seenMs, now)?.let {
        Text(it, color = CmTextFaint, fontFamily = Nunito, fontSize = 11.sp, maxLines = 1)
    }
}

/**
 * Buzz: no countdown on screen. After a Buzz it is greyed out and disabled
 * until it may send again, then it comes back by itself.
 */
@Composable
private fun BuzzButton(chatCmId: String?) {
    var ready by remember(chatCmId) {
        mutableStateOf(chatCmId != null && org.cmchat.app.buzz.BuzzPolicy.canSend(chatCmId))
    }
    LaunchedEffect(chatCmId, ready) {
        if (chatCmId != null && !ready) {
            delay(org.cmchat.app.buzz.BuzzPolicy.sendCooldownRemainingMs(chatCmId) + 50)
            ready = org.cmchat.app.buzz.BuzzPolicy.canSend(chatCmId)
        }
    }
    Box(Modifier.clip(RoundedCornerShape(10.dp))
        .background(if (ready) CmOrange else CmCard)
        .clickable(enabled = ready) {
            if (chatCmId != null && MessageService.sendBuzz(chatCmId)) ready = false
        }
        .padding(horizontal = 12.dp, vertical = 5.dp)) {
        Text(Tr.s(R.string.chat_buzz), color = if (ready) CmBackground else CmTextFaint,
            fontFamily = Nunito, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
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

/**
 * One message: sent = blue rounded bubble on the RIGHT, received = grey rounded
 * bubble on the LEFT, with the small am/pm time under it. (System notices are
 * drawn by the list as centred grey text, never as a bubble.)
 */
@Composable
private fun Bubble(m: ChatMessage, maxBubble: androidx.compose.ui.unit.Dp) {
    Row(Modifier.fillMaxWidth(),
        horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start) {
        Column(horizontalAlignment = if (m.mine) Alignment.End else Alignment.Start) {
            if (m.missed || m.closedMiss) {
                Text(Tr.s(R.string.missed_message), color = CmRed, fontFamily = Nunito, fontSize = 11.sp,
                    fontStyle = FontStyle.Italic, modifier = Modifier.padding(bottom = 2.dp))
            }
            Box(Modifier.widthIn(max = maxBubble).clip(RoundedCornerShape(16.dp))
                .background(if (m.mine) CmBubbleMine else CmBubbleTheirs)
                .padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(m.text, color = if (m.mine) CmBubbleMineText else CmBubbleText, fontFamily = Nunito,
                    fontSize = 15.sp, fontStyle = if (m.missed || m.closedMiss) FontStyle.Italic else null)
            }
            // Time only (h:mm AM/PM) — no delivery/read receipts. A timed message
            // also shows its small RED self-timer (no countdown).
            Row(Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(formatTimestamp(m.createdAt), color = CmTextFaint, fontFamily = Nunito, fontSize = 10.sp)
                if (m.selfTimer != SelfTimer.OFF) {
                    Text(if (m.selfTimer == SelfTimer.VIEW_ONCE) Tr.s(R.string.chat_view_once_mark) else m.selfTimer.displayLabel(),
                        color = CmRed, fontFamily = Nunito, fontSize = 10.sp)
                }
            }
        }
    }
}
