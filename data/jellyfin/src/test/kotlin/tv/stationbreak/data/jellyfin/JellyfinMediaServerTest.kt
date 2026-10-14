// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.errorOrNull
import tv.stationbreak.core.model.CleartextAcknowledgement
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The adapter's own rules around the probe: bad input and the cleartext gate send nothing at all. */
class JellyfinMediaServerTest {
    private val calls = AtomicInteger()

    private val counting =
        OkHttpClient
            .Builder()
            .addInterceptor(
                Interceptor {
                    calls.incrementAndGet()
                    throw IOException("the test client doesn't connect")
                },
            ).build()

    private val adapter = JellyfinMediaServer(counting)

    private val publicHttp = ServerAddress(Scheme.Http, "media.example.com", 8096, "/jf")

    @Test
    fun invalidInputIsAnInvalidAddressAndSendsNothing() =
        runBlocking {
            for (input in listOf("", "user:pw@host", "ftp://host", "127.1", "host:99999")) {
                assertIs<MediaServerError.InvalidAddress>(adapter.discover(input).errorOrNull(), input)
            }
            assertEquals(0, calls.get())
        }

    @Test
    fun connectToCleartextOnAPublicHostWithoutAnAcknowledgementSendsNothing() =
        runBlocking {
            assertIs<MediaServerError.CleartextNotConfirmed>(adapter.connect(publicHttp).errorOrNull())
            assertEquals(0, calls.get())
        }

    @Test
    fun anAcknowledgementForAnotherAddressSendsNothingEither() =
        runBlocking {
            val others =
                listOf(
                    publicHttp.copy(host = "other.example.com"),
                    publicHttp.copy(port = 8097),
                    publicHttp.copy(scheme = Scheme.Https),
                    publicHttp.copy(subpath = ""),
                )
            for (other in others) {
                val error = adapter.connect(publicHttp, CleartextAcknowledgement(other)).errorOrNull()
                assertIs<MediaServerError.CleartextNotConfirmed>(error, other.toString())
            }
            assertEquals(0, calls.get())
        }

    @Test
    fun theMatchingAcknowledgementLetsTheProbeGoOut() =
        runBlocking {
            // The test client refuses to connect, so what proves the gate opened is that a call was attempted.
            adapter.connect(publicHttp, CleartextAcknowledgement(publicHttp))
            assertEquals(1, calls.get())
        }

    @Test
    fun aPrivateOrHttpsAddressNeedsNoAcknowledgement() =
        runBlocking {
            adapter.connect(ServerAddress(Scheme.Http, "192.168.1.10", 8096))
            adapter.connect(ServerAddress(Scheme.Https, "media.example.com", 443))
            assertEquals(2, calls.get())
        }
}
