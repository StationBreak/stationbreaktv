// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

// Canary (never merged): seven parameters must fail detekt's LongParameterList.
internal fun canarySum(
    a: Int,
    b: Int,
    c: Int,
    d: Int,
    e: Int,
    f: Int,
    g: Int,
): Int = a + b + c + d + e + f + g
