package com.example.digi.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The player's chrome palette.
 *
 * Only two surfaces in this app are ever chrome — the pairing screen and the diagnostics overlay —
 * and both are read at a distance, often in a bright concourse. So the palette is a dark ground
 * with one strong accent and a deliberately narrow set of state colours, rather than a full
 * Material ramp most of which would never be drawn.
 */
val Ink = Color(0xFF070B12)          // page ground
val Panel = Color(0xFF121A28)        // cards, key caps, log pane
val PanelEdge = Color(0xFF22304A)    // borders
val Accent = Color(0xFF2F6BFF)       // focus, primary actions
val TextPrimary = Color(0xFFE8ECF3)
val TextMuted = Color(0xFF6B7688)
val StateGood = Color(0xFF3DD68C)
val StateWarn = Color(0xFFFFC14D)
val StateBad = Color(0xFFFF6B6B)
