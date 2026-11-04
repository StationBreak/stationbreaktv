// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.engine.toSchedulerSettings
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.rules.ChannelRules

// The day-boundary machinery (spec 23, D1, F2): what the planner and the edits change for an unedited starter, for the
// curated on-set and for "Use my collections" starts at the next broadcast-day start, never in the middle of a day.

/**
 * The rules for the broadcast day that starts at [dayStartMs]: the previous rules before
 * [ChannelSource.Starter.rulesFromMs], else the current ones.
 */
fun ChannelSource.Starter.rulesFor(dayStartMs: Long): ChannelRules {
    val from = rulesFromMs
    val previous = previousRules
    return if (from != null && previous != null && dayStartMs < from) previous else rules
}

/**
 * The "Use my collections" flag in force for the broadcast day that starts at [dayStartMs]: the flag before the
 * basis's boundary was [CurationBasis.household], from it on [Lineup.useMyCollections].
 */
fun Lineup.householdFor(dayStartMs: Long): Boolean {
    val basis = curationBasis
    return if (basis != null && dayStartMs < basis.fromMs) basis.household else useMyCollections
}

/**
 * The lineup with what is past at [nowMs] taken off, and [this] when nothing is: an untouched starter that was to
 * leave ([LineupChannel.leavesAtMs] at or before [nowMs]) goes to the shelf, previous rules whose boundary has passed
 * are dropped, and a basis whose boundary has passed is cleared. Pure and idempotent.
 *
 * Dropping a starter's previous rules changes its day key once (they sit inside its source, which feeds every day
 * key), so its days rebuild. That is accepted and deterministic: the rules for every day after the boundary are the
 * same before and after.
 */
fun Lineup.rolled(nowMs: Long): Lineup {
    val basis = curationBasis
    val basisPast = basis != null && basis.fromMs <= nowMs
    // Checked first, so a lineup with nothing past (nearly always) allocates nothing.
    if (!basisPast && channels.none { it.isPast(nowMs) }) return this
    return copy(channels = channels.map { it.rolled(nowMs) }, curationBasis = if (basisPast) null else basis)
}

private fun LineupChannel.isPast(nowMs: Long): Boolean {
    val source = source
    return leavesAtMs.let { it != null && it <= nowMs } ||
        (source is ChannelSource.Starter && source.rulesFromMs.let { it != null && it <= nowMs })
}

private fun LineupChannel.rolled(nowMs: Long): LineupChannel {
    val leaves = leavesAtMs.let { it != null && it <= nowMs }
    val source = source
    val dropsPrevious = source is ChannelSource.Starter && source.rulesFromMs.let { it != null && it <= nowMs }
    if (!leaves && !dropsPrevious) return this
    var next = this
    if (leaves) {
        next = next.copy(state = if (state == ChannelState.On) ChannelState.Shelf else state, leavesAtMs = null)
    }
    if (dropsPrevious) next = next.copy(source = source.withoutPrevious())
    return next
}

/**
 * This starter with [rules] as its rules, starting at [boundaryMs], for a starter that is on the air: nothing of one
 * that is not on today (a shelved starter being switched on) needs protecting, so that one takes new rules at once
 * ([ChannelSource.Starter.withRulesNow]). [boundaryMs] must be after [nowMs] and a broadcast-day start of the
 * starter's own timing. [rules] must keep the current timing (a timing
 * change is not a re-resolve) and the starter must be unedited. Equal to the current rules: unchanged. While a
 * boundary is pending ([ChannelSource.Starter.rulesFromMs] after [nowMs]) the previous rules stay and the rules are
 * replaced, so a second change before the boundary does not show today a third version; a change back to the previous
 * rules drops them. Otherwise the current rules become the previous ones until [boundaryMs].
 */
internal fun ChannelSource.Starter.withNewRules(
    rules: ChannelRules,
    nowMs: Long,
    boundaryMs: Long,
): ChannelSource.Starter {
    require(!edited) { "Only an unedited starter follows new rules" }
    require(rules.timing == this.rules.timing) { "New rules keep the current timing" }
    require(boundaryMs > nowMs) { "The boundary is after now" }
    require(isDayStart(this.rules, boundaryMs)) { "The boundary is a broadcast-day start" }
    if (rules == this.rules) return this
    val pending = rulesFromMs.let { it != null && it > nowMs }
    return when {
        pending && rules == previousRules -> copy(rules = rules).withoutPrevious()
        pending -> copy(rules = rules)
        else -> copy(rules = rules).withPrevious(this.rules, boundaryMs)
    }
}

// The epoch arithmetic of docs/rules-format.md ("Timing"): a day starts where (t + offset - start) is a whole epoch.
private fun isDayStart(
    rules: ChannelRules,
    t: Long,
): Boolean {
    val settings = rules.toSchedulerSettings()
    return (t + settings.offsetMs - settings.startMs).mod(settings.epochMs) == 0L
}

/**
 * [change], a lineup made from [this] at [nowMs], with the basis that keeps the days before [boundaryMs] as they were
 * when its curated on-set or "Use my collections" differs from [this]'s. A basis still pending is kept (the days
 * before its boundary are already those of the oldest on-set), and so is [change] when neither differs.
 */
internal fun Lineup.withBasisFor(
    change: Lineup,
    nowMs: Long,
    boundaryMs: Long,
): Lineup {
    val pending = curationBasis.let { it != null && it.fromMs > nowMs }
    val before = curatedOnSet()
    val differs = before != change.curatedOnSet() || useMyCollections != change.useMyCollections
    if (pending || !differs) return change
    return change.copy(curationBasis = CurationBasis(boundaryMs, before, useMyCollections))
}

// The channels the curation homes titles on: on, not leaving, unedited starters whose rules are programmed.
private fun Lineup.curatedOnSet(): Set<ChannelId> =
    channels
        .filter { channel ->
            val source = channel.source
            channel.state == ChannelState.On &&
                channel.leavesAtMs == null &&
                source is ChannelSource.Starter &&
                !source.edited &&
                source.rules.orderingMode == OrderingMode.Curated
        }.mapTo(HashSet()) { it.id }
