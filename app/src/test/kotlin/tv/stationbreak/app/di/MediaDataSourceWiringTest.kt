// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.di

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.Call
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.core.domain.DeviceIdProvider
import tv.stationbreak.core.domain.InMemorySecretStore
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.model.ClientIdentity
import tv.stationbreak.data.jellyfin.AdapterLog
import tv.stationbreak.data.jellyfin.JellyfinMediaServer

/**
 * #214: the player's data source must be Media3's OkHttp source over the adapter's `mediaCallFactory`, the only thing
 * that adds the `Authorization` header to stream requests and strips `api_key`. A `DefaultHttpDataSource`, a bare
 * `OkHttpClient` or a `DefaultDataSource` wrapper would each send streams
 * without the header (or open `file://`), and no other test would notice. Opening a `DataSpec` needs `android.net.Uri`,
 * so the factory's call factory is read by reflection instead.
 */
@UnstableApi
class MediaDataSourceWiringTest {
    private val identity = ClientIdentity("StationBreak", "TV", "0.0.1")
    private val server = JellyfinMediaServer(OkHttpClient(), identity, DeviceIdProvider { "d" }, AdapterLog.NONE)

    @Test
    fun theMediaDataSourceIsAnOkHttpSourceOverTheMediaCallFactory() {
        val factory = PlayerModule.mediaDataSource(server, SessionRepository(InMemorySecretStore(), server))

        assertEquals(OkHttpDataSource.Factory::class.java, factory.javaClass)
        val field = OkHttpDataSource.Factory::class.java.declaredFields.single { it.type == Call.Factory::class.java }
        field.isAccessible = true
        val calls = field.get(factory)
        assertEquals("MediaCallFactory", calls.javaClass.simpleName)
    }
}
