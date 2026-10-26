// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide

import javax.inject.Qualifier

/**
 * Marks the `Boolean` that says whether this build writes the guide's measurement line. The app provides it from its
 * build type (debug and benchmark: true), since this library cannot tell a benchmark build from a release one.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class GuideMeasured
