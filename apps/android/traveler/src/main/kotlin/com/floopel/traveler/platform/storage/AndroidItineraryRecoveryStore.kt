package com.floopel.traveler.platform.storage

import com.floopel.feature.traveler.domain.repository.*

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.floopel.feature.traveler.domain.repository.ItineraryRecoveryStore
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypted intent metadata only; never stores tokens, responses or an offline queue. */
class AndroidItineraryRecoveryStore(context: Context, private val storageName: String = "itinerary_recovery") : ItineraryRecoveryStore {
    private val preferences = context.getSharedPreferences(storageName, Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = if (storageName == "itinerary_recovery") "floopel.itinerary.recovery.v1" else "floopel.$storageName.recovery.v1"
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override fun read(): String? {
        val encrypted = preferences.getString("intent", null) ?: return null
        val bytes = Base64.decode(encrypted, Base64.NO_WRAP)
        require(bytes.size > 12)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
        }
    }
    override fun write(record: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(record.toByteArray(Charsets.UTF_8))
        check(preferences.edit().putString("intent", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit())
    }
    override fun clear() { check(preferences.edit().remove("intent").commit()) }
}

/** UUIDv7 timestamp plus cryptographic random bits; backend time validation remains authoritative. */
fun newItineraryOperationKey(nowMillis: Long = System.currentTimeMillis()): String {
    val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
    for (index in 0..5) bytes[index] = (nowMillis ushr ((5 - index) * 8)).toByte()
    bytes[6] = ((bytes[6].toInt() and 15) or 112).toByte()
    bytes[8] = ((bytes[8].toInt() and 63) or 128).toByte()
    val hex = bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    return "${hex.take(8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
}
