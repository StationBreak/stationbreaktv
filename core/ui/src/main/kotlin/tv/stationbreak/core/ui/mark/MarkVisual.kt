// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui.mark

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap

/** What one mark shows right now. */
sealed interface MarkVisual {
    data class Logo(
        val image: ImageBitmap,
    ) : MarkVisual

    data object Initials : MarkVisual
}

/** The render a guide draws: the colour logo when it has no mark tint (Dark), the mono one when it does (Light). */
fun variantForTint(tint: Color?): MarkVariant = if (tint == null) MarkVariant.Colour else MarkVariant.Mono

/** What a mark draws, as a test reads it through `MarkKindKey`. */
enum class MarkKind { Logo, Initials }

/** The logo only when [markKey] has a decoded [variant] bitmap in [cache]; otherwise the initials. Never blocks. */
fun markVisual(
    markKey: String?,
    cache: MarkCache?,
    variant: MarkVariant = MarkVariant.Colour,
): MarkVisual {
    val image = if (markKey == null) null else cache?.peek(markKey, variant)
    return if (image == null) MarkVisual.Initials else MarkVisual.Logo(image)
}
