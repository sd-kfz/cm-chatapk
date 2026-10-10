package org.cmchat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.cmchat.app.settings.Languages
import org.cmchat.app.ui.theme.*
import org.cmchat.app.R
import org.cmchat.app.i18n.Tr

@Composable
fun LanguageScreen(onBack: () -> Unit, onPick: (String) -> Unit) {
    val selected by Languages.selected.collectAsState()
    Column(Modifier.fillMaxSize().background(CmBackground)) {
        Box(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(Tr.s(R.string.back), color = CmBlue, fontFamily = Nunito, fontSize = 15.sp,
                modifier = Modifier.align(Alignment.CenterStart).clickable { onBack() })
            Text(Tr.s(R.string.lang_language), color = CmText, fontFamily = Nunito, fontSize = 17.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Languages.list) { (tag, name) ->
                val sel = tag == Languages.effective(selected)
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CmCard)
                    .clickable { onPick(tag) }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(name, color = CmText, fontFamily = Nunito, fontSize = 15.sp,
                        modifier = Modifier.weight(1f))
                    if (sel) Text("✓", color = CmGreen, fontFamily = Nunito, fontSize = 16.sp,
                        fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
