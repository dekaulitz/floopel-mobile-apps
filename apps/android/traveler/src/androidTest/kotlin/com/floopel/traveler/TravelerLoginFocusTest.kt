package com.floopel.traveler

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.floopel.core.designsystem.component.FloopelFormTextField
import com.floopel.core.designsystem.theme.FloopelTheme
import com.floopel.core.model.AppProduct
import com.floopel.feature.traveler.TravelerApp
import com.floopel.feature.traveler.domain.model.TravelerIdentityView
import com.floopel.feature.traveler.testing.TravelerFixture
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TravelerLoginFocusTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    @After fun resumeClock() { ui.mainClock.autoAdvance = true }

    private fun stableLabelAnimation(scale: Float) {
        ui.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, scale)) {
                FloopelTheme(AppProduct.TRAVELER) {
                    var first by remember { mutableStateOf("") }
                    var second by remember { mutableStateOf("") }
                    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        FloopelFormTextField(first, { first = it }, Modifier.fillMaxWidth().testTag("first"),
                            label = { androidx.compose.material3.Text("Email") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(showKeyboardOnFocus = false))
                        FloopelFormTextField(second, { second = it }, Modifier.fillMaxWidth().testTag("second"),
                            label = { androidx.compose.material3.Text("Password") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(showKeyboardOnFocus = false))
                    }
                }
            }
        }
        ui.waitForIdle(); ui.mainClock.autoAdvance = false
        val heights = mutableListOf<Float>()
        val secondTops = mutableListOf<Float>()
        fun sample() {
            val a = ui.onNodeWithTag("first").getUnclippedBoundsInRoot()
            heights += a.bottom.value - a.top.value
            secondTops += ui.onNodeWithTag("second").getUnclippedBoundsInRoot().top.value
        }
        sample()
        for (target in listOf("first", "second", "first", "second")) {
            ui.onNodeWithTag(target).performClick()
            repeat(20) { ui.mainClock.advanceTimeByFrame(); ui.waitForIdle(); sample() }
        }
        android.util.Log.i("FloopelFocus", "scale=$scale heightRange=${heights.max()-heights.min()} nextFieldRange=${secondTops.max()-secondTops.min()}")
        assertTrue("Focus changed field height: $heights", heights.max() - heights.min() <= 1f)
        assertTrue("Focus moved the following field: $secondTops", secondTops.max() - secondTops.min() <= 1f)
    }
    @Test fun blankLabelFocusKeepsFieldAndFollowingRowStable() = stableLabelAnimation(1f)
    @Test fun labelFocusRemainsStableAt130Percent() = stableLabelAnimation(1.3f)
    @Test fun labelFocusRemainsStableAt200Percent() = stableLabelAnimation(2f)

    @Test fun loginFocusTypingAndPasswordVisibilityStayStableWithTheRealKeyboard() {
        val f = TravelerFixture()
        f.identityState.value = TravelerIdentityView(generation = 1)
        ui.runOnUiThread {
            WindowCompat.enableEdgeToEdge(ui.activity.window)
            ui.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        ui.setContent { TravelerApp(f.runtime, {}, backHandler = { enabled, back -> BackHandler(enabled, back) }) }
        ui.waitUntil(5_000) { ui.onAllNodesWithText("Selamat datang").fetchSemanticsNodes().isNotEmpty() }
        val email = ui.onNode(hasSetTextAction() and hasText("Email"))
        val password = ui.onNode(hasSetTextAction() and hasText("Password"))
        email.performClick()
        ui.waitUntil(10_000) { ViewCompat.getRootWindowInsets(ui.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
        ui.waitForIdle()
        val emailTop = email.getUnclippedBoundsInRoot().top.value
        val passwordTop = password.getUnclippedBoundsInRoot().top.value
        for (node in listOf(password, email, password)) {
            node.performClick(); ui.mainClock.advanceTimeBy(500); ui.waitForIdle()
            assertEquals(emailTop, email.getUnclippedBoundsInRoot().top.value, 1f)
            assertEquals(passwordTop, password.getUnclippedBoundsInRoot().top.value, 1f)
        }
        password.performTextInput("synthetic-password")
        ui.onNodeWithContentDescription("Tampilkan kata sandi").performClick()
        ui.mainClock.advanceTimeBy(500); ui.waitForIdle()
        assertEquals(passwordTop, password.getUnclippedBoundsInRoot().top.value, 1f)
        ui.onNodeWithTag("traveler-navigation").assertDoesNotExist()
        assertFalse(f.identityState.value.signedIn)
    }
}
