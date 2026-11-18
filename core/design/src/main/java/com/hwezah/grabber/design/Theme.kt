package com.hwezah.grabber.design

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * WatchHub's palette: a near-black page (or warm paper in light mode) and three punchy accents,
 * in this order: deep red (primary), green (downloads, success), deep orange (torrents, convert, warnings).
 * Snapshot state, so everything that reads a colour redraws when the theme switches.
 */
object Ink {
    private class Palette(
        val bg: Color, val surface: Color, val surface2: Color, val stroke: Color,
        val text: Color, val muted: Color, val faint: Color, val danger: Color,
    )

    private val DARK = Palette(
        Color(0xFF0A0A0A), Color(0xFF181818), Color(0xFF262626), Color(0xFF333333),
        Color(0xFFFFFFFF), Color(0xFFB3B3B3), Color(0xFF7A7A7A), Color(0xFFFF5A4E),
    )
    private val LIGHT = Palette(
        Color(0xFFF6F2EE), Color(0xFFFFFFFF), Color(0xFFEAE4DE), Color(0xFFDDD5CE),
        Color(0xFF141414), Color(0xFF575757), Color(0xFF8A8A8A), Color(0xFFD32F2F),
    )
    private var p by mutableStateOf(DARK)

    val isDark get() = p === DARK
    fun use(dark: Boolean) {
        p = if (dark) DARK else LIGHT
    }

    val bg get() = p.bg
    val surface get() = p.surface
    val surface2 get() = p.surface2
    val stroke get() = p.stroke
    val text get() = p.text
    val muted get() = p.muted
    val faint get() = p.faint
    val danger get() = p.danger
    /** Sheets and popups: a plain grey. */
    val sheet get() = if (isDark) Color(0xFF161616) else Color(0xFFFFFFFF)

    /** Primary: deep red by default; the user can pick another in Settings ([ACCENTS]). */
    var red by mutableStateOf(Color(0xFFB81D24))

    /** The choices offered in Settings: name to colour. */
    val ACCENTS = listOf(
        "Red" to Color(0xFFB81D24),
        "Plum" to Color(0xFF6E4A5E),
        "Sand" to Color(0xFFB8A07A),
        "Olive" to Color(0xFF6B7A3A),
        "Teal" to Color(0xFF0F8B8D),
        "Slate" to Color(0xFF4A5A6A),
        "Graphite" to Color(0xFF3A3A3A),
        "Grey" to Color(0xFF8A8A8A),
        "White" to Color(0xFFEDEDED),
    )

    /** Text and icons on [c]: dark on light shades (white, sand), white otherwise. */
    fun on(c: Color): Color = if (c.luminance() > 0.45f) Color(0xFF141414) else Color.White
    val ember = Color(0xFFD9C2C2)
    /** Downloads and success. A shade deeper on the light page so it still reads. */
    val green get() = if (isDark) Color(0xFF1E9E52) else Color(0xFF13703A)
    /** Green buttons: a darker green than the icons and bars. */
    val greenButton get() = if (isDark) Color(0xFF15703A) else Color(0xFF0F5C2E)
    /** Third accent (torrents, conversion, warnings): a subtle warm taupe. No orange anywhere. */
    val orange = Color(0xFFA3927A)
}

@OptIn(ExperimentalTextApi::class)
private fun flex(weight: Int) = Font(
    R.font.google_sans_flex,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Google Sans Flex, a variable font: each weight is the same file at a different axis setting. */
val Sans = FontFamily(flex(400), flex(500), flex(600), flex(700), flex(800))

/** Text styles. Getters, so each picks up the palette in use when it is read. */
object Type {
    val hero get() = TextStyle(fontFamily = Sans, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-1).sp, color = Ink.text)
    val display get() = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.6).sp, color = Ink.text)
    val title get() = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = (-0.2).sp, color = Ink.text)
    val body get() = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp, color = Ink.muted)
    val label get() = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp, color = Ink.text)
    val meta get() = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp, color = Ink.muted)
    val overline get() = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 1.4.sp, color = Ink.faint)
}

object Motion {
    fun <T> spatial(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.85f, stiffness = 600f)
    fun <T> effects(): FiniteAnimationSpec<T> = tween(180)
}

/** An accent's glow: a deep base, the colour itself, and a warm light rising in it. */
fun glowOf(accent: Color, light: Color = Ink.ember) = listOf(lerp(accent, Color.Black, 0.45f), accent, lerp(accent, light, 0.6f))
