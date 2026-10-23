// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.bench

import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.ServerSettings
import tv.stationbreak.core.domain.SessionRepository
import javax.inject.Inject

private const val TAG = "SBPLAY"

/**
 * Signs the benchmark build in and out without the onboarding screens (spec 13), so the playback matrix's device setup
 * takes seconds. Started with `adb shell am start -n
 * tv.stationbreak.app.benchmark/tv.stationbreak.app.bench.BenchSetupActivity` and the extras `action` (`setup` or
 * `signout`) and, for setup, `server` (the address, as typed on the server screen). It prints to logcat under `SBPLAY`:
 * for setup `QC <code>` (approve it on the server), then `SETUP OK` or `SETUP ERROR <reason>`; for sign-out
 * `SIGNOUT OK revoked|not-revoked` or `SIGNOUT ERROR <reason>`. See [BenchSetup]. Like [PlaybackTestActivity] it runs
 * only while debugging is on. The server address, tokens, users and library names are never logged.
 */
@AndroidEntryPoint
class BenchSetupActivity : ComponentActivity() {
    @Inject lateinit var server: MediaServer

    @Inject lateinit var sessions: SessionRepository

    @Inject lateinit var settings: ServerSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Exported, so any app can start it: with debugging off, nothing started it by adb, and it does nothing.
        val adb = Settings.Global.getInt(contentResolver, Settings.Global.ADB_ENABLED, 0)
        if (!AdbGate.allows(adb)) {
            Log.i(TAG, "ERROR adb-disabled")
            return finish()
        }
        val args = parseArgs() ?: return finish()
        val setup = BenchSetup(server, sessions, settings, emit = { Log.i(TAG, it) })
        lifecycleScope.launch {
            try {
                when (args) {
                    is SetupArgs.Setup -> setup.setup(args.server)
                    SetupArgs.SignOut -> setup.signOut()
                }
            } catch (
                @Suppress("SwallowedException", "TooGenericExceptionCaught") e: Exception,
            ) {
                if (e is CancellationException) throw e
                // A name only: a message or a stack could hold a URL.
                Log.i(TAG, "ERROR run-failed")
            }
            finish()
        }
    }

    private fun parseArgs(): SetupArgs? =
        try {
            SetupArgs.parse(intent.getStringExtra("action"), intent.getStringExtra("server"))
        } catch (
            @Suppress("SwallowedException") e: IllegalArgumentException,
        ) {
            Log.i(TAG, "ERROR bad-args")
            null
        }
}
