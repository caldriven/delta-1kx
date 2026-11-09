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
