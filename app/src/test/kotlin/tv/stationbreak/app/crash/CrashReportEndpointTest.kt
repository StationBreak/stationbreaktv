// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A build has crash reporting only with a usable endpoint (#96): https, no credentials in the URL. */
class CrashReportEndpointTest {
    @Test
    fun theDefault_emptyOrBlank_meansNoEndpoint() {
        assertNull(CrashReportEndpoint.parse(""))
        assertNull(CrashReportEndpoint.parse("   "))
    }

    @Test
    fun anHttpsUrlIsTheEndpoint() {
        val endpoint = checkNotNull(CrashReportEndpoint.parse(" https://reports.example.invalid/v1/crash "))
        assertEquals("https://reports.example.invalid/v1/crash", endpoint.url.toString())
    }

    @Test
    fun cleartextCredentialsAndNonsenseAreRefused() {
        listOf(
            "http://reports.example.invalid/crash",
            "https://user@reports.example.invalid/crash",
            "https://user:secret@reports.example.invalid/crash",
            "ftp://reports.example.invalid/crash",
            "reports.example.invalid/crash",
            "https://",
            "not a url",
        ).forEach { assertNull("'$it'", CrashReportEndpoint.parse(it)) }
    }
}
