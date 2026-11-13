package com.floopel.traveler

import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.floopel.traveler.platform.storage.AndroidRefreshCredentialStore
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.File
import java.security.MessageDigest

/** Real local API 401; an isolated encrypted invalid credential is never a signed-in fallback. */
@RunWith(AndroidJUnit4::class)
class TravelerInvalidSessionStartupTest {
    private val ui = nativeTravelerRule(privateRuntimeScope(), startupDelayMillis = 600)
    private fun credentialStore(): AndroidRefreshCredentialStore {
        val scope = MessageDigest.getInstance("SHA-256").digest((BuildConfig.API_ORIGIN + "|" + privateRuntimeScope()).toByteArray())
            .take(8).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        return AndroidRefreshCredentialStore(InstrumentationRegistry.getInstrumentation().targetContext, scope)
    }
    @get:Rule val rule: TestRule = RuleChain.outerRule(TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            val args = InstrumentationRegistry.getArguments()
            assumeTrue(args.getString("floopelPrivateLive") == "true" && args.getString("floopelInvalidRestoreCheck") == "true")
            require(privateRuntimeScope().startsWith("qa-private-"))
            preparePrivateStartup()
            check(credentialStore().read() == null) { "Use a fresh isolated scope" }
            credentialStore().write("expired-local-fixture-" + privateRuntimeScope())
            base.evaluate()
        }
    } }).around(ui)

    @Test fun invalidRefreshReturnsToLoginAndClearsOnlyItsScopedCredential() {
        ui.waitUntil(15_000) { ui.onAllNodesWithText("Selamat datang").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("traveler-navigation").assertDoesNotExist()
        ui.onNodeWithTag("intro-heading").assertDoesNotExist()
        ui.onNodeWithContentDescription("Kembali").assertDoesNotExist()
        assertNull(credentialStore().read())
        val output = File(ui.activity.getExternalFilesDir(null), "audit/live-invalid-restore.json"); output.parentFile?.mkdirs()
        output.writeText(org.json.JSONObject().apply {
            put("scope", privateRuntimeScope()); put("login_visible", true); put("refresh_cleared", true)
            put("protected_navigation_visible", false); put("real_local_api", true)
        }.toString(2))
    }
}
