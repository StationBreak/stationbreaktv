// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

/**
 * Spec 03, Unit 4 (S0.3.5, R15, R17): connects, through the platform stack that honours the network security config,
 * to a real Jellyfin server and prints one `ProbeOutcome` line. It exists to prove, on the reference device, that
 * the owner's real certificate chain is trusted on Fire OS 6.
 *
 * The server address is an instrumentation argument the owner supplies at run time:
 * `adb shell am instrument -w -e class tv.stationbreak.app.OnDeviceServerProbeTest -e serverAddress "$ADDR" ...`.
 * CI never sets it (a guard enforces that), so there the test returns at once and prints nothing. The address is
 * read once, kept in no field, and appears in no log, message or output; neither does any exception message. The
 * request carries no credentials. Every `Throwable` is caught and reduced to its type.
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceServerProbeTest {
    @Test
    fun probeTheServerNamedByTheRunArgument() {
        val address = InstrumentationRegistry.getArguments().getString(ARGUMENT)
        // No argument (every CI run): return without a word. A JUnit assumption would be the natural skip, but on the
        // API 25 emulator run AGP records an assumption failure as a failed test (CI run 38495263038).
        if (address.isNullOrBlank()) return
        report(probe(address))
    }

    private fun probe(address: String): String =
        try {
            val connection = URL(address.trimEnd('/') + "/System/Info/Public").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = TIMEOUT_MILLIS
                connection.readTimeout = TIMEOUT_MILLIS
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "application/json")
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    ProbeOutcome.success(connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
                } else {
                    ProbeOutcome.status(connection.responseCode)
                }
            } finally {
                connection.disconnect()
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") failure: Throwable,
        ) {
            ProbeOutcome.failure(failure)
        }

    // The "stream" key is printed as-is on the `am instrument` stdout. The line is the only text, and it is not logged.
    private fun report(line: String) {
        val status = Bundle().apply { putString("stream", "$line\n") }
        InstrumentationRegistry.getInstrumentation().sendStatus(STATUS_IN_PROGRESS, status)
    }

    private companion object {
        const val ARGUMENT = "serverAddress"
        const val STATUS_IN_PROGRESS = 2
        const val TIMEOUT_MILLIS = 15_000
    }
}
