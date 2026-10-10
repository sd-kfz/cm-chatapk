package org.cmchat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.cmchat.app.R
import org.cmchat.app.ui.theme.*

/**
 * Plain-language "How to use" guide, in the order a new user needs it: setup →
 * adding friends → messaging → presence → privacy & panic → diagnostics.
 */
@Composable
fun HelpScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(CmBackground)) {
        Box(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(stringResource(R.string.back), color = CmBlue, fontFamily = Nunito, fontSize = 15.sp,
                modifier = Modifier.align(Alignment.CenterStart).clickable { onBack() })
            Text(stringResource(R.string.help_title), color = CmText, fontFamily = Nunito, fontSize = 17.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

            for ((section, entries) in HELP) {
                Section(stringResource(section))
                for ((title, text) in entries) Entry(stringResource(title), stringResource(text))
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** (section, [(title, text)]) — every line is a string resource, so it's translated. */
private val HELP: List<Pair<Int, List<Pair<Int, Int>>>> = listOf(
    R.string.help_s1 to listOf(
        R.string.help_pin_t to R.string.help_pin_b,
        R.string.help_engine_t to R.string.help_engine_b,
        R.string.help_nick_t to R.string.help_nick_b,
    ),
    R.string.help_s2 to listOf(
        R.string.help_add_t to R.string.help_add_b,
        R.string.help_requests_t to R.string.help_requests_b,
        R.string.help_qr_t to R.string.help_qr_b,
    ),
    R.string.help_s3 to listOf(
        R.string.help_send_t to R.string.help_send_b,
        R.string.help_files_t to R.string.help_files_b,
        R.string.help_disappear_t to R.string.help_disappear_b,
        R.string.help_teamclock_t to R.string.help_teamclock_b,
        R.string.help_buzz_t to R.string.help_buzz_b,
        R.string.help_x_t to R.string.help_x_b,
    ),
    R.string.help_s4 to listOf(
        R.string.help_presence_t to R.string.help_presence_b,
        R.string.help_closed_t to R.string.help_closed_b,
        R.string.help_dot_t to R.string.help_dot_b,
        R.string.help_lastseen_t to R.string.help_lastseen_b,
        R.string.help_exit_t to R.string.help_exit_b,
    ),
    R.string.help_s5 to listOf(
        R.string.help_privacy_t to R.string.help_privacy_b,
        R.string.help_cerberus_t to R.string.help_cerberus_b,
        R.string.help_kill_t to R.string.help_kill_b,
        R.string.help_decoy_t to R.string.help_decoy_b,
        R.string.help_cover_t to R.string.help_cover_b,
        R.string.help_shredder_t to R.string.help_shredder_b,
        R.string.help_wipe_t to R.string.help_wipe_b,
        R.string.help_bridges_t to R.string.help_bridges_b,
    ),
    R.string.help_s6 to listOf(
        R.string.help_conn_t to R.string.help_conn_b,
        R.string.help_update_t to R.string.help_update_b,
        R.string.help_diag_t to R.string.help_diag_b,
    ),
)

@Composable
private fun Section(title: String) {
    Text(title, color = CmBlue, fontFamily = Nunito, fontSize = 13.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp, start = 4.dp))
}

@Composable
private fun Entry(title: String, body: String) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CmCard).padding(14.dp)) {
        Text(title, color = CmText, fontFamily = Nunito, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(body, color = CmTextDim, fontFamily = Nunito, fontSize = 13.sp, lineHeight = 19.sp)
    }
}
