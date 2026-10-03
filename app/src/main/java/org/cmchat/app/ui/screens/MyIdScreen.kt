package org.cmchat.app.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.cmchat.app.ui.theme.*

@Composable
fun MyIdScreen(cmId: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    Column(Modifier.fillMaxSize().background(CmBackground)) {
        Box(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("‹ Back", color = CmBlue, fontFamily = Nunito, fontSize = 15.sp,
                modifier = Modifier.align(Alignment.CenterStart).clickable { onBack() })
            Text("My CMC-ID", color = CmText, fontFamily = Nunito, fontSize = 17.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
        }

        if (cmId == null) {
            Spacer(Modifier.weight(1f))
            Text(
                "Your ID appears once Tor is Online and your server is published.",
                color = CmTextDim, fontFamily = Nunito, fontSize = 14.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(24.dp),
            )
            Spacer(Modifier.weight(1f))
            return@Column
        }

        Spacer(Modifier.height(16.dp))
        val qr = remember(cmId) { qrBitmap(cmId, 640) }
        if (qr != null) {
            Image(
                bitmap = qr.asImageBitmap(),
                contentDescription = "CMC-ID QR",
                modifier = Modifier.align(Alignment.CenterHorizontally)
                    .size(240.dp).clip(RoundedCornerShape(16.dp)).background(androidx.compose.ui.graphics.Color.White)
                    .padding(12.dp),
            )
        }

        Spacer(Modifier.height(16.dp))
        SelectionContainer(Modifier.padding(horizontal = 16.dp)) {
            Text(cmId, color = CmText, fontFamily = Nunito, fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(CmCard).padding(12.dp))
        }

        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Action("Copy", CmBlue, Modifier.weight(1f)) {
                clipboard.setText(AnnotatedString(cmId))
            }
            Action("Share", CmCard, Modifier.weight(1f), textColor = CmText) {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, cmId)
                }
                context.startActivity(Intent.createChooser(send, null))
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun Action(
    label: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier,
    textColor: androidx.compose.ui.graphics.Color = CmBackground, onClick: () -> Unit,
) {
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(color).clickable { onClick() }
        .padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(label, color = textColor, fontFamily = Nunito, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun qrBitmap(text: String, size: Int): Bitmap? = runCatching {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    for (x in 0 until size) for (y in 0 until size) {
        bmp.setPixel(x, y, if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    }
    bmp
}.getOrNull()
