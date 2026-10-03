package org.cmchat.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val CmColors = darkColorScheme(
    primary = CmBlue,
    background = CmBackground,
    surface = CmCard,
    onPrimary = CmBackground,
    onBackground = CmText,
    onSurface = CmText,
    error = CmRed,
)

@Composable
fun CmChatTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CmColors, typography = CmTypography, content = content)
}
