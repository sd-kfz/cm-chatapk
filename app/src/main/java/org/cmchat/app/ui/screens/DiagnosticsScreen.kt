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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.cmchat.app.BuildConfig
import org.cmchat.app.diag.Diag
import org.cmchat.app.selftest.SelfTest
import org.cmchat.app.selftest.SelfTestLog
import org.cmchat.app.ui.theme.*
import org.cmchat.app.R
import org.cmchat.app.i18n.Tr

@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val entries by Diag.entries.collectAsState()
    val clipboard = LocalClipboardManager.current

    Column(Modifier.fillMaxSize().background(CmBackground)) {
        Box(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(Tr.s(R.string.back), color = CmBlue, fontFamily = Nunito, fontSize = 15.sp,
                modifier = Modifier.align(Alignment.CenterStart).clickable { onBack() })
            Text(Tr.s(R.string.diag_diagnostics), color = CmText, fontFamily = Nunito, fontSize = 17.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(CmCard)
                .clickable { clipboard.setText(AnnotatedString(Diag.dump())) }.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center) {
                Text(Tr.s(R.string.diag_copy_all), color = CmBlue, fontFamily = Nunito, fontSize = 13.sp)
            }
            Box(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(CmCard)
                .clickable { Diag.clear() }.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center) {
                Text(Tr.s(R.string.clear), color = CmRed, fontFamily = Nunito, fontSize = 13.sp)
            }
        }

        // Separate "Self-Test" section — debug builds only (compiled out of
        // release). Its findings live in their OWN log, apart from the Tor log.
        if (BuildConfig.DEBUG) {
            SelfTestSection()
        }

        Spacer(Modifier.height(8.dp))
        Text(Tr.s(R.string.diag_engine_log), color = CmTextDim, fontFamily = Nunito, fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp))
        if (entries.isEmpty()) {
            Text(Tr.s(R.string.diag_no_entries), color = CmTextFaint, fontFamily = Nunito, fontSize = 13.sp,
                modifier = Modifier.padding(16.dp))
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp)) {
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

@Composable
private fun SelfTestSection() {
    val ctx = LocalContext.current
    val findings by SelfTestLog.findings.collectAsState()
    val running by SelfTestLog.running.collectAsState()
    // Disabled while running AND for a short cooldown after, so mashing the
    // button can't queue runs back to back (SelfTest.run enforces it too).
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) } }
    val waitS = ((SelfTest.cooldownMs(now) + 999) / 1000).toInt()
    val canRun = !running && waitS == 0

    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(Tr.s(R.string.diag_self_test), color = CmOrange, fontFamily = Nunito, fontSize = 13.sp,
            fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Box(Modifier.clip(RoundedCornerShape(10.dp)).background(if (canRun) CmBlue else CmCard)
            .clickable(enabled = canRun) { SelfTest.run(ctx); now = System.currentTimeMillis() }
            .padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(when { running -> Tr.s(R.string.diag_running); waitS > 0 -> Tr.s(R.string.diag_again_in, waitS); else -> Tr.s(R.string.diag_run_self_test) },
                color = if (canRun) CmBackground else CmTextDim,
                fontFamily = Nunito, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        if (findings.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Box(Modifier.clip(RoundedCornerShape(10.dp)).background(CmCard)
                .clickable { SelfTestLog.clear() }.padding(horizontal = 10.dp, vertical = 6.dp)) {
                Text(Tr.s(R.string.clear), color = CmRed, fontFamily = Nunito, fontSize = 12.sp)
            }
        }
    }
    Text(Tr.s(R.string.diag_selftest_hint),
        color = CmTextFaint, fontFamily = Nunito, fontSize = 10.sp,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))

    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 230.dp).padding(horizontal = 16.dp)) {
        items(findings.asReversed()) { f ->
            val color = when (f.severity) {
                SelfTestLog.Severity.HIGH -> CmRed
                SelfTestLog.Severity.MEDIUM -> CmOrange
                SelfTestLog.Severity.LOW -> CmOrange
                SelfTestLog.Severity.OK -> CmGreen
                SelfTestLog.Severity.INFO -> CmTextDim
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text("[${f.severity}]", color = color, fontFamily = Nunito, fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.attack, color = CmText, fontFamily = Nunito, fontSize = 11.sp)
                    Text(f.result, color = CmTextDim, fontFamily = Nunito, fontSize = 11.sp)
                }
            }
        }
    }
}
