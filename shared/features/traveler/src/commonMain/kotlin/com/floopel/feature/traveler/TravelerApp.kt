package com.floopel.feature.traveler

import androidx.compose.runtime.Composable
import com.floopel.core.designsystem.theme.FloopelTheme
import com.floopel.core.model.AppProduct
import com.floopel.feature.traveler.presentation.home.TravelerHomeScreen

@Composable
fun TravelerApp() {
    FloopelTheme(product = AppProduct.TRAVELER) {
        TravelerHomeScreen()
    }
}
