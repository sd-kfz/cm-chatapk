package org.cmchat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.cmchat.app.R
import org.cmchat.app.ui.components.CmChatLogo
import org.cmchat.app.ui.theme.*

/**
 * First-run wizard for brand-new users, in very simple language. Shown until
 * finished (or skipped), skippable. Covers: identity, PIN, Engine/Online/Invisible,
 * adding via QR, and wipe/shredder. Text comes from string resources
 * (translation-ready). Resumable: it opens at [startPage], and every page turn
 * is reported via [onPage] (saved in the vault), so minimising — which re-locks
 * the app — or a restart brings you back to the same page.
 */
@Composable
fun OnboardingScreen(startPage: Int = 0, onPage: (Int) -> Unit = {}, onDone: () -> Unit) {
    val pages = listOf(
        R.string.ob_title_1 to R.string.ob_body_1,
        R.string.ob_title_2 to R.string.ob_body_2,
        R.string.ob_title_3 to R.string.ob_body_3,
        R.string.ob_title_4 to R.string.ob_body_4,
        R.string.ob_title_5 to R.string.ob_body_5,
    )
    var page by remember { mutableStateOf(startPage.coerceIn(0, pages.lastIndex)) }
    LaunchedEffect(page) { onPage(page) }
    val last = page == pages.lastIndex

    Column(Modifier.fillMaxSize().background(CmBackground).padding(24.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text("${page + 1} / ${pages.size}", color = CmTextFaint, fontFamily = Nunito, fontSize = 13.sp,
                modifier = Modifier.weight(1f))
            Text(stringResource(R.string.skip), color = CmTextDim, fontFamily = Nunito, fontSize = 14.sp,
                modifier = Modifier.clickable { onDone() })
        }

        Spacer(Modifier.height(40.dp))
        CmChatLogo(size = 30)
        Spacer(Modifier.height(28.dp))
        Text(stringResource(pages[page].first), color = CmText, fontFamily = Nunito,
            fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(pages[page].second), color = CmTextDim, fontFamily = Nunito,
            fontSize = 15.sp, lineHeight = 22.sp)

        Spacer(Modifier.weight(1f))

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (page > 0) {
                Text(stringResource(R.string.back), color = CmBlue, fontFamily = Nunito, fontSize = 15.sp,
                    modifier = Modifier.clickable { page -= 1 })
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.clip(RoundedCornerShape(16.dp)).background(CmBlue)
                .clickable { if (last) onDone() else page += 1 }
                .padding(horizontal = 28.dp, vertical = 12.dp)) {
                Text(stringResource(if (last) R.string.done else R.string.next),
                    color = CmBackground, fontFamily = Nunito, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
