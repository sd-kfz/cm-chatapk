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

        // Separate "Self-Test" section — debug builds only (compiled out of
        // release). Its findings live in their OWN log, apart from the Tor log.
        if (BuildConfig.DEBUG) {
            SelfTestSection()
        }

        Spacer(Modifier.height(8.dp))
        Text("Engine log", color = CmTextDim, fontFamily = Nunito, fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp))
        if (entries.isEmpty()) {
            Text("No entries.", color = CmTextFaint, fontFamily = Nunito, fontSize = 13.sp,
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

    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("Self-Test", color = CmOrange, fontFamily = Nunito, fontSize = 13.sp,
            fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Box(Modifier.clip(RoundedCornerShape(10.dp)).background(if (running) CmCard else CmBlue)
            .clickable(enabled = !running) { SelfTest.run(ctx) }
            .padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(if (running) "Running…" else "Run self-test",
                color = if (running) CmTextDim else CmBackground,
                fontFamily = Nunito, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        if (findings.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Box(Modifier.clip(RoundedCornerShape(10.dp)).background(CmCard)
                .clickable { SelfTestLog.clear() }.padding(horizontal = 10.dp, vertical = 6.dp)) {
                Text("Clear", color = CmRed, fontFamily = Nunito, fontSize = 12.sp)
            }
        }
    }
    Text("Adversarial attacks on this app's own parser, flood limits, lifecycle and PIN lockout.",
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
