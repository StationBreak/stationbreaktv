// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.engine.GroupCollections
import tv.stationbreak.core.engine.Pool
import tv.stationbreak.core.engine.PoolPolicy
import tv.stationbreak.core.engine.lineup.KidStatus
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.schedule.FilmCollections
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.SchedulerSettings
import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.scheduler.EpochPool

/**
 * One channel's pool and side map for one epoch from one snapshot: the single path LineupDays and the preview share.
 */
internal class RuledDay(
    val pool: Pool,
    val day: List<ScheduleItem>,
    val films: FilmCollections,
)

/** Rules to evaluate, and the policy the pool is narrowed by (ADR-0019, ADR-0020). */
internal data class PolicyRules(
    val rules: ChannelRules,
    val policy: PoolPolicy,
)

/** [rules] as [channel] airs them: under its scheduled ceiling and its own policy. The one place that decides both. */
internal fun Ceilings.policyRules(
    rules: ChannelRules,
    channel: LineupChannel,
): PolicyRules = PolicyRules(rules.copy(maxRating = of(channel)), KidStatus.policyOf(channel))

/**
 * The day of [channel] under [rules] for epoch [n], from [snapshot]. The ceiling is [ceilings]' and the policy is
 * `KidStatus.policyOf(channel)`; nothing else decides them. [evaluate] is the pool provider: LineupDays passes its
 * cache (ruled and withheld pools share it), the preview a plain evaluator call. The film-to-collection map is of the
 * very snapshot the pool was evaluated from.
 */
@Suppress("LongParameterList") // The day's inputs, one for one.
internal fun ruledDayOf(
    ceilings: Ceilings,
    rules: ChannelRules,
    channel: LineupChannel,
    snapshot: RuleSnapshot,
    settings: SchedulerSettings,
    n: Long,
    evaluate: (ChannelRules, PoolPolicy) -> Pool,
): RuledDay {
    val (ruled, policy) = ceilings.policyRules(rules, channel)
    val pool = evaluate(ruled, policy)
    val day = EpochPool.build(settings, pool.items, n)
    return RuledDay(pool, day, GroupCollections.of(ruled, snapshot, day))
}
