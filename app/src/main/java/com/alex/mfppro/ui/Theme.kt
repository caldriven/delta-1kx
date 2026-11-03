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
import androidx.compose.ui.graphics.luminance
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
    var theme by androidx.compose.runtime.mutableStateOf(AppTheme.STANDARD)
}

/** Colour sets; the dark ones follow the Δ1KX mockups. */
enum class AppTheme(
    val label: String, val dark: Boolean,
    val bg: Long, val panel: Long, val panel2: Long, val line: Long, val text: Long, val muted: Long,
    val up: Long, val down: Long, val accent: Long,
) {
    STANDARD("Standard", true, 0xFF0B0E11, 0xFF15191E, 0xFF1E2329, 0xFF2B3139, 0xFFEAECEF, 0xFF848E9C, 0xFF20B26C, 0xFFEF454A, 0xFFF7A600),
    HYBRID("Hybrid", true, 0xFF050508, 0xFF0D0D12, 0xFF16161D, 0xFF24242E, 0xFFE8E4D9, 0xFF8A8678, 0xFF20D0D0, 0xFFF06848, 0xFFF0A030),
    TERMINAL("Terminal", true, 0xFF000000, 0xFF0A0A0A, 0xFF141414, 0xFF262626, 0xFFFFB347, 0xFF9A7A4A, 0xFF33FF66, 0xFFFF3B3B, 0xFFFF9900),
    CLEAN("Clean", true, 0xFF0A0A0B, 0xFF111113, 0xFF18181B, 0xFF27272A, 0xFFFAFAFA, 0xFF8B8B93, 0xFF22C55E, 0xFFEF4444, 0xFF3B82F6),
    GLASS("Glass", true, 0xFF0A0F1E, 0xFF131A2E, 0xFF1B2440, 0xFF28324F, 0xFFE6ECFF, 0xFF8792B3, 0xFF10B981, 0xFFEF4444, 0xFF60A5FA),
    LIGHT("Light", false, 0xFFF4F5F7, 0xFFFFFFFF, 0xFFEDEFF2, 0xFFDCDFE4, 0xFF14171C, 0xFF6B7280, 0xFF0E9F5A, 0xFFD9363E, 0xFFD98A00),
}

/** Text colour that reads on [bg]: black on light fills, white on dark ones. */
fun onColor(bg: Color): Color = if (bg.luminance() > 0.5f) Color.Black else Color.White

object C {
    private val t get() = Palette.theme
    val bg get() = Color(t.bg)
    val panel get() = Color(t.panel)
    val panel2 get() = Color(t.panel2)
    val line get() = Color(t.line)
    val text get() = Color(t.text)
    val muted get() = Color(t.muted)
    val up get() = if (Palette.colorBlind) Color(0xFF3B8BFF) else Color(t.up)
    val down get() = if (Palette.colorBlind) Color(0xFFFF8C1A) else Color(t.down)
    val accent get() = if (Palette.colorBlind) Color(0xFFB794F6) else Color(t.accent)
    val liq get() = if (Palette.colorBlind) Color(0xFFE879F9) else Color(0xFFFF7A00)
    // Drawdown room (full → light orange), target cyan and the D open line, tuned for contrast per theme.
    val roomFull get() = if (Palette.theme.dark) Color(0xFFFDBA74) else Color(0xFFC2710C)
    val target get() = if (Palette.theme.dark) Color(0xFF22D3EE) else Color(0xFF0E7490)
    val openD get() = if (Palette.theme.dark) Color(0xFFE5E7EB) else Color(0xFF4B5563)
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
    colorScheme = if (Palette.theme.dark) darkColorScheme(
        background = C.bg, surface = C.panel, surfaceVariant = C.panel2, primary = C.accent,
        onPrimary = Color.Black, onBackground = C.text, onSurface = C.text, onSurfaceVariant = C.muted,
        outline = C.line, secondaryContainer = C.panel2, onSecondaryContainer = C.text,
    ) else androidx.compose.material3.lightColorScheme(
        background = C.bg, surface = C.panel, surfaceVariant = C.panel2, primary = C.accent,
        onPrimary = Color.White, onBackground = C.text, onSurface = C.text, onSurfaceVariant = C.muted,
        outline = C.line, secondaryContainer = C.panel2, onSecondaryContainer = C.text,
    ),
    typography = typography,
    content = content,
)

/** Tabular figures so columns of numbers don't jitter as they update. */
val num: TextStyle get() = TextStyle(fontFamily = Mono, fontFeatureSettings = "tnum", fontSize = 13.sp, color = C.text, letterSpacing = (-0.2).sp)

fun pnlColor(v: Double?) = when {
    v == null || v == 0.0 -> C.text
    v > 0 -> C.up
    else -> C.down
}

fun splitTag() = if (Split.pct < 100) " ${Split.pct}%" else ""

fun fmtPrice(p: Double?): String {
    if (p == null) return "—"
    return String.format(Locale.US, "%,.${priceDecimals(p)}f", p)
}

/**
 * A price as its market quotes it: never finer than the tick (oil 70.12, not 70.1200; BTC on a
 * whole-dollar tick 98,765), and a 0.25 tick keeps its two decimals instead of rounding off-tick.
 */
fun fmtPrice(p: Double?, m: com.alex.mfppro.data.Market?): String {
    if (p == null) return "—"
    if (m == null) return fmtPrice(p)
    val d = if (decimalTick(m)) naturalDecimals(m, p) else tickDecimals(m)
    return String.format(Locale.US, "%,.${d}f", p)
}

/** Decimals a price is shown with: about six significant digits. */
fun priceDecimals(p: Double): Int {
    val a = abs(p)
    return when {
        a >= 10_000 -> 1
        a >= 100 -> 2
        a >= 10 -> 4
        // Forex lives here: EUR/USD needs its fifth decimal.
        a >= 0.01 -> 5
        else -> 7
    }
}

/** Dollars with cents; from $10,000 up the cents are noise and are dropped. */
fun fmtUsd(v: Double?, signed: Boolean = false): String {
    if (v == null) return "—"
    val s = String.format(Locale.US, if (abs(v) >= 10_000) "%,.0f" else "%,.2f", abs(v))
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
    // About three significant digits, no trailing zeros: 1K, 1.25K, 12.5K, 523.
    val d = when { abs(x) >= 100 -> 0; abs(x) >= 10 -> 1; else -> 2 }
    var n = String.format(Locale.US, "%.${d}f", x)
    if ('.' in n) n = n.trimEnd('0').trimEnd('.')
    return n + u
}

/** Whole dollars, rounded (not cut): $9,999.99 → $10,000, -0.40 → $0. */
fun fmtUsdWhole(v: Double?): String {
    if (v == null) return "—"
    val r = kotlin.math.round(v)
    return (if (r < 0) "-" else "") + "$" + String.format(Locale.US, "%,.0f", abs(r))
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
