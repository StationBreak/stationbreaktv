// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.builder

import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.lineup.ChannelNumbers
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.Seed

/**
 * A [ChannelBuilder] for feature tests: it records what it was asked and answers as told. By default every build is
 * [BuildResult.Done], a blank draft is empty at number 600 and opening finds nothing.
 */
class FakeChannelBuilder : ChannelBuilder {
    /** What was asked, in order: `blank`, `open`, `create`, `save`, `reshuffle`, `delete`, `numberFor` and `freeNumbers`. */
    val calls = mutableListOf<String>()

    /** The drafts passed to [create] and [save]. */
    val created = mutableListOf<ChannelDraft>()
    val saved = mutableListOf<Triple<ChannelId, ChannelDraft, ChannelId?>>()

    /** What [blank] answers, with the asked-for kids setting. */
    var blankDraft: ChannelDraft =
        ChannelDraft("", emptyList(), emptyList(), DraftFilters(), kids = false, number = FIRST_NUMBER, seed = Seed(1uL))

    /** What [open] answers next. */
    var openResult: OpenResult = OpenResult.NotFound

    /** What [create], [save], [reshuffle] and [delete] answer next; null is [BuildResult.Done] with the channel's ID. */
    var buildResult: BuildResult? = null

    /** What [numberFor] answers next. */
    var number: Int? = FIRST_NUMBER

    /** What [freeNumbers] answers next; null is the whole of the band asked for. */
    var free: Listed<List<Int>>? = null

    override suspend fun blank(
        account: LibraryAccount,
        kids: Boolean,
    ): ChannelDraft {
        calls += "blank"
        return blankDraft.copy(kids = kids)
    }

    override suspend fun open(
        account: LibraryAccount,
        id: ChannelId,
        copy: Boolean,
    ): OpenResult {
        calls += "open"
        return openResult
    }

    override suspend fun create(
        account: LibraryAccount,
        draft: ChannelDraft,
    ): BuildResult {
        calls += "create"
        created += draft
        return buildResult ?: BuildResult.Done(ChannelId("custom:fake"))
    }

    override suspend fun save(
        account: LibraryAccount,
        id: ChannelId,
        draft: ChannelDraft,
        swapWith: ChannelId?,
    ): BuildResult {
        calls += "save"
        saved += Triple(id, draft, swapWith)
        return buildResult ?: BuildResult.Done(id)
    }

    override suspend fun reshuffle(
        account: LibraryAccount,
        id: ChannelId,
    ): BuildResult {
        calls += "reshuffle"
        return buildResult ?: BuildResult.Done(id)
    }

    override suspend fun delete(
        account: LibraryAccount,
        id: ChannelId,
    ): BuildResult {
        calls += "delete"
        return buildResult ?: BuildResult.Done(id)
    }

    override suspend fun numberFor(
        account: LibraryAccount,
        kids: Boolean,
    ): Int? {
        calls += "numberFor"
        return number
    }

    override suspend fun freeNumbers(
        account: LibraryAccount,
        kids: Boolean,
    ): Listed<List<Int>> {
        calls += "freeNumbers"
        return free ?: Listed.Ok((if (kids) ChannelNumbers.kids else ChannelNumbers.custom).toList())
    }

    private companion object {
        const val FIRST_NUMBER = 600
    }
}
