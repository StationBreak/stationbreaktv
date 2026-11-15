// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.audit

/** The audit's text output: for a person to read, and for a diff. Numbers by integer arithmetic ([AuditFormat]). */
internal object AuditText {
    private const val OVERLAP_LINES = 40
    private const val ID_CHARS = 8
    private const val CAUSE_WIDTH = 30
    private const val KEY_WIDTH = 28
    private const val STATS_WIDTH = 34
    private const val SCORE_WIDTH = 5
    private const val REASON_WIDTH = 9
    private const val STATE_WIDTH = 6
    private const val NUMBER_WIDTH = 4

    fun render(report: AuditReport): String {
        val out = StringBuilder()
        val versions = "catalog ${report.catalogVersion}, ${report.curationVersion}, ${report.extrasVersion}"
        out.append("LINEUP AUDIT ($versions)\n")
        out.append("SCOPE: ${report.scope}\n\n")
        library(out, report.library)
        for (note in report.notes) out.append("NOTE: $note\n")
        totals(out, report.totals)
        starters(out, report.starters)
        overlaps(out, report.overlaps)
        titles(out, report.titles)
        return out.toString()
    }

    private fun library(
        out: StringBuilder,
        l: LibrarySummary,
    ) {
        out.append("LIBRARY: ${l.items} items, ${l.titles} titles (${l.series} series, ${l.films} films)\n")
        val dropped = l.droppedByExtras.entries.joinToString(", ") { "${it.key} ${it.value}" }
        out.append("EXTRAS FILTER DROPPED: ${dropped.ifEmpty { "nothing" }}\n")
    }

    private fun totals(
        out: StringBuilder,
        t: Totals,
    ) {
        out.append("\nTOTALS\n")
        out.append("  titles: ${t.titles}, with a home: ${t.homed}, with no home: ${t.titles - t.homed}\n")
        out.append("  titles with no home, by cause:\n")
        for (cause in Cause.entries) {
            out.append("    ${cause.label.padEnd(CAUSE_WIDTH)} ${AuditFormat.count(t.noHomeByCause[cause] ?: 0)}\n")
        }
        val perTitle = t.channelsPerTitle.entries.joinToString(", ") { "${it.key} channels: ${it.value} titles" }
        out.append("  channels per title: $perTitle\n")
        out.append("  titles per channel that is on:\n")
        for (c in t.titlesPerChannel) out.append("    ${c.channel.padEnd(KEY_WIDTH)} ${AuditFormat.count(c.titles)}\n")
    }

    private fun stats(s: StarterStats?): String {
        if (s == null) return "-".padEnd(STATS_WIDTH)
        val score = s.score?.let { " score ${it.toString().padStart(SCORE_WIDTH)}" }.orEmpty()
        val thin = if (s.qualifies) "" else " THIN"
        return "${s.titles}t ${AuditFormat.hours(s.usableMs)}h ${s.anchors}a$score$thin".padEnd(STATS_WIDTH)
    }

    private fun starters(
        out: StringBuilder,
        rows: List<StarterRow>,
    ) {
        out.append("\nSTARTERS (selection: homed over every candidate; on air: homed over the channels that are on)\n")
        val header = "${"key".padEnd(KEY_WIDTH)} num  state  reason    cand  ${"selection".padEnd(STATS_WIDTH)} on air"
        out.append("  $header\n")
        for (s in rows) {
            val state = if (s.on) "on(${s.by?.first()})" else "off"
            val reason = s.shelfReason.orEmpty().padEnd(REASON_WIDTH)
            val number = (s.number?.toString() ?: "-").padStart(NUMBER_WIDTH)
            val key = s.key.padEnd(KEY_WIDTH)
            val head = "  $key $number  ${state.padEnd(STATE_WIDTH)} $reason ${AuditFormat.count(s.candidates)}"
            out.append("$head  ${stats(s.selection)} ${stats(s.onAir).trimEnd()}\n")
        }
    }

    private fun overlaps(
        out: StringBuilder,
        rows: List<OverlapRow>,
    ) {
        out.append("\nOVERLAP: pairs of channels that are on and air the same titles (most hours first)\n")
        for (o in rows.take(OVERLAP_LINES)) {
            val pair = "${o.a.padEnd(KEY_WIDTH)} ${o.b.padEnd(KEY_WIDTH)}"
            out.append("  $pair ${o.titles} titles, ${AuditFormat.hours(o.runtimeMs)}h\n")
        }
        val more = rows.size - OVERLAP_LINES
        if (more > 0) out.append("  ... and $more more pairs (all in the JSON)\n")
        if (rows.isEmpty()) out.append("  none\n")
    }

    private fun place(
        label: String,
        c: ChannelScore?,
    ): String {
        if (c == null) return ""
        return "\n    $label ${c.channel} ${AuditFormat.points(c.terms.total)} = ${AuditFormat.terms(c.terms)}"
    }

    private fun titles(
        out: StringBuilder,
        rows: List<TitleRow>,
    ) {
        out.append("\nTITLES (scores in points; a term that is zero is left out)\n")
        for (t in rows) {
            val kind = if (t.series) "series" else "film"
            val size = "$kind, ${t.items} items, ${AuditFormat.hours(t.runtimeMs)}h"
            out.append("  ${t.id.takeLast(ID_CHARS)} ${t.name ?: "(no name)"} [$size]")
            if (t.home == null) noHome(out, t) else homed(out, t)
            out.append('\n')
        }
    }

    private fun homed(
        out: StringBuilder,
        t: TitleRow,
    ) {
        out.append(place("home", t.home)).append(place("runner-up", t.runnerUp))
        if (t.guestSpots.isNotEmpty()) out.append("\n    guest spots: ${t.guestSpots.joinToString(", ")}")
    }

    private fun noHome(
        out: StringBuilder,
        t: TitleRow,
    ) {
        out.append("\n    NO HOME: ${t.cause?.label} (${t.cause?.means})")
        if (t.closest.isNotEmpty()) out.append("\n    closest: ${t.closest.joinToString(", ")}")
        if (t.shelvedLanes.isNotEmpty()) out.append("\n    candidate only on: ${t.shelvedLanes.joinToString(", ")}")
        out.append("\n    airs on: ${t.fallback.joinToString(", ").ifEmpty { "no channel" }}")
    }
}
