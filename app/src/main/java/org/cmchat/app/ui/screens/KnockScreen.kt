package org.cmchat.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.cmchat.app.crypto.CmId
import org.cmchat.app.ui.theme.*
import org.cmchat.app.R
import org.cmchat.app.i18n.Tr

@Composable
fun KnockScreen(
    myCmId: String?,
    /** Returns null when the friend was added (knock queued), else an error to show. */
    onSend: (cmId: String, nickname: String) -> String?,
    onBack: () -> Unit,
    onShowMyQr: () -> Unit = {},
) {
    var cmId by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    var scanned by remember { mutableStateOf(false) }
    // The scan result feeds straight into the add flow (validated right away).
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val raw = result.contents?.trim() ?: return@rememberLauncherForActivityResult   // cancelled
        if (CmId.decode(raw) != null) {
            cmId = raw; scanned = true; error = null
            org.cmchat.app.diag.ConnDiag.sys("Add friend: QR scanned (valid CMC-ID)")
        } else {
            error = Tr.s(R.string.add_not_cm_qr)
            org.cmchat.app.diag.ConnDiag.sys("Add friend: scanned QR was not a CMC-ID")
        }
    }
    fun openScanner() {
        // We're opening the scanner ourselves: don't treat it as leaving the app
        // (that re-lock is what made scanning re-ask the PIN and lose the result).
        org.cmchat.app.LifecycleController.expectOwnLaunch()
        scanLauncher.launch(
            ScanOptions()
                .setCaptureActivity(org.cmchat.app.ui.QrScanActivity::class.java)
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setOrientationLocked(true)
                .setBeepEnabled(false)
                .setPrompt(Tr.s(R.string.add_point_friend_s_cm))
        )
    }

    fun submit() {
        val id = cmId.trim()
        when {
            CmId.decode(id) == null -> error = Tr.s(R.string.add_not_cmc_id)
            myCmId != null && id == myCmId -> error = Tr.s(R.string.add_own_id)
            else -> onSend(id, nickname.trim())?.let { error = it }
        }
    }

    // The form scrolls; the Add button is pinned at the bottom (above the
    // keyboard too), so it can never fall below the fold on a small phone.
    Column(Modifier.fillMaxSize().background(CmBackground)) {
        Box(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(Tr.s(R.string.back), color = CmBlue, fontFamily = Nunito, fontSize = 15.sp,
                modifier = Modifier.align(Alignment.CenterStart).clickable { onBack() })
            Text(Tr.s(R.string.add_add_friend), color = CmText, fontFamily = Nunito, fontSize = 17.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
        }

        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val colors = TextFieldDefaults.colors(
                focusedContainerColor = CmCard, unfocusedContainerColor = CmCard,
                focusedTextColor = CmText, unfocusedTextColor = CmText, cursorColor = CmBlue,
            )
            OutlinedTextField(
                value = cmId, onValueChange = { cmId = it; error = null; scanned = false },
                label = { Text(Tr.s(R.string.add_their_id_label), color = CmTextDim) },
                singleLine = false, maxLines = 4, colors = colors, modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(CmCard)
                    .clickable { openScanner() }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center) {
                    Text(Tr.s(R.string.add_scan_their_qr), color = CmBlue, fontFamily = Nunito, fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold)
                }
                Box(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(CmCard)
                    .clickable { onShowMyQr() }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center) {
                    Text(Tr.s(R.string.add_show_my_qr), color = CmBlue, fontFamily = Nunito, fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold)
                }
            }
            OutlinedTextField(
                value = nickname, onValueChange = { nickname = it.take(24) },
                label = { Text(Tr.s(R.string.add_nickname_optional), color = CmTextDim) },
                singleLine = true, colors = colors, modifier = Modifier.fillMaxWidth(),
            )
            if (scanned && error == null) {
                Text(Tr.s(R.string.add_scanned_hint), color = CmGreen,
                    fontFamily = Nunito, fontSize = 13.sp)
            }
            error?.let { Text(it, color = CmRed, fontFamily = Nunito, fontSize = 13.sp) }
            Text(Tr.s(R.string.add_request_explainer),
                color = CmTextFaint, fontFamily = Nunito, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
        }

        Box(
            Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(14.dp)).background(CmBlue)
                .clickable { submit() }
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(Tr.s(R.string.add_add_friend), color = CmBackground,
                fontFamily = Nunito, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}
