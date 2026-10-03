package org.cmchat.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.cmchat.app.crypto.CmId
import org.cmchat.app.ui.theme.*

@Composable
fun KnockScreen(myCmId: String?, onSend: (cmId: String, nickname: String) -> Unit, onBack: () -> Unit) {
    var cmId by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { cmId = it.trim() }
    }

    Column(Modifier.fillMaxSize().background(CmBackground)) {
        Box(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("‹ Back", color = CmBlue, fontFamily = Nunito, fontSize = 15.sp,
                modifier = Modifier.align(Alignment.CenterStart).clickable { onBack() })
            Text("Knock", color = CmText, fontFamily = Nunito, fontSize = 17.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
        }

        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val colors = TextFieldDefaults.colors(
                focusedContainerColor = CmCard, unfocusedContainerColor = CmCard,
                focusedTextColor = CmText, unfocusedTextColor = CmText, cursorColor = CmBlue,
            )
            OutlinedTextField(
                value = cmId, onValueChange = { cmId = it; error = null },
                label = { Text("Their CMC-ID (cmc1:…)", color = CmTextDim) },
                singleLine = false, colors = colors, modifier = Modifier.fillMaxWidth(),
            )
            Box(Modifier.clip(RoundedCornerShape(14.dp)).background(CmCard)
                .clickable { scanLauncher.launch(ScanOptions().setOrientationLocked(true)) }
                .padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("Scan QR", color = CmBlue, fontFamily = Nunito, fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold)
            }
            OutlinedTextField(
                value = nickname, onValueChange = { nickname = it.take(24) },
                label = { Text("Nickname for them", color = CmTextDim) },
                singleLine = true, colors = colors, modifier = Modifier.fillMaxWidth(),
            )
            error?.let { Text(it, color = CmRed, fontFamily = Nunito, fontSize = 13.sp) }

            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CmOrange)
                    .clickable {
                        val id = cmId.trim()
                        when {
                            CmId.decode(id) == null -> error = "That doesn't look like a CMC-ID"
                            myCmId != null && id == myCmId -> error = "That's your own ID 🙂"
                            nickname.isBlank() -> error = "Pick a nickname"
                            else -> onSend(id, nickname.trim())
                        }
                    }
                    .padding(vertical = 13.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Send Knock", color = androidx.compose.ui.graphics.Color.White,
                    fontFamily = Nunito, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.weight(1f))
    }
}
