// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import android.os.StrictMode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.domain.DeviceIdProvider
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.model.ClientIdentity
import tv.stationbreak.data.jellyfin.AdapterLog
import tv.stationbreak.data.jellyfin.JellyfinMediaServer
import java.util.concurrent.TimeUnit

/**
 * The Insignia crash: the server screen's Test button calls `discover` from `Dispatchers.Main.immediate`, and a body
 * that arrives after the headers was read on the main thread (`NetworkOnMainThreadException`). Here the main thread
 * runs under StrictMode network detection, which throws on any socket use, against a server that sends the headers and
 * then the body a byte at a time.
 */
@RunWith(AndroidJUnit4::class)
class MainThreadNetworkTest {
    private val server = MockWebServer()

    @Before
    fun setUp() {
        server.start()
        onMain {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy
                    .Builder()
                    .detectNetwork()
                    .penaltyDeathOnNetwork()
                    .build(),
            )
        }
    }

    @After
    fun tearDown() {
        onMain { StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.LAX) }
        server.close()
    }

    @Test
    fun discoverFromTheMainThreadNeverTouchesTheNetworkThere() {
        server.enqueue(
            MockResponse
                .Builder()
                .code(200)
                .body("""{"ServerName":"x","Id":"y","Version":"10.9.0","ProductName":"Jellyfin Server"}""")
                .throttleBody(1, 20, TimeUnit.MILLISECONDS)
                .build(),
        )
        val adapter =
            JellyfinMediaServer(
                OkHttpClient(),
                ClientIdentity("StationBreak", "Main-thread test", "0.0.0"),
                DeviceIdProvider { "main-thread-device" },
                AdapterLog.NONE,
            )
        val result =
            runBlocking {
                CoroutineScope(Dispatchers.Main.immediate)
                    .async { adapter.discover("http://127.0.0.1:${server.port}") }
                    .await()
            }
        assertTrue("discover: $result", result is ServerResult.Success)
    }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
}
