// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.data.jellyfin.BaseHttpClient
import tv.stationbreak.data.jellyfin.JellyfinMediaServer
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

    @Inject @BaseHttpClient
    lateinit var baseClient: OkHttpClient

    @Inject lateinit var clock: Clock

    @Before
    fun inject() = hilt.inject()

    @Test
    fun theGraphResolvesThePortToTheAdapter() {
        assertTrue("MediaServer is ${mediaServer::class.java.name}", mediaServer is JellyfinMediaServer)
    }

    @Test
    fun theBaseClientHasSystemTrustAndNoInterceptorsOfItsOwn() {
        assertNotNull(baseClient.sslSocketFactory)
        assertEquals(emptyList<Any>(), baseClient.interceptors)
    }

    @Test
    fun theClockIsTheSystemClock() {
        assertTrue(clock === Clock.System)
    }
}
