// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.di

import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.data.jellyfin.JellyfinMediaServer
import javax.inject.Singleton

/**
 * Ruling R24: the adapter remembers which tokens are bound to which server, and the media call factory sends the
 * `Authorization` header only for a bound token. The session repository, every `MediaServer` injection and the media
 * data source must therefore share one adapter. Hilt gives them one only when the class is scoped, and no unit test can
 * build the Hilt graph, so the scope is what is checked here. MediaCallFactoryTest shows what an unshared adapter does.
 */
class OneAdapterTest {
    @Test
    fun theJellyfinAdapterIsOneSharedInstance() {
        assertTrue(JellyfinMediaServer::class.java.isAnnotationPresent(Singleton::class.java))
    }
}
