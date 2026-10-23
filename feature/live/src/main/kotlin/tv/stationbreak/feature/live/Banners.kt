// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import tv.stationbreak.core.domain.lineup.Airing
import tv.stationbreak.core.domain.lineup.AiringBlock
import tv.stationbreak.core.domain.lineup.ChannelAiring
import tv.stationbreak.core.domain.lineup.ChannelOnAir
import tv.stationbreak.core.domain.lineup.ProgramInfo
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import kotlin.time.Instant

/**
 * The banner for [block] on [channel], made from the schedule's own answer (design goal 7): the channel's number and
 * name, the show's names, the block's slot, and [joinedMs] (how far into the media the viewer joined; null for a cut at
 * the start and for "up next"). [driftMs] is how far the sitting runs ahead of the clock: the block's times move back
 * by it, so the slot is the one the clock shows (design goal 6: the banner shows what is actually on).
 */
internal fun bannerFor(
    channel: ChannelAiring,
    block: AiringBlock,
    label: BannerLabel,
    joinedMs: Long?,
    driftMs: Long = 0,
): Banner {
    val info =
        when (val airing = block.airing) {
            is Airing.Program -> airing.info
            is Airing.StandIn -> airing.info
            Airing.Slate -> null
        }
    return Banner(channel.number, channel.name, label, titleOf(info), info, block.startMs - driftMs, block.endMs - driftMs, joinedMs)
}

/**
 * A movie shows its title. An episode of a known series shows the series name; its "S2 E5 · episode title" line is made
 * on the screen from [Banner.info] (`BannerText.episodeLine`), so all copy lives in resources.
 */
private fun titleOf(info: ProgramInfo?): String? =
    when {
        info == null -> null
        info.kind == ScheduleItemKind.Episode && info.seriesName != null -> info.seriesName
        else -> info.title
    }

/** How many following blocks to look through for the next program on the ident card: a day of half hours. */
private const val UP_NEXT_WINDOW = 48

/**
 * The next program after [onAir]'s block as an "up next" banner, looking on across the day boundary if the blocks it
 * already holds are all slates. It looks no further than the end of the next day ([ChannelOnAir.builtUntilMs]); null
 * if no program starts before that (the card then shows the channel only).
 */
internal suspend fun Sitting.upNext(onAir: ChannelOnAir): Banner? {
    val near = onAir.next.firstProgramBefore(onAir.builtUntilMs)
    val reached = (onAir.next.lastOrNull() ?: onAir.now).endMs
    val wider =
        if (near == null && onAir.next.size < UP_NEXT_WINDOW && reached < onAir.builtUntilMs) {
            val at = Instant.fromEpochMilliseconds(vnowMs())
            schedule.onAir(channel, at, UP_NEXT_WINDOW)?.let { it.next.firstProgramBefore(it.builtUntilMs) }
        } else {
            null
        }
    return (near ?: wider)?.let { bannerFor(onAir.channel, it, BannerLabel.UpNext, null, driftMs) }
}

private fun List<AiringBlock>.firstProgramBefore(limitMs: Long): AiringBlock? =
    firstOrNull { it.airing is Airing.Program && it.startMs < limitMs }

/** What a card without an "up next" says: the channel's number and name. */
internal fun ChannelOnAir.tag() = ChannelTag(channel.number, channel.name)
