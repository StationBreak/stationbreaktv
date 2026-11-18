// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import tv.stationbreak.core.ui.LocalScreenFit
import tv.stationbreak.core.ui.ScreenLayout
import tv.stationbreak.core.ui.screenLift
import tv.stationbreak.core.ui.stan.StanHost
import tv.stationbreak.core.ui.stan.StanMood
import tv.stationbreak.core.ui.stan.StanRenderer
import tv.stationbreak.core.ui.stan.rememberStanRenderer

/** How long with no key before Stan dozes off (spec 2026-11-18, "Dozing"). */
internal const val DOZE_AFTER_MS = 60_000L

/**
 * Below this height Stan is left out: the docked keyboard or a short window has squeezed the screen, and he must never
 * crowd the form (Review Focus 1).
 */
internal val MIN_HOST_HEIGHT = 400.dp

/** Stan's column: the host's 320 dp and a gap, so a focused, scaled control at the form's edge still clears him. */
private val HOST_COLUMN = 340.dp

/** Test tags for the scaffold. [HOST] is on a wrapper outside Stan's cleared node: Stan himself has no semantics. */
internal object SetupScaffoldTags {
    const val HOST = "setupScaffold.host"
}

/**
 * Counts down to Stan's doze: [dozing] turns true [afterMillis] after the last [poke], while [run] is running and
 * enabled. A poke wakes him at once.
 */
internal class DozeTimer(
    private val afterMillis: Long = DOZE_AFTER_MS,
) {
    private val keys = MutableStateFlow(0L)
    private val asleep = MutableStateFlow(false)
    val dozing: StateFlow<Boolean> = asleep.asStateFlow()

    /** A key was pressed. */
    fun poke() {
        asleep.value = false
        keys.update { it + 1 }
    }

    /** Counts while [enabled]; disabled, he stays awake (a dialog has the keys, so no key here means nothing). */
    suspend fun run(enabled: Boolean) {
        asleep.value = false
        if (!enabled) return
        keys.collectLatest {
            asleep.value = false
            delay(afterMillis)
            asleep.value = true
        }
    }
}

/**
 * A setup screen with Stan as its host: the screen-lift ground, [content] on the left, and Stan's column, always
 * reserved, in the bottom-right corner inside the safe area, so the two never overlap and the content never moves when
 * Stan comes or goes.
 *
 * Layout contract: [content] gets the whole-screen safe area as [PaddingValues], with no end padding (Stan's column
 * keeps its own inset). A screen applies it inside its scroll area, replacing the top with its own offset if it has
 * one ([withTop]). It must not measure the safe area on its own box, which is narrower than the screen.
 *
 * [cue] null leaves the column empty. Below [MIN_HOST_HEIGHT] Stan is left out. After [DOZE_AFTER_MS] with no key he
 * dozes; any key, or a new [cue], wakes him, and the key still reaches the content. [covered] is true while a dialog
 * window is over the screen: its keys never pass through here, so the timer stops, and Stan, unseen, is not drawn.
 *
 * [fullWidth] (only with no [cue]) lets [content] span the whole screen, with the full safe area, so a plain line is
 * centred on the screen; the column stays reserved, and [content] keeps its place in the tree, so nothing restarts
 * when it narrows again. [renderer] defaults to one per screen; the app may hoist one across the setup destinations
 * later.
 */
@Composable
internal fun SetupScaffold(
    cue: StanCue?,
    modifier: Modifier = Modifier,
    covered: Boolean = false,
    fullWidth: Boolean = false,
    renderer: StanRenderer = rememberStanRenderer(),
    content: @Composable (PaddingValues) -> Unit,
) {
    val timer = remember { DozeTimer() }
    LaunchedEffect(timer, covered, cue) { timer.run(enabled = !covered) }
    val dozing by timer.dozing.collectAsState()
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .screenLift()
            .onPreviewKeyEvent {
                timer.poke()
                false
            },
    ) {
        val screen = DpSize(maxWidth, maxHeight)
        val fit = LocalScreenFit.current
        val safe = ScreenLayout.safeArea(screen, fit)
        val spanning = fullWidth && cue == null
        val contentPadding = safeAreaPadding(screen, fit, top = safe.top, end = spanning)
        val endInset = screen.width - safe.right
        val bottomInset = screen.height - safe.bottom
        val column = HOST_COLUMN + endInset
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().padding(end = if (spanning) 0.dp else column)) { content(contentPadding) }
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .width(column)
                    .fillMaxHeight()
                    .padding(end = endInset, bottom = bottomInset),
                contentAlignment = Alignment.BottomEnd,
            ) {
                if (cue != null && !covered && screen.height >= MIN_HOST_HEIGHT) {
                    Box(Modifier.testTag(SetupScaffoldTags.HOST)) {
                        StanHost(
                            mood = if (dozing) StanMood.Dozing else cue.mood,
                            line = cue.line?.let { stringResource(it) },
                            renderer = renderer,
                        )
                    }
                }
            }
        }
    }
}
