package com.floopel.feature.traveler.presentation.home

import androidx.compose.runtime.Composable
import com.floopel.core.designsystem.component.WelcomeScreen

@Composable
internal fun TravelerHomeScreen() {
    WelcomeScreen(
        title = "Floopel Traveler",
        description = "Selamat datang. Mulai rencanakan perjalanan dan pengalaman berikutnya.",
    )
}
