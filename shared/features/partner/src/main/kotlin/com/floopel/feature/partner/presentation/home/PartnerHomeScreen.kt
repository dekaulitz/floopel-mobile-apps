package com.floopel.feature.partner.presentation.home

import androidx.compose.runtime.Composable
import com.floopel.core.designsystem.component.WelcomeScreen

@Composable
internal fun PartnerHomeScreen() {
    WelcomeScreen(
        title = "Floopel Partner",
        description = "Selamat datang. Kelola layanan dan kebutuhan perjalanan pelanggan Anda.",
    )
}
