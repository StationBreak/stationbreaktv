// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.di

import javax.inject.Qualifier

/**
 * Marks the Media3 `DataSource.Factory` that reads media through the credentialed media client (ADR-0016, 9). A
 * qualifier, so no other data source in the graph (a plain HTTP one, or a file-opening default) can be injected into the
 * player by mistake.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class MediaDataSource
