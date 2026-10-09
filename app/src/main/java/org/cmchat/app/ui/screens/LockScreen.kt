package org.cmchat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cmchat.app.ui.components.CmChatLogo
import org.cmchat.app.ui.theme.*
import org.cmchat.app.vault.LoginThrottle
import org.cmchat.app.vault.UnlockResult
import org.cmchat.app.vault.VaultData
import org.cmchat.app.vault.VaultManager

private enum class Phase { UNLOCK, NEW_PIN, CONFIRM_PIN, NICKNAME }

/** Shift state for the in-app letter keyboard. */
private enum class Shift { OFF, ONE_SHOT, CAPS }

/** Input cap when UNLOCKING: older vaults may have passcodes up to 128 chars. */
private const val MAX_UNLOCK_INPUT = 128

@Composable
fun LockScreen(manager: VaultManager, onUnlocked: (String, VaultData, firstRun: Boolean) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    // STEALTH SHREDDER: after the duress passcode, show ONLY a plain error and
    // accept no input at all until the app is restarted. No new-PIN prompt, no
    // uninstall prompt — nothing that hints that a wipe just happened.
    val shredded by org.cmchat.app.vault.Shredder.tripped.collectAsState()
    if (shredded) {
        Box(Modifier.fillMaxSize().background(CmBackground), contentAlignment = Alignment.Center) {
            Text("Error: please restart the app.", color = CmTextDim, fontFamily = Nunito, fontSize = 15.sp)
        }
        return
    }
    // Recomputed after a duress wipe so the screen falls back to first-run.
    var epoch by remember { mutableStateOf(0) }
    val firstRun = remember(epoch) { manager.firstRunNeeded() }

    var phase by remember(epoch) { mutableStateOf(if (firstRun) Phase.NEW_PIN else Phase.UNLOCK) }
    var pin by remember(epoch) { mutableStateOf("") }
    var firstPin by remember(epoch) { mutableStateOf("") }
    var nickname by remember(epoch) { mutableStateOf("") }
    var status by remember(epoch) { mutableStateOf("") }
    var alpha by remember(epoch) { mutableStateOf(false) } // letter keyboard showing
    var shift by remember(epoch) { mutableStateOf(Shift.OFF) }
    var wrongCount by remember(epoch) { mutableStateOf(0) }
    var lockedFor by remember(epoch) { mutableStateOf(0) }
    // True while Argon2id runs off the main thread (unlock / create vault), so
    // the UI stays responsive and the keypad is disabled meanwhile.
    var busy by remember(epoch) { mutableStateOf(false) }

    LaunchedEffect(lockedFor, epoch) {
        while (lockedFor > 0) {
            delay(1000)
            lockedFor -= 1
        }
        // After the 30-minute lockout elapses, reset the attempt counter to zero.
        if (wrongCount > LoginThrottle.SCHEDULE.size) wrongCount = 0
    }

    fun submitPin(entered: String) {
        when (phase) {
            Phase.NEW_PIN -> {
                if (!VaultManager.isValidNewPin(entered)) {
                    status = if (entered.length < VaultManager.MIN_PASSCODE)
                        "Use at least ${VaultManager.MIN_PASSCODE} characters"
                    else "Can't read the same backwards (that's the Shredder code)"
                } else {
                    firstPin = entered; status = ""; phase = Phase.CONFIRM_PIN
                }
            }
            Phase.CONFIRM_PIN -> {
                if (entered != firstPin) {
                    status = "PINs didn't match — start again"; firstPin = ""; phase = Phase.NEW_PIN
                } else {
                    status = ""; phase = Phase.NICKNAME
                }
            }
            Phase.UNLOCK -> {
                // Argon2id is heavy — run it OFF the main thread, then resolve on
                // main. `busy` gates the keypad so no second unlock can overlap.
                if (busy) return
                busy = true
                scope.launch {
                    val r = withContext(Dispatchers.Default) { manager.unlock(entered) }
                    busy = false
                    when (r) {
                        is UnlockResult.Success -> { wrongCount = 0; onUnlocked(entered, r.data, false) }
                        UnlockResult.Duress -> {
                            // Shredder passcode: the vault is already gone; erase
                            // EVERYTHING else silently and show only a fake error.
                            org.cmchat.app.vault.Shredder.trip(ctx)
                        }
                        UnlockResult.WrongPin -> {
                            wrongCount += 1
                            status = "Wrong PIN"
                            lockedFor = LoginThrottle.delaySeconds(wrongCount)
                        }
                    }
                }
            }
            Phase.NICKNAME -> {}
        }
    }

    val alphaLayout = alpha && phase != Phase.NICKNAME

    // With the letter keyboard up, the header compacts and the side padding
    // shrinks so the (bigger) keys get the room.
    Column(
        modifier = Modifier.fillMaxSize().background(CmBackground)
            .padding(horizontal = if (alphaLayout) 8.dp else 28.dp, vertical = if (alphaLayout) 12.dp else 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(if (alphaLayout) 6.dp else 48.dp))
        CmChatLogo(size = 30, sweepMs = 3250)
        Spacer(Modifier.height(8.dp))
        Text(
            when (phase) {
                Phase.NEW_PIN -> "Create a passcode (4–56 characters) · ABC for letters"
                Phase.CONFIRM_PIN -> "Confirm your passcode"
                Phase.NICKNAME -> "Pick a nickname"
                Phase.UNLOCK -> "Welcome back"
            },
            color = CmTextDim, fontFamily = Nunito, fontSize = 14.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )

        Spacer(Modifier.height(if (alphaLayout) 12.dp else 24.dp))

        if (phase == Phase.NICKNAME) {
            Text(
                "This is the name friends see when you add them. It's stored only in your vault.",
                color = CmTextFaint, fontFamily = Nunito, fontSize = 12.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(bottom = 18.dp),
            )
            OutlinedTextField(
                value = nickname,
                onValueChange = { nickname = it.take(24) },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = CmCard, unfocusedContainerColor = CmCard,
                    focusedTextColor = CmText, unfocusedTextColor = CmText,
                    cursorColor = CmBlue,
                ),
            )
            Spacer(Modifier.height(20.dp))
            Box(
                Modifier.clip(RoundedCornerShape(16.dp)).background(CmBlue)
                    .clickable(enabled = !busy) {
                        // createVault runs Argon2id — do it off the main thread.
                        busy = true
                        scope.launch {
                            val data = withContext(Dispatchers.Default) { manager.createVault(firstPin, nickname) }
                            busy = false
                            onUnlocked(firstPin, data, true)
                        }
                    }
                    .padding(horizontal = 28.dp, vertical = 12.dp),
            ) {
                Text(if (busy) "Creating…" else "Create", color = CmBackground, fontFamily = Nunito,
                    fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            // SECURITY: `pin` is an opaque passcode string collected ONLY to be
            // passed to Argon2id (cryptoPwHash) as raw bytes. It is never executed,
            // eval'd, used as a filename, shell string, or SQL anywhere (see
            // OpaqueInputTest). New passcodes cap at 56; unlock allows legacy 128.
            val cap = if (phase == Phase.UNLOCK) MAX_UNLOCK_INPUT else VaultManager.MAX_PASSCODE
            fun append(s: String) { if (pin.length < cap) pin += s }

            // Masked display — one dot per character (capped so a long passcode
            // doesn't overflow), length-agnostic so digits+letters+symbols all fit.
            MaskedDots(pin.length)
            // Strength hint while CREATING a passcode — encourages 8+, never blocks.
            if (phase == Phase.NEW_PIN && pin.isNotEmpty()) {
                val st = VaultManager.strength(pin)
                Text("Strength: ${st.label}" + if (pin.length < 8) "  ·  8+ characters recommended" else "",
                    color = when (st) {
                        VaultManager.Companion.Strength.WEAK -> CmOrange
                        VaultManager.Companion.Strength.FAIR -> CmTextDim
                        else -> CmGreen
                    },
                    fontFamily = Nunito, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.height(if (alphaLayout) 8.dp else 14.dp))
            Text(
                when {
                    busy -> "Unlocking…"
                    lockedFor > 0 -> "Try again in " + LoginThrottle.format(lockedFor)
                    else -> status
                },
                color = when {
                    busy -> CmTextDim
                    lockedFor > 0 || status.isNotEmpty() -> CmRed
                    else -> CmBackground
                },
                fontFamily = Nunito, fontSize = 13.sp,
            )
            Spacer(Modifier.height(if (alphaLayout) 8.dp else 14.dp))

            if (!alpha) {
                Keypad(enabled = lockedFor <= 0 && !busy) { k ->
                    when (k) {
                        "ABC" -> alpha = true   // switch to letters
                        "<" -> if (pin.isNotEmpty()) pin = pin.dropLast(1)
                        else -> append(k)
                    }
                    // No auto-submit: passcodes are 4–56 characters now, so a fixed
                    // length can't be assumed. Submission is always via Enter.
                }
            } else {
                LetterKeyboard(
                    enabled = lockedFor <= 0 && !busy,
                    shift = shift,
                    onChar = { c ->
                        val ch = if (c.length == 1 && c[0].isLetter() && shift != Shift.OFF)
                            c.uppercase() else c
                        append(ch)
                        if (shift == Shift.ONE_SHOT) shift = Shift.OFF
                    },
                    onShift = {
                        shift = when (shift) {
                            Shift.OFF -> Shift.ONE_SHOT      // tap once = next char upper
                            Shift.ONE_SHOT -> Shift.CAPS     // tap again = caps lock
                            Shift.CAPS -> Shift.OFF
                        }
                    },
                    onBackspace = { if (pin.isNotEmpty()) pin = pin.dropLast(1) },
                    onToDigits = { alpha = false },
                )
            }

            // Explicit submit, always (variable-length passcodes).
            Spacer(Modifier.height(12.dp))
            val canSubmit = lockedFor <= 0 && pin.isNotEmpty() && !busy
            Box(Modifier.fillMaxWidth(if (alphaLayout) 0.6f else 0.7f).height(52.dp)
                .clip(RoundedCornerShape(14.dp)).background(if (canSubmit) CmBlue else CmCard)
                .then(if (canSubmit) Modifier.clickable { val e = pin; pin = ""; submitPin(e) } else Modifier),
                contentAlignment = Alignment.Center) {
                Text("Enter", color = if (canSubmit) CmBackground else CmTextFaint, fontFamily = Nunito,
                    fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.weight(1f))
        if (!alphaLayout) {
            Text("Ghost mode ready", color = CmGreen, fontFamily = Nunito, fontSize = 15.sp)
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** Masked passcode display: one dot per char, capped so it never overflows. */
@Composable
private fun MaskedDots(count: Int) {
    val shown = count.coerceAtMost(20)
    val text = "●".repeat(shown) + if (count > 20) " +${count - 20}" else ""
    // Reserve height so the layout doesn't jump between empty and filled.
    Box(Modifier.heightIn(min = 20.dp), contentAlignment = Alignment.Center) {
        Text(text, color = CmBlue, fontFamily = Nunito, fontSize = 18.sp,
            fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Keypad(enabled: Boolean, onKey: (String) -> Unit) {
    // Bottom-left cell (under 7, left of 0) switches to the letter keyboard.
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "ABC", "0", "<")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (row in 0..3) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                for (col in 0..2) {
                    val k = keys[row * 3 + col]
                    val special = k == "ABC" || k == "<"
                    Box(
                        Modifier.size(width = 84.dp, height = 70.dp).clip(RoundedCornerShape(18.dp))
                            .background(if (k == "ABC") CmBackground else CmCard)
                            .then(if (!enabled) Modifier else Modifier.clickable { onKey(k) }),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (k == "<") "⌫" else k,
                            color = if (k == "ABC") CmBlue else if (enabled) CmText else CmTextFaint,
                            fontFamily = Nunito,
                            fontSize = if (special) 18.sp else 26.sp,
                            fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/**
 * CM-Chat's own dark QWERTY (NOT the grey system keyboard) — only the key
 * ARRANGEMENT follows the reference. Row 1 is symbols only; [Shift] one-shot /
 * caps-lock; [123] returns to the number pad; [⌫] backspaces.
 */
@Composable
private fun LetterKeyboard(
    enabled: Boolean,
    shift: Shift,
    onChar: (String) -> Unit,
    onShift: () -> Unit,
    onBackspace: () -> Unit,
    onToDigits: () -> Unit,
) {
    val upper = shift != Shift.OFF
    val symbols = listOf("|", "@", "#", "$", "%", "^", "&", "*", "«", "»")
    val row2 = "qwertyuiop".map { it.toString() }
    val row3 = "asdfghjkl".map { it.toString() }
    val row4mid = "zxcvbnm".map { it.toString() }

    @Composable
    fun key(label: String, onClick: () -> Unit, bg: androidx.compose.ui.graphics.Color = CmCard,
            fg: androidx.compose.ui.graphics.Color = CmText) {
        Box(
            Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(10.dp)).background(bg)
                .then(if (enabled) Modifier.clickable { onClick() } else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, color = if (enabled) fg else CmTextFaint, fontFamily = Nunito,
                fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        }
    }

    val digits = "1234567890".map { it.toString() }

    // Taller keys (54dp) with roomier gaps; 5 rows still fit a small phone.
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Dedicated 0-9 row so digits are reachable without leaving the letters.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            digits.forEach { d -> Box(Modifier.weight(1f)) { key(d, { onChar(d) }) } }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            symbols.forEach { s -> Box(Modifier.weight(1f)) { key(s, { onChar(s) }) } }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            row2.forEach { c ->
                Box(Modifier.weight(1f)) { key(if (upper) c.uppercase() else c, { onChar(c) }) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Spacer(Modifier.weight(0.5f))
            row3.forEach { c ->
                Box(Modifier.weight(1f)) { key(if (upper) c.uppercase() else c, { onChar(c) }) }
            }
            Spacer(Modifier.weight(0.5f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // Shift: highlighted when armed (one-shot) or locked (caps).
            val shiftBg = if (shift == Shift.OFF) CmCard else CmBlue
            val shiftFg = if (shift == Shift.OFF) CmText else CmBackground
            Box(Modifier.weight(1.5f)) {
                key(if (shift == Shift.CAPS) "⇪" else "⇧", onShift, bg = shiftBg, fg = shiftFg)
            }
            row4mid.forEach { c ->
                Box(Modifier.weight(1f)) { key(if (upper) c.uppercase() else c, { onChar(c) }) }
            }
            // "#" returns to the digit pad (per spec).
            Box(Modifier.weight(1f)) { key("#", onToDigits, fg = CmBlue) }
            Box(Modifier.weight(1.5f)) { key("⌫", onBackspace) }
        }
    }
}
