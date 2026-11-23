// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.hours

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tv.stationbreak.core.domain.kids.KidsCap
import tv.stationbreak.core.domain.kids.KidsLockState
import tv.stationbreak.core.domain.kids.KidsLockView

/**
 * The hours of operation enforced (#789, ADR-0033 "The seam"): what the lineup and schedule filters and live TV read
 * in place of the Kids lock alone. Its state is the effective [HoursOfOperation.audience]: the more restrictive of the
 * period now and the lock, the PIN overriding the period. A level with a cap ([AudienceLevel.cap]) is the lock on at
 * that cap; [AudienceLevel.Adult] is no limit; [AudienceLevel.OffAir] allows no channel ([KidsLockState.offAir]).
 *
 * Nothing is hidden for good: the filters only leave channels out of what is shown (ADR-0023: lock, never delete), so
 * the channels return when the period ends, and with Pro off or no schedule this reads exactly as the lock does.
 */
class AudienceLockView(
    hours: HoursOfOperation,
) : KidsLockView {
    override val state: Flow<KidsLockState> = hours.audience().map(::lockStateOf)
}

internal fun lockStateOf(level: AudienceLevel): KidsLockState =
    when (level) {
        AudienceLevel.Adult -> KidsLockState(enabled = false, cap = KidsCap.DEFAULT, pinSet = false)
        AudienceLevel.OffAir -> KidsLockState(enabled = true, cap = KidsCap.DEFAULT, pinSet = false, offAir = true)
        else -> KidsLockState(enabled = true, cap = level.cap ?: KidsCap.DEFAULT, pinSet = false)
    }
