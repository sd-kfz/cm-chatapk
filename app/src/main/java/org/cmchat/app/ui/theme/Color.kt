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
val CmBuzzBlue     = Color(0xFF2F6BFF)   // "Buzzed you" text
val CmOrange       = Color(0xFFE0793E)   // new / missed message marker
val CmRed          = Color(0xFFE2696F)
val CmRedGlow      = Color(0xFFFF3B3B)

// Chat bubbles have their OWN colours, independent of the card palette: the
// v1.2 switch to true black turned the received bubble into a near-black box
// (#0D1117 on #000). Sent = the original CM-Chat bubble blue; received = a grey
// that reads as a grey bubble on black.
val CmBubbleMine     = Color(0xFF6FB8D9)
val CmBubbleMineText = Color(0xFF0B1118)
val CmBubbleTheirs   = Color(0xFF2A2D33)
val CmBubbleText     = Color(0xFFF2F4F7)

/** A NEW unread message is waiting (the only dot on a friend's row). */
val CmUnreadBlue     = Color(0xFF3D8BFF)
