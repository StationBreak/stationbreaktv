// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.SubtitleView

/**
 * The subtitle view for the live picture: it draws in the viewer's system caption style and text size (Settings,
 * Accessibility, Captions), which wins over any styling embedded in the cues, and it never takes focus or a key. The
 * style setters are Media3's unstable API, opted into with androidx's own `OptIn`, the form Lint accepts.
 */
@OptIn(markerClass = [UnstableApi::class])
internal fun captionView(context: Context): SubtitleView =
    SubtitleView(context).apply {
        setApplyEmbeddedStyles(false)
        setUserDefaultStyle()
        setUserDefaultTextSize()
        isFocusable = false
        isClickable = false
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
 * cues are placed inside the picture's own rectangle, not the screen's.
 */
@Composable
@OptIn(markerClass = [UnstableApi::class])
internal fun CaptionLayer(
    cues: List<Cue>,
    hidden: Boolean,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { captionView(it) },
        modifier = modifier,
        update = { it.show(cues, hidden) },
    )
}
