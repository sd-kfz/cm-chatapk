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

@Composable
fun LockScreen(manager: VaultManager, onUnlocked: (String, VaultData, firstRun: Boolean) -> Unit) {
    // Recomputed after a duress wipe so the screen falls back to first-run.
    var epoch by remember { mutableStateOf(0) }
    val firstRun = remember(epoch) { manager.firstRunNeeded() }

    var phase by remember(epoch) { mutableStateOf(if (firstRun) Phase.NEW_PIN else Phase.UNLOCK) }
    var pin by remember(epoch) { mutableStateOf("") }
    var firstPin by remember(epoch) { mutableStateOf("") }
    var faceName by remember(epoch) { mutableStateOf("") }
    var status by remember(epoch) { mutableStateOf("") }
    var alpha by remember(epoch) { mutableStateOf(false) } // alphanumeric passcode mode
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
                    status = "6-digit PIN, or 6+ chars with a letter — not a palindrome"
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
                    UnlockResult.Duress -> { epoch += 1 } // silent: back to first-run
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
                Phase.NEW_PIN -> "Create a 6-digit PIN  ·  Aa for letters"
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
        } else if (alpha) {
            // Alphanumeric passcode: variable length, explicit submit.
            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it.take(64) },
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = CmCard, unfocusedContainerColor = CmCard,
                    focusedTextColor = CmText, unfocusedTextColor = CmText,
                    cursorColor = CmBlue,
                ),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                if (lockedFor > 0) "Try again in " + LoginThrottle.format(lockedFor) else status,
                color = if (lockedFor > 0 || status.isNotEmpty()) CmRed else CmBackground,
                fontFamily = Nunito, fontSize = 13.sp,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("123", color = CmTextDim, fontFamily = Nunito, fontSize = 14.sp,
                    modifier = Modifier.clickable { alpha = false; pin = "" })
                Box(Modifier.clip(RoundedCornerShape(14.dp)).background(CmBlue)
                    .then(if (lockedFor > 0 || pin.isEmpty()) Modifier
                          else Modifier.clickable { val e = pin; pin = ""; submitPin(e) })
                    .padding(horizontal = 24.dp, vertical = 11.dp)) {
                    Text("Enter", color = CmBackground, fontFamily = Nunito,
                        fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(6) { i ->
                    Box(Modifier.size(16.dp).clip(CircleShape)
                        .background(if (i < pin.length) CmBlue else CmCard))
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                if (lockedFor > 0) "Try again in " + LoginThrottle.format(lockedFor) else status,
                color = if (lockedFor > 0 || status.isNotEmpty()) CmRed else CmBackground,
                fontFamily = Nunito, fontSize = 13.sp,
            )
            Spacer(Modifier.height(18.dp))
            Keypad(enabled = lockedFor <= 0) { k ->
                when (k) {
                    "Aa" -> { alpha = true; pin = "" }       // switch to full keyboard
                    "<" -> if (pin.isNotEmpty()) pin = pin.dropLast(1)
                    else -> if (pin.length < 6) pin += k
                }
                if (!alpha && pin.length == 6) {
                    val entered = pin; pin = ""
                    submitPin(entered)
                }
            }
        }

        Spacer(Modifier.weight(1f))
        Text("Ghost mode ready", color = CmGreen, fontFamily = Nunito, fontSize = 15.sp)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Keypad(enabled: Boolean, onKey: (String) -> Unit) {
    // Bottom-left cell (under 7, left of 0) is the "Aa" alphanumeric toggle.
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "Aa", "0", "<")
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        for (row in 0..3) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                for (col in 0..2) {
                    val k = keys[row * 3 + col]
                    Box(
                        Modifier.size(74.dp).clip(RoundedCornerShape(16.dp))
                            .background(if (k == "Aa") CmBackground else CmCard)
                            .then(
                                if (k.isEmpty() || !enabled) Modifier
                                else Modifier.clickable { onKey(k) }
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (k.isNotEmpty())
                            Text(k, color = if (k == "Aa") CmBlue
                                    else if (enabled) CmText else CmTextFaint,
                                fontFamily = Nunito,
                                fontSize = if (k == "Aa") 18.sp else 22.sp,
                                fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
