package org.cmchat.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.cmchat.app.R

val Nunito = FontFamily(
    Font(R.font.nunito_regular, FontWeight.Normal),
    Font(R.font.nunito_semibold, FontWeight.SemiBold),
    Font(R.font.nunito_bold, FontWeight.Bold),
)

val CmTypography = Typography().let { d ->
    d.copy(
        bodyLarge   = d.bodyLarge.copy(fontFamily = Nunito),
        bodyMedium  = d.bodyMedium.copy(fontFamily = Nunito),
        titleLarge  = d.titleLarge.copy(fontFamily = Nunito),
        titleMedium = d.titleMedium.copy(fontFamily = Nunito),
        labelLarge  = d.labelLarge.copy(fontFamily = Nunito),
    )
}
