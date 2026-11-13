package com.floopel.traveler.platform.media

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/** Native dialog bars follow the crop surface; no painted status or gesture bar substitutes. */
@Composable
@Suppress("DEPRECATION") // API24/25 support only light navigation icons.
fun PhotoWindowStyle(dark: Boolean) {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window
    SideEffect {
        if (window != null) {
            WindowCompat.enableEdgeToEdge(window)
            if (Build.VERSION.SDK_INT < 26) window.navigationBarColor = android.graphics.Color.BLACK
            if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
            WindowCompat.getInsetsController(window, view).run {
                isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
            }
        }
    }
}
