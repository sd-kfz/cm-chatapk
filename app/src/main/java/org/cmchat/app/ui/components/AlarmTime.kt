package org.cmchat.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.cmchat.app.ui.theme.CmTextDim
import org.cmchat.app.ui.theme.Nunito

/**
 * An alarm-style time picker (the same one Android's clock app uses): pick the
 * hour and minute with AM/PM. Tall screens get the round dial; small / old
 * screens get the compact type-it-in version, so it always fits. No time zones.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmTimeDialog(
    title: String,
    initialHour: Int,
    initialMinute: Int,
    note: String? = null,
    confirmLabel: String = "Set",
    /** Optional extra action under the picker (e.g. "Turn off"). */
    extra: (@Composable () -> Unit)? = null,
    onConfirm: (hour: Int, minute: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberTimePickerState(initialHour = initialHour, initialMinute = initialMinute, is24Hour = false)
    val roomy = LocalConfiguration.current.screenHeightDp >= 640
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally) {
                note?.let {
                    Text(it, color = CmTextDim, fontFamily = Nunito, fontSize = 12.sp)
                    Spacer(Modifier.height(10.dp))
                }
                if (roomy) TimePicker(state = state) else TimeInput(state = state)
                extra?.let { Spacer(Modifier.height(4.dp)); it() }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(state.hour, state.minute) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
