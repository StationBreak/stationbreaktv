// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.model.schedule.Caps
import tv.stationbreak.core.model.schedule.CuratedTuning
import tv.stationbreak.core.model.schedule.FilmCollections
import tv.stationbreak.core.model.schedule.LeftoverPolicy
import tv.stationbreak.core.model.schedule.RuntimeFilter
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.SchedulerSettings
import tv.stationbreak.core.model.schedule.TitleKeeps
import tv.stationbreak.core.model.schedule.TitleWeights

internal const val MS_MINUTE = 60_000L
internal const val MS_HOUR = 3_600_000L
internal const val MS_DAY = 86_400_000L

/** The modes the scheduler builds so far: those of rules format version 1. */
internal val SCHEDULED_MODES: List<OrderingMode> =
    listOf(OrderingMode.Shuffle, OrderingMode.Chronological, OrderingMode.RandomInOrder)

/** Test helper. The settings type has no defaults, so a test that only cares about some fields uses this. */
@Suppress("LongParameterList")
internal fun testSettings(
    orderingMode: OrderingMode = OrderingMode.Shuffle,
    seed: Seed = Seed(1uL),
    blockLength: Int = 1,
    alignToGrid: Boolean = true,
    gridMinutes: Int = 30,
    epochHours: Int = 24,
    utcOffsetMinutes: Int = 0,
    startMinuteOfDay: Int = 360,
    graceHours: Int = 6,
    minRuntimeMs: Long = 60_000L,
    caps: Caps? = null,
    runtimeFilter: RuntimeFilter? = null,
    repeatGapMs: Long = 10_800_000L,
    pairUpToMs: Long = 2_100_000L,
    leftover: LeftoverPolicy = LeftoverPolicy.Gather,
) = SchedulerSettings(
    orderingMode = orderingMode,
    seed = seed,
    blockLength = blockLength,
    alignToGrid = alignToGrid,
    gridMinutes = gridMinutes,
    epochHours = epochHours,
    utcOffsetMinutes = utcOffsetMinutes,
    startMinuteOfDay = startMinuteOfDay,
    graceHours = graceHours,
    minRuntimeMs = minRuntimeMs,
    caps = caps,
    runtimeFilter = runtimeFilter,
    curated = CuratedTuning(repeatGapMs, pairUpToMs).takeIf { orderingMode == OrderingMode.Curated },
    leftover = leftover,
)

/** No film belongs to a collection: the v1 tests' side input. */
internal fun noFilms() = FilmCollections.of(emptyMap())

/** The v1 tests' group rules: no film is in a collection. */
internal fun noGroups() = GroupRules(noFilms(), TitleWeights.NONE)

/** No title has a weight above 1: the side input of every test that is not about the curated order. */
internal fun noWeights() = TitleWeights.NONE

/** No series is trimmed. */
internal fun noKeeps() = TitleKeeps.NONE

/** Rotation turns with no epoch limit that matters: the items of these tests are far shorter than a day. */
internal fun rotationTurnsOfADay(
    pool: List<ScheduleItem>,
    seed: Seed,
    caps: Caps,
    groups: GroupRules,
) = Orderings.rotationTurns(pool, seed, caps, groups, MS_DAY)
