// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.firstsync

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.feature.onboarding.R
import tv.stationbreak.feature.onboarding.server.BodySize
import tv.stationbreak.feature.onboarding.server.DetailsSize
import tv.stationbreak.feature.onboarding.server.ErrorCopy
import tv.stationbreak.feature.onboarding.server.ErrorCopyTable
import tv.stationbreak.feature.onboarding.server.Gap
import tv.stationbreak.feature.onboarding.server.ProblemIcon
import tv.stationbreak.feature.onboarding.server.ServerButton
import tv.stationbreak.feature.onboarding.server.TightGap
import tv.stationbreak.feature.onboarding.server.messageText
import tv.stationbreak.feature.onboarding.server.politeLiveRegion
import java.text.NumberFormat

private val HeadlineSize = 40.sp
private val ContentWidth = 640.dp
private val BarHeight = 12.dp
private const val OVERSCAN = 0.05f
private const val TOP_OFFSET = 0.1f

/**
 * The first-sync progress screen (spec 06, Unit 3), shown from the end of onboarding until the first sync of the
 * signed-in account ends. The first sync is queued here if it is not already, and the app moves on by itself when it
 * ends, with no button. Back is the platform's: the navigation graph puts nothing beneath this screen, so Back leaves
 * the app and the sync carries on in the background.
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
    FirstSyncScreen(phase, onRetry = viewModel::onRetry)
}

/**
 * The stateless screen. While it syncs nothing on it takes focus and Select and the arrow keys do nothing; the count
 * and the bar change at most twice a second and there is no animation. On a failure the error from the error table
 * shows with Retry, which has default focus.
 */
@Composable
internal fun FirstSyncScreen(
    phase: FirstSyncPhase,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).testTag(FirstSyncTags.SCREEN),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = maxWidth * OVERSCAN,
                        end = maxWidth * OVERSCAN,
                        top = maxHeight * TOP_OFFSET,
                        bottom = maxHeight * OVERSCAN,
                    ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = ContentWidth).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Gap),
            ) {
                when (phase) {
                    FirstSyncPhase.Starting -> Progress(copied = null, total = null, fraction = 0f)
                    is FirstSyncPhase.Syncing -> Progress(phase.copied, phase.total, phase.fraction)
                    is FirstSyncPhase.Failed -> Failure(phase.error, phase.copy, onRetry)
                }
            }
        }
    }
}

@Composable
private fun Progress(
    copied: Int?,
    total: Int?,
    fraction: Float,
) {
    val numbers = remember { NumberFormat.getInstance() }
    val count =
        when {
            copied == null -> stringResource(R.string.first_sync_starting)
            total == null -> stringResource(R.string.first_sync_count_only, numbers.format(copied))
            else -> stringResource(R.string.first_sync_count, numbers.format(copied), numbers.format(total))
        }
    val description =
        if (copied == null || total == null) {
            stringResource(R.string.first_sync_progress_description_starting)
        } else {
            stringResource(R.string.first_sync_progress_description, numbers.format(copied), numbers.format(total))
        }
    Text(
        text = stringResource(R.string.first_sync_headline),
        // A heading, so a screen reader has something to land on: nothing here is focusable.
        modifier = Modifier.testTag(FirstSyncTags.HEADLINE).semantics { heading() },
        style = MaterialTheme.typography.headlineLarge.copy(fontSize = HeadlineSize),
        color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
        text = stringResource(R.string.first_sync_second_line),
        modifier = Modifier.testTag(FirstSyncTags.SECOND_LINE),
        fontSize = BodySize,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Bar(fraction, description)
    Text(
        text = count,
        modifier = Modifier.testTag(FirstSyncTags.COUNT),
        fontSize = BodySize,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/** A plain bar: no animation of its own, so the Insignia only redraws it when the count changes. */
@Composable
private fun Bar(
    fraction: Float,
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
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                },
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(fill))
    }
}

/** The error from the error table with Retry, which has default focus (as on the launch screen). */
@Composable
private fun Failure(
    error: MediaServerError,
    copy: ErrorCopy,
    onRetry: () -> Unit,
) {
    val retryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { retryFocus.requestFocus() }
    val cause = ErrorCopyTable.causeText(error)
    val details = stringResource(copy.details)
    Text(
        text = stringResource(R.string.first_sync_failed_headline),
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.headlineLarge.copy(fontSize = HeadlineSize),
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
                    fontSize = BodySize,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text =
                        if (cause.isEmpty()) {
                            details
                        } else {
                            stringResource(R.string.error_details_with_cause, details, cause)
                        },
                    fontSize = DetailsSize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    ServerButton(
        label = R.string.server_retry,
        onClick = onRetry,
        modifier = Modifier.focusRequester(retryFocus).testTag(FirstSyncTags.RETRY),
    )
}
