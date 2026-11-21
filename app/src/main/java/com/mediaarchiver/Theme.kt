package com.mediaarchiver

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------- Brand colors: dark (near-black, OLED friendly) ----------
private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF5A6E),
    onPrimary = Color(0xFF2B0008),
    primaryContainer = Color(0xFF5C1020),
    onPrimaryContainer = Color(0xFFFFD9DD),
    secondary = Color(0xFF3DDBC8),
    onSecondary = Color(0xFF00201C),
    secondaryContainer = Color(0xFF1B3A36),
    onSecondaryContainer = Color(0xFFB8F3EA),
    tertiary = Color(0xFFFFC857),
    onTertiary = Color(0xFF3A2A00),
    background = Color(0xFF0B0C10),
    onBackground = Color(0xFFECEDF2),
    surface = Color(0xFF111319),
    onSurface = Color(0xFFECEDF2),
    surfaceVariant = Color(0xFF232733),
    onSurfaceVariant = Color(0xFFC3C7D4),
    outline = Color(0xFF8A90A2),
    outlineVariant = Color(0xFF3A3F4E),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF3A0000),
    errorContainer = Color(0xFF5C1410),
    onErrorContainer = Color(0xFFFFDAD6),
    surfaceContainerLowest = Color(0xFF080A0D),
    surfaceContainerLow = Color(0xFF0F1116),
    surfaceContainer = Color(0xFF151820),
    surfaceContainerHigh = Color(0xFF1B1F29),
    surfaceContainerHighest = Color(0xFF232733)
)

// ---------- Brand colors: light ----------
private val LightColors = lightColorScheme(
    primary = Color(0xFFC4162A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDADB),
    onPrimaryContainer = Color(0xFF410006),
    secondary = Color(0xFF00695C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFBFF2E9),
    onSecondaryContainer = Color(0xFF00201B),
    tertiary = Color(0xFF7A5900),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFAF8F9),
    onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFFAF8F9),
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFE6E1E4),
    onSurfaceVariant = Color(0xFF49454C),
    outline = Color(0xFF7A757D),
    outlineVariant = Color(0xFFCBC5CC),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F2F4),
    surfaceContainer = Color(0xFFF0EDEF),
    surfaceContainerHigh = Color(0xFFEAE7E9),
    surfaceContainerHighest = Color(0xFFE4E1E3)
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp)
)

// ---------- Typography (Tajawal: Arabic + Latin) ----------

/**
 * Loads Tajawal from assets/fonts (downloaded by the GitHub workflow).
 * If the files are missing, the normal system font is used instead,
 * so the app never crashes because of a font.
 */
private fun loadFontFamily(context: Context): FontFamily {
    return try {
        val assets = context.assets
        val available = assets.list("fonts")?.toSet() ?: emptySet()
        val wanted = listOf(
            "Tajawal-Regular.ttf" to FontWeight.Normal,
            "Tajawal-Medium.ttf" to FontWeight.Medium,
            "Tajawal-Bold.ttf" to FontWeight.Bold
        )
        val fonts = wanted
            .filter { it.first in available }
            .map { item -> Font(assetManager = assets, path = "fonts/" + item.first, weight = item.second) }
        if (fonts.isEmpty()) FontFamily.Default else FontFamily(fonts)
    } catch (e: Exception) {
        FontFamily.Default
    }
}

// letterSpacing = 0 keeps Arabic letters properly connected.
private fun TextStyle.brand(family: FontFamily, weight: FontWeight? = null): TextStyle =
    copy(fontFamily = family, letterSpacing = 0.sp, fontWeight = weight ?: fontWeight)

private fun buildTypography(family: FontFamily): Typography {
    val d = Typography()
    return Typography(
        displayLarge = d.displayLarge.brand(family, FontWeight.Bold),
        displayMedium = d.displayMedium.brand(family, FontWeight.Bold),
        displaySmall = d.displaySmall.brand(family, FontWeight.Bold),
        headlineLarge = d.headlineLarge.brand(family, FontWeight.Bold),
        headlineMedium = d.headlineMedium.brand(family, FontWeight.Bold),
        headlineSmall = d.headlineSmall.brand(family, FontWeight.Bold),
        titleLarge = d.titleLarge.brand(family, FontWeight.Bold),
        titleMedium = d.titleMedium.brand(family, FontWeight.Medium),
        titleSmall = d.titleSmall.brand(family, FontWeight.Medium),
        bodyLarge = d.bodyLarge.brand(family),
        bodyMedium = d.bodyMedium.brand(family),
        bodySmall = d.bodySmall.brand(family),
        labelLarge = d.labelLarge.brand(family, FontWeight.Medium),
        labelMedium = d.labelMedium.brand(family, FontWeight.Medium),
        labelSmall = d.labelSmall.brand(family, FontWeight.Medium)
    )
}

@Composable
fun ContentSlayerTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val typography = remember(context) { buildTypography(loadFontFamily(context)) }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = typography,
            shapes = AppShapes,
            content = content
        )
    }
}
