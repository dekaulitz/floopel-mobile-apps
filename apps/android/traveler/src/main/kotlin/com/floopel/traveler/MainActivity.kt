package com.floopel.traveler

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.activity.compose.BackHandler
import androidx.lifecycle.ViewModelProvider
import android.content.Intent
import com.floopel.traveler.platform.location.AndroidQuestLocation
import com.floopel.traveler.platform.auth.AndroidGoogleSignIn
import com.floopel.traveler.platform.media.AndroidTravelerPhotoPicker
import com.floopel.traveler.platform.media.AndroidProfilePhotoCropper
import com.floopel.traveler.platform.media.PhotoWindowStyle
import com.floopel.feature.traveler.TravelerApp
import com.floopel.traveler.platform.storage.TravelerLanguage

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) { super.attachBaseContext(TravelerLanguage.wrap(newBase)) }
    private lateinit var owner: TravelerRuntimeOwner
    private lateinit var location: AndroidQuestLocation
    private var brandedStartup = true
    override fun onCreate(savedInstanceState: Bundle?) {
        // Android 15 can preserve a DecorView with a stale content-insets listener. Set this
        // before decor initialization; after edge-to-edge enforcement the platform call is a no-op.
        if (android.os.Build.VERSION.SDK_INT >= 35) WindowCompat.setDecorFitsSystemWindows(window, false)
        installSplashScreen()
        super.onCreate(savedInstanceState)
        updateSystemBars(branded = true)
        val runtimeScope = if (BuildConfig.DEBUG) intent.getStringExtra(EXTRA_RUNTIME_SCOPE)
            ?.takeIf { Regex("qa-[a-z0-9-]{8,64}").matches(it) } else null
        val startupDelay = if (runtimeScope != null) intent.getLongExtra(EXTRA_STARTUP_DELAY_MILLIS, 0).coerceIn(0, 2_000) else 0
        owner = ViewModelProvider(this, TravelerRuntimeOwner.Factory(applicationContext, runtimeScope, startupDelay))[TravelerRuntimeOwner::class.java]
        location = AndroidQuestLocation(this)
        owner.location.delegate = location
        val photoPicker = AndroidTravelerPhotoPicker(this)
        setContent {
            TravelerApp(owner.runtime, share = { text ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
                }, null))
            }, googleSignIn = if (BuildConfig.GOOGLE_SERVER_CLIENT_ID.isNotBlank()) ({
                AndroidGoogleSignIn(this, BuildConfig.GOOGLE_SERVER_CLIENT_ID).token()
            }) else null, photoPicker = photoPicker::select, startupSurface = ::updateSystemBars,
                profilePhotoPicker = photoPicker::selectProfile, profilePhotoCropper = AndroidProfilePhotoCropper,
                photoWindowStyle = { dark -> PhotoWindowStyle(dark) },
                language = TravelerLanguage.current(this), selectLanguage = { TravelerLanguage.select(this, it) },
                backHandler = { enabled, back -> BackHandler(enabled, back) })
        }
    }
    override fun onDestroy() {
        if (::owner.isInitialized && owner.location.delegate === location) owner.location.delegate = null
        super.onDestroy()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Restore icon contrast after native windows (including the launch window) return focus.
        if (hasFocus) updateSystemBars(brandedStartup)
    }
    @Suppress("DEPRECATION") // API 24/25 cannot display dark navigation icons.
    private fun updateSystemBars(branded: Boolean) {
        brandedStartup = branded
        val dark = branded || (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        // Initializes the decor before setting flags and prevents native color views from
        // consuming the content insets after recreation. It does not add protection layouts.
        WindowCompat.enableEdgeToEdge(window)
        if (android.os.Build.VERSION.SDK_INT < 26) window.navigationBarColor =
            if (branded) getColor(R.color.floopel_startup_background) else android.graphics.Color.BLACK
        WindowCompat.getInsetsController(window, window.decorView).run {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    companion object {
        const val EXTRA_RUNTIME_SCOPE = "com.floopel.traveler.DEBUG_RUNTIME_SCOPE"
        const val EXTRA_STARTUP_DELAY_MILLIS = "com.floopel.traveler.DEBUG_STARTUP_DELAY_MILLIS"
    }
}
