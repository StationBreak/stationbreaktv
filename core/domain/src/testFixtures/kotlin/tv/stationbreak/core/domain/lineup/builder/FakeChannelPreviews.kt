// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.builder

import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.model.ChannelId
import kotlin.time.Instant

/**
 * A [ChannelPreviews] for feature tests: it answers as told and records the drafts it was asked about, and the channel
 * each was an edit of.
 */
class FakeChannelPreviews : ChannelPreviews {
    /** What [preview] answers next. */
    var answer: ChannelPreview = ChannelPreview.NothingFits(emptyList())

    /** The drafts previewed, in order. */
    val drafts = mutableListOf<ChannelDraft>()

    /** The channel each preview was an edit of (null for a new channel), in order. */
    val editing = mutableListOf<ChannelId?>()

    override suspend fun preview(
        account: LibraryAccount,
        draft: ChannelDraft,
        at: Instant,
        editing: ChannelId?,
    ): ChannelPreview {
        drafts += draft
        this.editing += editing
        return answer
    }
}
