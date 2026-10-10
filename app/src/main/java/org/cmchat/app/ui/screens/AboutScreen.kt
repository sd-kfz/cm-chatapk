package org.cmchat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.cmchat.app.ui.theme.*
import org.cmchat.app.R
import org.cmchat.app.i18n.Tr

/** About / Version: the serious CMC safety welcome. */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(CmBackground)) {
        Box(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(Tr.s(R.string.back), color = CmBlue, fontFamily = Nunito, fontSize = 15.sp,
                modifier = Modifier.align(Alignment.CenterStart).clickable { onBack() })
            Text(Tr.s(R.string.about_about), color = CmText, fontFamily = Nunito, fontSize = 17.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("CM-Chat v0.1", color = CmText, fontFamily = Nunito, fontSize = 18.sp,
                fontWeight = FontWeight.Bold)
            Text(WELCOME, color = CmTextDim, fontFamily = Nunito, fontSize = 13.sp)
            Text(
                Tr.s(R.string.about_test_warning),
                color = CmRed, fontFamily = Nunito, fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

private val WELCOME: String get() = Tr.s(R.string.about_welcome)
