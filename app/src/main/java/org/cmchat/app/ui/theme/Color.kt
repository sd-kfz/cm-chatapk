package org.cmchat.app.ui.theme

import androidx.compose.ui.graphics.Color

// v1.2 final look: true-black OLED background, CYAN accent, teal status.
// Every screen reads these tokens, so the look applies app-wide from here.
val CmBackground   = Color(0xFF000000)   // true black (OLED pixels off)
val CmCard         = Color(0xFF0D1117)   // cards lift just off the black
val CmCardHi       = Color(0xFF161C25)   // pressed / secondary card
val CmText         = Color(0xFFE7EBF1)
val CmTextDim      = Color(0xFF8B94A3)
val CmTextFaint    = Color(0xFF565F6C)
val CmBlue         = Color(0xFF35C6F2)   // the CYAN accent (links, buttons, focus)
val CmCyanDeep     = Color(0xFF0E8FC2)   // deep end of the cyan "+" gradient
val CmBlueGlow     = Color(0xFF5FDCFF)
val CmGreen        = Color(0xFF35D6A6)   // teal: "On", online status dots
val CmTeal         = Color(0xFF35D6A6)
val CmBuzzBlue     = Color(0xFF2F6BFF)   // Buzz marker — distinct from orange + cyan
val CmOrange       = Color(0xFFE0793E)   // new / missed message marker
val CmRed          = Color(0xFFE2696F)
val CmRedGlow      = Color(0xFFFF3B3B)
