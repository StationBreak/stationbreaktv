// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tv.stationbreak.core.domain.details.ProgramDetailsCache
import tv.stationbreak.core.model.ItemDetails

/**
 * The viewer's own banner and the program info overlay (spec 17) over the playing picture. They are written to the
 * [Screen] beside the sitting, never in its place: a tune, a boundary cut or a flip writes the next state and both are
 * gone. All of it runs on the run's one dispatcher, like the commands that drive it.
 *
 * The actions banner (Info, Last, and the Audio / Subtitles / Version chips) stays [LiveTimings.actionsLinger] after
 * the last key; the overlay has no timer, it holds text to read, and nor has a picker open over the chips: its clock is
 * suspended while it is open and starts again when it closes (design doc B2.4). [details] is null where there is no
 * cache (a test): the overlay then shows what the banner knows, and never fails.
 */
internal class Overlays(
    private val screen: Screen,
    private val timings: LiveTimings,
    private val tuned: Tuned,
    private val details: ProgramDetailsCache?,
) {
    private var timer: Job? = null
    private var fetch: Job? = null

    /** Acts on [command] if it is an overlay's, and keeps the actions banner up for a flip's key. */
    fun handle(
        command: LiveCommand,
        scope: CoroutineScope,
    ) {
        when (command) {
            LiveCommand.ShowBanner -> showBanner(scope)

            LiveCommand.ShowInfo -> showInfo(scope)

            LiveCommand.DismissOverlay -> dismiss(scope)

            is LiveCommand.OpenPicker -> openPicker(command.kind)

            is LiveCommand.Choose -> closePicker(scope)

            is LiveCommand.Flip -> keepActions(scope)

            LiveCommand.Previous, is LiveCommand.Tune, LiveCommand.FlipReleased -> Unit

            LiveCommand.PlayPause, LiveCommand.Play, LiveCommand.Pause, is LiveCommand.Seek,
            LiveCommand.SeekReleased, LiveCommand.GoLive,
            -> Unit
        }
    }

    private fun shownWatching(): LiveState.Watching? =
        (screen.value as? LiveState.Watching)?.takeIf { screen.pictureUp && screen.info.value == null }

    private fun showBanner(scope: CoroutineScope) {
        val shown = shownWatching()
        val playing = screen.nowPlaying
        if (shown == null || playing == null) return
        if (shown.banner?.actions == true) {
            arm(scope)
        } else {
            val actions = LiveState.Watching(playing.banner.copy(actions = true, hasLast = tuned.previous != null))
            screen.overlay(actions)
            arm(scope)
        }
    }

    private fun showInfo(scope: CoroutineScope) {
        val shown = shownWatching()
        val playing = screen.nowPlaying
        when {
            shown == null || playing == null -> Unit

            // With no banner the key asks for one: the next press opens the overlay.
            shown.banner == null -> showBanner(scope)

            else -> openInfo(scope, playing)
        }
    }

    private fun openInfo(
        scope: CoroutineScope,
        playing: Playing,
    ) {
        timer?.cancel()
        fetch?.cancel()
        val banner = playing.banner
        // The overlay replaces the banner: two panels never stack.
        screen.overlay(LiveState.Watching(null))
        val id = playing.itemId
        val known = id?.let { details?.cached(it) }
        if (known != null || id == null || details == null) {
            screen.showInfo(LiveInfo(banner, known, loading = false))
        } else {
            val opening = LiveInfo(banner, null, loading = true)
            screen.showInfo(opening)
            fetch =
                scope.launch {
                    val found = quietly { details.details(id) }
                    screen.updateInfo(opening, LiveInfo(banner, found, loading = false))
                }
        }
    }

    /** What [load] answers, or null when it fails: the overlay stays quiet and keeps what the banner knows. */
    private suspend fun quietly(load: suspend () -> ItemDetails?): ItemDetails? =
        try {
            load()
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
        ) {
            null
        }

    /**
     * Opens the [kind] picker over the chips, from the chip or a remote key. With no actions banner up (a key from the
     * picture) it brings the banner up with the picker open. Nothing when the picture does not play, there are no facts
     * to list, or the chip is not focusable (one audio track, no subtitle tracks).
     */
    private fun openPicker(kind: PickerKind) {
        val shown = shownWatching()
        val playing = screen.nowPlaying
        if (shown == null || playing == null || !playing.banner.stream.canPick(kind)) return
        screen.touch()
        timer?.cancel()
        val raised = playing.banner.copy(actions = true, hasLast = tuned.previous != null)
        val base = shown.banner?.takeIf { it.actions } ?: raised
        screen.overlay(LiveState.Watching(base.copy(picker = kind)))
    }

    /** A choice was made, or the picker was left: the chips are back, and the banner's clock starts again. */
    private fun closePicker(scope: CoroutineScope) {
        val banner = shownWatching()?.banner?.takeIf { it.picker != null } ?: return
        screen.overlay(LiveState.Watching(banner.copy(picker = null)))
        arm(scope)
    }

    /** Back: a picker closes to its chip; the info overlay closes and the banner returns; the bare banner closes. */
    private fun dismiss(scope: CoroutineScope) {
        if (shownWatching()?.banner?.picker != null) {
            closePicker(scope)
        } else if (screen.info.value != null) {
            fetch?.cancel()
            screen.showInfo(null)
            showBanner(scope)
        } else if (shownWatching()?.banner?.actions == true) {
            timer?.cancel()
            screen.overlay(LiveState.Watching(null))
        }
    }

    private fun keepActions(scope: CoroutineScope) {
        val shown = shownWatching() ?: return
        if (shown.banner?.actions == true) arm(scope)
    }

    /** Starts the actions banner's clock again from now; it hides the banner unless a picker is open by then. */
    private fun arm(scope: CoroutineScope) {
        screen.touch()
        timer?.cancel()
        timer =
            scope.launch {
                delay(timings.actionsLinger)
                screen.hideActions()
            }
    }
}
