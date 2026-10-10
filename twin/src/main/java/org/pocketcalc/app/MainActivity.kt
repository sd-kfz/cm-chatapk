package org.pocketcalc.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A calculator, and nothing else as far as anyone can see. It works like one;
 * the only extra is the hidden sequence in [Secret], which opens the other app.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Calculator(onOpen = {
                // Leave nothing behind: the calculator closes when the other app opens.
                if (Target.open(this)) finishAndRemoveTask()
            })
        }
    }
}

private val Background = Color(0xFF000000)
private val Card = Color(0xFF0D1117)
private val Ink = Color(0xFFE7EBF1)
private val InkDim = Color(0xFF8B94A3)
private val InkFaint = Color(0xFF565F6C)
private val Accent = Color(0xFF35C6F2)
private val OpColor = Color(0xFFE0793E)

private val Nunito = FontFamily(
    Font(R.font.nunito_regular, FontWeight.Normal),
    Font(R.font.nunito_semibold, FontWeight.SemiBold),
    Font(R.font.nunito_bold, FontWeight.Bold),
)

@Composable
private fun Calculator(onOpen: () -> Unit) {
    val ctx = LocalContext.current
    val secret = remember { Secret(SecretStore.load(ctx)) }
    fun feed(k: String) {
        when (secret.press(k)) {
            Secret.Action.OPEN -> { SecretStore.save(ctx, secret.key); onOpen() }
            Secret.Action.FORGET -> SecretStore.save(ctx, null)
            Secret.Action.NONE -> {}
        }
    }
    Column(Modifier.fillMaxSize().background(Background)) {
        Text(stringResource(R.string.app_name), color = Ink, fontFamily = Nunito, fontSize = 17.sp,
            fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().padding(16.dp), textAlign = TextAlign.Center)
        Box(Modifier.weight(1f)) {
            Pad(onKey = { feed(it) }) { clear ->
                Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.hint), color = InkFaint, fontFamily = Nunito, fontSize = 11.sp,
                        fontStyle = FontStyle.Italic, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.reset), color = InkDim, fontFamily = Nunito, fontSize = 13.sp,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                            .clickable { clear(); feed(Secret.RESET) }
                            .padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
        }
    }
}

/** Display + keys that share the height there is: on a short screen the keys get shorter, never cut off. */
@Composable
private fun Pad(onKey: (String) -> Unit, footer: @Composable (clear: () -> Unit) -> Unit) {
    val engine = remember { CalcEngine() }
    var display by remember { mutableStateOf(engine.display) }
    var hasMem by remember { mutableStateOf(engine.hasMemory) }
    fun sync() { display = engine.display; hasMem = engine.hasMemory }

    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        val short = maxHeight < 520.dp
        val gap = if (short) 6.dp else 10.dp
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Card)
                .padding(horizontal = 18.dp, vertical = if (short) 10.dp else 24.dp),
                contentAlignment = Alignment.CenterEnd) {
                if (hasMem) Text("M", color = OpColor, fontFamily = Nunito, fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.CenterStart))
                FitOneLine(display, Modifier.fillMaxWidth().padding(start = 18.dp), max = if (short) 30.sp else 40.sp)
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
                            Key(key, Modifier.weight(1f).fillMaxHeight(), small = short) {
                                press(engine, key); sync(); onKey(key)
                            }
                        }
                    }
                }
            }
            footer { engine.clearCe(); engine.clearCe(); sync() }
        }
    }
}

/** One line, right-aligned; a long number shrinks until every digit fits. */
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
                sp = (sp * avail / w).coerceIn(min.value, max.value)
                while (sp > min.value && width(sp) > avail) sp -= 1f
            }
            sp.sp
        }
        Text(text, color = Ink, fontFamily = Nunito, fontSize = size, fontWeight = FontWeight.Bold,
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
private fun Key(label: String, modifier: Modifier, small: Boolean, onClick: () -> Unit) {
    val isOp = label in setOf("+", "−", "×", "÷")
    val isEquals = label == "="
    val isFn = label in setOf("√", "%", "C/CE", "MRC", "M-", "M+")
    val bg = when { isEquals -> Accent; isOp -> OpColor; else -> Card }
    val fg = when { isEquals -> Background; isOp -> Color.White; isFn -> Accent; else -> Ink }
    Box(modifier.heightIn(min = 34.dp).clip(RoundedCornerShape(if (small) 12.dp else 16.dp)).background(bg)
        .clickable { onClick() }, contentAlignment = Alignment.Center) {
        Text(label, color = fg, fontFamily = Nunito, maxLines = 1,
            fontSize = when { label.length > 2 -> if (small) 13.sp else 15.sp; small -> 17.sp; else -> 20.sp },
            fontWeight = FontWeight.SemiBold)
    }
}
