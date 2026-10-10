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
            title = { Text("Your nickname") },
            text = {
                Column {
                    OutlinedTextField(value = v, onValueChange = { v = it.take(24) }, singleLine = true)
                    Text("Friends see this unless they gave you a name of their own.",
                        color = CmTextDim, fontFamily = Nunito, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = { v.trim().takeIf { it.isNotEmpty() }?.let(onRenameMe); renamingMe = false }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renamingMe = false }) { Text("Cancel") } },
        )
    }

    // The sensitive group is open when no Privacy PIN is set, or once it's entered.
    val privacyOpen = !privacyPinSet || privacyUnlocked

    Column(Modifier.fillMaxSize().background(CmBackground)) {
        Box(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("‹ Back", color = CmBlue, fontFamily = Nunito, fontSize = 15.sp,
                modifier = Modifier.align(Alignment.CenterStart).clickable { onBack() })
            Text("Settings", color = CmText, fontFamily = Nunito, fontSize = 17.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
        }

        // A lazy list: only the rows on screen are built, and a vault save or a
        // Tor tick doesn't rebuild the whole page.
        androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {

            // 1) Language first.
            item { Setting("Language", languageLabel, onClick = onLanguage, hint = "Choose the app's language.") }

            // 2) The groups.
            item { GroupHeader("Identity") }
            item { Setting("My identity (CMC-ID · QR)", onClick = onOpenMyId,
                hint = "Your address + QR for friends to add you.") }
            item { Setting("My nickname", myNickname, onClick = { renamingMe = true },
                hint = "The name your friends see for you.") }

            item { GroupHeader("Chats") }
            item { GeneralTimerRow() }
            item { BuzzFrequencyRow() }

            item { GroupHeader("Privacy & Safety 🔒") }
            if (!privacyOpen) {
                item {
                    Setting("Unlock Privacy & Safety", "🔒 locked", onClick = { askMode = PinMode.UNLOCK },
                        hint = "Guards the server, stealth, wipe, PIN and diagnostics settings.")
                }
            } else {
                if (!privacyPinSet) item {
                    Setting("Set a Privacy PIN", "set up", onClick = { askMode = PinMode.SET },
                        hint = "Locks this group (digits only).")
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
                            Text("Open as a calculator (cover)", color = CmText, fontFamily = Nunito, fontSize = 14.sp,
                                modifier = Modifier.weight(1f))
                            Text(if (coverOn) "Yes" else "No", color = if (coverOn) CmGreen else CmTextDim,
                                fontFamily = Nunito, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Text("Opens to a working calculator. Tap the same key 10× in a row to get in; " +
                            "tap “reset” 20× to choose a different key.", color = CmTextFaint, fontFamily = Nunito,
                            fontSize = 11.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                            modifier = Modifier.padding(start = 4.dp, top = 2.dp))
                    }
                }
                item { StatusDefaultRow() }
                item { Setting("My Server", onClick = onOpenMyServer, hint = "Your own address that friends connect to.") }
                item { Setting("Stealth / Bridges (obfs4 · Snowflake)", onClick = onOpenBridges,
                    hint = "Hide that you use Tor from your network. Can be slower.") }
                item { InfoRow("Photo / video data removal", "Yes — always",
                    "Location and camera data are removed from every photo and video you send.") }
                item { Setting("Keep engine running in background", onClick = onIgnoreBattery,
                    hint = "Ask Android not to sleep the engine so messages still arrive.") }
                item { SessionWindowRow(onSessionWindow) }
                item { Setting("Change app PIN", onClick = { showChangePin = true },
                    hint = "Change the PIN that unlocks the app.") }
                if (privacyPinSet) {
                    item { Setting("Change Privacy PIN", onClick = { askMode = PinMode.CHANGE },
                        hint = "Enter the current PIN, then set a new one.") }
                    item { Setting("Remove Privacy PIN", onClick = { askMode = PinMode.REMOVE },
                        hint = "This group then opens without a PIN.") }
                }
                item { GroupHeader("Diagnostics") }
                item { Setting("Diagnostics & troubleshoot", onClick = onOpenDiagnostics,
                    hint = "See what's happening if something isn't working.") }
                item { Setting("Connection test (Link Test)", onClick = onOpenConnection,
                    hint = "Watch each step of reaching a friend, live.") }
                item { Setting("RAM diagnostics", onClick = onRamDiag,
                    hint = "See which features use the most memory.") }
                item {
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(CmRed.copy(alpha = 0.15f)).clickable { onWipeEverything() }.padding(14.dp),
                        contentAlignment = Alignment.Center) {
                        Text("Wipe Everything Now", color = CmRed, fontFamily = Nunito,
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // 3) Everything else.
            item { GroupHeader("System") }
            item { TextSizeRow(textSize, onTextSize) }
            item { Setting("Verify App Integrity", onClick = onOpenIntegrity, hint = "Check the app's signature + version.") }
            item { Setting("How to use", onClick = onHelp, hint = "Plain-language guide, from setup to panic buttons.") }
            item { Setting("About / Version", onClick = onAbout, hint = "App version and credits.") }

            // 4) Tools last.
            item { GroupHeader("Tools") }
            item { ToolToggle("Tool: Calculator", org.cmchat.app.tools.ToolsState.calcEnabled) }
            item { ToolToggle("Tool: Notes", org.cmchat.app.tools.ToolsState.notesEnabled) }
            item { ToolToggle("Tool: Flashlight", org.cmchat.app.tools.ToolsState.flashlightEnabled) }
            item { Spacer(Modifier.height(4.dp)) }
        }

        Box(Modifier.fillMaxWidth().padding(16.dp)
            .clip(RoundedCornerShape(14.dp)).background(CmCard).clickable { onExit() }.padding(14.dp),
            contentAlignment = Alignment.Center) {
            Text("Exit (stop server, clear RAM, log out)", color = CmText, fontFamily = Nunito,
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
                Text("Cerberus · idle auto-wipe armed", color = CmText, fontFamily = Nunito, fontSize = 14.sp)
                Text(if (reach) "Off while \"Stay reachable\" is on."
                     else "Untouched this long → clears RAM, stops the engine, closes the app.",
                    color = CmTextFaint, fontFamily = Nunito, fontSize = 11.sp)
            }
            Text(if (armed) "Yes" else "No", color = if (armed) CmGreen else CmTextDim,
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
            title = "Kill Timer — fires at",
            initialHour = c.get(java.util.Calendar.HOUR_OF_DAY),
            initialMinute = c.get(java.util.Calendar.MINUTE),
            note = "At this time it clears RAM, stops the engine and closes the app.",
            confirmLabel = "Arm",
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
                Text("Kill Timer", color = CmText, fontFamily = Nunito, fontSize = 14.sp)
                Text(if (reach) "Off while \"Stay reachable\" is on."
                     else "Like an alarm: at that time it clears RAM, stops the engine, closes the app.",
                    color = CmTextFaint, fontFamily = Nunito, fontSize = 11.sp)
            }
            val d = deadline
            if (d != null) {
                val secs = ((d - now) / 1000).coerceAtLeast(0)
                Column(horizontalAlignment = Alignment.End) {
                    Text(org.cmchat.app.chat.formatTimestamp(d), color = CmRed, fontFamily = Nunito,
                        fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text("in %d:%02d:%02d".format(secs / 3600, (secs % 3600) / 60, secs % 60),
                        color = CmTextDim, fontFamily = Nunito, fontSize = 11.sp)
                }
            } else {
                Text("not armed", color = CmTextDim, fontFamily = Nunito, fontSize = 13.sp)
            }
        }
        if (deadline != null) {
            Text("Cancel Kill Timer", color = CmBlue, fontFamily = Nunito, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                    .clickable { org.cmchat.app.guard.GuardController.cancelKillTimer() }.padding(6.dp))
        } else {
            Text("Set the time…", color = if (reach) CmTextFaint else CmBlue, fontFamily = Nunito,
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
            Text("Text size", color = CmText, fontFamily = Nunito, fontSize = 14.sp,
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
            Text("Stay unlocked for 6h", color = CmText, fontFamily = Nunito, fontSize = 14.sp)
            Text("Don't re-ask the PIN for 6h after unlocking (this run only).",
                color = CmTextFaint, fontFamily = Nunito, fontSize = 11.sp)
        }
        Text(if (on) "Yes" else "No", color = if (on) CmGreen else CmTextDim,
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
                    entry = ""; err = "Wrong PIN"
                }
            }
            "new" -> {
                if (v.length in VaultManager.MIN_PASSCODE..VaultManager.MAX_PASSCODE && v.all { it.isDigit() }) {
                    newPin = v; entry = ""; err = null; phase = "confirm"
                } else err = "Use 4 to 56 digits"
            }
            "confirm" -> {
                if (v == newPin) { onSetNew(v); onPass() }
                else { err = "PINs didn't match — start again"; entry = ""; newPin = ""; phase = "new" }
            }
        }
    }

    val title = when {
        phase == "current" && mode == PinMode.UNLOCK -> "Unlock Privacy & Safety"
        phase == "current" && mode == PinMode.CHANGE -> "Enter current PIN"
        phase == "current" && mode == PinMode.REMOVE -> "Remove the Privacy PIN"
        phase == "new" -> "Create a Privacy PIN"
        phase == "confirm" -> "Confirm the Privacy PIN"
        else -> "Privacy PIN"
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
                    Text("Without it, Privacy & Safety opens for anyone holding the unlocked phone.",
                        color = CmTextDim, fontFamily = Nunito, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
                if (phase == "new") {
                    Text("Digits · 4 to 56", color = CmTextDim, fontFamily = Nunito,
                        fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    PinStrengthHint(entry)
                }
                if (lockedFor > 0) {
                    Text("Too many tries — wait " + LoginThrottle.format(lockedFor),
                        color = CmRed, fontFamily = Nunito, fontSize = 12.sp)
                } else err?.let {
                    Text(it, color = CmRed, fontFamily = Nunito, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            val label = when (phase) {
                "current" -> if (mode == PinMode.REMOVE) "Remove" else "Unlock"
                "new" -> "Next"
                else -> if (mode == PinMode.CHANGE) "Change" else "Set"
            }
            TextButton(onClick = { submit() }, enabled = lockedFor <= 0) { Text(label) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
                        entry = ""; err = "Wrong PIN"
                    }
                }
            }
            "new" -> when {
                !org.cmchat.app.vault.VaultManager.isValidNewPin(entry) ->
                    err = "Use 4 to 56 characters, not the same backwards"
                entry == current -> err = "Choose a different PIN"
                else -> { newPin = entry; entry = ""; err = null; phase = "confirm" }
            }
            "confirm" -> {
                if (entry != newPin) {
                    err = "Didn't match — start again"; entry = ""; newPin = ""; phase = "new"
                } else {
                    working = true; err = null
                    onChange(current, newPin) { ok ->
                        working = false
                        if (ok) onDone()
                        else { err = "Could not change the PIN"; entry = ""; newPin = ""; phase = "new" }
                    }
                }
            }
        }
    }

    val title = when (phase) {
        "current" -> "Enter your current PIN"
        "new" -> "Create a new PIN"
        else -> "Confirm the new PIN"
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
                    Text("Numbers, letters or symbols · 4 to 56", color = CmTextDim, fontFamily = Nunito,
                        fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    PinStrengthHint(entry)
                }
                when {
                    working -> Text("Working…", color = CmTextDim, fontFamily = Nunito, fontSize = 12.sp)
                    lockedFor > 0 -> Text("Too many tries — wait " + LoginThrottle.format(lockedFor),
                        color = CmRed, fontFamily = Nunito, fontSize = 12.sp)
                    else -> err?.let { Text(it, color = CmRed, fontFamily = Nunito, fontSize = 12.sp) }
                }
            }
        },
        confirmButton = {
            val label = when (phase) { "current" -> "Next"; "new" -> "Next"; else -> "Change" }
            TextButton(onClick = { submit() }, enabled = !working && lockedFor <= 0) { Text(label) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !working) { Text("Cancel") } },
    )
}

@Composable
private fun ShredderRow() {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CmCard).padding(14.dp)) {
        Text("Shredder PIN", color = CmText, fontFamily = Nunito, fontSize = 14.sp)
        Text("Your PIN typed backwards at the lock screen silently erases everything; the app " +
            "then only shows an error until it's restarted.",
            color = CmTextFaint, fontFamily = Nunito, fontSize = 11.sp)
    }
}

@Composable
private fun StatusDefaultRow() {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CmCard).padding(14.dp)) {
        Text("Status default", color = CmText, fontFamily = Nunito, fontSize = 14.sp)
        Text("You always start Invisible at login; switch to Online from the Friends screen.",
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
                Text("Decoy chat", color = CmText, fontFamily = Nunito, fontSize = 14.sp)
                Text("A fake contact; tapping it silently Exits + wipes RAM.",
                    color = CmTextFaint, fontFamily = Nunito, fontSize = 11.sp)
            }
            Text(if (on) "Yes" else "No", color = if (on) CmGreen else CmTextDim,
                fontFamily = Nunito, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { org.cmchat.app.settings.AppSettings.decoyEnabled.value = !on })
        }
        if (on) {
            OutlinedTextField(
                value = name, onValueChange = { org.cmchat.app.settings.AppSettings.decoyName.value = it.take(24) },
                singleLine = true, label = { Text("Decoy name", color = CmTextDim) },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Position", color = CmTextDim, fontFamily = Nunito, fontSize = 13.sp,
                    modifier = Modifier.weight(1f))
                Text(if (top) "Top" else "Bottom", color = CmBlue, fontFamily = Nunito, fontSize = 13.sp,
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
            Text("Stay reachable in background", color = CmText, fontFamily = Nunito, fontSize = 14.sp)
            Text("Keeps the server up after close (forces Cerberus + Kill off)",
                color = CmTextFaint, fontFamily = Nunito, fontSize = 11.sp)
        }
        Text(if (on) "Yes" else "No", color = if (on) CmGreen else CmTextDim,
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
        Text("General timer (all messages)", color = CmText, fontFamily = Nunito, fontSize = 14.sp,
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
        Text("Accept Buzz", color = CmText, fontFamily = Nunito, fontSize = 14.sp,
            modifier = Modifier.weight(1f))
        Text(freq.label, color = CmBlue, fontFamily = Nunito, fontSize = 13.sp,
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
            Text(if (on) "Yes" else "No", color = if (on) CmGreen else CmTextDim,
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
