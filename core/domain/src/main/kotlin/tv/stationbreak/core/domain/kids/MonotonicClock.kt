// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.kids

/**
 * Time that only moves forward while the device is on and is not set by anyone (`SystemClock.elapsedRealtime()` on
 * Android): the Kids Mode lockout (#789) counts against it as well as the wall clock, so setting the wall clock
 * forward cannot end a lockout early. It starts again from near 0 at each boot.
 */
fun interface MonotonicClock {
    /** Milliseconds since the device booted, counting sleep. */
    fun elapsedMillis(): Long
}
