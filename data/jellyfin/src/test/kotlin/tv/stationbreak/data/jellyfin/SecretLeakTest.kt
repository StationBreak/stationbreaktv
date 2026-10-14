// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.QuickConnectState
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.errorOrNull
import tv.stationbreak.core.domain.getOrNull
import tv.stationbreak.core.model.AccessToken
import tv.stationbreak.core.model.QuickConnectSecret
import tv.stationbreak.core.model.SecretAccess
import tv.stationbreak.core.model.SignInRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R4, R20 and Success Metric 2: the known fake password, token and Quick Connect secret appear in no string form, no
 * log line and no request URL. A failure names *where* the secret turned up and never prints the text searched.
 */
@OptIn(SecretAccess::class)
class SecretLeakTest {
    private val jellyfin = FakeJellyfin()
    private val password = fake("password")
    private val token = fake("token")
    private val secret = fake("qc-secret")
    private val logged = CopyOnWriteArrayList<String>()
    private val adapter = testAdapter(log = { logged += it })

    @AfterTest
    fun tearDown() = jellyfin.close()

    private fun assertClean(
        where: String,
        text: String,
        vararg secrets: String,
    ) {
        for (value in secrets) assertTrue(!text.contains(value), "a secret reached $where")
    }

    private suspend fun signInError(address: tv.stationbreak.core.model.ServerAddress = jellyfin.address) =
        adapter.signIn(SignInRequest(address, "alice", password.toCharArray(), null)).errorOrNull()

    @Test
    fun noSecretReachesAnyStringLogOrUrl() =
        runBlocking {
            jellyfin.on("POST", "/Users/AuthenticateByName") { jellyfin.json(authResult(token, admin = true)) }
            jellyfin.on("GET", "/QuickConnect/Enabled") { jellyfin.json("true") }
            jellyfin.on("POST", "/QuickConnect/Initiate") {
                jellyfin.json("{\"Authenticated\":false,\"Secret\":\"$secret\",\"Code\":\"123456\"}")
            }
            jellyfin.on("GET", "/QuickConnect/Connect") {
                jellyfin.json("{\"Authenticated\":true,\"Secret\":\"$secret\",\"Code\":\"123456\"}")
            }
            jellyfin.on("POST", "/Users/AuthenticateWithQuickConnect") { jellyfin.json(authResult(token)) }
            jellyfin.on("POST", "/Sessions/Logout") { jellyfin.status(401) }

            val request = SignInRequest(jellyfin.address, "alice", password.toCharArray(), null)
            val requestText = request.toString()
            val session = adapter.signIn(request).getOrNull()!!
            val states = adapter.quickConnect(jellyfin.address, null).toList()
            val errors = mutableListOf<MediaServerError>()
            adapter.revoke(session).errorOrNull()?.let(errors::add)
            jellyfin.on("POST", "/Users/AuthenticateByName") { jellyfin.status(401) }
            signInError()?.let(errors::add)
            jellyfin.on("POST", "/Users/AuthenticateByName") { jellyfin.redirect("http://127.0.0.1:1/") }
            signInError()?.let(errors::add)
            signInError(jellyfin.fallbackAddress)?.let(errors::add)

            val all = arrayOf(password, token, secret)
            assertClean("SignInRequest", requestText, *all)
            assertClean("Session", session.toString(), *all)
            assertClean("StoredSession", session.toStored().toString(), *all)
            assertClean("AccessToken", AccessToken(token).toString(), *all)
            assertClean("QuickConnectSecret", QuickConnectSecret(secret).toString(), *all)
            for (state in states) assertClean("a Quick Connect state", state.toString(), *all)
            assertTrue(states.any { it is QuickConnectState.SignedIn })
            for (error in errors) assertClean("an error", error.toString(), *all)
            assertTrue(errors.size >= 4)
            for (line in logged) assertClean("the adapter log", line, *all)
            assertTrue(logged.isNotEmpty())

            for (recorded in jellyfin.log) {
                val url = recorded.url.toString()
                assertClean("a request URL", url, password, token)
                if (recorded.url.encodedPath.endsWith("/QuickConnect/Connect")) {
                    // The Jellyfin API takes the secret as this one query parameter. Nowhere else.
                    assertEquals(secret, recorded.url.queryParameter("secret"))
                    assertEquals(listOf("secret"), recorded.url.queryParameterNames.toList())
                    assertClean("a Connect URL outside its secret parameter", url.replace(secret, ""), secret)
                } else {
                    assertClean("a request URL", url, secret)
                }
                val headers = recorded.headers.toString().replace("Token=\"$token\"", "")
                assertClean("a request header", headers, password, token, secret)
            }
        }

    @Test
    fun failedCallsLeakNothingEither() =
        runBlocking {
            for (code in listOf(401, 403, 404, 500)) {
                jellyfin.on("POST", "/Users/AuthenticateByName") { jellyfin.status(code) }
                val result =
                    adapter.signIn(SignInRequest(jellyfin.address, "alice", password.toCharArray(), null))
                assertClean("an error", (result as ServerResult.Failure).toString(), password, token, secret)
            }
            for (line in logged) assertClean("the adapter log", line, password, token, secret)
        }
}
