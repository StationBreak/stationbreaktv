// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.crash

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.acra.ReportField

/**
 * What a crash report says (#96): only the fields in [FIELDS], each redacted ([CrashReportRedactor]) before it is
 * written. ACRA is configured to collect only these, and the body is built from this list again, so a field some
 * other collector added is not sent. There is no log, no shared preference, no device id and no user-entered text in
 * it.
 */
internal object CrashReportBody {
    /** The only fields a report holds: the crash, the build it came from, and what kind of TV it was. */
    val FIELDS: List<ReportField> =
        listOf(
            ReportField.REPORT_ID,
            ReportField.USER_CRASH_DATE,
            ReportField.PACKAGE_NAME,
            ReportField.APP_VERSION_NAME,
            ReportField.APP_VERSION_CODE,
            ReportField.ANDROID_VERSION,
            ReportField.PHONE_MODEL,
            ReportField.BRAND,
            ReportField.PRODUCT,
            ReportField.TOTAL_MEM_SIZE,
            ReportField.AVAILABLE_MEM_SIZE,
            ReportField.STACK_TRACE,
        )

    /** The JSON body for [collected] (field name to value), or null when it holds none of the allowed fields. */
    fun encode(collected: Map<String, Any?>): String? {
        val fields =
            FIELDS.mapNotNull { field ->
                collected[field.name]?.toString()?.let { field.name to JsonPrimitive(CrashReportRedactor.redact(it)) }
            }
        return if (fields.isEmpty()) null else Json.encodeToString(JsonObject.serializer(), JsonObject(fields.toMap()))
    }
}
