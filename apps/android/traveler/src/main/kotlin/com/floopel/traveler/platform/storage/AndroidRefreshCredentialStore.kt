package com.floopel.traveler.platform.storage

import com.floopel.feature.traveler.domain.repository.*

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.floopel.feature.traveler.domain.repository.RefreshCredentialStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AndroidKeyStore key never leaves the device; app backups are disabled in the manifest. */
class AndroidRefreshCredentialStore(context: Context, scope: String) : RefreshCredentialStore {
    private val preferences = context.getSharedPreferences("native_session_$scope", Context.MODE_PRIVATE)
    private val alias = "floopel.refresh.$scope.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override fun read(): String? {
        val saved = preferences.getString("refresh", null) ?: return null
        return try {
            val bytes = Base64.decode(saved, Base64.NO_WRAP)
            require(bytes.size > 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
        } catch (_: Exception) { clear(); null }
    }
    override fun write(credential: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(credential.toByteArray(Charsets.UTF_8))
        check(preferences.edit().putString("refresh", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit())
    }
    override fun clear() { check(preferences.edit().remove("refresh").commit()) }
}
