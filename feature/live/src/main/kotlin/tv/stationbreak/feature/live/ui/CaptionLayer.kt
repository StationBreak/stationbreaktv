// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import android.content.Context
import android.view.View
import android.view.accessibility.CaptioningManager
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import kotlinx.coroutines.flow.StateFlow

/**
 * The subtitle view for the live picture: it draws in the viewer's system caption style and text size (Settings,
 * Accessibility, Captions), which wins over any styling embedded in the cues, and it never takes focus or a key. The
 * style setters are Media3's unstable API, opted into with androidx's own `OptIn`, the form Lint accepts.
 */
@OptIn(markerClass = [UnstableApi::class])
internal fun captionView(context: Context): SubtitleView =
    SubtitleView(context).apply {
        setApplyEmbeddedStyles(false)
        addOnAttachStateChangeListener(SystemCaptionStyle(this).also { it.apply() })
        isFocusable = false
        isClickable = false
    }

/**
 * Keeps [view] in the system caption style (#725): the font, colours, edges, window and text size from Settings,
 * Accessibility, Captions, whether that page's own switch is on or off. Captions are on or off by the carried subtitle
 * choice, which the system switch only starts, so a viewer who turns them on in the app still gets the style they
 * chose. (Media3's own `setUserDefaultStyle` falls back to its plain default while the switch is off.) A change made
 * while the view is on screen is drawn from then on, and one made while it was off screen when it comes back.
 */
@OptIn(markerClass = [UnstableApi::class])
internal class SystemCaptionStyle(
    view: SubtitleView,
) : View.OnAttachStateChangeListener {
    private val captioning = view.context.getSystemService(CaptioningManager::class.java)

    private val listener =
        object : CaptioningManager.CaptioningChangeListener() {
            override fun onUserStyleChanged(userStyle: CaptioningManager.CaptionStyle) =
                view.setStyle(CaptionStyleCompat.createFromCaptionStyle(userStyle))

            override fun onFontScaleChanged(fontScale: Float) =
                view.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * fontScale)
        }

    /** Draws in the style the system holds now. */
    fun apply() {
        val system = captioning ?: return
        listener.onUserStyleChanged(system.userStyle)
        listener.onFontScaleChanged(system.fontScale)
    }

    override fun onViewAttachedToWindow(v: View) {
        captioning?.addCaptioningChangeListener(listener)
        apply()
    }

    override fun onViewDetachedFromWindow(v: View) {
        captioning?.removeCaptioningChangeListener(listener)
    }
}

/** Shows [cues] on this view, or nothing while [hidden] (the guide is open over the picture). */
@OptIn(markerClass = [UnstableApi::class])
internal fun SubtitleView.show(
    cues: List<Cue>,
    hidden: Boolean,
) {
    setCues(if (hidden) emptyList() else cues)
}

/**
 * The captions over the picture: lay it in the same box and with the same [modifier] as the video surface, so the
 * cues are placed inside the picture's own rectangle, not the screen's. [cues] is collected here, so a cue change
 * recomposes this layer alone.
 */
@Composable
@OptIn(markerClass = [UnstableApi::class])
internal fun CaptionLayer(
    cues: StateFlow<List<Cue>>,
    hidden: Boolean,
    modifier: Modifier = Modifier,
) {
    val shown by cues.collectAsStateWithLifecycle()
    AndroidView(
        factory = { captionView(it) },
        modifier = modifier,
        update = { it.show(shown, hidden) },
    )
}
