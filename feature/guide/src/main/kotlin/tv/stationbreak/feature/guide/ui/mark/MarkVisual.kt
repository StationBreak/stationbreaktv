// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui.mark

import androidx.compose.ui.graphics.ImageBitmap

/** What one mark shows right now. */
internal sealed interface MarkVisual {
    data class Logo(
        val image: ImageBitmap,
    ) : MarkVisual

    data object Initials : MarkVisual
}

/** What a mark draws, as a test reads it through `MarkKindKey`. */
internal enum class MarkKind { Logo, Initials }

/** The logo only when [markKey] has a decoded bitmap in [cache]; otherwise the initials tile. Never blocks. */
internal fun markVisual(
    markKey: String?,
    cache: MarkCache?,
): MarkVisual {
    val image = if (markKey == null) null else cache?.peek(markKey)
    return if (image == null) MarkVisual.Initials else MarkVisual.Logo(image)
}
