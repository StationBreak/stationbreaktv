// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.nav

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.clearAndSetSemantics
import tv.stationbreak.core.ui.LocalCovered
import tv.stationbreak.core.ui.stan.StanOpeningKey
import tv.stationbreak.core.ui.stan.stanOpeningKey

/**
 * Something drawn over the whole graph that hides it: Stan's opening, or setup's cheer held over live TV. While a
 * cover is [active] the graph beneath receives no key and a screen reader does not read it, and the cover owns what a
 * key does: [onKey] for a fresh press of any key but Back and volume (not a repeat, not a key already held), [onBack]
 * for the release of a Back it saw pressed. [holdsFocus] is for a cover whose screen beneath may hold no focus yet:
 * the host takes it, so the keys still reach the host.
 */
internal interface GraphCover {
    val active: Boolean
    val holdsFocus: Boolean

    fun onKey()

    fun onBack()
}

/**
 * Setup's cheer held over live TV ([SetupHandOver]): a key or Back lifts it and goes no further, as during the cheer
 * itself. Live TV and its first-visit guide take the focus beneath it themselves, so it holds none: the focus is
 * already where it belongs when the cover lifts.
 */
internal class CheerCover(
    private val handOver: SetupHandOver,
) : GraphCover {
    override val active: Boolean get() = handOver.covering
    override val holdsFocus: Boolean = false

    override fun onKey() = handOver.end()

    override fun onBack() = handOver.end()
}

/**
 * The graph's [content] under its [covers] (topmost first) and their [overlays]. While one is active:
 * - **Keys:** every key but volume up, down and mute is consumed here, on a parent of whatever holds the focus, and
 *   goes to the active cover (see [GraphCover]). A key a cover consumed is swallowed to its end, repeats and key-up
 *   included, even after the cover has gone, so a Select that lifted a cover never clicks what is beneath.
 * - **Back** is handled by that same key handler, not a `BackHandler`: a `BackHandler` here would rank below the ones
 *   the destinations register after it, and TV remotes deliver Back as a key. It acts on its release, as the
 *   platform's Back does.
 * - **Semantics:** the graph's are cleared, and [LocalCovered] tells the graph it is covered, so a live region keyed on
 *   it is announced when the cover lifts.
 */
@Composable
internal fun GraphCoverHost(
    covers: List<GraphCover>,
    overlays: @Composable BoxScope.() -> Unit,
    content: @Composable () -> Unit,
) {
    val cover = covers.firstOrNull { it.active }
    val active by rememberUpdatedState(cover)
    val covered = cover != null
    val holding = cover?.holdsFocus == true
    // The keys whose down a cover consumed and whose up it has yet to: swallowed to the end, covered or not.
    val swallowing = remember { SwallowedKeys() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(holding) { if (holding) runCatching { focus.requestFocus() } }
    val keys =
        if (covered || swallowing.any) {
            Modifier.onPreviewKeyEvent {
                handleCoverKey(it.key, it.type, it.nativeKeyEvent.repeatCount, active, swallowing)
            }
        } else {
            Modifier
        }
    val focusable = if (holding) Modifier.focusRequester(focus).focusable() else Modifier
    Box(Modifier.fillMaxSize().then(keys).then(focusable)) {
        Box(Modifier.fillMaxSize().then(if (covered) Modifier.clearAndSetSemantics {} else Modifier)) {
            CompositionLocalProvider(LocalCovered provides covered) { content() }
        }
        overlays()
    }
}

/** The keys held down that a cover consumed, each with whether its press was fresh; [any] is observable. */
internal class SwallowedKeys {
    private val keys = mutableMapOf<Key, Boolean>()
    private var count by mutableIntStateOf(0)

    val any: Boolean get() = count > 0

    operator fun contains(key: Key) = key in keys

    /** Holds [key]; a key already held keeps the freshness of its first press. */
    fun add(
        key: Key,
        fresh: Boolean,
    ) {
        if (key !in keys) keys[key] = fresh
        count = keys.size
    }

    /** Lets [key] go; true when it was held from a fresh press, so a release is matched to a press the cover saw. */
    fun remove(key: Key): Boolean {
        val fresh = keys.remove(key) == true
        count = keys.size
        return fresh
    }
}

/** One key event under [cover] (null: no cover is up, only the end of a key one consumed is still its). */
internal fun handleCoverKey(
    key: Key,
    type: KeyEventType,
    repeatCount: Int,
    cover: GraphCover?,
    swallowing: SwallowedKeys,
): Boolean {
    val isUp = type == KeyEventType.KeyUp
    return when {
        cover == null -> {
            (key in swallowing).also { if (it && isUp) swallowing.remove(key) }
        }

        stanOpeningKey(key) == StanOpeningKey.PassThrough -> {
            false
        }

        else -> {
            val isBack = stanOpeningKey(key) == StanOpeningKey.Back
            if (isUp) {
                // Back acts on its release: finishing on the press would send the release to whatever is behind.
                if (swallowing.remove(key) && isBack) cover.onBack()
            } else if (type == KeyEventType.KeyDown) {
                // A repeat, or a key held from before the cover, is consumed but does nothing.
                val fresh = repeatCount == 0 && key !in swallowing
                swallowing.add(key, fresh)
                if (fresh && !isBack) cover.onKey()
            }
            true
        }
    }
}
