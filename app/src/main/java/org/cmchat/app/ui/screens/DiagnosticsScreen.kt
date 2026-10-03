package org.cmchat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.cmchat.app.diag.Diag
import org.cmchat.app.ui.theme.*

@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val entries by Diag.entries.collectAsState()
    val clipboard = LocalClipboardManager.current

    Column(Modifier.fillMaxSize().background(CmBackground)) {
        Box(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("‹ Back", color = CmBlue, fontFamily = Nunito, fontSize = 15.sp,
                modifier = Modifier.align(Alignment.CenterStart).clickable { onBack() })
            Text("Diagnostics", color = CmText, fontFamily = Nunito, fontSize = 17.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(CmCard)
                .clickable { clipboard.setText(AnnotatedString(Diag.dump())) }.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center) {
                Text("Copy all", color = CmBlue, fontFamily = Nunito, fontSize = 13.sp)
            }
            Box(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(CmCard)
                .clickable { Diag.clear() }.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center) {
                Text("Clear", color = CmRed, fontFamily = Nunito, fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(8.dp))
        if (entries.isEmpty()) {
            Text("No entries.", color = CmTextFaint, fontFamily = Nunito, fontSize = 13.sp,
                modifier = Modifier.padding(16.dp))
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            items(entries.asReversed()) { e ->
                val color = when (e.level) {
                    Diag.Level.E -> CmRed; Diag.Level.W -> CmOrange
                    Diag.Level.I -> CmText; Diag.Level.D -> CmTextDim
                }
                Text("[${e.tag}] ${e.message}", color = color, fontFamily = Nunito,
                    fontSize = 12.sp, modifier = Modifier.padding(vertical = 3.dp))
            }
        }
    }
}
