// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.hours

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import tv.stationbreak.core.domain.ProEntitlement
import tv.stationbreak.core.domain.kids.KidsCap
import tv.stationbreak.core.domain.kids.KidsLock
import tv.stationbreak.core.domain.kids.KidsLockState
import java.io.IOException
import java.time.Instant
import java.util.TimeZone
import kotlin.time.Clock

/** Where this TV's hours schedule is kept (#789): one schedule, never sent anywhere. */
interface HoursStore {
    /** The stored schedule and each change of it; [HoursSchedule.EMPTY] when nothing is stored or it cannot be read. */
    val schedule: Flow<HoursSchedule>

    /** Replaces the schedule, atomically. Throws [IOException] when it could not be written; then nothing changed. */
    suspend fun save(schedule: HoursSchedule)
}

/** What editing the hours came to ([HoursOfOperation.edit]). */
enum class HoursEdit {
    Saved,

    /** Pro is off: hours are Pro. The stored schedule is untouched. */
    ProRequired,

    /** No PIN is set, so nobody could override the hours: set one first. */
    NoPin,

    /** The schedule could not be written. */
    Unavailable,
}

/**
 * The level that applies now, from the schedule's [period] level (null: no period, or the feature is inert) and the
 * Kids lock [lock] (ADR-0033). While the PIN has unlocked the TV (lock off with a PIN set) the PIN overrides the
 * period: grown-up access, for as long as the lock stays off (the idle fallback turns it on again). Otherwise the
 * more restrictive of the period and the lock's own cap wins; a lock that is off is no limit.
 */
fun effectiveAudience(
    period: AudienceLevel?,
    lock: KidsLockState,
): AudienceLevel {
    val unlocked = !lock.enabled && lock.pinSet
    val lockLevel = if (lock.enabled) lock.cap.level() else AudienceLevel.Adult
    return when {
        period == null -> lockLevel
        unlocked -> AudienceLevel.Adult
        else -> period.moreRestrictive(lockLevel)
    }
}

private fun KidsCap.level(): AudienceLevel =
    when (this) {
        KidsCap.Kids -> AudienceLevel.Kids
        KidsCap.Family -> AudienceLevel.Family
        KidsCap.Teen -> AudienceLevel.Teen
    }

/**
 * Hours of operation (#789, Pro): the weekly schedule, behind the Pro gate and under the PIN (ADR-0033).
 *
 * With Pro off the schedule is inert (no period applies, so the free behaviour is unchanged) and [edit] is refused, but
 * what is stored is kept, and applies again when Pro returns (ADR-0023: lock, never delete). [ProEntitlement.pro] is
 * read at each check. Time is the TV's local wall clock in [zone], read at each tick, so a DST change or a changed time
 * zone needs no handling of its own.
 */
class HoursOfOperation(
    private val store: HoursStore,
    private val pro: ProEntitlement,
    private val lock: KidsLock,
    private val clock: Clock = Clock.System,
    private val zone: () -> TimeZone = TimeZone::getDefault,
) {
    /** The stored schedule, whatever Pro says, for a screen that shows what Pro would apply. */
    val stored: Flow<HoursSchedule> get() = store.schedule

    /**
     * Replaces the schedule with [schedule]: [HoursEdit.ProRequired] without Pro, [HoursEdit.NoPin] with no PIN set.
     */
    suspend fun edit(schedule: HoursSchedule): HoursEdit =
        when {
            !pro.pro.value -> {
                HoursEdit.ProRequired
            }

            !lock.state.first().pinSet -> {
                HoursEdit.NoPin
            }

            else -> {
                try {
                    store.save(schedule)
                    HoursEdit.Saved
                } catch (_: IOException) {
                    HoursEdit.Unavailable
                }
            }
        }

    /**
     * The level that applies now, and each change of it: at every change of the schedule, of Pro, of the Kids lock, and
     * at the minute the local time crosses a period's edge (it looks again each minute). [AudienceLevel.OffAir] is the
     * off-the-air state the UI shows.
     */
    fun audience(): Flow<AudienceLevel> =
        combine(store.schedule, pro.pro, lock.state, minutes()) { schedule, isPro, kids, _ ->
            effectiveAudience(if (isPro) levelNow(schedule) else null, kids)
        }.distinctUntilChanged()

    private fun levelNow(schedule: HoursSchedule): AudienceLevel? {
        val local = Instant.ofEpochMilli(clock.now().toEpochMilliseconds()).atZone(zone().toZoneId())
        return schedule.levelAt(local.dayOfWeek.value, local.hour * MINUTES_PER_HOUR + local.minute)
    }

    private fun minutes(): Flow<Unit> =
        flow {
            while (true) {
                emit(Unit)
                delay(MINUTE_MS - clock.now().toEpochMilliseconds() % MINUTE_MS)
            }
        }
}

private const val MINUTE_MS = 60_000L
