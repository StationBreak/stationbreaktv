// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import okhttp3.HttpUrl.Companion.toHttpUrl
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import kotlin.test.Test
import kotlin.test.assertEquals

/** Angua (Security) R9: when a probe's redirect may be followed, as a pure decision over the two addresses. */
class RedirectPolicyTest {
    private val endpoint = ServerProbe.ENDPOINT

    private fun decide(
        original: ServerAddress,
        target: String,
    ) = RedirectPolicy.decide(original, target.toHttpUrl(), endpoint)

    private fun adopted(address: ServerAddress) = RedirectDecision.Adopt(address)

    private val http = ServerAddress(Scheme.Http, "media.example.com", 80)

    @Test
    fun httpToHttpsOnTheSameHostAndDefaultPortsIsAdopted() {
        assertEquals(
            adopted(ServerAddress(Scheme.Https, "media.example.com", 443)),
            decide(http, "https://media.example.com/System/Info/Public"),
        )
    }

    @Test
    fun httpToHttpsKeepingTheSameExplicitPortIsAdopted() {
        val original = http.copy(port = 8096)
        assertEquals(
            adopted(ServerAddress(Scheme.Https, "media.example.com", 8096)),
            decide(original, "https://media.example.com:8096/System/Info/Public"),
        )
    }

    @Test
    fun httpToHttpsOnTheDefaultPortIsAdoptedWhateverThePortWas() {
        assertEquals(
            adopted(ServerAddress(Scheme.Https, "media.example.com", 443)),
            decide(http.copy(port = 8096), "https://media.example.com/System/Info/Public"),
        )
    }

    @Test
    fun theSubpathIsKept() {
        val original = http.copy(subpath = "/jf")
        assertEquals(
            adopted(ServerAddress(Scheme.Https, "media.example.com", 443, "/jf")),
            decide(original, "https://media.example.com/jf/System/Info/Public"),
        )
    }

    @Test
    fun theHostIsComparedWithoutCase() {
        assertEquals(
            adopted(ServerAddress(Scheme.Https, "media.example.com", 443)),
            decide(http, "https://MEDIA.example.com/System/Info/Public"),
        )
    }

    @Test
    fun aRedirectToTheSameAddressIsAdoptedAndTheHopLimitStopsLoops() {
        assertEquals(adopted(http), decide(http, "http://media.example.com/System/Info/Public"))
    }

    @Test
    fun httpsToHttpIsNeverAdopted() {
        val https = ServerAddress(Scheme.Https, "media.example.com", 443)
        assertEquals(RedirectDecision.Reject, decide(https, "http://media.example.com/System/Info/Public"))
        val target = "http://media.example.com:8920/System/Info/Public"
        assertEquals(RedirectDecision.Reject, decide(https.copy(port = 8920), target))
    }

    @Test
    fun onceOnHttpsThereIsNoWayBackToHttp() {
        // http to https is adopted; a hop back to http is judged against where the probe is now.
        val https = ServerAddress(Scheme.Https, "media.example.com", 443)
        val target = "http://media.example.com/System/Info/Public".toHttpUrl()
        assertEquals(RedirectDecision.Reject, RedirectPolicy.decide(http, target, endpoint, current = https))
        assertEquals(adopted(http), RedirectPolicy.decide(http, target, endpoint, current = http))
    }

    @Test
    fun aDifferentHostIsRejected() {
        assertEquals(RedirectDecision.Reject, decide(http, "https://other.example.com/System/Info/Public"))
        assertEquals(RedirectDecision.Reject, decide(http, "https://media.example.com.evil.example/System/Info/Public"))
        assertEquals(RedirectDecision.Reject, decide(http, "https://sub.media.example.com/System/Info/Public"))
    }

    @Test
    fun aDifferentPortIsRejected() {
        val on8096 = http.copy(port = 8096)
        assertEquals(RedirectDecision.Reject, decide(on8096, "https://media.example.com:8920/System/Info/Public"))
        assertEquals(RedirectDecision.Reject, decide(http, "http://media.example.com:8096/System/Info/Public"))
        assertEquals(RedirectDecision.Reject, decide(on8096, "http://media.example.com:8097/System/Info/Public"))
    }

    @Test
    fun aDifferentPathIsRejected() {
        assertEquals(RedirectDecision.Reject, decide(http, "https://media.example.com/jf/System/Info/Public"))
        val withSubpath = http.copy(subpath = "/jf")
        assertEquals(RedirectDecision.Reject, decide(withSubpath, "https://media.example.com/System/Info/Public"))
        assertEquals(RedirectDecision.Reject, decide(http, "https://media.example.com/login"))
        assertEquals(RedirectDecision.Reject, decide(http, "https://media.example.com/"))
        assertEquals(RedirectDecision.Reject, decide(http, "https://media.example.com/System/Info/Public/extra"))
    }

    @Test
    fun aQueryOrCredentialsOnTheTargetAreRejected() {
        assertEquals(RedirectDecision.Reject, decide(http, "https://media.example.com/System/Info/Public?next=1"))
        assertEquals(RedirectDecision.Reject, decide(http, "https://user:pw@media.example.com/System/Info/Public"))
    }

    @Test
    fun theRedirectLimitIsFive() {
        assertEquals(5, RedirectPolicy.MAX_REDIRECTS)
    }
}
