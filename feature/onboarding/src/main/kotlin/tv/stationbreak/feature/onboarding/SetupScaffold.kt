// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
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

/** How long with no key before Stan dozes off (spec 2026-11-18, "Dozing"). */
internal const val DOZE_AFTER_MS = 60_000L

/**
 * Below this height Stan is left out: the docked keyboard or a short window has squeezed the screen, and he must never
 * crowd the form (Review Focus 1).
 */
internal val MIN_HOST_HEIGHT = 400.dp

/** Stan's column: the host's 320 dp and a gap, so a focused, scaled control at the form's edge still clears him. */
private val HOST_COLUMN = 340.dp

/**
 * What Stan does on a setup screen: his [mood], and his [line] in the bubble (null: no bubble). The line is decoration;
 * the screen's own text says everything the viewer needs (spec 2026-11-18, "Anti-goals").
 */
internal data class StanCue(
    val mood: StanMood,
    @param:StringRes val line: Int?,
)

/** Test tags for the scaffold. [HOST] is on a wrapper outside Stan's cleared node: Stan himself has no semantics. */
internal object SetupScaffoldTags {
    const val CONTENT = "setupScaffold.content"
    const val HOST = "setupScaffold.host"
}

/**
 * The screen a [SetupScaffold] stands in, for [safeAreaPadding]: the safe area is measured on the whole [screen], not
 * on the narrower box the content gets. [besideStan] is true when Stan's column takes the end side, so the content
 * needs no safe-area inset there.
 */
internal data class SetupFrame(
    val screen: DpSize,
    val besideStan: Boolean,
)

internal val LocalSetupFrame = compositionLocalOf<SetupFrame?> { null }

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
 * A setup screen with Stan as its host: the screen-lift ground, [content] on the left, and Stan in a reserved column
 * in the bottom-right corner, inside the safe area, so the two never overlap. Each [content] keeps its own scroll and
 * safe-area padding ([safeAreaPadding] reads the whole screen from here).
 *
 * [cue] null shows no Stan and gives the content the whole width. Below [MIN_HOST_HEIGHT] Stan is left out, but his
 * column stays reserved so the form does not reflow while the keyboard is up. After [DOZE_AFTER_MS] with no key he
 * dozes; any key wakes him and still reaches the content. Pass [dozeEnabled] false while a dialog window is up: its
 * keys never pass through here.
 *
 * Stan uses [StanHost]'s own renderer, one per screen: hoisting one across the setup destinations needs app wiring.
 */
@Composable
internal fun SetupScaffold(
    cue: StanCue?,
    modifier: Modifier = Modifier,
    dozeEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val timer = remember { DozeTimer() }
    LaunchedEffect(timer, dozeEnabled) { timer.run(dozeEnabled) }
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
        val safe = ScreenLayout.safeArea(screen, LocalScreenFit.current)
        val endInset = screen.width - safe.right
        val bottomInset = screen.height - safe.bottom
        val frame = SetupFrame(screen, besideStan = cue != null)
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight().testTag(SetupScaffoldTags.CONTENT)) {
                CompositionLocalProvider(LocalSetupFrame provides frame, content = content)
            }
            if (cue != null) {
                Box(
                    Modifier
                        .width(HOST_COLUMN + endInset)
                        .fillMaxHeight()
                        .padding(end = endInset, bottom = bottomInset),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    if (screen.height >= MIN_HOST_HEIGHT) {
                        Box(Modifier.testTag(SetupScaffoldTags.HOST)) {
                            StanHost(
                                mood = if (dozing) StanMood.Dozing else cue.mood,
                                line = cue.line?.let { stringResource(it) },
                            )
                        }
                    }
                }
            }
        }
    }
}
