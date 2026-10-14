// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.OkHttpClient
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.api.operations.SystemApi
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ADR-0006: the Jellyfin SDK logs through SLF4J, and on Android a request made without a binding on the runtime
 * classpath crashes with `NoClassDefFoundError: org.slf4j.LoggerFactory`. The adapter doesn't make its calls through
 * the SDK's client, but the SDK stays on the classpath, so this makes one real SDK request on the API 25 emulator
 * and pins that the app ships `slf4j-nop` (F0.3 Unit 3 records the red run, with the binding left out).
 */
@RunWith(AndroidJUnit4::class)
class SdkLoggingBindingTest {
    @Test
    fun anSdkRequestRunsBecauseTheAppShipsAnSlf4jBinding() {
        LoopbackJellyfin().use { server ->
            val factory = OkHttpFactory(OkHttpClient())
            val api =
                factory.create(
                    baseUrl = "http://127.0.0.1:${server.port}",
                    accessToken = null,
                    clientInfo = ClientInfo("StationBreak", "0.0.0"),
                    deviceInfo = DeviceInfo("sdk-binding-test", "Binding test"),
                    httpClientOptions = HttpClientOptions(),
                    socketConnectionFactory = factory,
                )

            val info = LoopbackJellyfin.blocking { SystemApi(api).getPublicSystemInfo().content }

            assertEquals("Smoke", info.serverName)
        }
    }
}
