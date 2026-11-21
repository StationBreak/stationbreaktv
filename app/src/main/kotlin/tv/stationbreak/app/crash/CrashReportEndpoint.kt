// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.crash

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Where opt-in crash reports go (#96): the owner's own endpoint, given at build time (`CRASH_REPORT_URL`, never in the
 * repository). Only an `https` URL with no user name or password in it counts; anything else, including the empty
 * default, means this build has no crash reporting.
 */
internal class CrashReportEndpoint private constructor(
    val url: HttpUrl,
) {
    companion object {
        fun parse(raw: String): CrashReportEndpoint? {
            val url = raw.trim().toHttpUrlOrNull() ?: return null
            val acceptable = url.isHttps && url.username.isEmpty() && url.password.isEmpty()
            return if (acceptable) CrashReportEndpoint(url) else null
        }
    }
}
