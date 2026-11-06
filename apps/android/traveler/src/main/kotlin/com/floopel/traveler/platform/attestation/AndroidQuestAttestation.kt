package com.floopel.traveler.platform.attestation

import com.floopel.feature.traveler.domain.repository.*

import android.content.Context
import android.util.Base64
import com.floopel.feature.traveler.domain.repository.QuestAttestation
import com.google.android.gms.tasks.Task
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AndroidQuestAttestation(context: Context, private val cloudProject: Long) : QuestAttestation {
    override val available get() = cloudProject > 0
    private val manager = IntegrityManagerFactory.createStandard(context.applicationContext)
    private val guard = Mutex()
    private var provider: StandardIntegrityManager.StandardIntegrityTokenProvider? = null

    override suspend fun androidIntegrityToken(challenge: String): String = guard.withLock {
        check(available)
        try {
            withTimeout(60_000) {
                val current = provider ?: manager.prepareIntegrityToken(
                    StandardIntegrityManager.PrepareIntegrityTokenRequest.builder().setCloudProjectNumber(cloudProject).build()
                ).awaitResult().also { provider = it }
                val digest = MessageDigest.getInstance("SHA-256").digest(challenge.toByteArray(Charsets.UTF_8))
                val hash = Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                current.request(StandardIntegrityManager.StandardIntegrityTokenRequest.builder().setRequestHash(hash).build()).awaitResult().token()
            }
        } catch (cause: Exception) {
            provider = null
            throw cause
        }
    }
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
    addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
