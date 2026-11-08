// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.di

import javax.inject.Qualifier

/**
 * The app's start alone (spec 26, Part 4): the selection itself, its resume and the gate the resume opens are bound
 * under this qualifier, which is internal to the app, so no feature or worker can inject any of them. They get the
 * selection's narrow ports, and wait on `SelectionReady`.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class AppStart
