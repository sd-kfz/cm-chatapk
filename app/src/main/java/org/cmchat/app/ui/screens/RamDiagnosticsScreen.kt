package org.cmchat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.cmchat.app.diag.RamDiag
import org.cmchat.app.ui.theme.*
import org.cmchat.app.R
import org.cmchat.app.i18n.Tr

/** RAM diagnostics view — its own separate list, not mixed with the Tor log. */
@Composable
fun RamDiagnosticsScreen(onBack: () -> Unit) {
    val samples by RamDiag.samples.collectAsState()
    val peak by RamDiag.peakMb.collectAsState()

    // Sample only while this screen is open.
    DisposableEffect(Unit) {
        RamDiag.start()
        onDispose { RamDiag.stop() }
    }

    Column(Modifier.fillMaxSize().background(CmBackground)) {
        Box(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(Tr.s(R.string.back), color = CmBlue, fontFamily = Nunito, fontSize = 15.sp,
                modifier = Modifier.align(Alignment.CenterStart).clickable { onBack() })
            Text(Tr.s(R.string.ramdiag_ram_diagnostics), color = CmText, fontFamily = Nunito, fontSize = 17.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
        }
        Text(Tr.s(R.string.ramdiag_explainer),
            color = CmTextFaint, fontFamily = Nunito, fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 18.dp))
        Text(Tr.s(R.string.ramdiag_peak, peak), color = CmOrange, fontFamily = Nunito, fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp))

        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill(Tr.s(R.string.ramdiag_enable_all_features), CmBlue, Modifier.weight(1f)) { RamDiag.enableAllFeatures() }
            Pill(Tr.s(R.string.ramdiag_clear), CmCard, Modifier.weight(1f), CmText) { RamDiag.clear() }
        }

        LazyColumn(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (samples.isEmpty()) {
                item { Text(Tr.s(R.string.ramdiag_sampling), color = CmTextFaint, fontFamily = Nunito, fontSize = 13.sp) }
            }
            items(samples.reversed()) { s ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CmCard)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(Tr.s(R.string.ramdiag_mb, s.usedMb), color = CmText, fontFamily = Nunito, fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(10.dp))
                    Text(s.features, color = CmTextDim, fontFamily = Nunito, fontSize = 12.sp,
                        modifier = Modifier.weight(1f))
                    Text("/ ${s.maxMb}", color = CmTextFaint, fontFamily = Nunito, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun Pill(label: String, bg: androidx.compose.ui.graphics.Color, modifier: Modifier,
                 fg: androidx.compose.ui.graphics.Color = CmBackground, onClick: () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(bg).clickable { onClick() }
        .padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(label, color = fg, fontFamily = Nunito, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
