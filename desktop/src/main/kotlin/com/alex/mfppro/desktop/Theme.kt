package com.alex.mfppro.desktop

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.sp

/** The phone app's Standard theme colours. */
object D {
    val bg = Color(0xFF0B0E11)
    val panel = Color(0xFF15191E)
    val panel2 = Color(0xFF1E2329)
    val line = Color(0xFF2B3139)
    val text = Color(0xFFEAECEF)
    val muted = Color(0xFF848E9C)
    val up = Color(0xFF20B26C)
    val down = Color(0xFFEF454A)
    val accent = Color(0xFFF7A600)
    val upDim = Color(0x3320B26C)
    val downDim = Color(0x33EF454A)
}

// The same font files as the Android app (desktop/build.gradle.kts puts app/src/main/res/font on the classpath).
val Inter = FontFamily(
    Font("inter_400.ttf", FontWeight.Normal),
    Font("inter_500.ttf", FontWeight.Medium),
    Font("inter_600.ttf", FontWeight.SemiBold),
    Font("inter_700.ttf", FontWeight.Bold),
)
val Mono = FontFamily(
    Font("jbmono_400.ttf", FontWeight.Normal),
    Font("jbmono_500.ttf", FontWeight.Medium),
    Font("jbmono_700.ttf", FontWeight.Bold),
)

/** Tabular figures so columns of numbers don't jitter as they tick. */
val num = TextStyle(fontFamily = Mono, fontFeatureSettings = "tnum", fontSize = 12.sp, color = D.text)
val label = TextStyle(fontFamily = Inter, fontSize = 11.sp, color = D.muted, fontWeight = FontWeight.Medium)

fun pnlColor(v: Double?) = when {
    v == null || v == 0.0 -> D.text
    v > 0 -> D.up
    else -> D.down
}

@Composable
fun DesktopTheme(content: @Composable () -> Unit) {
    val base = TextStyle(fontFamily = Inter)
    val t = Typography()
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = D.bg, surface = D.panel, surfaceVariant = D.panel2, primary = D.accent,
            onPrimary = Color.Black, onBackground = D.text, onSurface = D.text, onSurfaceVariant = D.muted, outline = D.line,
        ),
        typography = Typography(
            bodyLarge = t.bodyLarge.merge(base), bodyMedium = t.bodyMedium.merge(base), bodySmall = t.bodySmall.merge(base),
            labelLarge = t.labelLarge.merge(base), labelMedium = t.labelMedium.merge(base), labelSmall = t.labelSmall.merge(base),
            titleLarge = t.titleLarge.merge(base), titleMedium = t.titleMedium.merge(base), titleSmall = t.titleSmall.merge(base),
        ),
    ) {
        CompositionLocalProvider(LocalContentColor provides D.text, content = content)
    }
}
