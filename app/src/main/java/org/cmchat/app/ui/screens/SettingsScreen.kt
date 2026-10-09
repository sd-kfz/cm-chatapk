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
    onRamDiag: () -> Unit = {},
    privacyPinSet: Boolean = false,
    verifyPrivacyPin: (String) -> Boolean = { false },
    onCreatePrivacyPin: (String) -> Unit = {},
    onSessionWindow: (Boolean) -> Unit = {},
    onOpenIntegrity: () -> Unit = {},
    verifyVaultPin: (String, (Boolean) -> Unit) -> Unit = { _, cb -> cb(false) },
    onChangeVaultPin: (String, String, (Boolean) -> Unit) -> Unit = { _, _, cb -> cb(false) },
    onCerberusChange: (armed: Boolean, minutes: Int) -> Unit = { _, _ -> },
    textSize: Int = 0,
    onTextSize: (Int) -> Unit = {},
) {
    var privacyUnlocked by remember { mutableStateOf(false) }
    var askMode by remember { mutableStateOf<PinMode?>(null) }
    var showChangePin by remember { mutableStateOf(false) }

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
            verify = verifyPrivacyPin,
            onSetNew = onCreatePrivacyPin,
            onPass = {
                if (mode == PinMode.UNLOCK || mode == PinMode.SET) privacyUnlocked = true
                askMode = null
            },
            onDismiss = { askMode = null },
        )
    }

    Column(Modifier.fillMaxSize().background(CmBackground)) {
        Box(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("‹ Back", color = CmBlue, fontFamily = Nunito, fontSize = 15.sp,
                modifier = Modifier.align(Alignment.CenterStart).clickable { onBack() })
            Text("Settings", color = CmText, fontFamily = Nunito, fontSize = 17.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {

            // One identity per app (no multiple "Faces"/tags).
            GroupHeader("Identity")
            Setting("My identity (CMC-ID · QR)", onClick = onOpenMyId,
                hint = "Your address + QR for friends to add you.")

            GroupHeader("Chats")
            GeneralTimerRow()
            ToolToggle("Share my last-seen", org.cmchat.app.settings.AppSettings.shareLastSeen,
                hint = "Let friends see you were online recently.")
            BuzzFrequencyRow()
            ToolToggle("Let a Buzz reach me when closed",
                org.cmchat.app.settings.AppSettings.buzzListenerWhenClosed,
                hint = "A nudge can still wake you after you close the app.")

            // SENSITIVE settings — behind the Privacy PIN. The PIN can be CHANGED
            // but never removed: once set, this section is always gated.
            GroupHeader("Privacy & Safety 🔒")
            if (!privacyUnlocked) {
                Setting(
                    if (privacyPinSet) "Unlock Privacy & Safety" else "Set a Privacy PIN (4-8 digits)",
                    if (privacyPinSet) "🔒 locked" else "set up",
                    onClick = { askMode = if (privacyPinSet) PinMode.UNLOCK else PinMode.SET },
                    hint = "Guards the server, stealth, wipe and passcode settings.",
                )
            } else {
                CerberusRow(onCerberusChange)
                KillTimerRow()
                StayReachableRow()
                ShredderRow()
                DecoyGroup()
                StatusDefaultRow()
                Setting("My Server", onClick = onOpenMyServer,
                    hint = "Your own address that friends connect to.")
                Setting("Stealth / Bridges (obfs4 · Snowflake)", onClick = onOpenBridges,
                    hint = "Hide that you use Tor from your network. Can be slower.")
                ToolToggle("Metadata scrub (strip EXIF/GPS)", org.cmchat.app.settings.AppSettings.metadataScrub,
                    hint = "Removes hidden location/date from photos you send.")
                Setting("Keep engine running in background", onClick = onIgnoreBattery,
                    hint = "Ask Android not to sleep the engine so messages still arrive.")
                SessionWindowRow(onSessionWindow)
                Setting("Change PIN (app passcode)", onClick = { showChangePin = true },
                    hint = "Change the passcode that unlocks the app.")
                Setting("Change Privacy PIN", onClick = { askMode = PinMode.CHANGE },
                    hint = "Enter the current PIN, then set a new one.")
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(CmRed.copy(alpha = 0.15f)).clickable { onWipeEverything() }.padding(14.dp),
                    contentAlignment = Alignment.Center) {
                    Text("Wipe Everything Now", color = CmRed, fontFamily = Nunito,
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            GroupHeader("Tools")
            ToolToggle("Tool: Calculator", org.cmchat.app.tools.ToolsState.calcEnabled)
            ToolToggle("Tool: Notes", org.cmchat.app.tools.ToolsState.notesEnabled)
            ToolToggle("Tool: Flashlight", org.cmchat.app.tools.ToolsState.flashlightEnabled)

            GroupHeader("System")
            TextSizeRow(textSize, onTextSize)
            Setting("Diagnostics & troubleshoot", onClick = onOpenDiagnostics,
                hint = "See what's happening if something isn't working.")
            Setting("Connection test (Link Test)", onClick = onOpenConnection,
                hint = "Watch each step of reaching a friend, live.")
            Setting("RAM diagnostics", onClick = onRamDiag,
                hint = "See which features use the most memory.")
            Setting("Verify App Integrity", onClick = onOpenIntegrity,
                hint = "Check the app's signature + version.")
            Setting("Language", onClick = onLanguage,
                hint = "Choose the app's language.")
            Setting("How to use", onClick = onHelp,
                hint = "Plain-language guide, from setup to panic buttons.")
            Setting("About / Version", onClick = onAbout,
                hint = "App version and credits.")
            Spacer(Modifier.height(4.dp))
        }

        Box(Modifier.fillMaxWidth().padding(16.dp)
            .clip(RoundedCornerShape(14.dp)).background(CmCard).clickable { onExit() }.padding(14.dp),
            contentAlignment = Alignment.Center) {
            Text("Exit (stop server, clear RAM, log out)", color = CmText, fontFamily = Nunito,
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
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
                Text("Cerberus · idle auto-wipe", color = CmText, fontFamily = Nunito, fontSize = 14.sp)
                Text(if (reach) "Off while \"Stay reachable\" is on."
                     else "Untouched this long → clears RAM, stops the engine, closes the app.",
                    color = CmTextFaint, fontFamily = Nunito, fontSize = 11.sp)
            }
            Text(if (armed) "Armed" else "Off", color = if (armed) CmGreen else CmTextDim,
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
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CmCard).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Kill Timer", color = CmText, fontFamily = Nunito, fontSize = 14.sp)
                Text(if (reach) "Off while \"Stay reachable\" is on."
                     else "A countdown: at zero it clears RAM, stops the engine, closes the app.",
                    color = CmTextFaint, fontFamily = Nunito, fontSize = 11.sp)
            }
            val d = deadline
            if (d != null) {
                val secs = ((d - now) / 1000).coerceAtLeast(0)
                Text("%d:%02d:%02d".format(secs / 3600, (secs % 3600) / 60, secs % 60), color = CmRed,
                    fontFamily = Nunito, fontSize = 15.sp, fontWeight = FontWeight.Bold)
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
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())) {
                for (m in org.cmchat.app.guard.GuardController.KILL_CHOICES) {
                    Chip(if (m >= 60) "${m / 60}h" else "${m}m", selected = false, enabled = !reach) {
                        org.cmchat.app.guard.GuardController.armKillTimer(m * 60_000L)
                    }
                }
            }
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
            Text("Don't re-ask the passcode for 6h after unlocking (this run only).",
                color = CmTextFaint, fontFamily = Nunito, fontSize = 11.sp)
        }
        Text(if (on) "On" else "Off", color = if (on) CmGreen else CmTextDim,
            fontFamily = Nunito, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun GroupHeader(title: String) {
    Text(title, color = CmBlue, fontFamily = Nunito, fontSize = 12.sp,
        fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, start = 4.dp))
}

/** Which flow the privacy-PIN lock is running. There is deliberately NO
 * "remove": the Privacy PIN can be changed, never turned off. */
enum class PinMode { UNLOCK, SET, CHANGE }

/**
 * A real digital-lock for the privacy PIN (not a plain text field):
 *  - masked numeric entry;
 *  - SET / CHANGE require enter + confirm, with a clear mismatch error;
 *  - verifying the current PIN (UNLOCK / CHANGE / REMOVE) applies the same
 *    escalating lockout as the login screen ([LoginThrottle]);
 *  - distinct Set / Change actions (no Remove — the PIN can't be turned off).
 */
@Composable
private fun PrivacyPinDialog(
    mode: PinMode,
    verify: (String) -> Boolean,
    onSetNew: (String) -> Unit,
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
                        PinMode.SET -> {}
                    }
                } else {
                    wrongCount += 1
                    lockedFor = LoginThrottle.delaySeconds(wrongCount)
                    entry = ""; err = "Wrong PIN"
                }
            }
            "new" -> {
                if (v.length in 4..8) { newPin = v; entry = ""; err = null; phase = "confirm" }
                else err = "Use 4 to 8 digits"
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
        phase == "new" -> "Choose a new PIN (4-8 digits)"
        phase == "confirm" -> "Re-enter the new PIN"
        else -> "Privacy PIN"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = entry,
                    onValueChange = { v -> if (lockedFor <= 0) { entry = v.filter { it.isDigit() }.take(8); err = null } },
                    singleLine = true,
                    enabled = lockedFor <= 0,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword),
                )
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
                "current" -> "Unlock"
                "new" -> "Next"
                else -> if (mode == PinMode.CHANGE) "Change" else "Set"
            }
            TextButton(onClick = { submit() }, enabled = lockedFor <= 0) { Text(label) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Change the VAULT passcode (the one that unlocks the app) — distinct from the
 * numeric Privacy PIN above. Flow: verify current → choose new → confirm. Both
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
                        entry = ""; err = "Wrong passcode"
                    }
                }
            }
            "new" -> when {
                !org.cmchat.app.vault.VaultManager.isValidNewPin(entry) ->
                    err = "4–56 characters, not a palindrome"
                entry == current -> err = "Choose a different passcode"
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
                        else { err = "Could not change passcode"; entry = ""; newPin = ""; phase = "new" }
                    }
                }
            }
        }
    }

    val title = when (phase) {
        "current" -> "Enter current passcode"
        "new" -> "Choose a new passcode"
        else -> "Re-enter the new passcode"
    }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = entry,
                    onValueChange = { v -> if (!working && lockedFor <= 0) { entry = v.take(if (phase == "current") 128 else 56); err = null } },
                    singleLine = true,
                    enabled = !working && lockedFor <= 0,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
                )
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
        Text("Entering your PIN reversed silently wipes all data back to first-run.",
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
            Text(if (on) "On" else "Off", color = if (on) CmGreen else CmTextDim,
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
        Text(if (on) "On" else "Off", color = if (on) CmGreen else CmTextDim,
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
            Text(if (on) "On" else "Off", color = if (on) CmGreen else CmTextDim,
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
