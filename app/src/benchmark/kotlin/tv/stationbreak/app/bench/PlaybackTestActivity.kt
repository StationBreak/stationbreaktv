// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.bench

import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.RestoreOutcome
import tv.stationbreak.core.domain.ServerSettings
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.playback.ExpectedRoute
import tv.stationbreak.core.playback.PlaybackResolver
import tv.stationbreak.core.playback.TuneController
import tv.stationbreak.core.playback.VideoPlayer
import tv.stationbreak.core.playback.VideoPlayerFactory
import tv.stationbreak.core.playback.probe.DeviceCapabilitiesCache
import java.io.File
import javax.inject.Inject

private const val TAG = "SBPLAY"
private const val RESULTS_FILE = "playback-results.jsonl"

/**
 * The playback matrix's driver (spec 13), in the benchmark build only. Started with `adb shell am start -n
 * tv.stationbreak.app.benchmark/tv.stationbreak.app.bench.PlaybackTestActivity` and these extras: `items` (String[] of
 * item IDs), `starts_ms` (long[]), `audio` (int[], -1 none), `subtitles` (int[], -1 off, when `override`), `override`
 * (boolean), `section` and `run_id`. It plays the items in order, each through the tune controller and a 15 s watch,
 * and prints to logcat under `SBPLAY`: `META {...}`, a `RESULT {...}` per item, then `DONE <run_id>`. The same lines
 * are appended to `filesDir/playback-results.jsonl`. Item IDs, titles and URLs are never logged. It uses the stored
 * session, so the app must have been signed in before.
 *
 * One player from the [VideoPlayerFactory] serves the whole run. The run closes the tune controller, and only then
 * releases the player.
 */
@AndroidEntryPoint
class PlaybackTestActivity : ComponentActivity() {
    @Inject lateinit var playerFactory: VideoPlayerFactory

    @Inject lateinit var resolver: PlaybackResolver

    @Inject lateinit var server: MediaServer

    @Inject lateinit var sessions: SessionRepository

    @Inject lateinit var settings: ServerSettings

    @Inject lateinit var capabilities: DeviceCapabilitiesCache

    @Suppress("InjectDispatcher") // The activity is the root of this test run: there is nothing to inject into.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Exported, so any app can start it: with debugging off, nothing started it by adb, and it does nothing.
        val adb = Settings.Global.getInt(contentResolver, Settings.Global.ADB_ENABLED, 0)
        if (!AdbGate.allows(adb)) {
            Log.i(TAG, "ERROR adb-disabled")
            return finish()
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ResultsFile.trimIfLarge(File(filesDir, RESULTS_FILE))
        val args = parseArgs() ?: return finish()
        val surface = SurfaceView(this)
        setContentView(surface)
        val created = playerFactory.create(this, SystemClock::elapsedRealtime)
        created.attach(surface)
        // ATOMIC: the body starts even if the activity is already gone, so the player below is always released.
        lifecycleScope.launch(Dispatchers.Default, start = CoroutineStart.ATOMIC) {
            try {
                try {
                    runAll(args, created)
                } catch (
                    @Suppress("SwallowedException", "TooGenericExceptionCaught") e: Exception,
                ) {
                    if (e is CancellationException) throw e
                    // A name only: a message or a stack could hold a URL.
                    emit("ERROR run-failed")
                }
                emit("DONE ${args.runId}")
                // Activity calls belong on the main thread.
                withContext(Dispatchers.Main) { finish() }
            } finally {
                // Close, then release: runAll's own finally has closed the controller by now. The player is not
                // released in onDestroy, which can run before that teardown has finished.
                created.release()
            }
        }
    }

    private fun parseArgs(): RunArgs? =
        try {
            RunArgs.parse(
                items = intent.getStringArrayExtra("items"),
                startsMs = intent.getLongArrayExtra("starts_ms"),
                audio = intent.getIntArrayExtra("audio"),
                subtitles = intent.getIntArrayExtra("subtitles"),
                override = intent.getBooleanExtra("override", false),
                section = intent.getStringExtra("section"),
                runId = intent.getStringExtra("run_id"),
            )
        } catch (
            @Suppress("SwallowedException") e: IllegalArgumentException,
        ) {
            emit("ERROR bad-args")
            emit("DONE none")
            null
        }

    private suspend fun runAll(
        args: RunArgs,
        player: VideoPlayer,
    ) {
        val session = signIn()
        emit("META " + meta(session, args.runId))
        if (session == null) {
            emit("ERROR no-session")
            return
        }
        val clock = SystemClock::elapsedRealtime
        val controller = TuneController(resolver, server, player, clock = clock)
        val run = PlaybackRun(controller, player)
        try {
            for ((ordinal, play) in args.plays.withIndex()) {
                val result =
                    run.run(session, args.section, play) { resolved ->
                        // The chosen audio track is the oracle's input (not the source default), so an override is
                        // graded.
                        ExpectedRoute.of(
                            capabilities.get(),
                            resolved.media,
                            resolved.chosenSubtitle,
                            resolved.chosenAudio,
                        )
                    }
                emit("RESULT " + result.toJsonLine(args.runId, ordinal))
                if (result.error == "session-expired") {
                    sessions.onServerError(MediaServerError.SessionExpired())
                }
            }
        } finally {
            // Even when the run is cancelled: nothing may keep playing or encoding. The player is released after this.
            controller.close(session)
        }
    }

    /**
     * The stored session, confirmed with the server; null when there is none. Confirming it makes the repository hold
     * it as `current`, which is what the media requests read.
     */
    private suspend fun signIn(): Session? {
        val record = settings.load() ?: return null
        return (sessions.verify(record.address) as? RestoreOutcome.Restored)?.session
    }

    private fun meta(
        session: Session?,
        runId: String,
    ): String {
        val device = capabilities.get()
        val passthrough = device.audioPassthrough.sorted().joinToString("+")
        val sink = "passthrough=$passthrough;channels=${device.maxAudioChannels}"
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        return jsonObject(
            "app" to (packageManager.getPackageInfo(packageName, 0).versionName ?: "?"),
            "device" to Build.MODEL,
            "sdk" to Build.VERSION.SDK_INT,
            "jellyfin" to (session?.server?.version?.toString() ?: "?"),
            "sink" to sink,
            "debuggable" to debuggable,
            "run_id" to runId,
        )
    }

    /** One line to logcat and to the results file. */
    private fun emit(line: String) {
        Log.i(TAG, line)
        File(filesDir, RESULTS_FILE).appendText(line + "\n")
    }
}
