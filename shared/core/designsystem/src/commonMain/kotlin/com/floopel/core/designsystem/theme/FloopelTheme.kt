package com.floopel.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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
        AppProduct.TRAVELER -> if (darkTheme) Color(0xFF8AD5C8) else Color(0xFF006B5E)
        AppProduct.PARTNER -> if (darkTheme) Color(0xFFB9C3FF) else Color(0xFF4354A3)
    }
    val colorScheme = if (darkTheme) {
        darkColorScheme(primary = primary, onPrimary = Color(0xFF10201C))
    } else {
        lightColorScheme(primary = primary, onPrimary = Color.White)
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}
