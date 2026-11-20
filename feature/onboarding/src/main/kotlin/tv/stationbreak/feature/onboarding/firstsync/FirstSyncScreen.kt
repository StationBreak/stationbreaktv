// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.firstsync

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.ui.screenBody
import tv.stationbreak.core.ui.screenDetail
import tv.stationbreak.core.ui.stan.StanOpeningKey
import tv.stationbreak.core.ui.stan.stanOpeningKey
import tv.stationbreak.feature.onboarding.R
import tv.stationbreak.feature.onboarding.SetupColumn
import tv.stationbreak.feature.onboarding.SetupScaffold
import tv.stationbreak.feature.onboarding.server.ErrorCopy
import tv.stationbreak.feature.onboarding.server.ErrorCopyTable
import tv.stationbreak.feature.onboarding.server.Gap
import tv.stationbreak.feature.onboarding.server.ProblemIcon
import tv.stationbreak.feature.onboarding.server.ServerButton
import tv.stationbreak.feature.onboarding.server.TightGap
import tv.stationbreak.feature.onboarding.server.detailsText
import tv.stationbreak.feature.onboarding.server.messageText
import tv.stationbreak.feature.onboarding.server.politeLiveRegion
import java.text.NumberFormat

private val BarHeight = 12.dp
private const val QUARTERS = 4
private const val PERCENT_PER_QUARTER = 25
private const val TOP_OFFSET = 0.1f

/**
 * The first-sync progress screen (spec 06, Unit 3), shown from the end of onboarding until the first sync of the
 * signed-in account ends and its first lineup is built. The first sync is queued here if it is not already, and the app
 * moves on by itself when it ends, with no button. Back is the platform's: the navigation graph puts nothing beneath
 * this screen, so Back leaves the app and the sync carries on in the background. Only during the cheer at the end does
 * Back skip to live TV.
 */
@Composable
fun FirstSyncDestination(
    onFinished: () -> Unit,
    onSessionLost: () -> Unit,
) {
    val viewModel: FirstSyncViewModel = hiltViewModel()
    FirstSyncRoot(viewModel, onFinished, onSessionLost)
}

/** [FirstSyncDestination] without Hilt, so a test can bring its own ViewModel. */
@Composable
internal fun FirstSyncRoot(
    viewModel: FirstSyncViewModel,
    onFinished: () -> Unit,
    onSessionLost: () -> Unit,
) {
    val phase by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.events.collect {
            when (it) {
                FirstSyncEvent.Finished -> onFinished()
                FirstSyncEvent.SessionLost -> onSessionLost()
            }
        }
    }
    FirstSyncScreen(phase, onRetry = viewModel::onRetry, onSkipCheer = viewModel::skipCheer)
}

/**
 * The stateless screen, hosted by Stan. While it syncs nothing on it takes focus and Select and the arrow keys do
 * nothing; the count and the bar change at most twice a second and do not animate. Stan, in the corner, does: he works
 * the aerial for the whole sync (a busy Stan never dozes), at the host's 20 fps cap. On a failure the error from the
 * error table shows with Retry, which has default focus. While the channels are built he keeps working; once one is on
 * the air he cheers us on it, and any key but volume, Back included, calls [onSkipCheer]: on to live TV, never out of
 * the app.
 */
@Composable
internal fun FirstSyncScreen(
    phase: FirstSyncPhase,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onSkipCheer: () -> Unit = {},
) {
    val cheering = phase == FirstSyncPhase.Done
    val keys = if (cheering) Modifier.onPreviewKeyEvent { skipsCheer(it, onSkipCheer) } else Modifier
    SetupScaffold(firstSyncCue(phase), modifier.then(keys)) { contentPadding ->
        // Only the failure panel can outgrow the screen (large text); the progress never scrolls.
        SetupColumn(
            contentPadding,
            TOP_OFFSET,
            modifier = Modifier.testTag(FirstSyncTags.SCREEN),
            scrollable = phase is FirstSyncPhase.Failed,
        ) {
            when (phase) {
                FirstSyncPhase.Starting -> Progress(copied = null, total = null, fraction = 0f)
                FirstSyncPhase.WaitingForNetwork -> Progress(null, null, fraction = 0f, waiting = true)
                is FirstSyncPhase.Syncing -> Progress(phase.copied, phase.total, phase.fraction)
                is FirstSyncPhase.Failed -> Failure(phase.error, phase.copy, phase.sessionLost, onRetry)
                FirstSyncPhase.Building -> BuildingChannels()
                FirstSyncPhase.Done -> OnTheAir()
            }
        }
    }
}

/**
 * A key during the cheer: every key but volume is consumed, and its release skips, as the platform's Back acts on its
 * release; so Back goes on to live TV instead of leaving the app, and no release reaches live TV.
 */
private fun skipsCheer(
    event: KeyEvent,
    onSkipCheer: () -> Unit,
): Boolean {
    if (stanOpeningKey(event.key) == StanOpeningKey.PassThrough) return false
    if (event.type == KeyEventType.KeyUp) onSkipCheer()
    return true
}

/**
 * While the first lineup is built, after the library is in: one line, said once to a screen reader as it appears, and
 * a bar with no end in sight (indeterminate, never "complete"). No count and no percentage: there is nothing true to
 * count.
 */
@Composable
private fun BuildingChannels() {
    val headline = stringResource(R.string.first_sync_building_headline)
    Text(
        text = headline,
        modifier =
            Modifier
                .testTag(FirstSyncTags.BUILDING)
                .politeLiveRegion()
                .semantics { heading() },
        style = MaterialTheme.typography.headlineLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Bar(fraction = null, description = headline)
}

/**
 * The moment between the first channel on the air and live TV: the full bar, and a headline that takes focus, so the
 * keys that skip the cheer reach this screen (nothing else on it is focusable).
 */
@Composable
private fun OnTheAir() {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val headline = stringResource(R.string.first_sync_done_headline)
    Text(
        text = headline,
        modifier =
            Modifier
                .testTag(FirstSyncTags.DONE)
                .focusRequester(focus)
                .focusable()
                .semantics { heading() },
        style = MaterialTheme.typography.headlineLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
        text = stringResource(R.string.first_sync_done_second_line),
        style = MaterialTheme.typography.screenBody,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // The headline already says it is ready; the bar says how far, as it does while syncing, so nothing is read twice.
    Bar(fraction = 1f, description = stringResource(R.string.first_sync_announce, PERCENT_PER_QUARTER * QUARTERS))
}

@Composable
private fun Progress(
    copied: Int?,
    total: Int?,
    fraction: Float,
    waiting: Boolean = false,
) {
    val locale = LocalConfiguration.current.locales[0]
    val numbers = remember(locale) { NumberFormat.getInstance(locale) }
    val count =
        when {
            waiting -> {
                stringResource(R.string.first_sync_waiting_for_network)
            }

            copied == null -> {
                stringResource(R.string.first_sync_starting)
            }

            total == null -> {
                pluralStringResource(R.plurals.first_sync_count_only, copied, numbers.format(copied))
            }

            else -> {
                pluralStringResource(R.plurals.first_sync_count, total, numbers.format(copied), numbers.format(total))
            }
        }
    val description =
        if (copied == null || total == null) {
            stringResource(R.string.first_sync_progress_description_starting)
        } else {
            pluralStringResource(
                R.plurals.first_sync_progress_description,
                total,
                numbers.format(copied),
                numbers.format(total),
            )
        }
    Text(
        text = stringResource(R.string.first_sync_headline),
        // A heading, so a screen reader has something to land on: nothing here is focusable.
        modifier = Modifier.testTag(FirstSyncTags.HEADLINE).semantics { heading() },
        style = MaterialTheme.typography.headlineLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
        text = stringResource(R.string.first_sync_second_line),
        modifier = Modifier.testTag(FirstSyncTags.SECOND_LINE),
        style = MaterialTheme.typography.screenBody,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Bar(fraction, description)
    Text(
        text = count,
        // The bar and the announcement below say it for TalkBack; reading the count too would say it twice.
        modifier = Modifier.testTag(FirstSyncTags.COUNT).semantics { hideFromAccessibility() },
        style = MaterialTheme.typography.screenBody,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Announcement(fraction)
}

/**
 * What TalkBack hears as the sync goes on: one polite announcement each time a quarter is passed, from a node with no
 * size, so there is no chatter for every update of the count.
 */
@Composable
private fun Announcement(fraction: Float) {
    val quarter = (fraction * QUARTERS).toInt()
    if (quarter == 0) return
    val said = stringResource(R.string.first_sync_announce, quarter * PERCENT_PER_QUARTER)
    Box(
        Modifier
            .size(0.dp)
            .testTag(FirstSyncTags.ANNOUNCE)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = said
            },
    )
}

/**
 * A plain bar with no animation of its own: it redraws only when the count changes (Stan beside it animates). A null
 * [fraction] is a bar with no known end: an empty track that reads as indeterminate.
 */
@Composable
private fun Bar(
    fraction: Float?,
    description: String,
) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = MaterialTheme.colorScheme.primary
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(BarHeight)
                .clip(RoundedCornerShape(BarHeight / 2))
                .background(track)
                .testTag(FirstSyncTags.BAR)
                .semantics {
                    contentDescription = description
                    progressBarRangeInfo =
                        fraction?.let { ProgressBarRangeInfo(it, 0f..1f) } ?: ProgressBarRangeInfo.Indeterminate
                },
    ) {
        fraction?.let { Box(Modifier.fillMaxHeight().fillMaxWidth(it).background(fill)) }
    }
}

/** The error from the error table with Retry, which has default focus (as on the launch screen). */
@Composable
private fun Failure(
    error: MediaServerError,
    copy: ErrorCopy,
    sessionLost: Boolean,
    onRetry: () -> Unit,
) {
    val retryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { retryFocus.requestFocus() }
    val cause = ErrorCopyTable.causeText(error)
    val details = detailsText(copy)
    Text(
        text = stringResource(R.string.first_sync_failed_headline),
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.headlineLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Column(
        modifier = Modifier.testTag(FirstSyncTags.ERROR_PANEL).politeLiveRegion(),
        verticalArrangement = Arrangement.spacedBy(Gap),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(TightGap), verticalAlignment = Alignment.Top) {
            ProblemIcon()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = messageText(error, copy),
                    style = MaterialTheme.typography.screenBody,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text =
                        if (cause.isEmpty()) {
                            details
                        } else {
                            stringResource(R.string.error_details_with_cause, details, cause)
                        },
                    style = MaterialTheme.typography.screenDetail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    ServerButton(
        // A refused sign-in cannot be retried: the button goes back to signing in, and says so.
        label = if (sessionLost) R.string.first_sync_sign_in_again else R.string.server_retry,
        onClick = onRetry,
        modifier = Modifier.focusRequester(retryFocus).testTag(FirstSyncTags.RETRY),
    )
}
