package org.cmchat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.cmchat.app.tools.CalcEngine
import org.cmchat.app.tools.ToolsState
import org.cmchat.app.ui.theme.*

@Composable
fun ToolsScreen(which: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(CmBackground)) {
        Box(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("‹ Back", color = CmBlue, fontFamily = Nunito, fontSize = 15.sp,
                modifier = Modifier.align(Alignment.CenterStart).clickable { onBack() })
            Text(which.replaceFirstChar { it.uppercase() }, color = CmText, fontFamily = Nunito,
                fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
        }
        when (which) {
            "calculator" -> CalculatorUi()
            "notes" -> NotesUi()
            "flashlight" -> FlashlightUi()
        }
    }
}

@Composable
private fun FlashlightUi() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val on by org.cmchat.app.tools.Flashlight.on.collectAsState()
    Column(Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Box(
            Modifier.size(140.dp).clip(CircleShape)
                .background(if (on) CmOrange else CmCard)
                .clickable { org.cmchat.app.tools.Flashlight.toggle(context) },
            contentAlignment = Alignment.Center,
        ) {
            Text("☀", color = if (on) Color.White else CmTextDim, fontSize = 64.sp)
        }
        Spacer(Modifier.height(16.dp))
        Text(if (on) "Torch ON — tap to turn off" else "Tap to turn the torch on",
            color = CmTextDim, fontFamily = Nunito, fontSize = 14.sp)
    }
}

// ---- Calculator: Google-calculator look, CT-200N key set ------------------

@Composable
private fun CalculatorUi() {
    val engine = remember { CalcEngine() }
    var display by remember { mutableStateOf(engine.display) }
    var hasMem by remember { mutableStateOf(engine.hasMemory) }
    fun sync() { display = engine.display; hasMem = engine.hasMemory }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        // Display
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(CmCard)
            .padding(horizontal = 20.dp, vertical = 28.dp), contentAlignment = Alignment.CenterEnd) {
            if (hasMem) Text("M", color = CmOrange, fontFamily = Nunito, fontSize = 14.sp,
                modifier = Modifier.align(Alignment.CenterStart))
            Text(display, color = CmText, fontFamily = Nunito, fontSize = 40.sp,
                fontWeight = FontWeight.Bold, maxLines = 1)
        }
        Spacer(Modifier.height(16.dp))

        val rows = listOf(
            listOf("MRC", "M-", "M+", "C/CE"),
            listOf("√", "%", "÷", "×"),
            listOf("7", "8", "9", "−"),
            listOf("4", "5", "6", "+"),
            listOf("1", "2", "3", "="),
            listOf("0", ".", "", ""),
        )
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (row in rows) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (key in row) {
                        if (key.isEmpty()) { Spacer(Modifier.weight(1f)); continue }
                        CalcKey(key, Modifier.weight(1f)) {
                            press(engine, key); sync()
                        }
                    }
                }
            }
        }
    }
}

private fun press(e: CalcEngine, key: String) {
    when (key) {
        in "0".."9" -> e.digit(key.toInt())
        "." -> e.dot()
        "+" -> e.op('+'); "−" -> e.op('-'); "×" -> e.op('*'); "÷" -> e.op('/')
        "=" -> e.equals()
        "%" -> e.percent()
        "√" -> e.sqrt()
        "C/CE" -> e.clearCe()
        "MRC" -> e.memRecall()
        "M-" -> e.memMinus()
        "M+" -> e.memPlus()
    }
}

@Composable
private fun CalcKey(label: String, modifier: Modifier, onClick: () -> Unit) {
    val isOp = label in setOf("+", "−", "×", "÷")
    val isEquals = label == "="
    val isFn = label in setOf("√", "%", "C/CE", "MRC", "M-", "M+")
    val bg = when {
        isEquals -> CmBlue
        isOp -> CmOrange
        isFn -> CmCard
        else -> CmCard
    }
    val fg = when {
        isEquals -> CmBackground
        isOp -> Color.White
        isFn -> CmBlue
        else -> CmText
    }
    Box(modifier.height(58.dp).clip(RoundedCornerShape(16.dp)).background(bg).clickable { onClick() },
        contentAlignment = Alignment.Center) {
        Text(label, color = fg, fontFamily = Nunito,
            fontSize = if (label.length > 2) 15.sp else 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ---- Notes: RAM-only scratchpad + checklist -------------------------------

@Composable
private fun NotesUi() {
    val notes by ToolsState.notes.collectAsState()
    val checks by ToolsState.checks.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("RAM only — cleared when the app closes.", color = CmTextDim, fontFamily = Nunito, fontSize = 12.sp)

        Box(Modifier.fillMaxWidth().heightIn(min = 120.dp)
            .clip(RoundedCornerShape(12.dp)).background(CmCard).padding(12.dp)) {
            if (notes.isEmpty()) Text("Scratchpad…", color = CmTextDim, fontFamily = Nunito, fontSize = 15.sp)
            BasicTextField(
                value = notes, onValueChange = { ToolsState.notes.value = it },
                textStyle = TextStyle(color = CmText, fontFamily = Nunito, fontSize = 15.sp),
                cursorBrush = SolidColor(CmBlue), modifier = Modifier.fillMaxWidth(),
            )
        }

        Box(Modifier.clip(RoundedCornerShape(12.dp)).background(CmBlue)
            .clickable { ToolsState.addCheck() }.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text("+ Add check", color = CmBackground, fontFamily = Nunito,
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(checks, key = { it.id }) { item ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CmCard)
                    .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp))
                        .background(if (item.done) CmGreen else CmBackground)
                        .clickable { ToolsState.toggleCheck(item.id) },
                        contentAlignment = Alignment.Center) {
                        if (item.done) Text("✓", color = CmBackground, fontSize = 14.sp,
                            fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(10.dp))
                    BasicTextField(
                        value = item.text,
                        onValueChange = { ToolsState.setCheckText(item.id, it) },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = if (item.done) CmTextDim else CmText,
                            fontFamily = Nunito, fontSize = 15.sp,
                            textDecoration = if (item.done) TextDecoration.LineThrough else null,
                        ),
                        cursorBrush = SolidColor(CmBlue),
                        decorationBox = { inner ->
                            if (item.text.isEmpty())
                                Text("To-do…", color = CmTextDim, fontFamily = Nunito, fontSize = 15.sp)
                            inner()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
