// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.engine.toSchedulerSettings
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
 *
 * [nowMs] is the device clock. A clock that jumps forward (set wrong, then put right) rolls what is pending early:
 * the new rules, the leaving starters and the new on-set and flag then apply from that moment instead of at their
 * boundary. That is accepted: it is deterministic (the same lineup and time give the same result on every TV) and
 * recoverable (nothing is lost; the next plan works from what the lineup holds then).
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
    if (rules == this.rules) return this
    // Checked only for a change: no change needs a boundary.
    require(boundaryMs > nowMs) { "The boundary is after now" }
    require(isDayStart(this.rules, boundaryMs)) { "The boundary is a broadcast-day start" }
    val pending = rulesFromMs.let { it != null && it > nowMs }
    return when {
        pending && rules == previousRules -> copy(rules = rules).withoutPrevious()
        pending -> copy(rules = rules)
        else -> copy(rules = rules).withPrevious(this.rules, boundaryMs)
    }
}

/**
 * Whether [t] is the start of a broadcast day of [rules]' timing, by the epoch arithmetic of docs/rules-format.md
 * ("Timing"): a day starts where (t + offset - start) is a whole epoch. The offset is the fixed one the rules hold, so
 * a daylight-saving change moves nothing. Public so `core:domain`, which sees the scheduler's `Epochs`, can check it
 * against `Epochs.epochStart`; the engine itself does not depend on the scheduler.
 */
fun isDayStart(
    rules: ChannelRules,
    t: Long,
): Boolean {
    val settings = rules.toSchedulerSettings()
    return (t + settings.offsetMs - settings.startMs).mod(settings.epochMs) == 0L
}

/**
 * [change], a lineup made from [this] at [nowMs], with the basis that keeps the days before [boundaryMs] as they were
 * when its curated on-set or "Use my collections" differs from [this]'s. The on-sets are the shared ones
 * ([curatedOn], with [recipeKeys], the catalog's recipe keys): [this]'s of today (the day that ends at [boundaryMs])
 * and [change]'s of the day that starts at [boundaryMs], so a starter that starts at the boundary is only in the second
 * and one that leaves at it only in the first. A basis still pending is kept (the days before its boundary are already
 * those of the oldest on-set), and so is [change] when neither differs.
 */
internal fun Lineup.withBasisFor(
    change: Lineup,
    nowMs: Long,
    boundaryMs: Long,
    recipeKeys: Set<String>,
): Lineup {
    val pending = curationBasis.let { it != null && it.fromMs > nowMs }
    // Worked out only when no basis is pending (nearly every edit and plan of a day after its first change).
    val before = if (pending) null else curatedOn(LineupDay.endingAt(boundaryMs), recipeKeys)
    val differs =
        before != null &&
            (
                before != change.curatedOn(LineupDay.startingAt(boundaryMs), recipeKeys) ||
                    useMyCollections != change.useMyCollections
            )
    val basis = if (differs) CurationBasis(boundaryMs, checkNotNull(before), useMyCollections) else null
    return if (basis != null) change.copy(curationBasis = basis) else change
}
