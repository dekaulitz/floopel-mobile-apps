package com.floopel.feature.traveler.presentation.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.floopel.core.designsystem.component.FloopelButton
import com.floopel.core.designsystem.theme.FloopelBrand
import com.floopel.core.designsystem.theme.FloopelShape
import com.floopel.feature.traveler.presentation.*
import com.floopel.feature.traveler.resources.*

@Composable
internal fun TravelerStartupSplash(failure: ReadFailure?, retry: () -> Unit) {
    Box(Modifier.fillMaxSize().background(FloopelBrand.travelerStartup).testTag("startup-splash"), contentAlignment = Alignment.Center) {
        AsyncImage(Res.getUri("files/startup_mark_white.svg"), s(Res.string.app_name),
            Modifier.size(96.dp).testTag("startup-mark"), contentScale = ContentScale.Fit)
        if (failure != null) Surface(Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
            shape = FloopelShape.card) { ErrorPanel(failure, retry) }
    }
}

@Composable
internal fun TravelerIntroScreen(state: TravelerState, model: TravelerViewModel) {
    val step = state.intro?.step ?: return
    val titles = listOf(Res.string.intro_title_1, Res.string.intro_title_2, Res.string.intro_title_3, Res.string.intro_title_4)
    val bodies = listOf(Res.string.intro_body_1, Res.string.intro_body_2, Res.string.intro_body_3, Res.string.intro_body_4)
    val scenes = listOf("intro_meaningful.svg", "intro_stories.svg", "intro_together.svg", "intro_quests.svg")
    val progress = s(Res.string.intro_progress, step + 1, 4)
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).testTag("intro-viewport")) {
        val viewportHeight = constraints.maxHeight
        // Measure natural text/asset height first. Center the introduction in spare space and
        // let the whole page scroll when a small window or larger font needs more room.
        Layout(content = {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                AsyncImage(Res.getUri("files/" + if (step < 2) "intro_mark_early.svg" else "intro_mark_late.svg"), s(Res.string.app_name),
                    Modifier.size(36.dp).testTag("intro-brand"), contentScale = ContentScale.Fit)
            }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                AsyncImage(Res.getUri("files/${scenes[step]}"), null,
                    Modifier.widthIn(max = 280.dp).fillMaxWidth().aspectRatio(280f / 236f).testTag("intro-illustration"), contentScale = ContentScale.Fit)
                Text(s(titles[step]), Modifier.fillMaxWidth().testTag("intro-heading"), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.headlineLarge.copy(lineHeight = 37.8.sp))
                Text(s(bodies[step]), Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.25.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                state.startupFailure?.let { ErrorPanel(it, model::retryStartup) }
                Row(Modifier.fillMaxWidth().semantics { contentDescription = progress }.testTag("intro-progress"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                    repeat(4) { index -> Box(Modifier.size(if (index == step) 24.dp else 8.dp, 8.dp)
                        .background(if (index == step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer, FloopelShape.control)) }
                }
            }
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).testTag("intro-actions"), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (step > 0) FloopelButton(s(Res.string.back), model::previousIntro, Modifier.width(100.dp).fillMaxHeight(),
                    enabled = !state.introSaving, secondary = true)
                FloopelButton(s(if (step == 3) Res.string.intro_continue_login else Res.string.intro_next), model::nextIntro,
                    Modifier.weight(1f).fillMaxHeight(), enabled = !state.introSaving)
            }
        }, modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) { measurables, measureConstraints ->
            val childConstraints = Constraints(minWidth = measureConstraints.maxWidth, maxWidth = measureConstraints.maxWidth,
                minHeight = 0, maxHeight = Constraints.Infinity)
            val children = measurables.map { it.measure(childConstraints) }
            val top = 20.dp.roundToPx(); val bottom = 24.dp.roundToPx(); val gap = 20.dp.roundToPx()
            val naturalHeight = top + bottom + children.sumOf { it.height } + gap * 3
            val height = maxOf(viewportHeight, naturalHeight)
            val spare = height - naturalHeight
            layout(measureConstraints.maxWidth, height) {
                children[0].placeRelative(0, top)
                val introTop = top + children[0].height + gap + spare / 2
                children[1].placeRelative(0, introTop)
                val progressTop = introTop + children[1].height + gap + spare - spare / 2
                children[2].placeRelative(0, progressTop)
                children[3].placeRelative(0, progressTop + children[2].height + gap)
            }
        }
    }
}
