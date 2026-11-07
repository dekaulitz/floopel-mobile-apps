package com.floopel.traveler

import android.content.Intent
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.floopel.traveler.platform.storage.AndroidTravelerStartupPreferences
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicReference

/** Debug-only scopes isolate installation preferences, encrypted sessions and journals. */
internal fun nativeTravelerRule(scope: String, startupDelayMillis: Long = 0):
    AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity> {
    require(scope.matches(Regex("qa-[a-z0-9-]{8,64}")))
    val intent = Intent(InstrumentationRegistry.getInstrumentation().targetContext, MainActivity::class.java)
        .putExtra(MainActivity.EXTRA_RUNTIME_SCOPE, scope)
        .putExtra(MainActivity.EXTRA_STARTUP_DELAY_MILLIS, startupDelayMillis)
    return AndroidComposeTestRule(ActivityScenarioRule<MainActivity>(intent)) { rule ->
        val activity = AtomicReference<MainActivity>()
        rule.scenario.onActivity { activity.set(it) }
        requireNotNull(activity.get())
    }
}

internal fun privateRuntimeScope(): String =
    InstrumentationRegistry.getArguments().getString("floopelRuntimeScope") ?: "qa-private-audit-v1"

internal fun preparePrivateStartup() {
    val scope = privateRuntimeScope()
    require(scope.startsWith("qa-private-"))
    runBlocking { AndroidTravelerStartupPreferences(InstrumentationRegistry.getInstrumentation().targetContext, scope).complete() }
}
