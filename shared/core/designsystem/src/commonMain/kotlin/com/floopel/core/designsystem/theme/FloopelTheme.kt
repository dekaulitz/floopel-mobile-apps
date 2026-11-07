package com.floopel.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.floopel.core.model.AppProduct

@Composable
fun FloopelTheme(
    product: AppProduct,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val primary = when (product) {
        AppProduct.TRAVELER -> if (darkTheme) Color(0xFFC7C2FF) else Color(0xFF3730A3)
        AppProduct.PARTNER -> if (darkTheme) Color(0xFFB9C3FF) else Color(0xFF4354A3)
    }
    val colorScheme = if (darkTheme) {
        darkColorScheme(primary = primary, onPrimary = Color(0xFF211A66),
            primaryContainer = Color(0xFF332C70), onPrimaryContainer = Color(0xFFE5E1FF),
            surface = Color(0xFF15141D), onSurface = Color(0xFFE8E6F1),
            surfaceContainerLow = Color(0xFF201F2B), onSurfaceVariant = Color(0xFFC7C4D4),
            tertiaryContainer = Color(0xFF503600), onTertiaryContainer = Color(0xFFFFE4A8))
    } else {
        lightColorScheme(primary = primary, onPrimary = Color.White,
            primaryContainer = Color(0xFFEEF0FF), onPrimaryContainer = Color(0xFF3730A3),
            secondaryContainer = Color(0xFFF0F2F7), onSecondaryContainer = Color(0xFF343A4D),
            surface = Color.White, onSurface = Color(0xFF202332),
            surfaceContainerLow = Color(0xFFF7F8FC), onSurfaceVariant = Color(0xFF555B6B),
            outline = Color(0xFF797E95), outlineVariant = Color(0xFFE4E6EF),
            tertiaryContainer = Color(0xFFFFE4A8), onTertiaryContainer = Color(0xFF5B3500))
    }

    MaterialTheme(colorScheme = colorScheme, typography = FloopelTypography, content = content)
}

private val FloopelTypography = Typography(
    headlineLarge = TextStyle(fontSize = 28.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 27.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp),
)
