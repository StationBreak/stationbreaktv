// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin.contract

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import tv.stationbreak.core.domain.DeviceIdProvider
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.model.CleartextAcknowledgement
import tv.stationbreak.core.model.ClientIdentity
import tv.stationbreak.core.model.ItemDetails
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.SignInRequest
import tv.stationbreak.data.jellyfin.AdapterLog
import tv.stationbreak.data.jellyfin.AdapterTuning
import tv.stationbreak.data.jellyfin.JellyfinMediaServer
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Spec 17: the item-details call (`GET Items/{id}?userId=<signed-in user>&fields=Overview,Genres`) returns the
 * overview, year, genres and runtime on the real server, through the adapter's own `itemDetails()`. The movies carry
 * the metadata [ServerSeeding] gives them. Failure messages name the step only: a body or a URL may carry a token.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class DetailsContractSuite {
    internal abstract fun startServer(image: String): JellyfinServer

    private lateinit var server: JellyfinServer
    private val identity = ClientIdentity("StationBreak", "Contract test", "0.0.0")

    @BeforeAll
    fun startContainers() {
        ContractEnvironment.requireDocker()
        server = startServer(ContractEnvironment.jellyfinImage())
    }

    @AfterAll
    fun stopContainers() {
        if (::server.isInitialized) server.close()
    }

    private suspend fun signedIn(): Pair<JellyfinMediaServer, Session> {
        val deviceId = "contract-details-${UUID.randomUUID()}"
        val adapter =
            JellyfinMediaServer(
                OkHttpClient(),
                identity,
                DeviceIdProvider { deviceId },
                AdapterLog.NONE,
                AdapterTuning(),
            )
        val address = server.address
        val acknowledgement = if (address.cleartextNeedsConfirmation) CleartextAcknowledgement(address) else null
        val account = server.viewer
        val result = adapter.signIn(SignInRequest(address, account.name, account.password(), acknowledgement))
        assertIs<ServerResult.Success<Session>>(result, "sign-in")
        return adapter to result.value
    }

    /** `RunTimeTicks` of [itemId] as the server holds it, read by the harness's own admin device. */
    private fun rawRunTimeTicks(itemId: String): Long {
        val token = server.http.signIn(server.admin.name, server.admin.rawPassword())
        val adminId =
            server.http
                .get("Users/Me", token)
                .obj()
                .getValue("Id")
                .jsonPrimitive.content
        val raw = server.http.expectOk("read item", server.http.get("Users/$adminId/Items/$itemId", token)).obj()
        return raw
            .getValue("RunTimeTicks")
            .jsonPrimitive.content
            .toLong()
    }

    @Test
    fun itemDetailsReturnsTheSeededMetadata() {
        runBlocking {
            val (adapter, session) = signedIn()
            val item = ItemId(server.seeded.movieIds[0])

            val result = adapter.itemDetails(session, item)

            assertIs<ServerResult.Success<ItemDetails>>(result, "itemDetails answered an error")
            val details = result.value
            assertEquals(item, details.id)
            assertEquals(ServerSeeding.MOVIE_OVERVIEW, details.overview)
            assertEquals(ServerSeeding.MOVIE_YEAR, details.year)
            assertEquals(ServerSeeding.MOVIE_GENRES, details.genres)
            val runtime = assertNotNull(details.runtime, "the default response carries no RunTimeTicks")
            assertTrue(runtime > Duration.ZERO, "the runtime should be positive")
            // The server's own figure, read raw with the harness's device: the adapter converts ticks to milliseconds.
            val ticks = rawRunTimeTicks(item.value)
            assertEquals((ticks / TICKS_PER_MS).milliseconds, runtime, "runtime against the raw RunTimeTicks")
        }
    }

    @Test
    fun itemDetailsForAnUnknownItemIsAFailureNotAnException() {
        runBlocking {
            val (adapter, session) = signedIn()

            val result = adapter.itemDetails(session, ItemId(UNKNOWN_ITEM))

            assertIs<ServerResult.Failure>(result, "an unknown item should be a failure")
            assertIs<MediaServerError.NotFound>(result.error, "an unknown item should read as not found")
        }
    }

    private companion object {
        /** A well-formed item ID no server has. */
        const val UNKNOWN_ITEM = "0badc0de0badc0de0badc0de0badc0de"
        const val TICKS_PER_MS = 10_000L
    }
}

/** The details suite against a server on its own port. */
class DirectDetailsContractTest : DetailsContractSuite() {
    override fun startServer(image: String): JellyfinServer = JellyfinServer.direct(image)
}
