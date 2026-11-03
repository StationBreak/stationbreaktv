// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.builder

import tv.stationbreak.core.domain.LibraryAccount
import kotlin.time.Instant

/** A [ChannelPreviews] for feature tests: it answers as told and records the drafts it was asked about. */
class FakeChannelPreviews : ChannelPreviews {
    /** What [preview] answers next. */
    var answer: ChannelPreview = ChannelPreview.NothingFits(emptyList())

    /** The drafts previewed, in order. */
    val drafts = mutableListOf<ChannelDraft>()

    override suspend fun preview(
        account: LibraryAccount,
        draft: ChannelDraft,
        at: Instant,
    ): ChannelPreview {
        drafts += draft
        return answer
    }
}
