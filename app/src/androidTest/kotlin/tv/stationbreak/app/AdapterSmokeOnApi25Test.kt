// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.domain.DeviceIdProvider
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.model.ClientIdentity
import tv.stationbreak.core.model.SignInRequest
import tv.stationbreak.data.jellyfin.AdapterLog
import tv.stationbreak.data.jellyfin.JellyfinMediaServer

/**
 * F0.3 task 2.13 (ADR-0006, logging): the adapter makes a probe, a password sign-in and a failing call on the API 25
 * emulator, with the app's runtime classpath (which carries `slf4j-nop`). It covers what the JVM tests can't: the
 * adapter's OkHttp, kotlinx.serialization and error-mapping code (which loads the Jellyfin SDK's exception types) on
 * the old platform's class loader and TLS stack. A raw loopback socket plays the server; nothing here is a real
 * credential or address.
 */
@RunWith(AndroidJUnit4::class)
class AdapterSmokeOnApi25Test {
    @Test
    fun probesSignsInAndMapsAFailureOnApi25() {
        LoopbackJellyfin().use { server ->
            val adapter =
                JellyfinMediaServer(
                    OkHttpClient(),
                    ClientIdentity("StationBreak", "Smoke test", "0.0.0"),
                    DeviceIdProvider { "smoke-device" },
                    AdapterLog.NONE,
                )
            LoopbackJellyfin.blocking {
                val probed = adapter.discover("http://127.0.0.1:${server.port}")
                assertTrue("probe: $probed", probed is ServerResult.Success)
                val address = (probed as ServerResult.Success).value.address

                val request = SignInRequest(address, "smoke", "not-a-real-password".toCharArray(), null)
                val signedIn = adapter.signIn(request)
                assertTrue("sign-in: $signedIn", signedIn is ServerResult.Success)
                assertEquals("smoke-user", (signedIn as ServerResult.Success).value.userId.value)

                // Nothing listens on the closed port: the mapper (and the SDK exception types it reads) must load.
                server.close()
                val refused = adapter.discover("http://127.0.0.1:${server.port}")
                assertTrue("refused: $refused", refused is ServerResult.Failure)
                assertTrue((refused as ServerResult.Failure).error is MediaServerError)
            }
        }
    }
}
