package com.raijin.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── Raijin Sci-Fi Tactical HUD Colour Palette (Karen / Friday / Edith) ────────
val RaijinBlack     = Color(0xFF04060C) // Deep space void
val RaijinSurface   = Color(0xFF090E1A) // Cyber slate
val RaijinSurfaceGlass = Color(0xDD090E1A) // Glassmorphic HUD overlay
val RaijinBlue      = Color(0xFF00E5FF) // Electric Arc Reactor Cyan
val RaijinBlueLight = Color(0xFF80F5FF) // Holographic Cyan glow
val RaijinBlueDim   = Color(0xFF0A2938) // Sub-hud cyan
val RaijinGold      = Color(0xFFFFB700) // Stark Industries Gold
val RaijinWhite     = Color(0xFFEDF8FC)
val RaijinText      = Color(0xFF86A5BA)
val RaijinTextDim   = Color(0xFF384F66)
val RaijinGreen     = Color(0xFF00E676) // Tactical green
val RaijinAmber     = Color(0xFFFFAB00) // Tactical warning amber
val RaijinRed       = Color(0xFFFF334B) // Alert red
val RaijinBorderDim = Color(0xFF14243B)

private val RaijinColorScheme = darkColorScheme(
    primary        = RaijinBlue,
    onPrimary      = RaijinBlack,
    background     = RaijinBlack,
    onBackground   = RaijinWhite,
    surface        = RaijinSurface,
    onSurface      = RaijinWhite,
    secondary      = RaijinBlueDim,
    onSecondary    = RaijinText,
    error          = RaijinRed,
    onError        = RaijinWhite,
)

@Composable
fun RaijinTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RaijinColorScheme,
        content     = content,
    )
}
