package com.floopel.feature.partner

import androidx.compose.runtime.Composable
import com.floopel.core.designsystem.theme.FloopelTheme
import com.floopel.core.model.AppProduct
import com.floopel.feature.partner.presentation.home.PartnerHomeScreen

@Composable
fun PartnerApp() {
    FloopelTheme(product = AppProduct.PARTNER) {
        PartnerHomeScreen()
    }
}
