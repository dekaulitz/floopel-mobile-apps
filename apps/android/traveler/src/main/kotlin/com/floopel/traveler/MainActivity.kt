package com.floopel.traveler

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.lifecycle.ViewModelProvider
import android.content.Intent
import com.floopel.traveler.platform.location.AndroidQuestLocation
import com.floopel.traveler.platform.auth.AndroidGoogleSignIn
import com.floopel.traveler.platform.media.AndroidTravelerPhotoPicker
import com.floopel.feature.traveler.TravelerApp

class MainActivity : ComponentActivity() {
    private lateinit var owner: TravelerRuntimeOwner
    private lateinit var location: AndroidQuestLocation
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        owner = ViewModelProvider(this, TravelerRuntimeOwner.Factory(applicationContext))[TravelerRuntimeOwner::class.java]
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
            }) else null, photoPicker = photoPicker::select, backHandler = { enabled, back -> BackHandler(enabled, back) })
        }
    }
    override fun onDestroy() {
        if (::owner.isInitialized && owner.location.delegate === location) owner.location.delegate = null
        super.onDestroy()
    }
}
