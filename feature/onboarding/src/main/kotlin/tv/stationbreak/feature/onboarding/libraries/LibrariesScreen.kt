// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.libraries

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.LibraryKind
import tv.stationbreak.core.ui.FocusStyle
import tv.stationbreak.feature.onboarding.R
import tv.stationbreak.feature.onboarding.server.BodySize
import tv.stationbreak.feature.onboarding.server.DetailsSize
import tv.stationbreak.feature.onboarding.server.Gap
import tv.stationbreak.feature.onboarding.server.ProblemIcon
import tv.stationbreak.feature.onboarding.server.ProgressSpinner
import tv.stationbreak.feature.onboarding.server.ServerButton
import tv.stationbreak.feature.onboarding.server.TightGap
import tv.stationbreak.feature.onboarding.server.messageText
import tv.stationbreak.feature.onboarding.server.politeLiveRegion

private val HeadlineSize = 40.sp
private val ContentWidth = 640.dp
private val RowHeight = 64.dp
private val BoxSize = 28.dp
private const val OVERSCAN = 0.05f
private const val TOP_OFFSET = 0.08f

/**
 * The library picker as the app shows it. Back is handled by the caller ([onBack]): it goes to "Signed in as", so the
 * user is never signed out silently.
 *
 * @param onDone setup is saved: on to the "Setup complete" screen.
 * @param onSessionLost the server refused the session: launch routing decides where to go (sign-in).
 * @param onMissingSession no sign-in is held (process death): launch routing restores it.
 */
@Composable
fun LibrariesDestination(
    onBack: () -> Unit,
    onDone: () -> Unit,
    onSessionLost: () -> Unit,
    onMissingSession: () -> Unit,
) {
    val viewModel: LibrariesViewModel = hiltViewModel()
    LibrariesRoot(viewModel, onBack, onDone, onSessionLost, onMissingSession)
}

/** [LibrariesDestination] without Hilt, so a test can bring its own ViewModel. */
@Composable
internal fun LibrariesRoot(
    viewModel: LibrariesViewModel,
    onBack: () -> Unit,
    onDone: () -> Unit,
    onSessionLost: () -> Unit,
    onMissingSession: () -> Unit,
) {
    if (viewModel.missingSession) {
        // Nothing to list: show the dark background (no flash) and let launch routing restore the session.
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
        LaunchedEffect(viewModel) { onMissingSession() }
        return
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.events.collect {
            when (it) {
                LibrariesEvent.Finished -> onDone()
                LibrariesEvent.SessionLost -> onSessionLost()
            }
        }
    }
    BackHandler(onBack = onBack)
    LibrariesScreen(
        state = state,
        onToggle = viewModel::onToggle,
        onDone = viewModel::onDone,
        onRetry = viewModel::onRetry,
    )
}

/** The stateless screen: everything it shows comes from [state], and every action goes back out as a callback. */
@Suppress("LongMethod") // One screen's layout; splitting hides the focus flow.
@Composable
internal fun LibrariesScreen(
    state: LibrariesUiState,
    onToggle: (ItemId) -> Unit,
    onDone: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { LibrariesFocus() }
    FocusFollowsPhase(state.phase, focus)
    BoxWithConstraints(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).testTag(LibrariesTags.SCREEN),
    ) {
        // A 5% safe area on every side, inside the scroll area so a focused, scaled row isn't clipped.
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
                Text(
                    text = stringResource(R.string.libraries_headline),
                    style = MaterialTheme.typography.headlineLarge.copy(fontSize = HeadlineSize),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.libraries_subtitle),
                    fontSize = BodySize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Content(state, focus, onToggle, onDone, onRetry)
            }
        }
    }
}

@Composable
private fun Content(
    state: LibrariesUiState,
    focus: LibrariesFocus,
    onToggle: (ItemId) -> Unit,
    onDone: () -> Unit,
    onRetry: () -> Unit,
) {
    when (val phase = state.phase) {
        LibrariesPhase.Loading -> {
            LoadingStatus()
        }

        is LibrariesPhase.LoadFailed -> {
            val copy = phase.copy
            Problem(
                message = messageText(phase.error, copy),
                details = stringResource(copy.details),
                retryFocus = focus.retry,
                onRetry = onRetry,
            )
        }

        LibrariesPhase.NoLibraries -> {
            Problem(
                message = stringResource(R.string.libraries_none_message),
                details = stringResource(R.string.libraries_none_details),
                retryFocus = focus.retry,
                onRetry = onRetry,
            )
        }

        LibrariesPhase.Choosing, LibrariesPhase.Saving, LibrariesPhase.SaveFailed -> {
            Checklist(state, focus, onToggle, onDone)
            if (phase == LibrariesPhase.SaveFailed) {
                Problem(
                    message = stringResource(R.string.libraries_save_failed_message),
                    details = stringResource(R.string.libraries_save_failed_details),
                    retryFocus = focus.retry,
                    onRetry = onRetry,
                )
            }
        }
    }
}

@Composable
private fun LoadingStatus() {
    Row(
        modifier = Modifier.testTag(LibrariesTags.LOADING).politeLiveRegion(),
        horizontalArrangement = Arrangement.spacedBy(TightGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProgressSpinner(contentDescription = stringResource(R.string.libraries_progress_description))
        Text(
            text = stringResource(R.string.libraries_loading),
            fontSize = BodySize,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** A plain sentence, a small details line and Retry (decision 5). */
@Composable
private fun Problem(
    message: String,
    details: String,
    retryFocus: FocusRequester,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.testTag(LibrariesTags.ERROR_PANEL).politeLiveRegion(),
        verticalArrangement = Arrangement.spacedBy(Gap),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(TightGap), verticalAlignment = Alignment.Top) {
            ProblemIcon()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = message, fontSize = BodySize, color = MaterialTheme.colorScheme.onSurface)
                Text(text = details, fontSize = DetailsSize, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        ServerButton(
            label = R.string.server_retry,
            onClick = onRetry,
            modifier = Modifier.focusRequester(retryFocus).testTag(LibrariesTags.RETRY),
        )
    }
}

/** The rows, then Done below them, reached by Down from the last row. Done says why when it is off. */
@Composable
private fun Checklist(
    state: LibrariesUiState,
    focus: LibrariesFocus,
    onToggle: (ItemId) -> Unit,
    onDone: () -> Unit,
) {
    Column(
        modifier = Modifier.testTag(LibrariesTags.LIST),
        verticalArrangement = Arrangement.spacedBy(TightGap),
    ) {
        state.libraries.forEachIndexed { index, library ->
            LibraryRow(
                library = library,
                ticked = library.id in state.selected,
                onToggle = { onToggle(library.id) },
                modifier = if (index == 0) Modifier.focusRequester(focus.firstRow) else Modifier,
            )
        }
    }
    if (state.selected.isEmpty()) {
        Row(
            modifier = Modifier.testTag(LibrariesTags.REASON).politeLiveRegion(),
            horizontalArrangement = Arrangement.spacedBy(TightGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProblemIcon()
            Text(
                text = stringResource(R.string.libraries_none_ticked),
                fontSize = BodySize,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
    ServerButton(
        label = R.string.libraries_done,
        onClick = onDone,
        enabled = state.canFinish,
        emphasis = true,
        modifier = Modifier.testTag(LibrariesTags.DONE),
    )
}

/** One library: a tick box, its name and its kind. Select toggles it. */
@Composable
private fun LibraryRow(
    library: Library,
    ticked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val tickedText = stringResource(if (ticked) R.string.libraries_row_ticked else R.string.libraries_row_unticked)
    Surface(
        onClick = onToggle,
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(LibrariesTags.row(library.id.value))
                .semantics(mergeDescendants = true) {
                    role = Role.Checkbox
                    toggleableState = ToggleableState(ticked)
                    stateDescription = tickedText
                },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = colors.surfaceVariant,
                contentColor = colors.onSurfaceVariant,
                focusedContainerColor = colors.surfaceVariant,
                focusedContentColor = colors.onSurfaceVariant,
                pressedContainerColor = colors.surfaceVariant,
                pressedContentColor = colors.onSurfaceVariant,
            ),
        border = FocusStyle.surfaceBorder(),
        scale = FocusStyle.surfaceScale(),
    ) {
        Row(
            modifier = Modifier.heightIn(min = RowHeight).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(Gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TickBox(ticked)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = library.name, fontSize = BodySize, color = colors.onSurface)
                Text(
                    text = stringResource(kindLabel(library.kind)),
                    fontSize = DetailsSize,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

/** A box with a tick mark when ticked: shape as well as colour, so the state is never colour alone. */
@Composable
private fun TickBox(ticked: Boolean) {
    val colors = MaterialTheme.colorScheme
    val outline = colors.onSurface
    val fill: Color = if (ticked) colors.primary else Color.Transparent
    val mark = colors.onPrimary
    Canvas(
        modifier =
            Modifier
                .size(BoxSize)
                .background(fill, RoundedCornerShape(4.dp))
                .border(3.dp, outline, RoundedCornerShape(4.dp)),
    ) {
        if (ticked) {
            val w = size.width
            val h = size.height
            val stroke = 3.dp.toPx()
            val start = Offset(w * 0.22f, h * 0.52f)
            val bottom = Offset(w * 0.42f, h * 0.72f)
            val end = Offset(w * 0.78f, h * 0.30f)
            drawLine(mark, start, bottom, stroke, StrokeCap.Round)
            drawLine(mark, bottom, end, stroke, StrokeCap.Round)
        }
    }
}

private fun kindLabel(kind: LibraryKind): Int =
    when (kind) {
        LibraryKind.Movies -> R.string.libraries_kind_movies
        LibraryKind.TvShows -> R.string.libraries_kind_tv_shows
        LibraryKind.Mixed -> R.string.libraries_kind_mixed
        LibraryKind.HomeVideos -> R.string.libraries_kind_home_videos
        LibraryKind.MusicVideos -> R.string.libraries_kind_music_videos
    }

/** The focus targets on the picker. */
private class LibrariesFocus {
    val firstRow = FocusRequester()
    val retry = FocusRequester()
}

/**
 * Focus follows the phase (spec 05, Adorabelle's defaults): the first row when the list appears, Retry when there is
 * nothing to choose from or Done failed. Ticking a row after a failed Done is the user's own move, so focus stays.
 */
@Composable
private fun FocusFollowsPhase(
    phase: LibrariesPhase,
    focus: LibrariesFocus,
) {
    var shown by remember { mutableStateOf<Class<*>?>(null) }
    LaunchedEffect(phase::class.java) {
        val previous = shown
        shown = phase::class.java
        when (phase) {
            LibrariesPhase.Choosing -> {
                if (previous == null || previous == LibrariesPhase.Loading::class.java) focus.firstRow.requestFocus()
            }

            is LibrariesPhase.LoadFailed, LibrariesPhase.NoLibraries, LibrariesPhase.SaveFailed -> {
                focus.retry.requestFocus()
            }

            LibrariesPhase.Loading, LibrariesPhase.Saving -> {
                Unit
            }
        }
    }
}
