// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.kids

/** A [MonotonicClock] a test moves by hand; [reboot] starts it again from [ms]. */
class FakeMonotonicClock(
    var ms: Long = 5_000_000L,
) : MonotonicClock {
    override fun elapsedMillis(): Long = ms

    fun reboot(at: Long = 1_000L) {
        ms = at
    }
}
