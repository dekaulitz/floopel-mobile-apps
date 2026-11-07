package com.floopel.traveler.platform.storage

import android.content.Context
import com.floopel.feature.traveler.domain.repository.TravelerIntroProgress
import com.floopel.feature.traveler.domain.repository.TravelerStartupRepository
import kotlinx.coroutines.delay

/** Non-sensitive installation preferences. Logout does not reset this independent store. */
class AndroidTravelerStartupPreferences(context: Context, runtimeScope: String? = null,
    private val readDelayMillis: Long = 0) : TravelerStartupRepository {
    init {
        require(runtimeScope == null || Regex("qa-[a-z0-9-]{8,64}").matches(runtimeScope))
        require(readDelayMillis in 0..2_000 && (readDelayMillis == 0L || runtimeScope != null))
    }
    private val preferences = context.applicationContext.getSharedPreferences(
        if (runtimeScope == null) "traveler_installation" else "traveler_installation_$runtimeScope", Context.MODE_PRIVATE)

    override suspend fun read(): TravelerIntroProgress {
        if (readDelayMillis > 0) delay(readDelayMillis)
        val completed = try { preferences.getBoolean("intro_completed", false) } catch (_: ClassCastException) { false }
        val step = try { preferences.getInt("intro_step", 0).coerceIn(0, 3) } catch (_: ClassCastException) { 0 }
        return TravelerIntroProgress(completed, step)
    }

    override suspend fun saveStep(step: Int) {
        require(step in 0..3)
        check(preferences.edit().putInt("intro_version", 1).putInt("intro_step", step).commit()) { "Installation preferences could not be saved" }
    }

    override suspend fun complete() {
        check(preferences.edit().putInt("intro_version", 1).putInt("intro_step", 3).putBoolean("intro_completed", true).commit()) {
            "Installation preferences could not be saved"
        }
    }
}
