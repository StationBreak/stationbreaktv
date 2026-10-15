// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.di

import android.content.Context
import android.os.Build
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import tv.stationbreak.core.domain.DeviceIdProvider
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.SecretStore
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.domain.StoredDeviceIdProvider
import tv.stationbreak.core.model.ClientIdentity
import tv.stationbreak.data.jellyfin.AdapterLog
import tv.stationbreak.data.jellyfin.BaseHttpClient
import tv.stationbreak.data.jellyfin.JellyfinMediaServer
import tv.stationbreak.data.settings.keystore.KeystoreSecretStore
import javax.inject.Singleton
import kotlin.time.Clock

/**
 * The bindings for the media server port (ADR-0005): the Jellyfin adapter behind [MediaServer], the base client it
 * derives its own from, the Keystore-backed secret store (ADR-0009) and the system clock (ADR-0004). The `[KMP]` and
 * `[JVM]` modules hold no Hilt; they are wired here.
 */
@Module
@InstallIn(SingletonComponent::class)
internal interface MediaServerModule {
    @Binds
    fun mediaServer(adapter: JellyfinMediaServer): MediaServer

    companion object {
        /**
         * System trust only. F0.5 replaces this with the per-server pinned client (S0.5.2); the adapter drops this
         * client's interceptors, cookie jar and authenticators and keeps its trust configuration.
         */
        @Provides
        @Singleton
        @BaseHttpClient
        fun baseHttpClient(): OkHttpClient = OkHttpClient()

        @Provides
        fun clock(): Clock = Clock.System

        @Provides
        @Singleton
        fun identity(
            @ApplicationContext context: Context,
        ): ClientIdentity {
            val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
            return ClientIdentity("StationBreak", Build.MODEL, version ?: "0")
        }

        /**
         * The Keystore-backed store (ADR-0009): the session and the device ID survive a restart and fail closed. No
         * screen can consume [MediaServer] without real storage (#213).
         */
        @Provides
        @Singleton
        fun secretStore(
            @ApplicationContext context: Context,
        ): SecretStore = KeystoreSecretStore(context)

        /** The one stored session (F0.4): sign-in saves through it, and launch routing (task 4.0) restores via it. */
        @Provides
        @Singleton
        fun sessionRepository(
            store: SecretStore,
            server: MediaServer,
        ): SessionRepository = SessionRepository(store, server)

        @Provides
        @Singleton
        fun deviceIds(store: SecretStore): DeviceIdProvider = StoredDeviceIdProvider(store)

        /**
         * Silent for now, but already behind the redactor (F0.4): whichever sink a later task plugs in sees only masked
         * lines and error messages. The sink goes here, inside the decorator, never around it.
         */
        @Provides
        fun adapterLog(): AdapterLog = AdapterLog.redacting(AdapterLog.NONE)
    }
}
