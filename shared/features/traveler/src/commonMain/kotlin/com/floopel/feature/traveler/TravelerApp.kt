package com.floopel.feature.traveler

import androidx.compose.runtime.Composable
import com.floopel.core.designsystem.theme.FloopelTheme
import com.floopel.core.model.AppProduct
import com.floopel.feature.traveler.presentation.home.TravelerHomeScreen
import com.floopel.feature.traveler.presentation.TravelerRoot
import com.floopel.feature.traveler.domain.model.TravelerImage
import com.floopel.feature.traveler.domain.model.ProfilePhotoSelection
import com.floopel.feature.traveler.domain.repository.ProfilePhotoCropper

@Composable
fun TravelerApp() {
    FloopelTheme(product = AppProduct.TRAVELER) {
        TravelerHomeScreen()
    }
}

/** Native hosts supply their retained runtime and platform effects; UI stays in commonMain. */
@Composable
fun TravelerApp(runtime: TravelerRuntime, share: (String) -> Unit,
    googleSignIn: (suspend () -> String?)? = null,
    photoPicker: (suspend () -> TravelerImage?)? = null,
    startupSurface: (Boolean) -> Unit = {},
    language: String = "id",
    selectLanguage: ((String) -> Unit)? = null,
    profilePhotoPicker: (suspend () -> ProfilePhotoSelection?)? = null,
    profilePhotoCropper: ProfilePhotoCropper? = null,
    photoWindowStyle: @Composable (Boolean) -> Unit = {},
    backHandler: @Composable (Boolean, () -> Unit) -> Unit) {
    FloopelTheme(product = AppProduct.TRAVELER) {
        TravelerRoot(runtime, share, googleSignIn, photoPicker, startupSurface, language, selectLanguage,
            profilePhotoPicker, profilePhotoCropper, photoWindowStyle, backHandler)
    }
}
