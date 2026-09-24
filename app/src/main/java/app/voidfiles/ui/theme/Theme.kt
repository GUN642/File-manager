package app.voidfiles.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import app.voidfiles.R
import app.voidfiles.data.Accent
import app.voidfiles.data.ThemeMode

@Immutable
data class VoidColors(
    val background: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val text: Color,
    val textMuted: Color,
    val divider: Color,
    val accent: Color,
    val onAccent: Color,
    val selection: Color,
    val dot: Color,
    val isDark: Boolean,
)

@OptIn(ExperimentalTextApi::class)
val DotFont = FontFamily(
    Font(
        R.font.doto,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(800), FontVariation.Setting("ROND", 100f)),
    ),
    Font(
        R.font.doto,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(500), FontVariation.Setting("ROND", 100f)),
    ),
)

val MonoFont = FontFamily(Font(R.font.space_mono, FontWeight.Normal))

val LocalVoidColors = staticCompositionLocalOf {
    VoidColors(
        Color.Black, Color(0xFF111111), Color(0xFF1C1C1C), Color.White, Color(0xFF8A8A8A),
        Color(0xFF262626), Color(0xFFD71921), Color.White, Color(0x33D71921), Color(0xFF2A2A2A), true,
    )
}
val LocalDotHeadings = staticCompositionLocalOf { true }
val LocalDotGrid = staticCompositionLocalOf { true }

object VoidTheme {
    val colors: VoidColors @Composable get() = LocalVoidColors.current
    val dotHeadings: Boolean @Composable get() = LocalDotHeadings.current
    val dotGrid: Boolean @Composable get() = LocalDotGrid.current
}

private fun palette(mode: ThemeMode, systemDark: Boolean): Triple<Color, Color, Color> = when (mode) {
    ThemeMode.BLACK -> Triple(Color(0xFF000000), Color(0xFF0E0E0E), Color(0xFF1A1A1A))
    ThemeMode.GRAPHITE -> Triple(Color(0xFF1C1C1E), Color(0xFF242427), Color(0xFF2E2E32))
    ThemeMode.STEEL -> Triple(Color(0xFF12161B), Color(0xFF1A2027), Color(0xFF232B34))
    ThemeMode.PAPER -> Triple(Color(0xFFEFEEEA), Color(0xFFF7F6F2), Color(0xFFE4E3DE))
    ThemeMode.WHITE -> Triple(Color(0xFFFFFFFF), Color(0xFFF4F4F4), Color(0xFFE9E9E9))
    ThemeMode.SYSTEM, ThemeMode.DYNAMIC ->
        if (systemDark) palette(ThemeMode.BLACK, true) else palette(ThemeMode.PAPER, false)
}

@Composable
fun VoidFilesTheme(
    mode: ThemeMode,
    accent: Accent,
    dotHeadings: Boolean,
    dotGrid: Boolean,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val context = LocalContext.current
    val dynamic = mode == ThemeMode.DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val colors: VoidColors
    val scheme: ColorScheme
    if (dynamic) {
        scheme = if (systemDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        colors = VoidColors(
            background = scheme.background,
            surface = scheme.surfaceContainerLow,
            surfaceHigh = scheme.surfaceContainerHigh,
            text = scheme.onBackground,
            textMuted = scheme.onSurfaceVariant,
            divider = scheme.outlineVariant,
            accent = scheme.primary,
            onAccent = scheme.onPrimary,
            selection = scheme.primary.copy(alpha = 0.18f),
            dot = scheme.outlineVariant,
            isDark = systemDark,
        )
    } else {
        val (bg, surface, high) = palette(mode, systemDark)
        val dark = bg.luminance() < 0.5f
        var accentColor = Color(accent.argb)
        if (accent == Accent.MONO && !dark) accentColor = Color(0xFF111111)
        val onAccent = if (accentColor.luminance() > 0.55f) Color.Black else Color.White
        val text = if (dark) Color(0xFFF2F2F2) else Color(0xFF111111)
        val muted = if (dark) Color(0xFF8C8C8C) else Color(0xFF6B6B6B)
        val divider = if (dark) Color.White.copy(alpha = 0.09f) else Color.Black.copy(alpha = 0.09f)
        colors = VoidColors(
            background = bg, surface = surface, surfaceHigh = high, text = text, textMuted = muted,
            divider = divider, accent = accentColor, onAccent = onAccent,
            selection = accentColor.copy(alpha = if (dark) 0.20f else 0.14f),
            dot = if (dark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.10f),
            isDark = dark,
        )
        scheme = if (dark) {
            darkColorScheme(
                primary = accentColor, onPrimary = onAccent, background = bg, onBackground = text,
                surface = surface, onSurface = text, surfaceVariant = high, onSurfaceVariant = muted,
                surfaceContainer = surface, surfaceContainerHigh = high, surfaceContainerHighest = high,
                surfaceContainerLow = surface, surfaceContainerLowest = bg,
                outline = muted, outlineVariant = divider, secondary = text, onSecondary = bg,
                secondaryContainer = high, onSecondaryContainer = text, error = Color(0xFFFF453A),
            )
        } else {
            lightColorScheme(
                primary = accentColor, onPrimary = onAccent, background = bg, onBackground = text,
                surface = surface, onSurface = text, surfaceVariant = high, onSurfaceVariant = muted,
                surfaceContainer = surface, surfaceContainerHigh = high, surfaceContainerHighest = high,
                surfaceContainerLow = surface, surfaceContainerLowest = bg,
                outline = muted, outlineVariant = divider, secondary = text, onSecondary = bg,
                secondaryContainer = high, onSecondaryContainer = text, error = Color(0xFFD70015),
            )
        }
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !colors.isDark
            controller.isAppearanceLightNavigationBars = !colors.isDark
        }
    }

    val base = Typography()
    val typography = Typography(
        displayLarge = base.displayLarge.copy(fontFamily = if (dotHeadings) DotFont else FontFamily.Default),
        headlineMedium = base.headlineMedium.copy(fontFamily = if (dotHeadings) DotFont else FontFamily.Default),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Medium),
        titleMedium = base.titleMedium,
        bodyLarge = base.bodyLarge,
        bodyMedium = base.bodyMedium,
        labelSmall = TextStyle(fontFamily = MonoFont, fontSize = 11.sp, letterSpacing = 0.6.sp),
        labelMedium = TextStyle(fontFamily = MonoFont, fontSize = 12.sp, letterSpacing = 0.8.sp),
        labelLarge = base.labelLarge.copy(fontFamily = MonoFont, letterSpacing = 1.sp),
    )

    CompositionLocalProvider(
        LocalVoidColors provides colors,
        LocalDotHeadings provides dotHeadings,
        LocalDotGrid provides dotGrid,
    ) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}

/** Heading style: dot-matrix when enabled, otherwise a clean grotesk. */
@Composable
fun headingStyle(size: Int): TextStyle = if (VoidTheme.dotHeadings) {
    TextStyle(fontFamily = DotFont, fontWeight = FontWeight.Bold, fontSize = size.sp, letterSpacing = 1.sp)
} else {
    TextStyle(fontWeight = FontWeight.SemiBold, fontSize = (size * 0.85f).sp, letterSpacing = 0.sp)
}
