package com.floopel.traveler.platform.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.util.UUID

/** Activity-owned explicit attempt. No silent sign-in, credential persistence or diagnostics. */
class AndroidGoogleSignIn(private val context: Context, private val serverClientId: String) {
    suspend fun token(): String? {
        require(serverClientId.isNotBlank())
        val option = GetSignInWithGoogleOption.Builder(serverClientId).setNonce(UUID.randomUUID().toString()).build()
        return try {
            val credential = CredentialManager.create(context).getCredential(context,
                GetCredentialRequest.Builder().addCredentialOption(option).build()).credential
            require(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
            GoogleIdTokenCredential.createFrom(credential.data).idToken
        } catch (_: GetCredentialCancellationException) { null }
        catch (_: NoCredentialException) { throw IllegalStateException("Google sign-in unavailable") }
    }
}
