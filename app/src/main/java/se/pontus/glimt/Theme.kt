package se.pontus.glimt

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

// ---- Färger ----
internal val Bg = Color(0xFF090B10)
internal val SideBg = Color(0xFF0E1118)
internal val Surface1 = Color(0xFF141924)
internal val CardBg = Color(0xFF1B2130)
internal val Accent = Color(0xFFFFB648) // "glimt" – varmt guld
internal val AccentDeep = Color(0xFFFF8A3D)
internal val OnAccent = Color(0xFF1C1205)
internal val Muted = Color(0xFF8E97A9)
internal val TextBright = Color(0xFFEEF1F6)
internal val Good = Color(0xFF4ADE80)
internal val Bad = Color(0xFFFF6B7A)

internal val AccentBrush = Brush.horizontalGradient(listOf(Accent, AccentDeep))

/** Outfit – en modern geometrisk sans-serif (SIL Open Font License). */
internal val Outfit = FontFamily(
    Font(R.font.outfit_regular, FontWeight.Normal),
    Font(R.font.outfit_medium, FontWeight.Medium),
    Font(R.font.outfit_semibold, FontWeight.SemiBold),
    Font(R.font.outfit_bold, FontWeight.Bold),
)

private fun Typography.withFont(f: FontFamily): Typography {
    fun TextStyle.f() = copy(fontFamily = f)
    return copy(
        displayLarge = displayLarge.f(), displayMedium = displayMedium.f(), displaySmall = displaySmall.f(),
        headlineLarge = headlineLarge.f(), headlineMedium = headlineMedium.f(), headlineSmall = headlineSmall.f(),
        titleLarge = titleLarge.f(), titleMedium = titleMedium.f(), titleSmall = titleSmall.f(),
        bodyLarge = bodyLarge.f(), bodyMedium = bodyMedium.f(), bodySmall = bodySmall.f(),
        labelLarge = labelLarge.f(), labelMedium = labelMedium.f(), labelSmall = labelSmall.f(),
    )
}

@Composable
internal fun GlimtTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = OnAccent,
            background = Bg,
            surface = Surface1,
            onSurface = TextBright,
        ),
        typography = Typography().withFont(Outfit),
        content = content,
    )
}
