package org.cmchat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
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
private fun CalculatorUi() = CalculatorPad()

/**
 * The calculator: display + keys that SHARE the height that's there — on an
 * old, short or low-res screen (or with large text) the keys get shorter
 * instead of falling off the bottom. [onKey] sees every key pressed (the
 * cover's secret); [footer] sits under the keys.
 */
@Composable
fun CalculatorPad(onKey: (String) -> Unit = {}, footer: (@Composable (clear: () -> Unit) -> Unit)? = null) {
    val engine = remember { CalcEngine() }
    var display by remember { mutableStateOf(engine.display) }
    var hasMem by remember { mutableStateOf(engine.hasMemory) }
    fun sync() { display = engine.display; hasMem = engine.hasMemory }

    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        val short = maxHeight < 520.dp
        val gap = if (short) 6.dp else 10.dp
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
            // Display
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(CmCard)
                .padding(horizontal = 18.dp, vertical = if (short) 10.dp else 24.dp),
                contentAlignment = Alignment.CenterEnd) {
                if (hasMem) Text("M", color = CmOrange, fontFamily = Nunito, fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.CenterStart))
                // Always ONE line: a long number shrinks until every digit fits
                // (never wraps with the last digit under the first).
                FitOneLine(display, Modifier.fillMaxWidth().padding(start = 18.dp),
                    max = if (short) 30.sp else 40.sp)
            }

            val rows = listOf(
                listOf("MRC", "M-", "M+", "C/CE"),
                listOf("√", "%", "÷", "×"),
                listOf("7", "8", "9", "−"),
                listOf("4", "5", "6", "+"),
                listOf("1", "2", "3", "="),
                listOf("0", ".", "", ""),
            )
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
                for (row in rows) {
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        for (key in row) {
                            if (key.isEmpty()) { Spacer(Modifier.weight(1f)); continue }
                            CalcKey(key, Modifier.weight(1f).fillMaxHeight(), small = short) {
                                press(engine, key); sync(); onKey(key)
                            }
                        }
                    }
                }
            }
            footer?.invoke { engine.clearCe(); engine.clearCe(); sync() }
        }
    }
}

/**
 * Cover mode: a calculator and nothing else — it works like one. The same key
 * 10 times in a row opens the real app; "reset" clears the calculator, and 20
 * in a row forgets the key (see [org.cmchat.app.tools.CoverSecret]). The only
 * tell is the small italic "tap 10 times".
 */
@Composable
fun CalculatorCover(onOpen: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val secret = remember { org.cmchat.app.tools.CoverSecret(org.cmchat.app.tools.CoverMode.secretKey(ctx)) }
    fun feed(k: String) {
        when (secret.press(k)) {
            org.cmchat.app.tools.CoverSecret.Action.OPEN -> {
                org.cmchat.app.tools.CoverMode.setSecretKey(ctx, secret.key)
                onOpen()
            }
            org.cmchat.app.tools.CoverSecret.Action.FORGET -> org.cmchat.app.tools.CoverMode.setSecretKey(ctx, null)
            org.cmchat.app.tools.CoverSecret.Action.NONE -> {}
        }
    }
    Column(Modifier.fillMaxSize().background(CmBackground)) {
        Text("Calculator", color = CmText, fontFamily = Nunito, fontSize = 17.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth().padding(16.dp), textAlign = TextAlign.Center)
        Box(Modifier.weight(1f)) {
            CalculatorPad(onKey = { feed(it) }) { clear ->
                Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("tap 10 times", color = CmTextFaint, fontFamily = Nunito, fontSize = 11.sp,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, modifier = Modifier.weight(1f))
                    Text("reset", color = CmTextDim, fontFamily = Nunito, fontSize = 13.sp,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                            .clickable { clear(); feed(org.cmchat.app.tools.CoverSecret.RESET) }
                            .padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
        }
    }
}

/** Right-aligned, single-line text that scales its font down (40sp → 14sp) to fit. */
@Composable
private fun FitOneLine(text: String, modifier: Modifier, max: TextUnit = 40.sp, min: TextUnit = 14.sp) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier, contentAlignment = Alignment.CenterEnd) {
        val avail = constraints.maxWidth
        val size = remember(text, avail) {
            fun width(sp: Float) = measurer.measure(text,
                TextStyle(fontFamily = Nunito, fontSize = sp.sp, fontWeight = FontWeight.Bold),
                maxLines = 1, softWrap = false).size.width
            var sp = max.value
            val w = width(sp)
            if (w > avail && w > 0) {
                // Text width scales ~linearly with font size: jump close, then step down.
                sp = (sp * avail / w).coerceIn(min.value, max.value)
                while (sp > min.value && width(sp) > avail) sp -= 1f
            }
            sp.sp
        }
        Text(text, color = CmText, fontFamily = Nunito, fontSize = size, fontWeight = FontWeight.Bold,
            maxLines = 1, softWrap = false, textAlign = TextAlign.End)
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
private fun CalcKey(label: String, modifier: Modifier, small: Boolean = false, onClick: () -> Unit) {
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
    Box(modifier.heightIn(min = 34.dp).clip(RoundedCornerShape(if (small) 12.dp else 16.dp)).background(bg)
        .clickable { onClick() }, contentAlignment = Alignment.Center) {
        Text(label, color = fg, fontFamily = Nunito, maxLines = 1,
            fontSize = when { label.length > 2 -> if (small) 13.sp else 15.sp; small -> 17.sp; else -> 20.sp },
            fontWeight = FontWeight.SemiBold)
    }
}

// ---- Notes: RAM-only scratchpad + checklist -------------------------------

@Composable
private fun NotesUi() {
    val notes by ToolsState.notes.collectAsState()
    val checks by ToolsState.checks.collectAsState()
    // GUARD: notes are only ever cleared after an explicit confirmation.
    var confirmClear by remember { mutableStateOf(false) }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear your notes?") },
            text = { Text("This erases the scratchpad and the checklist. It can't be undone.") },
            confirmButton = {
                TextButton(onClick = { ToolsState.clear(); confirmClear = false }) { Text("Clear", color = CmRed) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Keep") } },
        )
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("RAM only — kept while the app runs, gone when it closes.", color = CmTextDim,
                fontFamily = Nunito, fontSize = 12.sp, modifier = Modifier.weight(1f))
            if (notes.isNotEmpty() || checks.isNotEmpty()) {
                Text("Clear…", color = CmRed, fontFamily = Nunito, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { confirmClear = true }
                        .padding(horizontal = 8.dp, vertical = 4.dp))
            }
        }

        // The scratchpad takes the free space and SCROLLS inside itself, so a
        // long note never grows off the screen and no line is ever lost.
        Box(Modifier.fillMaxWidth().weight(1f)
            .clip(RoundedCornerShape(12.dp)).background(CmCard).padding(12.dp)) {
            if (notes.isEmpty()) Text("Scratchpad…", color = CmTextDim, fontFamily = Nunito, fontSize = 15.sp)
            BasicTextField(
                value = notes,
                onValueChange = { if (it.length <= ToolsState.MAX_NOTES_CHARS) ToolsState.notes.value = it },
                textStyle = TextStyle(color = CmText, fontFamily = Nunito, fontSize = 15.sp),
                cursorBrush = SolidColor(CmBlue), modifier = Modifier.fillMaxSize(),
            )
        }
        if (notes.length > ToolsState.MAX_NOTES_CHARS * 9 / 10) {
            Text("${notes.length} / ${ToolsState.MAX_NOTES_CHARS} characters", color = CmOrange,
                fontFamily = Nunito, fontSize = 11.sp)
        }

        val atMax = checks.size >= ToolsState.MAX_CHECKS
        Box(Modifier.clip(RoundedCornerShape(12.dp))
            .background(if (atMax) CmCard else CmBlue)
            .clickable(enabled = !atMax) { ToolsState.addCheck() }
            .padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(if (atMax) "Max ${ToolsState.MAX_CHECKS} checks" else "+ Add check",
                color = if (atMax) CmTextDim else CmBackground, fontFamily = Nunito,
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }

        // Bounded too (scrolls), so the checklist never pushes the scratchpad away.
        LazyColumn(Modifier.heightIn(max = 220.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
