package org.cmchat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.foundation.horizontalScroll
import kotlin.math.roundToInt
import org.cmchat.app.chat.displayLabel
import org.cmchat.app.vault.LoginThrottle
import org.cmchat.app.vault.VaultManager
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.cmchat.app.ui.theme.*
import org.cmchat.app.R
import org.cmchat.app.i18n.Tr

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenMyServer: () -> Unit = {},
    onOpenMyId: () -> Unit = {},
    onOpenBridges: () -> Unit = {},
    onWipeEverything: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
    onOpenConnection: () -> Unit = {},
    onIgnoreBattery: () -> Unit = {},
    onExit: () -> Unit = {},
    onAbout: () -> Unit = {},
    onHelp: () -> Unit = {},
    onLanguage: () -> Unit = {},
    /** The language now in use, shown on its row. */
    languageLabel: String = "",
    onRamDiag: () -> Unit = {},
    privacyPinSet: Boolean = false,
    /** The stored Privacy PIN is all digits (typed on the number pad). */
    privacyPinNumeric: Boolean = true,
    verifyPrivacyPin: (String) -> Boolean = { false },
    onCreatePrivacyPin: (String) -> Unit = {},
    /** Remove the Privacy PIN (after the current one is entered). */
    onRemovePrivacyPin: () -> Unit = {},
    onSessionWindow: (Boolean) -> Unit = {},
    onOpenIntegrity: () -> Unit = {},
    verifyVaultPin: (String, (Boolean) -> Unit) -> Unit = { _, cb -> cb(false) },
    onChangeVaultPin: (String, String, (Boolean) -> Unit) -> Unit = { _, _, cb -> cb(false) },
    onCerberusChange: (armed: Boolean, minutes: Int) -> Unit = { _, _ -> },
    textSize: Int = 0,
    onTextSize: (Int) -> Unit = {},
    /** My own nickname (what friends see unless they named me). */
    myNickname: String = "",
    onRenameMe: (String) -> Unit = {},
    /** Cover mode: open to a calculator. */
    coverOn: Boolean = false,
    onCoverMode: (Boolean) -> Unit = {},
) {
    var privacyUnlocked by remember { mutableStateOf(false) }
    var askMode by remember { mutableStateOf<PinMode?>(null) }
    var showChangePin by remember { mutableStateOf(false) }
    var renamingMe by remember { mutableStateOf(false) }

    if (showChangePin) {
        ChangePinDialog(
            verifyCurrent = verifyVaultPin,
            onChange = onChangeVaultPin,
            onDone = { showChangePin = false },
            onDismiss = { showChangePin = false },
        )
    }

    askMode?.let { mode ->
        PrivacyPinDialog(
            mode = mode,
            storedNumeric = privacyPinNumeric,
            verify = verifyPrivacyPin,
            onSetNew = onCreatePrivacyPin,
            onRemove = onRemovePrivacyPin,
            onPass = {
                privacyUnlocked = true
                askMode = null
            },
            onDismiss = { askMode = null },
        )
    }

    if (renamingMe) {
        var v by remember { mutableStateOf(myNickname) }
        AlertDialog(
            onDismissRequest = { renamingMe = false },
            title = { Text(Tr.s(R.string.set_nickname_title)) },
            text = {
                Column {
                    OutlinedTextField(value = v, onValueChange = { v = it.take(24) }, singleLine = true)
                    Text(Tr.s(R.string.set_nickname_hint),
                        color = CmTextDim, fontFamily = Nunito, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = { v.trim().takeIf { it.isNotEmpty() }?.let(onRenameMe); renamingMe = false }) { Text(Tr.s(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { renamingMe = false }) { Text(Tr.s(R.string.cancel)) } },
        )
    }

    // The sensitive group is open when no Privacy PIN is set, or once it's entered.
    val privacyOpen = !privacyPinSet || privacyUnlocked

    Column(Modifier.fillMaxSize().background(CmBackground)) {
        Box(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(Tr.s(R.string.back), color = CmBlue, fontFamily = Nunito, fontSize = 15.sp,
                modifier = Modifier.align(Alignment.CenterStart).clickable { onBack() })
            Text(Tr.s(R.string.friends_settings), color = CmText, fontFamily = Nunito, fontSize = 17.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
        }

        // A lazy list: only the rows on screen are built, and a vault save or a
        // Tor tick doesn't rebuild the whole page.
        androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {

            // 1) Language first.
            item { Setting(Tr.s(R.string.lang_language), languageLabel, onClick = onLanguage, hint = Tr.s(R.string.set_language_hint)) }

            // 2) The groups.
            item { GroupHeader(Tr.s(R.string.set_identity)) }
            item { Setting(Tr.s(R.string.set_my_identity_cmc_id), onClick = onOpenMyId,
                hint = Tr.s(R.string.set_myid_hint)) }
            item { Setting(Tr.s(R.string.set_my_nickname), myNickname, onClick = { renamingMe = true },
                hint = Tr.s(R.string.set_nickname_row_hint)) }

            item { GroupHeader(Tr.s(R.string.set_chats)) }
            item { GeneralTimerRow() }
            item { BuzzFrequencyRow() }

            item { GroupHeader(Tr.s(R.string.set_privacy_group)) }
            if (!privacyOpen) {
                item {
                    Setting(Tr.s(R.string.set_unlock_privacy), Tr.s(R.string.set_locked), onClick = { askMode = PinMode.UNLOCK },
                        hint = Tr.s(R.string.set_privacy_guards))
                }
            } else {
                if (!privacyPinSet) item {
                    Setting(Tr.s(R.string.set_set_privacy_pin), Tr.s(R.string.set_set_up), onClick = { askMode = PinMode.SET },
                        hint = Tr.s(R.string.set_privacy_pin_hint))
                }
                item { CerberusRow(onCerberusChange) }
                item { KillTimerRow() }
                item { StayReachableRow() }
                item { ShredderRow() }
                item { DecoyGroup() }
                item {
                    Column {
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CmCard)
                            .clickable { onCoverMode(!coverOn) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(Tr.s(R.string.set_cover), color = CmText, fontFamily = Nunito, fontSize = 14.sp,
                                modifier = Modifier.weight(1f))
                            Text(if (coverOn) Tr.s(R.string.yes) else Tr.s(R.string.no), color = if (coverOn) CmGreen else CmTextDim,
                                fontFamily = Nunito, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Text(Tr.s(R.string.set_cover_hint), color = CmTextFaint, fontFamily = Nunito,
                            fontSize = 11.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                            modifier = Modifier.padding(start = 4.dp, top = 2.dp))
                    }
                }
                item { StatusDefaultRow() }
                item { Setting(Tr.s(R.string.server_my_server), onClick = onOpenMyServer, hint = Tr.s(R.string.set_server_hint)) }
                item { Setting(Tr.s(R.string.set_bridges), onClick = onOpenBridges,
                    hint = Tr.s(R.string.set_bridges_hint)) }
                item { InfoRow(Tr.s(R.string.set_scrub), Tr.s(R.string.set_scrub_value),
                    Tr.s(R.string.set_scrub_hint)) }
                item { Setting(Tr.s(R.string.set_keep_engine), onClick = onIgnoreBattery,
                    hint = Tr.s(R.string.set_keep_engine_hint)) }
                item { SessionWindowRow(onSessionWindow) }
                item { Setting(Tr.s(R.string.set_change_app_pin), onClick = { showChangePin = true },
                    hint = Tr.s(R.string.set_change_pin_hint)) }
                if (privacyPinSet) {
                    item { Setting(Tr.s(R.string.set_change_privacy_pin), onClick = { askMode = PinMode.CHANGE },
                        hint = Tr.s(R.string.set_change_privacy_pin_hint)) }
                    item { Setting(Tr.s(R.string.set_remove_privacy_pin), onClick = { askMode = PinMode.REMOVE },
                        hint = Tr.s(R.string.set_remove_privacy_pin_hint)) }
                }
                item { GroupHeader(Tr.s(R.string.diag_diagnostics)) }
                item { Setting(Tr.s(R.string.set_diag), onClick = onOpenDiagnostics,
                    hint = Tr.s(R.string.set_diag_hint)) }
                item { Setting(Tr.s(R.string.set_conn_test), onClick = onOpenConnection,
                    hint = Tr.s(R.string.set_conn_test_hint)) }
                item { Setting(Tr.s(R.string.ramdiag_ram_diagnostics), onClick = onRamDiag,
                    hint = Tr.s(R.string.set_ramdiag_hint)) }
                item {
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(CmRed.copy(alpha = 0.15f)).clickable { onWipeEverything() }.padding(14.dp),
                        contentAlignment = Alignment.Center) {
                        Text(Tr.s(R.string.set_wipe_now), color = CmRed, fontFamily = Nunito,
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // 3) Everything else.
            item { GroupHeader(Tr.s(R.string.set_system)) }
            item { TextSizeRow(textSize, onTextSize) }
            item { Setting(Tr.s(R.string.integ_verify_app_integrity), onClick = onOpenIntegrity, hint = Tr.s(R.string.set_integrity_hint)) }
            item { Setting(Tr.s(R.string.help_title), onClick = onHelp, hint = Tr.s(R.string.set_help_hint)) }
            item { Setting(Tr.s(R.string.set_about_version), onClick = onAbout, hint = Tr.s(R.string.set_about_hint)) }

            // 4) Tools last.
            item { GroupHeader(Tr.s(R.string.set_tools)) }
            item { ToolToggle(Tr.s(R.string.set_tool_calculator), org.cmchat.app.tools.ToolsState.calcEnabled) }
            item { ToolToggle(Tr.s(R.string.set_tool_notes), org.cmchat.app.tools.ToolsState.notesEnabled) }
            item { ToolToggle(Tr.s(R.string.set_tool_flashlight), org.cmchat.app.tools.ToolsState.flashlightEnabled) }
            item { Spacer(Modifier.height(4.dp)) }
        }

        Box(Modifier.fillMaxWidth().padding(16.dp)
            .clip(RoundedCornerShape(14.dp)).background(CmCard).clickable { onExit() }.padding(14.dp),
            contentAlignment = Alignment.Center) {
            Text(Tr.s(R.string.set_exit), color = CmText, fontFamily = Nunito,
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** A fixed fact (not a switch). */
@Composable
private fun InfoRow(label: String, value: String, hint: String) {
    Column {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CmCard).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = CmText, fontFamily = Nunito, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(value, color = CmGreen, fontFamily = Nunito, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Hint(hint)
    }
}

/**
 * Cerberus idle auto-wipe — operable: arm/disarm + pick the idle window. When it
 * fires it clears RAM, stops the engine and closes the app (the vault stays).
 * Disabled while "Stay reachable" is on (that mode forces both guardians off).
 */
@Composable
private fun CerberusRow(onChange: (Boolean, Int) -> Unit) {
    val armed by org.cmchat.app.guard.GuardController.cerberusArmed.collectAsState()
    val minutes by org.cmchat.app.guard.GuardController.cerberusMinutes.collectAsState()
    val reach by org.cmchat.app.settings.AppSettings.stayReachable.collectAsState()
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CmCard).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(Tr.s(R.string.set_cerberus), color = CmText, fontFamily = Nunito, fontSize = 14.sp)
                Text(if (reach) Tr.s(R.string.set_off_while_reachable)
                     else Tr.s(R.string.set_cerberus_hint),
                    color = CmTextFaint, fontFamily = Nunito, fontSize = 11.sp)
            }
            Text(if (armed) Tr.s(R.string.yes) else Tr.s(R.string.no), color = if (armed) CmGreen else CmTextDim,
                fontFamily = Nunito, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = !reach) { onChange(!armed, minutes) }.padding(6.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (m in org.cmchat.app.guard.GuardController.CERBERUS_CHOICES) {
                Chip(if (m >= 60) "${m / 60}h${if (m % 60 != 0) "${m % 60}m" else ""}" else "${m}m",
                    selected = m == minutes, enabled = !reach) { onChange(armed, m) }
            }
        }
    }
}

/**
 * Kill Timer — operable: arm a countdown (or cancel it) and watch it tick. When
 * it reaches zero it clears RAM, stops the engine and closes the app.
 */
@Composable
private fun KillTimerRow() {
    val deadline by org.cmchat.app.guard.GuardController.killDeadline.collectAsState()
    val reach by org.cmchat.app.settings.AppSettings.stayReachable.collectAsState()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(deadline) {
        while (deadline != null) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) }
    }
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        // Alarm-style: pick the time it fires (today, or tomorrow if it's passed).
        val c = java.util.Calendar.getInstance().apply { add(java.util.Calendar.HOUR_OF_DAY, 1) }
        org.cmchat.app.ui.components.AlarmTimeDialog(
            title = Tr.s(R.string.set_kill_title),
            initialHour = c.get(java.util.Calendar.HOUR_OF_DAY),
            initialMinute = c.get(java.util.Calendar.MINUTE),
            note = Tr.s(R.string.set_kill_note),
            confirmLabel = Tr.s(R.string.set_arm),
            onConfirm = { h, m ->
                org.cmchat.app.guard.GuardController.armKillAt(h, m)
                now = System.currentTimeMillis()
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CmCard).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(Tr.s(R.string.help_kill_t), color = CmText, fontFamily = Nunito, fontSize = 14.sp)
                Text(if (reach) Tr.s(R.string.set_off_while_reachable)
                     else Tr.s(R.string.set_kill_hint),
                    color = CmTextFaint, fontFamily = Nunito, fontSize = 11.sp)
            }
            val d = deadline
            if (d != null) {
                val secs = ((d - now) / 1000).coerceAtLeast(0)
                Column(horizontalAlignment = Alignment.End) {
                    Text(org.cmchat.app.chat.formatTimestamp(d), color = CmRed, fontFamily = Nunito,
                        fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(Tr.s(R.string.set_kill_in, "%d:%02d:%02d".format(secs / 3600, (secs % 3600) / 60, secs % 60)),
                        color = CmTextDim, fontFamily = Nunito, fontSize = 11.sp)
                }
            } else {
                Text(Tr.s(R.string.set_not_armed), color = CmTextDim, fontFamily = Nunito, fontSize = 13.sp)
            }
        }
        if (deadline != null) {
            Text(Tr.s(R.string.set_kill_cancel), color = CmBlue, fontFamily = Nunito, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                    .clickable { org.cmchat.app.guard.GuardController.cancelKillTimer() }.padding(6.dp))
        } else {
            Text(Tr.s(R.string.set_kill_set), color = if (reach) CmTextFaint else CmBlue, fontFamily = Nunito,
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = !reach) { picking = true }.padding(6.dp))
        }
    }
}

/** Text size, applied app-wide (every screen + dialog) and saved in the vault. */
@Composable
private fun TextSizeRow(saved: Int, onSave: (Int) -> Unit) {
    var v by remember(saved) { mutableStateOf(saved.toFloat()) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CmCard).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Tr.s(R.string.set_text_size), color = CmText, fontFamily = Nunito, fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("${(org.cmchat.app.settings.AppSettings.textScale(v.toInt()) * 100).toInt()}%",
                color = CmBlue, fontFamily = Nunito, fontSize = 13.sp)
        }
        Slider(
            value = v,
            onValueChange = {
                v = it
                org.cmchat.app.settings.AppSettings.textSize.value = it.roundToInt()  // live preview
            },
            onValueChangeFinished = { onSave(v.roundToInt()) },
            valueRange = -6f..6f, steps = 11,
        )
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(if (selected) CmBlue else CmCardHi)
        .clickable(enabled = enabled) { onClick() }.padding(horizontal = 10.dp, vertical = 6.dp)) {
        Text(label, color = when { selected -> CmBackground; enabled -> CmText; else -> CmTextFaint },
            fontFamily = Nunito, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SessionWindowRow(onChange: (Boolean) -> Unit) {
    val on by org.cmchat.app.settings.AppSettings.sessionWindowEnabled.collectAsState()
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CmCard)
        .clickable {
            val now = !on
            org.cmchat.app.settings.AppSettings.sessionWindowEnabled.value = now
            onChange(now)
        }
        .padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(Tr.s(R.string.set_session_window), color = CmText, fontFamily = Nunito, fontSize = 14.sp)
            Text(Tr.s(R.string.set_session_window_hint),
                color = CmTextFaint, fontFamily = Nunito, fontSize = 11.sp)
        }
        Text(if (on) Tr.s(R.string.yes) else Tr.s(R.string.no), color = if (on) CmGreen else CmTextDim,
            fontFamily = Nunito, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun GroupHeader(title: String) {
    Text(title, color = CmBlue, fontFamily = Nunito, fontSize = 12.sp,
        fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, start = 4.dp))
}

/** Which flow the privacy-PIN lock is running. */
enum class PinMode { UNLOCK, SET, CHANGE, REMOVE }

/**
 * A real lock for the privacy PIN (not a plain text field):
 *  - masked entry on the NUMBER pad: digits only, 4–56, with a strength hint
 *    while creating one (an older non-digit PIN can still be typed to verify);
 *  - SET / CHANGE require enter + confirm, with a clear mismatch error;
 *  - verifying the current PIN (UNLOCK / CHANGE) applies the same escalating
 *    lockout as the login screen ([LoginThrottle]);
 *  - distinct Set / Change / Remove actions (Remove also needs the current PIN).
 */
@Composable
private fun PrivacyPinDialog(
    mode: PinMode,
    storedNumeric: Boolean,
    verify: (String) -> Boolean,
    onSetNew: (String) -> Unit,
    onRemove: () -> Unit,
    onPass: () -> Unit,
    onDismiss: () -> Unit,
) {
    // phase: "current" (verify existing) -> "new" -> "confirm".
    var phase by remember { mutableStateOf(if (mode == PinMode.SET) "new" else "current") }
    var entry by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var wrongCount by remember { mutableStateOf(0) }
    var lockedFor by remember { mutableStateOf(0) }

    LaunchedEffect(lockedFor) {
        while (lockedFor > 0) { kotlinx.coroutines.delay(1000); lockedFor -= 1 }
    }

    fun submit() {
        if (lockedFor > 0) return
        val v = entry
        when (phase) {
            "current" -> {
                if (verify(v)) {
                    wrongCount = 0; entry = ""; err = null
                    when (mode) {
                        PinMode.UNLOCK -> onPass()
                        PinMode.CHANGE -> phase = "new"
                        PinMode.REMOVE -> { onRemove(); onPass() }
                        PinMode.SET -> {}
                    }
                } else {
                    wrongCount += 1
                    lockedFor = LoginThrottle.delaySeconds(wrongCount)
                    entry = ""; err = Tr.s(R.string.lock_wrong_pin)
                }
            }
            "new" -> {
                if (v.length in VaultManager.MIN_PASSCODE..VaultManager.MAX_PASSCODE && v.all { it.isDigit() }) {
                    newPin = v; entry = ""; err = null; phase = "confirm"
                } else err = Tr.s(R.string.set_privacy_pin_digits)
            }
            "confirm" -> {
                if (v == newPin) { onSetNew(v); onPass() }
                else { err = Tr.s(R.string.lock_pins_didn_t_match); entry = ""; newPin = ""; phase = "new" }
            }
        }
    }

    val title = when {
        phase == "current" && mode == PinMode.UNLOCK -> Tr.s(R.string.set_unlock_privacy)
        phase == "current" && mode == PinMode.CHANGE -> Tr.s(R.string.set_enter_current_pin)
        phase == "current" && mode == PinMode.REMOVE -> Tr.s(R.string.set_remove_privacy_pin_title)
        phase == "new" -> Tr.s(R.string.set_create_privacy_pin)
        phase == "confirm" -> Tr.s(R.string.set_confirm_privacy_pin)
        else -> Tr.s(R.string.set_privacy_pin)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                // The NUMBER pad — except to verify an older PIN that has letters.
                val numberPad = phase != "current" || storedNumeric
                org.cmchat.app.ui.components.PinField(
                    value = entry,
                    onValueChange = { v -> if (lockedFor <= 0) {
                        entry = (if (numberPad) v.filter { it.isDigit() } else v).take(VaultManager.MAX_PASSCODE)
                        err = null
                    } },
                    numeric = numberPad,
                    enabled = lockedFor <= 0,
                )
                if (mode == PinMode.REMOVE && phase == "current") {
                    Text(Tr.s(R.string.set_privacy_pin_why),
                        color = CmTextDim, fontFamily = Nunito, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
                if (phase == "new") {
                    Text(Tr.s(R.string.set_privacy_pin_format), color = CmTextDim, fontFamily = Nunito,
                        fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    PinStrengthHint(entry)
                }
                if (lockedFor > 0) {
                    Text(Tr.s(R.string.set_too_many_tries, LoginThrottle.format(lockedFor)),
                        color = CmRed, fontFamily = Nunito, fontSize = 12.sp)
                } else err?.let {
                    Text(it, color = CmRed, fontFamily = Nunito, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            val label = when (phase) {
                "current" -> if (mode == PinMode.REMOVE) Tr.s(R.string.set_remove) else Tr.s(R.string.set_unlock)
                "new" -> Tr.s(R.string.next)
                else -> if (mode == PinMode.CHANGE) Tr.s(R.string.set_change) else Tr.s(R.string.set)
            }
            TextButton(onClick = { submit() }, enabled = lockedFor <= 0) { Text(label) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Tr.s(R.string.cancel)) } },
    )
}

/**
 * Change the app PIN (the vault passcode that unlocks the app) — distinct from
 * the Privacy PIN above. Flow: verify current → choose new → confirm. Both
 * the verify and the re-encrypt run Argon2id, so they go through async callbacks
 * ([verifyCurrent]/[onChange] hand back the result on the main thread) and the
 * dialog shows "Working…" while they run. The new passcode is validated with the
 * same rule as first-run ([VaultManager.isValidNewPin]): 4–56 chars, not a
 * palindrome. No data is lost; afterwards only the new passcode opens the vault.
 */
@Composable
private fun ChangePinDialog(
    verifyCurrent: (String, (Boolean) -> Unit) -> Unit,
    onChange: (String, String, (Boolean) -> Unit) -> Unit,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
) {
    var phase by remember { mutableStateOf("current") }   // current -> new -> confirm
    var current by remember { mutableStateOf("") }
    var entry by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var wrongCount by remember { mutableStateOf(0) }
    var lockedFor by remember { mutableStateOf(0) }

    LaunchedEffect(lockedFor) {
        while (lockedFor > 0) { kotlinx.coroutines.delay(1000); lockedFor -= 1 }
    }

    fun submit() {
        if (working || lockedFor > 0) return
        when (phase) {
            "current" -> {
                working = true; err = null
                val tried = entry
                verifyCurrent(tried) { ok ->
                    working = false
                    if (ok) { current = tried; entry = ""; wrongCount = 0; phase = "new" }
                    else {
                        wrongCount += 1
                        lockedFor = LoginThrottle.delaySeconds(wrongCount)
                        entry = ""; err = Tr.s(R.string.lock_wrong_pin)
                    }
                }
            }
            "new" -> when {
                !org.cmchat.app.vault.VaultManager.isValidNewPin(entry) ->
                    err = Tr.s(R.string.set_pin_rules)
                entry == current -> err = Tr.s(R.string.set_pin_different)
                else -> { newPin = entry; entry = ""; err = null; phase = "confirm" }
            }
            "confirm" -> {
                if (entry != newPin) {
                    err = Tr.s(R.string.set_pin_mismatch); entry = ""; newPin = ""; phase = "new"
                } else {
                    working = true; err = null
                    onChange(current, newPin) { ok ->
                        working = false
                        if (ok) onDone()
                        else { err = Tr.s(R.string.set_pin_change_failed); entry = ""; newPin = ""; phase = "new" }
                    }
                }
            }
        }
    }

    val title = when (phase) {
        "current" -> Tr.s(R.string.set_enter_your_current_pin)
        "new" -> Tr.s(R.string.set_create_new_pin)
        else -> Tr.s(R.string.set_confirm_new_pin)
    }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(title) },
        text = {
            Column {
                org.cmchat.app.ui.components.PinField(
                    value = entry,
                    onValueChange = { v -> if (!working && lockedFor <= 0) { entry = v.take(if (phase == "current") 128 else 56); err = null } },
                    numeric = false,
                    enabled = !working && lockedFor <= 0,
                )
                if (phase == "new" && !working) {
                    Text(Tr.s(R.string.set_pin_format), color = CmTextDim, fontFamily = Nunito,
                        fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    PinStrengthHint(entry)
                }
                when {
                    working -> Text(Tr.s(R.string.set_working), color = CmTextDim, fontFamily = Nunito, fontSize = 12.sp)
                    lockedFor > 0 -> Text(Tr.s(R.string.set_too_many_tries, LoginThrottle.format(lockedFor)),
                        color = CmRed, fontFamily = Nunito, fontSize = 12.sp)
                    else -> err?.let { Text(it, color = CmRed, fontFamily = Nunito, fontSize = 12.sp) }
                }
            }
        },
        confirmButton = {
            val label = when (phase) { "current" -> Tr.s(R.string.next); "new" -> Tr.s(R.string.next); else -> Tr.s(R.string.set_change) }
            TextButton(onClick = { submit() }, enabled = !working && lockedFor <= 0) { Text(label) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !working) { Text(Tr.s(R.string.cancel)) } },
    )
}

@Composable
private fun ShredderRow() {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CmCard).padding(14.dp)) {
        Text(Tr.s(R.string.set_shredder), color = CmText, fontFamily = Nunito, fontSize = 14.sp)
        Text(Tr.s(R.string.set_shredder_hint),
            color = CmTextFaint, fontFamily = Nunito, fontSize = 11.sp)
    }
}

@Composable
private fun StatusDefaultRow() {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CmCard).padding(14.dp)) {
        Text(Tr.s(R.string.set_status_default), color = CmText, fontFamily = Nunito, fontSize = 14.sp)
        Text(Tr.s(R.string.set_status_default_hint),
            color = CmTextFaint, fontFamily = Nunito, fontSize = 11.sp)
    }
}

@Composable
private fun DecoyGroup() {
    val on by org.cmchat.app.settings.AppSettings.decoyEnabled.collectAsState()
    val name by org.cmchat.app.settings.AppSettings.decoyName.collectAsState()
    val top by org.cmchat.app.settings.AppSettings.decoyAtTop.collectAsState()
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CmCard).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(Tr.s(R.string.set_decoy), color = CmText, fontFamily = Nunito, fontSize = 14.sp)
                Text(Tr.s(R.string.set_decoy_hint),
                    color = CmTextFaint, fontFamily = Nunito, fontSize = 11.sp)
            }
            Text(if (on) Tr.s(R.string.yes) else Tr.s(R.string.no), color = if (on) CmGreen else CmTextDim,
                fontFamily = Nunito, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { org.cmchat.app.settings.AppSettings.decoyEnabled.value = !on })
        }
        if (on) {
            OutlinedTextField(
                value = Tr.decoyName(name), onValueChange = { org.cmchat.app.settings.AppSettings.decoyName.value = it.take(24) },
                singleLine = true, label = { Text(Tr.s(R.string.set_decoy_name), color = CmTextDim) },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Tr.s(R.string.set_position), color = CmTextDim, fontFamily = Nunito, fontSize = 13.sp,
                    modifier = Modifier.weight(1f))
                Text(if (top) Tr.s(R.string.set_top) else Tr.s(R.string.set_bottom), color = CmBlue, fontFamily = Nunito, fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { org.cmchat.app.settings.AppSettings.decoyAtTop.value = !top })
            }
        }
    }
}

@Composable
private fun StayReachableRow() {
    val on by org.cmchat.app.settings.AppSettings.stayReachable.collectAsState()
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CmCard)
        .clickable {
            val now = !on
            org.cmchat.app.settings.AppSettings.stayReachable.value = now
            if (now) {
                // Staying reachable forces the auto-wipers off.
                org.cmchat.app.guard.GuardController.setCerberusArmed(false)
                org.cmchat.app.guard.GuardController.cancelKillTimer()
            }
        }
        .padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(Tr.s(R.string.set_stay_reachable), color = CmText, fontFamily = Nunito, fontSize = 14.sp)
            Text(Tr.s(R.string.set_stay_reachable_hint),
                color = CmTextFaint, fontFamily = Nunito, fontSize = 11.sp)
        }
        Text(if (on) Tr.s(R.string.yes) else Tr.s(R.string.no), color = if (on) CmGreen else CmTextDim,
            fontFamily = Nunito, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun GeneralTimerRow() {
    val t by org.cmchat.app.settings.AppSettings.generalTimer.collectAsState()
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CmCard)
        .clickable {
            // The general timer is time-based only — view-once is a per-message
            // choice, so it's excluded from this cycle.
            val all = org.cmchat.app.chat.SelfTimer.entries
                .filter { it != org.cmchat.app.chat.SelfTimer.VIEW_ONCE }
            val i = all.indexOf(t).coerceAtLeast(0)
            org.cmchat.app.settings.AppSettings.generalTimer.value = all[(i + 1) % all.size]
        }
        .padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(Tr.s(R.string.set_general_timer), color = CmText, fontFamily = Nunito, fontSize = 14.sp,
            modifier = Modifier.weight(1f))
        Text(t.displayLabel(),
            color = if (t == org.cmchat.app.chat.SelfTimer.OFF) CmTextDim else CmRed,
            fontFamily = Nunito, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun BuzzFrequencyRow() {
    val freq by org.cmchat.app.buzz.BuzzPolicy.frequency.collectAsState()
    // Tap cycles through how often a person's buzzes are accepted.
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CmCard)
        .clickable {
            val all = org.cmchat.app.buzz.BuzzFrequency.entries
            org.cmchat.app.buzz.BuzzPolicy.frequency.value =
                all[(freq.ordinal + 1) % all.size]
        }
        .padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(Tr.s(R.string.set_accept_buzz), color = CmText, fontFamily = Nunito, fontSize = 14.sp,
            modifier = Modifier.weight(1f))
        Text(buzzLabel(freq), color = CmBlue, fontFamily = Nunito, fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold)
    }
}

/** A one-line, very-simple italic hint under a setting (<=60 chars). */
@Composable
private fun Hint(text: String) {
    Text(text.take(60), color = CmTextFaint, fontFamily = Nunito, fontSize = 11.sp,
        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
        modifier = Modifier.padding(start = 4.dp, top = 2.dp))
}

@Composable
private fun ToolToggle(label: String, flow: kotlinx.coroutines.flow.MutableStateFlow<Boolean>, hint: String = "") {
    val on by flow.collectAsState()
    Column {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CmCard)
            .clickable { flow.value = !on }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = CmText, fontFamily = Nunito, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(if (on) Tr.s(R.string.yes) else Tr.s(R.string.no), color = if (on) CmGreen else CmTextDim,
                fontFamily = Nunito, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        if (hint.isNotEmpty()) Hint(hint)
    }
}

@Composable
private fun Setting(label: String, value: String = "", hint: String = "", onClick: () -> Unit = {}) {
    Column {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CmCard)
            .clickable { onClick() }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = CmText, fontFamily = Nunito, fontSize = 14.sp,
                modifier = Modifier.weight(1f))
            if (value.isNotEmpty())
                Text(value, color = CmBlue, fontFamily = Nunito, fontSize = 13.sp)
        }
        if (hint.isNotEmpty()) Hint(hint)
    }
}

/** How often a Buzz is accepted, in the user's language (the stored value stays [BuzzFrequency.label]). */
private fun buzzLabel(f: org.cmchat.app.buzz.BuzzFrequency): String = when (f) {
    org.cmchat.app.buzz.BuzzFrequency.H1 -> Tr.s(R.string.buzz_every_h, 1)
    org.cmchat.app.buzz.BuzzFrequency.H12 -> Tr.s(R.string.buzz_every_h, 12)
    org.cmchat.app.buzz.BuzzFrequency.H24 -> Tr.s(R.string.buzz_every_h, 24)
    org.cmchat.app.buzz.BuzzFrequency.ONCE -> Tr.s(R.string.buzz_once)
}
