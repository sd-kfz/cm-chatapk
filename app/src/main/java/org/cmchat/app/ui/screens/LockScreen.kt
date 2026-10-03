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
import kotlinx.coroutines.delay
import org.cmchat.app.ui.components.CmChatLogo
import org.cmchat.app.ui.theme.*
import org.cmchat.app.vault.LoginThrottle
import org.cmchat.app.vault.UnlockResult
import org.cmchat.app.vault.VaultData
import org.cmchat.app.vault.VaultManager

private enum class Phase { UNLOCK, NEW_PIN, CONFIRM_PIN, NAME_FACE }

/** Shift state for the in-app letter keyboard. */
private enum class Shift { OFF, ONE_SHOT, CAPS }

private const val MAX_PASSCODE = 128

@Composable
fun LockScreen(manager: VaultManager, onUnlocked: (String, VaultData, firstRun: Boolean) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // Recomputed after a duress wipe so the screen falls back to first-run.
    var epoch by remember { mutableStateOf(0) }
    val firstRun = remember(epoch) { manager.firstRunNeeded() }

    var phase by remember(epoch) { mutableStateOf(if (firstRun) Phase.NEW_PIN else Phase.UNLOCK) }
    var pin by remember(epoch) { mutableStateOf("") }
    var firstPin by remember(epoch) { mutableStateOf("") }
    var faceName by remember(epoch) { mutableStateOf("") }
    var status by remember(epoch) { mutableStateOf("") }
    var alpha by remember(epoch) { mutableStateOf(false) } // letter keyboard showing
    var usedAlpha by remember(epoch) { mutableStateOf(false) } // password mixes letters/symbols
    var shift by remember(epoch) { mutableStateOf(Shift.OFF) }
    var wrongCount by remember(epoch) { mutableStateOf(0) }
    var lockedFor by remember(epoch) { mutableStateOf(0) }

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
                    status = "Use at least 6 characters — and not a palindrome"
                } else {
                    firstPin = entered; status = ""; phase = Phase.CONFIRM_PIN
                }
            }
            Phase.CONFIRM_PIN -> {
                if (entered != firstPin) {
                    status = "PINs didn't match — start again"; firstPin = ""; phase = Phase.NEW_PIN
                } else {
                    status = ""; phase = Phase.NAME_FACE
                }
            }
            Phase.UNLOCK -> {
                when (val r = manager.unlock(entered)) {
                    is UnlockResult.Success -> { wrongCount = 0; onUnlocked(entered, r.data, false) }
                    UnlockResult.Duress -> {
                        // Shredder PIN: erase ALL recoverable on-disk data + RAM,
                        // then fall silently back to first-run.
                        org.cmchat.app.guard.GuardController.wipeRamOnly()
                        org.cmchat.app.vault.Shredder.shredAll(ctx)
                        epoch += 1
                    }
                    UnlockResult.WrongPin -> {
                        wrongCount += 1
                        status = "Wrong PIN"
                        lockedFor = LoginThrottle.delaySeconds(wrongCount)
                    }
                }
            }
            Phase.NAME_FACE -> {}
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(CmBackground).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(60.dp))
        CmChatLogo(size = 30, sweepMs = 3250)
        Spacer(Modifier.height(10.dp))
        Text(
            when (phase) {
                Phase.NEW_PIN -> "Create a 6-digit PIN  ·  ABC for letters"
                Phase.CONFIRM_PIN -> "Confirm your PIN"
                Phase.NAME_FACE -> "Name your first Tag"
                Phase.UNLOCK -> "Welcome back"
            },
            color = CmTextDim, fontFamily = Nunito, fontSize = 14.sp,
        )

        Spacer(Modifier.height(28.dp))

        if (phase == Phase.NAME_FACE) {
            Text(
                "Open = present; minimised = present but on Cerberus's timer; " +
                    "swiped away = closed and offline.",
                color = CmTextFaint, fontFamily = Nunito, fontSize = 12.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(bottom = 18.dp),
            )
            OutlinedTextField(
                value = faceName,
                onValueChange = { faceName = it.take(24) },
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
                    .clickable {
                        val data = manager.createVault(firstPin, faceName)
                        onUnlocked(firstPin, data, true)
                    }
                    .padding(horizontal = 28.dp, vertical = 12.dp),
            ) {
                Text("Create", color = CmBackground, fontFamily = Nunito,
                    fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            // SECURITY: `pin` is an opaque passcode string collected ONLY to be
            // passed to Argon2id (cryptoPwHash) as raw bytes. It is never executed,
            // eval'd, used as a filename, shell string, or SQL anywhere.
            fun append(s: String) { if (pin.length < MAX_PASSCODE) pin += s }

            // Masked display — one dot per character (capped so a long passcode
            // doesn't overflow), length-agnostic so digits+letters+symbols all fit.
            MaskedDots(pin.length)
            Spacer(Modifier.height(16.dp))
            Text(
                if (lockedFor > 0) "Try again in " + LoginThrottle.format(lockedFor) else status,
                color = if (lockedFor > 0 || status.isNotEmpty()) CmRed else CmBackground,
                fontFamily = Nunito, fontSize = 13.sp,
            )
            Spacer(Modifier.height(16.dp))

            if (!alpha) {
                Keypad(enabled = lockedFor <= 0) { k ->
                    when (k) {
                        "ABC" -> { alpha = true; usedAlpha = true }   // switch to letters
                        "<" -> if (pin.isNotEmpty()) pin = pin.dropLast(1)
                        else -> append(k)
                    }
                    // Pure 6-digit PIN keeps the instant-submit UX; once the letter
                    // keyboard has been used, submission is via the Enter key.
                    if (!usedAlpha && pin.length == 6) { val e = pin; pin = ""; submitPin(e) }
                }
            } else {
                LetterKeyboard(
                    enabled = lockedFor <= 0,
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

            // Explicit submit once a mixed passcode is in use (variable length).
            if (usedAlpha) {
                Spacer(Modifier.height(14.dp))
                Box(Modifier.clip(RoundedCornerShape(14.dp)).background(CmBlue)
                    .then(if (lockedFor > 0 || pin.isEmpty()) Modifier
                          else Modifier.clickable { val e = pin; pin = ""; submitPin(e) })
                    .padding(horizontal = 40.dp, vertical = 12.dp)) {
                    Text("Enter", color = CmBackground, fontFamily = Nunito,
                        fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(Modifier.weight(1f))
        Text("Ghost mode ready", color = CmGreen, fontFamily = Nunito, fontSize = 15.sp)
        Spacer(Modifier.height(16.dp))
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
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        for (row in 0..3) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                for (col in 0..2) {
                    val k = keys[row * 3 + col]
                    val special = k == "ABC" || k == "<"
                    Box(
                        Modifier.size(74.dp).clip(RoundedCornerShape(16.dp))
                            .background(if (k == "ABC") CmBackground else CmCard)
                            .then(if (!enabled) Modifier else Modifier.clickable { onKey(k) }),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (k == "<") "⌫" else k,
                            color = if (k == "ABC") CmBlue else if (enabled) CmText else CmTextFaint,
                            fontFamily = Nunito,
                            fontSize = if (special) 18.sp else 22.sp,
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
            Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(10.dp)).background(bg)
                .then(if (enabled) Modifier.clickable { onClick() } else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, color = if (enabled) fg else CmTextFaint, fontFamily = Nunito,
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            symbols.forEach { s -> Box(Modifier.weight(1f)) { key(s, { onChar(s) }) } }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            row2.forEach { c ->
                Box(Modifier.weight(1f)) { key(if (upper) c.uppercase() else c, { onChar(c) }) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Spacer(Modifier.weight(0.5f))
            row3.forEach { c ->
                Box(Modifier.weight(1f)) { key(if (upper) c.uppercase() else c, { onChar(c) }) }
            }
            Spacer(Modifier.weight(0.5f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            // Shift: highlighted when armed (one-shot) or locked (caps).
            val shiftBg = if (shift == Shift.OFF) CmCard else CmBlue
            val shiftFg = if (shift == Shift.OFF) CmText else CmBackground
            Box(Modifier.weight(1.5f)) {
                key(if (shift == Shift.CAPS) "⇪" else "⇧", onShift, bg = shiftBg, fg = shiftFg)
            }
            row4mid.forEach { c ->
                Box(Modifier.weight(1f)) { key(if (upper) c.uppercase() else c, { onChar(c) }) }
            }
            Box(Modifier.weight(1f)) { key("123", onToDigits, fg = CmBlue) }
            Box(Modifier.weight(1.5f)) { key("⌫", onBackspace) }
        }
    }
}
