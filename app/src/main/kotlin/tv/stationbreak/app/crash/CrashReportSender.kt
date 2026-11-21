// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.crash

import android.content.Context
import android.os.Looper
import okhttp3.ConnectionSpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.acra.config.CoreConfiguration
import org.acra.data.CrashReportData
import org.acra.sender.ReportSender
import org.acra.sender.ReportSenderException
import org.acra.sender.ReportSenderFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

/** ACRA finds this through `META-INF/services` (app/src/main/resources); it hands the sender [CrashReporting] made. */
class CrashReportSenderFactory : ReportSenderFactory {
    override fun create(
        context: Context,
        config: CoreConfiguration,
    ): ReportSender = CrashReporting.sender()
}

/**
 * Posts one redacted report to the owner's endpoint (#96). Plain HTTPS to the system's trust, no redirect followed, no
 * cookies, no credentials: the report holds nothing a caller could use, and the endpoint is write-only by design.
 *
 * [endpointNow] is asked at send time: it is null when the viewer has not agreed (or has since withdrawn), and then the
 * report is dropped unsent. A failed send throws, so ACRA keeps the report and tries again at the next start.
 */
internal class CrashReportSender(
    private val endpointNow: () -> CrashReportEndpoint?,
    private val client: OkHttpClient = defaultClient(),
    private val onMainThread: () -> Boolean = { Looper.myLooper() == Looper.getMainLooper() },
) : ReportSender {
    // ACRA sends on the crashing thread; a main-thread network call is refused by Android, so that report waits.
    override fun requiresForeground(): Boolean = true

    override fun send(
        context: Context,
        errorContent: CrashReportData,
    ) = deliver(errorContent.toMap())

    internal fun deliver(report: Map<String, Any?>) {
        val endpoint = endpointNow() ?: return
        val body = CrashReportBody.encode(report) ?: return
        if (onMainThread()) throw ReportSenderException("Not sent on the main thread; it is sent at the next start")
        post(endpoint, body)
    }

    private fun post(
        endpoint: CrashReportEndpoint,
        body: String,
    ) {
        val request =
            Request
                .Builder()
                .url(endpoint.url)
                .post(body.toRequestBody(JSON))
                .build()
        val code =
            try {
                client.newCall(request).execute().use { it.code }
            } catch (e: IOException) {
                throw ReportSenderException("The report could not be sent: ${e.javaClass.simpleName}", e)
            }
        if (code !in HTTP_OK) throw ReportSenderException("The endpoint answered $code")
    }

    internal companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val TIMEOUT_SECONDS = 10L
        private val HTTP_OK = 200..299

        // Never cleartext, whatever the network security config allows for the media server; never a redirect.
        fun defaultClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS))
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
    }
}
