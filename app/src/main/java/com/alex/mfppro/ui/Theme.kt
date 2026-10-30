package com.alex.mfppro.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.alex.mfppro.R
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs

/**
 * Display-only profit split: positive PnL is shown at 100/90/80 % to reflect
 * the payout share. Rule maths (floors, room, targets) always use raw PnL.
 */
/** Competition accounts can be left out of Positions and Accounts (and their totals). */
object Comp {
    var hide by androidx.compose.runtime.mutableStateOf(false)
}

object Split {
    var pct by androidx.compose.runtime.mutableIntStateOf(100)
}

fun net(v: Double?): Double? = v?.let { if (it > 0) it * Split.pct / 100.0 else it }

/** Colour-blind mode swaps green/red for blue/orange everywhere (state-backed, so it applies live). */
object Palette {
    var colorBlind by androidx.compose.runtime.mutableStateOf(false)
}

object C {
    val bg = Color(0xFF0B0E11)
    val panel = Color(0xFF15191E)
    val panel2 = Color(0xFF1E2329)
    val line = Color(0xFF2B3139)
    val text = Color(0xFFEAECEF)
    val muted = Color(0xFF848E9C)
    val up get() = if (Palette.colorBlind) Color(0xFF3B8BFF) else Color(0xFF20B26C)
    val down get() = if (Palette.colorBlind) Color(0xFFFF8C1A) else Color(0xFFEF454A)
    val accent get() = if (Palette.colorBlind) Color(0xFFB794F6) else Color(0xFFF7A600)
    val liq get() = if (Palette.colorBlind) Color(0xFFE879F9) else Color(0xFFFF7A00)
}

val Inter = FontFamily(
    Font(R.font.inter_400, FontWeight.Normal),
    Font(R.font.inter_500, FontWeight.Medium),
    Font(R.font.inter_600, FontWeight.SemiBold),
    Font(R.font.inter_700, FontWeight.Bold),
)

val Mono = FontFamily(
    Font(R.font.jbmono_400, FontWeight.Normal),
    Font(R.font.jbmono_500, FontWeight.Medium),
    Font(R.font.jbmono_500, FontWeight.SemiBold),
    Font(R.font.jbmono_700, FontWeight.Bold),
)

private val base = TextStyle(fontFamily = Inter, letterSpacing = 0.sp)
private val typography = Typography().let { t ->
    Typography(
        displayLarge = t.displayLarge.merge(base), displayMedium = t.displayMedium.merge(base), displaySmall = t.displaySmall.merge(base),
        headlineLarge = t.headlineLarge.merge(base), headlineMedium = t.headlineMedium.merge(base), headlineSmall = t.headlineSmall.merge(base),
        titleLarge = t.titleLarge.merge(base), titleMedium = t.titleMedium.merge(base), titleSmall = t.titleSmall.merge(base),
        bodyLarge = t.bodyLarge.merge(base), bodyMedium = t.bodyMedium.merge(base), bodySmall = t.bodySmall.merge(base),
        labelLarge = t.labelLarge.merge(base), labelMedium = t.labelMedium.merge(base), labelSmall = t.labelSmall.merge(base),
    )
}

@Composable
fun MfpTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(
        background = C.bg, surface = C.panel, surfaceVariant = C.panel2, primary = C.accent,
        onPrimary = Color.Black, onBackground = C.text, onSurface = C.text, onSurfaceVariant = C.muted,
        outline = C.line, secondaryContainer = C.panel2, onSecondaryContainer = C.text,
    ),
    typography = typography,
    content = content,
)

/** Tabular figures so columns of numbers don't jitter as they update. */
val num = TextStyle(fontFamily = Mono, fontFeatureSettings = "tnum", fontSize = 13.sp, color = C.text, letterSpacing = (-0.2).sp)

fun pnlColor(v: Double?) = when {
    v == null || v == 0.0 -> C.text
    v > 0 -> C.up
    else -> C.down
}

fun splitTag() = if (Split.pct < 100) " ${Split.pct}%" else ""

fun fmtPrice(p: Double?): String {
    if (p == null) return "—"
    val a = abs(p)
    val d = when {
        a >= 10_000 -> 1
        a >= 100 -> 2
        a >= 1 -> 4
        a >= 0.01 -> 5
        else -> 7
    }
    return String.format(Locale.US, "%,.${d}f", p)
}

fun fmtUsd(v: Double?, signed: Boolean = false): String {
    if (v == null) return "—"
    val s = String.format(Locale.US, "%,.2f", abs(v))
    val sign = if (v < 0) "-" else if (signed && v > 0) "+" else ""
    return "$sign$$s"
}

fun fmtCompact(v: Double?): String {
    if (v == null) return "—"
    val a = abs(v)
    val (x, u) = when {
        a >= 1e9 -> v / 1e9 to "B"
        a >= 1e6 -> v / 1e6 to "M"
        a >= 1e3 -> v / 1e3 to "K"
        else -> v to ""
    }
    return String.format(Locale.US, "%.2f%s", x, u)
}

fun fmtPct(v: Double?, signed: Boolean = true): String {
    if (v == null) return "—"
    return String.format(Locale.US, if (signed) "%+.2f%%" else "%.2f%%", v)
}

fun fmtSize(v: Double): String = String.format(Locale.US, "%,.4f", v).trimEnd('0').trimEnd('.')

fun fmtCountdown(ms: Long): String {
    if (ms <= 0) return "00:00:00"
    val s = ms / 1000
    return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
}

/** Display ticker: "BNBUSDT" for USDT perps, the plain symbol for indices, commodities and FX. */
fun ticker(symbol: String, provider: String): String =
    if (provider in setOf("binance", "bybit", "okx") && !symbol.endsWith("USDT")) symbol + "USDT" else symbol
