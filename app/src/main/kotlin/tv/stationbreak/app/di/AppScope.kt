// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/** The app-lifetime [CoroutineScope], for work that is started from a caller's path and must outlive it. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class AppScope

/** Binds the [AppScope]: a supervisor over the default dispatcher, so one failed child does not end the others. */
@Module
@InstallIn(SingletonComponent::class)
internal object AppScopeModule {
    @Provides
    @Singleton
    @AppScope
    @Suppress("InjectDispatcher") // A root scope for the process, with nothing to inject a dispatcher into.
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
