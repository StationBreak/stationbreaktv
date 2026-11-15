// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.audit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The audit's JSON output, `lineup-audit` version 1 (tools/audit-export/README.md). Every score is its terms in
 * 16-bit fixed point, in the order of `termOrder` (divide by 65,536 for points); hours are given in milliseconds where
 * a threshold reads them and in hours to two places.
 */
internal object AuditJson {
    const val FORMAT = "lineup-audit"
    const val VERSION = 1

    fun render(report: AuditReport): String {
        val top = LinkedHashMap<String, JsonElement>()
        top["format"] = JsonPrimitive(FORMAT)
        top["version"] = JsonPrimitive(VERSION)
        top["scope"] = JsonPrimitive(report.scope)
        top["catalogVersion"] = JsonPrimitive(report.catalogVersion)
        top["curation"] = JsonPrimitive(report.curationVersion)
        top["extras"] = JsonPrimitive(report.extrasVersion)
        top["termOrder"] = JsonArray(TERM_NAMES.map(::JsonPrimitive))
        top["library"] = library(report.library)
        top["notes"] = JsonArray(report.notes.map(::JsonPrimitive))
        top["starters"] = JsonArray(report.starters.map(::starter))
        top["overlaps"] = JsonArray(report.overlaps.map(::overlap))
        top["totals"] = totals(report.totals)
        top["titles"] = JsonArray(report.titles.map(::title))
        return JsonLayout.layout(top)
    }

    private fun library(l: LibrarySummary): JsonElement =
        JsonParts.obj(
            "items" to JsonPrimitive(l.items),
            "titles" to JsonPrimitive(l.titles),
            "series" to JsonPrimitive(l.series),
            "films" to JsonPrimitive(l.films),
            "droppedByExtras" to JsonObject(l.droppedByExtras.mapValues { JsonPrimitive(it.value) }),
        )

    private fun starter(s: StarterRow): JsonObject =
        JsonParts.obj(
            "key" to JsonPrimitive(s.key),
            "number" to (s.number?.let { JsonPrimitive(it) } ?: JsonNull),
            "name" to JsonPrimitive(s.name),
            "state" to JsonPrimitive(if (s.on) "on" else "off"),
            "by" to JsonParts.text(s.by),
            "shelfReason" to JsonParts.text(s.shelfReason),
            "kid" to JsonPrimitive(s.kid),
            "optIn" to JsonPrimitive(s.optIn),
            "candidates" to JsonPrimitive(s.candidates),
            "selection" to JsonParts.stats(s.selection),
            "onAir" to JsonParts.stats(s.onAir),
        )

    private fun overlap(o: OverlapRow): JsonObject =
        JsonParts.obj(
            "a" to JsonPrimitive(o.a),
            "b" to JsonPrimitive(o.b),
            "titles" to JsonPrimitive(o.titles),
            "hours" to JsonParts.hours(o.runtimeMs),
        )

    private fun title(t: TitleRow): JsonObject =
        JsonParts.obj(
            "id" to JsonPrimitive(t.id),
            "name" to JsonParts.text(t.name),
            "kind" to JsonPrimitive(if (t.series) "series" else "film"),
            "items" to JsonPrimitive(t.items),
            "hours" to JsonParts.hours(t.runtimeMs),
            "home" to JsonParts.place(t.home),
            "runnerUp" to JsonParts.place(t.runnerUp),
            "guestSpots" to JsonParts.strings(t.guestSpots),
            "cause" to JsonParts.text(t.cause?.label),
            "closest" to JsonParts.strings(t.closest),
            "shelvedLanes" to JsonParts.strings(t.shelvedLanes),
            "fallback" to JsonParts.strings(t.fallback),
        )

    private fun totals(t: Totals): JsonElement =
        JsonParts.obj(
            "titles" to JsonPrimitive(t.titles),
            "homed" to JsonPrimitive(t.homed),
            "noHomeByCause" to
                JsonObject(t.noHomeByCause.entries.associate { it.key.label to JsonPrimitive(it.value) }),
            "titlesPerChannel" to JsonObject(t.titlesPerChannel.associate { it.channel to JsonPrimitive(it.titles) }),
            "channelsPerTitle" to
                JsonObject(t.channelsPerTitle.entries.associate { it.key.toString() to JsonPrimitive(it.value) }),
        )
}

/** The small pieces the audit's JSON is made of. */
internal object JsonParts {
    private const val HUNDRED = 100.0

    fun obj(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(linkedMapOf(*pairs))

    fun text(value: String?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)

    fun hours(ms: Long): JsonElement = JsonPrimitive(AuditFormat.centiHours(ms) / HUNDRED)

    fun strings(values: Collection<String>): JsonElement = JsonArray(values.map(::JsonPrimitive))

    private fun terms(t: TermValues): JsonElement = JsonArray(t.q16.map(::JsonPrimitive))

    fun place(c: ChannelScore?): JsonElement =
        if (c == null) {
            JsonNull
        } else {
            obj(
                "channel" to JsonPrimitive(c.channel),
                "total" to JsonPrimitive(c.terms.total),
                "terms" to terms(c.terms),
            )
        }

    fun stats(s: StarterStats?): JsonElement =
        if (s == null) {
            JsonNull
        } else {
            obj(
                "titles" to JsonPrimitive(s.titles),
                "totalHours" to hours(s.totalMs),
                "usableHours" to hours(s.usableMs),
                "usableMs" to JsonPrimitive(s.usableMs),
                "anchors" to JsonPrimitive(s.anchors),
                "qualifies" to JsonPrimitive(s.qualifies),
                "score" to (s.score?.let { JsonPrimitive(it) } ?: JsonNull),
            )
        }
}
