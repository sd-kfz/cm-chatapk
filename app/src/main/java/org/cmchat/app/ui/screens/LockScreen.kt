package org.cmchat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
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
import org.cmchat.app.R
import org.cmchat.app.i18n.Tr

private enum class Phase { UNLOCK, NEW_PIN, CONFIRM_PIN, NICKNAME }

/** Shift state for the in-app letter keyboard. */
private enum class Shift { OFF, ONE_SHOT, CAPS }

/** Which page of CM-Chat's own keyboard is showing. */
private enum class Pad { DIGITS, LETTERS, SYMBOLS }

/** Input cap when UNLOCKING: older vaults may have PINs up to 128 chars. */
private const val MAX_UNLOCK_INPUT = 128

@Composable
fun LockScreen(manager: VaultManager, onUnlocked: (VaultData) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    // STEALTH SHREDDER: after the duress PIN the NORMAL lock screen stays up,
    // with one small red line under "Welcome back", and its keys do nothing.
    // Closing / leaving the app clears it (Shredder.reset) and a fresh lock
    // screen appears — never a dead screen, never a reinstall.
    val shredded by org.cmchat.app.vault.Shredder.tripped.collectAsState()
    val firstRun = remember { manager.firstRunNeeded() }

    var phase by remember { mutableStateOf(if (firstRun) Phase.NEW_PIN else Phase.UNLOCK) }
    var pin by remember { mutableStateOf("") }
    var shownPin by remember { mutableStateOf(false) }   // the eye: show what's typed
    var firstPin by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var pad by remember { mutableStateOf(Pad.DIGITS) }
    var shift by remember { mutableStateOf(Shift.OFF) }
    var wrongCount by remember { mutableStateOf(0) }
    var lockedFor by remember { mutableStateOf(0) }
    // True while Argon2id runs off the main thread (unlock / create vault), so
    // the UI stays responsive and the keys are disabled meanwhile.
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(lockedFor) {
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
                        Tr.s(R.string.lock_min_chars, VaultManager.MIN_PASSCODE)
                    else Tr.s(R.string.lock_not_palindrome)
                } else {
                    firstPin = entered; status = ""; phase = Phase.CONFIRM_PIN
                }
            }
            Phase.CONFIRM_PIN -> {
                if (entered != firstPin) {
                    status = Tr.s(R.string.lock_pins_didn_t_match); firstPin = ""; phase = Phase.NEW_PIN
                } else {
                    status = ""; phase = Phase.NICKNAME
                }
            }
            Phase.UNLOCK -> {
                // Argon2id is heavy — run it OFF the main thread, then resolve on
                // main. `busy` gates the keys so no second unlock can overlap.
                if (busy) return
                // The vault is gone (Wipe Everything ran and its uninstall prompt
                // was cancelled): start fresh instead of "Wrong PIN" forever.
                if (manager.firstRunNeeded()) { status = ""; phase = Phase.NEW_PIN; return }
                busy = true
                scope.launch {
                    val r = withContext(Dispatchers.Default) { manager.unlock(entered) }
                    busy = false
                    when (r) {
                        // The vault key was derived ONCE (Argon2id) and is cached by the
                        // manager for this session; the typed PIN isn't kept anywhere.
                        is UnlockResult.Success -> { wrongCount = 0; onUnlocked(r.data) }
                        UnlockResult.Duress -> {
                            // Shredder PIN: the vault is already gone; erase
                            // EVERYTHING else silently and show only a fake error.
                            org.cmchat.app.vault.Shredder.trip(ctx)
                        }
                        UnlockResult.WrongPin -> {
                            wrongCount += 1
                            status = Tr.s(R.string.lock_wrong_pin)
                            lockedFor = LoginThrottle.delaySeconds(wrongCount)
                        }
                    }
                }
            }
            Phase.NICKNAME -> {}
        }
    }

    // Small / old screens get a compact header so the keys keep their size.
    val compact = LocalConfiguration.current.screenHeightDp < 640
    val typing = phase != Phase.NICKNAME
    val bigKeys = typing && pad != Pad.DIGITS

    Column(
        modifier = Modifier.fillMaxSize().background(CmBackground)
            .padding(horizontal = if (bigKeys) 4.dp else 24.dp, vertical = if (compact || bigKeys) 8.dp else 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(if (compact || bigKeys) 4.dp else 32.dp))
        CmChatLogo(size = 38, sweepMs = 3250)
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                shredded -> Tr.s(R.string.lock_welcome_back)
                phase == Phase.NEW_PIN -> Tr.s(R.string.lock_create_pin_numbers_letters)
                phase == Phase.CONFIRM_PIN -> Tr.s(R.string.lock_confirm_pin)
                phase == Phase.NICKNAME -> Tr.s(R.string.lock_pick_nickname)
                else -> Tr.s(R.string.lock_welcome_back)
            },
            color = CmTextDim, fontFamily = Nunito, fontSize = 14.sp, textAlign = TextAlign.Center,
        )
        if (shredded) {
            Text(Tr.s(R.string.lock_error_please_restart_app), color = CmRed, fontFamily = Nunito, fontSize = 12.sp,
                fontStyle = FontStyle.Italic, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp))
        }
        // Honest nudge at PIN creation: longer is far stronger if the phone is taken.
        if (phase == Phase.NEW_PIN && !shredded) {
            Text(Tr.s(R.string.lock_passphrase_hint), color = CmTextFaint, fontFamily = Nunito,
                fontSize = 12.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp, start = 24.dp, end = 24.dp))
        }
        Spacer(Modifier.height(if (compact || bigKeys) 8.dp else 20.dp))

        if (phase == Phase.NICKNAME) {
            Text(
                Tr.s(R.string.lock_name_friends_see_when),
                color = CmTextFaint, fontFamily = Nunito, fontSize = 12.sp, textAlign = TextAlign.Center,
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
                            firstPin = ""
                            onUnlocked(data)
                        }
                    }
                    .padding(horizontal = 28.dp, vertical = 12.dp),
            ) {
                Text(if (busy) Tr.s(R.string.lock_creating) else Tr.s(R.string.lock_create), color = CmBackground, fontFamily = Nunito,
                    fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.weight(1f))
            return@Column
        }

        // SECURITY: `pin` is an opaque string collected ONLY to be passed to
        // Argon2id (cryptoPwHash) as raw bytes. It is never executed, eval'd,
        // used as a filename, shell string, or SQL anywhere (see OpaqueInputTest).
        // New PINs cap at 56; unlock allows legacy 128.
        val cap = if (phase == Phase.UNLOCK) MAX_UNLOCK_INPUT else VaultManager.MAX_PASSCODE
        // After the Shredder the keys look normal but do nothing until restart.
        fun append(s: String) { if (!shredded && pin.length < cap) pin += s }
        fun backspace() { if (pin.isNotEmpty()) pin = pin.dropLast(1) }
        val enabled = lockedFor <= 0 && !busy
        val canSubmit = enabled && pin.isNotEmpty() && !shredded
        // Explicit submit, always (variable-length PINs).
        fun submit() { if (shredded) return; val e = pin; pin = ""; submitPin(e) }

        // Masked display — one dot per character, wrapping onto more lines for a
        // long PIN (no "+N"); the eye shows what's typed instead.
        MaskedDots(pin, shownPin) { shownPin = !shownPin }
        // Strength hint while CREATING a PIN — encourages 8+, never blocks.
        if (phase == Phase.NEW_PIN) PinStrengthHint(pin, Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                shredded -> ""
                busy -> Tr.s(R.string.lock_unlocking)
                lockedFor > 0 -> Tr.s(R.string.lock_try_again_in, LoginThrottle.format(lockedFor))
                else -> status
            },
            color = when {
                busy -> CmTextDim
                lockedFor > 0 || status.isNotEmpty() -> CmRed
                else -> CmBackground
            },
            fontFamily = Nunito, fontSize = 13.sp,
        )
        Spacer(Modifier.height(6.dp))

        // The keys take ALL the height that's left, anchored at the bottom where
        // thumbs reach — as big as the screen allows, and never cut off on a
        // small / old phone.
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.BottomCenter) {
            val gap = 6.dp
            when (pad) {
                Pad.DIGITS -> {
                    val enterH = 52.dp
                    val keyH = ((maxHeight - enterH - 12.dp - gap * 3) / 4).coerceIn(44.dp, 72.dp)
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Keypad(enabled, keyH, gap) { k ->
                            when (k) {
                                "ABC" -> pad = Pad.LETTERS
                                "<" -> backspace()
                                else -> append(k)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Box(Modifier.fillMaxWidth(0.7f).height(enterH)
                            .clip(RoundedCornerShape(14.dp)).background(if (canSubmit) CmBlue else CmCard)
                            .then(if (canSubmit) Modifier.clickable { submit() } else Modifier),
                            contentAlignment = Alignment.Center) {
                            Text(Tr.s(R.string.lock_enter), color = if (canSubmit) CmBackground else CmTextFaint, fontFamily = Nunito,
                                fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Pad.LETTERS -> {
                    val keyH = ((maxHeight - gap * 3) / 4).coerceIn(42.dp, 68.dp)
                    LetterPad(
                        enabled = enabled, shift = shift, keyH = keyH, gap = gap, canSubmit = canSubmit,
                        onChar = { c ->
                            append(if (shift != Shift.OFF) c.uppercase() else c)
                            if (shift == Shift.ONE_SHOT) shift = Shift.OFF
                        },
                        onShift = {
                            shift = when (shift) {
                                Shift.OFF -> Shift.ONE_SHOT      // tap once = next letter upper
                                Shift.ONE_SHOT -> Shift.CAPS     // tap again = caps lock
                                Shift.CAPS -> Shift.OFF
                            }
                        },
                        onBackspace = { backspace() },
                        onDigits = { pad = Pad.DIGITS },
                        onSymbols = { pad = Pad.SYMBOLS },
                        onEnter = { submit() },
                    )
                }
                Pad.SYMBOLS -> {
                    val keyH = ((maxHeight - gap * 4) / 5).coerceIn(40.dp, 64.dp)
                    SymbolPad(
                        enabled = enabled, keyH = keyH, gap = gap, canSubmit = canSubmit,
                        onChar = { append(it) },
                        onBackspace = { backspace() },
                        onLetters = { pad = Pad.LETTERS },
                        onDigits = { pad = Pad.DIGITS },
                        onEnter = { submit() },
                    )
                }
            }
        }
        if (!bigKeys && !compact) {
            Spacer(Modifier.height(12.dp))
            Text(Tr.s(R.string.lock_ghost_mode_ready), color = CmGreen, fontFamily = Nunito, fontSize = 15.sp)
        }
    }
}

/** "Strength: Fair · 8+ characters recommended" — encourages, never blocks. */
@Composable
internal fun PinStrengthHint(pin: String, modifier: Modifier = Modifier) {
    if (pin.isEmpty()) return
    val st = VaultManager.strength(pin)
    val label = Tr.s(when (st) {
        VaultManager.Companion.Strength.WEAK -> R.string.lock_strength_weak
        VaultManager.Companion.Strength.FAIR -> R.string.lock_strength_fair
        VaultManager.Companion.Strength.GOOD -> R.string.lock_strength_good
        VaultManager.Companion.Strength.STRONG -> R.string.lock_strength_strong
    })
    Text(Tr.s(R.string.lock_strength, label) +
        if (pin.length < 8) "  ·  " + Tr.s(R.string.lock_8_recommended) else "",
        color = when (st) {
            VaultManager.Companion.Strength.WEAK -> CmOrange
            VaultManager.Companion.Strength.FAIR -> CmTextDim
            else -> CmGreen
        },
        fontFamily = Nunito, fontSize = 12.sp, modifier = modifier)
}

/**
 * The PIN being typed: one dot per character, WRAPPING onto more lines for a
 * long one (every character counted, no "+N"). The eye shows the characters
 * themselves instead (hidden again when the screen is left).
 */
@Composable
private fun MaskedDots(pin: String, shown: Boolean, onToggle: () -> Unit) {
    // A break chance between characters, so a long run wraps instead of overflowing.
    val text = (if (shown) pin else "●".repeat(pin.length)).toList().joinToString("\u200B")
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.size(40.dp))
        Box(Modifier.weight(1f).heightIn(min = 24.dp), contentAlignment = Alignment.Center) {
            Text(text, color = CmBlue, fontFamily = Nunito, fontSize = if (pin.length > 16) 15.sp else 18.sp,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, softWrap = true, maxLines = 4)
        }
        Box(Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape)
            .clickable(enabled = pin.isNotEmpty()) { onToggle() }, contentAlignment = Alignment.Center) {
            if (pin.isNotEmpty()) org.cmchat.app.ui.components.EyeIcon(open = shown, color = CmTextDim)
        }
    }
}

/** The number pad. Bottom-left (under 7, left of 0) switches to letters. */
@Composable
private fun Keypad(enabled: Boolean, keyH: Dp, gap: Dp, onKey: (String) -> Unit) {
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "ABC", "0", "<")
    // Shrinks to fit a narrow (320 dp) phone; never wider than a comfortable pad.
    Column(Modifier.widthIn(max = 330.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
        for (row in 0..3) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (col in 0..2) {
                    val k = keys[row * 3 + col]
                    val special = k == "ABC" || k == "<"
                    Box(
                        Modifier.weight(1f).height(keyH).clip(RoundedCornerShape(18.dp))
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

/** One key of the letter / symbol pages. */
@Composable
private fun RowScope.Key(
    label: String, keyH: Dp, enabled: Boolean, weight: Float = 1f,
    bg: Color = CmCard, fg: Color = CmText, size: TextUnit = 22.sp, onClick: () -> Unit,
) {
    Box(
        Modifier.weight(weight).height(keyH).clip(RoundedCornerShape(10.dp)).background(bg)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) fg else CmTextFaint, fontFamily = Nunito,
            fontSize = size, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** The big Enter key at the bottom-right of the letter / symbol pages. */
@Composable
private fun RowScope.EnterKey(keyH: Dp, canSubmit: Boolean, onEnter: () -> Unit) {
    Key(Tr.s(R.string.lock_enter), keyH, enabled = canSubmit, weight = 5f, bg = if (canSubmit) CmBlue else CmCard,
        fg = CmBackground, size = 17.sp, onClick = onEnter)
}

/**
 * CM-Chat's own QWERTY (never the system keyboard, which could log or predict
 * what's typed). Full-width, tall keys, everything a thumb needs on the bottom
 * row: [123] number pad, [#+=] symbols, and a big Enter.
 */
@Composable
private fun LetterPad(
    enabled: Boolean, shift: Shift, keyH: Dp, gap: Dp, canSubmit: Boolean,
    onChar: (String) -> Unit, onShift: () -> Unit, onBackspace: () -> Unit,
    onDigits: () -> Unit, onSymbols: () -> Unit, onEnter: () -> Unit,
) {
    val upper = shift != Shift.OFF
    val hGap = 4.dp
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(hGap)) {
            "qwertyuiop".forEach { c ->
                Key(if (upper) c.uppercase() else c.toString(), keyH, enabled) { onChar(c.toString()) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(hGap)) {
            Spacer(Modifier.weight(0.5f))
            "asdfghjkl".forEach { c ->
                Key(if (upper) c.uppercase() else c.toString(), keyH, enabled) { onChar(c.toString()) }
            }
            Spacer(Modifier.weight(0.5f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(hGap)) {
            // Shift: highlighted when armed (one-shot) or locked (caps).
            Key(if (shift == Shift.CAPS) "⇪" else "⇧", keyH, enabled, weight = 1.5f,
                bg = if (shift == Shift.OFF) CmCard else CmBlue,
                fg = if (shift == Shift.OFF) CmText else CmBackground, onClick = onShift)
            "zxcvbnm".forEach { c ->
                Key(if (upper) c.uppercase() else c.toString(), keyH, enabled) { onChar(c.toString()) }
            }
            Key("⌫", keyH, enabled, weight = 1.5f, onClick = onBackspace)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(hGap)) {
            Key("123", keyH, enabled, weight = 2.5f, fg = CmBlue, size = 17.sp, onClick = onDigits)
            Key("#+=", keyH, enabled, weight = 2.5f, fg = CmBlue, size = 17.sp, onClick = onSymbols)
            EnterKey(keyH, canSubmit, onEnter)
        }
    }
}

/**
 * Every printable ASCII symbol (so a PIN made with ANY earlier keyboard can
 * still be typed), plus « » from v1.2's keyboard and a space.
 */
@Composable
private fun SymbolPad(
    enabled: Boolean, keyH: Dp, gap: Dp, canSubmit: Boolean,
    onChar: (String) -> Unit, onBackspace: () -> Unit,
    onLetters: () -> Unit, onDigits: () -> Unit, onEnter: () -> Unit,
) {
    val hGap = 4.dp
    val rows = listOf("!@#$%^&*()", "-_=+[]{};:", "'\",./?\\|`~")
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
        rows.forEach { r ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(hGap)) {
                r.forEach { c -> Key(c.toString(), keyH, enabled) { onChar(c.toString()) } }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(hGap)) {
            listOf("<", ">", "«", "»").forEach { s -> Key(s, keyH, enabled) { onChar(s) } }
            Key("space", keyH, enabled, weight = 3f, size = 15.sp) { onChar(" ") }
            Key("⌫", keyH, enabled, weight = 3f, onClick = onBackspace)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(hGap)) {
            Key("ABC", keyH, enabled, weight = 2.5f, fg = CmBlue, size = 17.sp, onClick = onLetters)
            Key("123", keyH, enabled, weight = 2.5f, fg = CmBlue, size = 17.sp, onClick = onDigits)
            EnterKey(keyH, canSubmit, onEnter)
        }
    }
}
