// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import tv.stationbreak.app.net.PinSyncingServerSettings
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.ServerSettings
import tv.stationbreak.data.jellyfin.BaseHttpClient
import tv.stationbreak.data.jellyfin.HttpClientSource
import tv.stationbreak.data.jellyfin.JellyfinMediaServer
import tv.stationbreak.data.jellyfin.trust.PinRegistry
import tv.stationbreak.data.jellyfin.trust.PinnedHttpClients
import javax.inject.Inject
import kotlin.time.Clock

/**
 * F0.3 task 3.13: the Hilt graph resolves the port to the Jellyfin adapter, the base `OkHttpClient` (qualified, so the
 * client with system trust can't be confused with F0.5's pinned one) and `Clock.System`, on the API 25 emulator. No
 * screen consumes them yet.
 */
@HiltAndroidTest
class MediaServerInjectionTest {
    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject lateinit var mediaServer: MediaServer

    @Inject lateinit var adapter: JellyfinMediaServer

    @Inject @BaseHttpClient
    lateinit var baseClient: HttpClientSource

    @Inject lateinit var pinRegistry: PinRegistry

    @Inject lateinit var serverSettings: ServerSettings

    @Inject lateinit var pinnedClients: PinnedHttpClients

    @Inject lateinit var clock: Clock

    @Before
    fun inject() = hilt.inject()

    @Test
    fun theGraphResolvesThePortToTheAdapter() {
        assertTrue("MediaServer is ${mediaServer::class.java.name}", mediaServer is JellyfinMediaServer)
    }

    /** Spec 13: one adapter, so the media client is cleared by the same sign-in as the API calls (ADR-0016). */
    @Test
    fun thePortAndTheMediaClientShareOneAdapter() {
        assertTrue(mediaServer === adapter)
    }

    @Test
    fun theBaseClientIsThePinnedClientWithNoInterceptorsOfItsOwn() {
        assertTrue(baseClient === pinnedClients)
        assertNotNull(baseClient.current().sslSocketFactory)
        assertEquals(emptyList<Any>(), baseClient.current().interceptors)
    }

    @Test
    fun thePinRegistryAndTheStoredPinAreTheSameClient() {
        assertTrue(pinRegistry === pinnedClients)
        assertTrue("ServerSettings is $serverSettings", serverSettings is PinSyncingServerSettings)
    }

    @Test
    fun theClockIsTheSystemClock() {
        assertTrue(clock === Clock.System)
    }
}
