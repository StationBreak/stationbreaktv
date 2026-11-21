// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.crash

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.acra.ACRA
import org.acra.config.CoreConfiguration
import org.acra.file.ReportLocator
import tv.stationbreak.core.ui.CrashReportConsentStore
import tv.stationbreak.core.ui.CrashReportConsents

private const val TAG = "SBCRASH"

/**
 * Opt-in crash reports (#96, Q-PRIV-1): ACRA, reporting to the owner's own endpoint, and only while the viewer has
 * agreed. In a build with no endpoint ([CrashReportEndpoint]) none of this starts. ACRA does not start either until the
 * viewer first agrees, so a viewer who never does has no crash handler of ours, no collected report and no file on
 * disk.
 * Turning it off stops collecting at once and deletes any report still waiting; a report is also checked against the
 * consent again just before it is sent ([CrashReportSender]).
 */
internal object CrashReporting {
    @Volatile private var endpoint: CrashReportEndpoint? = null

    @Volatile private var consented = false

    private var started = false

    /** What ACRA collects: the allowed fields only. No log, no shared preferences, no device id, no custom data. */
    private fun config() = CoreConfiguration(reportContent = CrashReportBody.FIELDS)

    /** The sender ACRA uses: it sends only while there is an endpoint and consent. */
    fun sender(): CrashReportSender = CrashReportSender(endpointNow = { if (consented) endpoint else null })

    /**
     * Starts crash reporting if [rawEndpoint] (the build's `CRASH_REPORT_URL`) is a usable endpoint, and tells the
     * consent store whether there is one, so the Settings sheet shows its switch only then. Call once, from `onCreate`.
     */
    fun start(
        app: Application,
        rawEndpoint: String,
        scope: CoroutineScope,
    ) {
        val parsed = CrashReportEndpoint.parse(rawEndpoint)
        CrashReportConsents.configure(parsed != null)
        if (parsed != null) {
            install(app, parsed, CrashReportConsents.get(app), scope)
        } else {
            // A build with nowhere to send: whatever an earlier build left waiting is not kept.
            discardWaitingReports(app)
        }
    }

    /** Follows [consent]: the value now, then every change. Call on the main thread. */
    internal fun install(
        app: Application,
        endpoint: CrashReportEndpoint,
        consent: CrashReportConsentStore,
        scope: CoroutineScope,
    ) {
        this.endpoint = endpoint
        apply(app, consent.enabled.value)
        @Suppress("InjectDispatcher") // The Application is the root: there is nothing to inject a dispatcher into.
        scope.launch(Dispatchers.Main.immediate) { consent.enabled.drop(1).collect { apply(app, it) } }
    }

    private fun apply(
        app: Application,
        on: Boolean,
    ) {
        consented = on
        when {
            on && !started -> {
                started = true
                ACRA.init(app, config())
                Log.i(TAG, "crash reports on")
            }

            on -> {
                ACRA.errorReporter.setEnabled(true)
            }

            else -> {
                if (started) ACRA.errorReporter.setEnabled(false)
                // Also when ACRA never started in this process: a report may be left from an earlier run.
                discardWaitingReports(app)
                Log.i(TAG, "crash reports off")
            }
        }
    }

    // A withdrawn consent also withdraws what was kept for sending.
    private fun discardWaitingReports(app: Application) {
        val locator = ReportLocator(app)
        (locator.approvedReports + locator.unapprovedReports).forEach { it.delete() }
    }
}
