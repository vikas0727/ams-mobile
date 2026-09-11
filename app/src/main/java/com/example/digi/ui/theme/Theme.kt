package com.example.digi.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * One theme, always dark, no dynamic colour.
 *
 * A signage player has exactly two chrome surfaces — the pairing screen and the diagnostics overlay
 * — and both sit on a panel in a public space. Light chrome on a wall-mounted screen is a glare
 * source, and Material You's wallpaper-derived palette would make a fleet of identical boxes look
 * inconsistent for no benefit to anyone. So there is no light scheme and no `dynamicColor` flag to
 * get switched on by accident.
 */
private val DigiColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondary = StateGood,
    background = Ink,
    onBackground = TextPrimary,
    surface = Panel,
    onSurface = TextPrimary,
    outline = PanelEdge,
    error = StateBad,
)

@Composable
fun DigiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DigiColors,
        typography = DigiTypography,
        content = content,
    )
}
