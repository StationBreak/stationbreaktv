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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import tv.stationbreak.app.net.AndroidSystemTrust
import tv.stationbreak.app.net.PinSyncingServerSettings
import tv.stationbreak.core.domain.AccountCache
import tv.stationbreak.core.domain.DeviceIdProvider
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.SecretStore
import tv.stationbreak.core.domain.ServerSettings
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.domain.StoredDeviceIdProvider
import tv.stationbreak.core.domain.details.ProgramDetailsCache
import tv.stationbreak.core.model.ClientIdentity
import tv.stationbreak.data.jellyfin.AdapterLog
import tv.stationbreak.data.jellyfin.BaseHttpClient
import tv.stationbreak.data.jellyfin.HttpClientSource
import tv.stationbreak.data.jellyfin.JellyfinMediaServer
import tv.stationbreak.data.jellyfin.trust.PinRegistry
import tv.stationbreak.data.jellyfin.trust.PinnedHttpClients
import tv.stationbreak.data.settings.keystore.KeystoreSecretStore
import tv.stationbreak.data.settings.server.FileServerSettings
import javax.inject.Singleton
import kotlin.time.Clock

/**
 * The bindings for the media server port (ADR-0005): the Jellyfin adapter behind [MediaServer], the pinned base client
 * it derives its own from, the stored server record, the Keystore-backed secret store (ADR-0009) and the system clock
 * (ADR-0004). The `[KMP]` and `[JVM]` modules hold no Hilt; they are wired here.
 */
@Module
@InstallIn(SingletonComponent::class)
internal interface MediaServerModule {
    @Binds
    fun mediaServer(adapter: JellyfinMediaServer): MediaServer

    companion object {
        /**
         * The one pinned client (S0.5.2, Angua (Security)): system trust through the Network Security Config's trust
         * manager, plus an exact-host SPKI pin when the user has trusted a self-signed certificate. The adapter drops
         * its interceptors, cookie jar and authenticators and keeps its trust configuration. It is replaced whenever a
         * pin changes, so it is handed out through [HttpClientSource], never as a client to keep.
         */
        @Provides
        @Singleton
        fun pinnedHttpClients(): PinnedHttpClients = PinnedHttpClients(AndroidSystemTrust(), OkHttpClient())

        @Provides
        @BaseHttpClient
        fun baseHttpClient(clients: PinnedHttpClients): HttpClientSource = clients

        @Provides
        fun pinRegistry(clients: PinnedHttpClients): PinRegistry = clients

        /**
         * The stored server record (address and pin), on a file out of backup. It keeps the pinned client in step with
         * what is stored, and its first read applies the stored pin.
         */
        @Provides
        @Singleton
        fun serverSettings(
            @ApplicationContext context: Context,
            clients: PinnedHttpClients,
        ): ServerSettings = PinSyncingServerSettings(FileServerSettings(context), clients)

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

        /**
         * The one stored session (F0.4): sign-in saves through it, and launch routing (task 4.0) restores via it. It
         * tells the saved library (F0.6) who is signed in and who signed out, so another account's copy never lingers.
         */
        @Provides
        @Singleton
        fun sessionRepository(
            store: SecretStore,
            server: MediaServer,
            library: AccountCache,
        ): SessionRepository = SessionRepository(store, server, library)

        /**
         * Program details fetched on demand (spec 17), kept per account in memory. It runs its requests in its own
         * supervised scope, so a screen that goes away never cancels a request another screen is waiting on.
         */
        @Provides
        @Singleton
        @Suppress("InjectDispatcher") // The cache's own scope: a root, with nothing to inject a dispatcher into.
        fun programDetails(
            server: MediaServer,
            sessions: SessionRepository,
        ): ProgramDetailsCache {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            return ProgramDetailsCache(server, sessions, scope)
        }

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
