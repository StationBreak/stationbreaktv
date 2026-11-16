// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.audit

/** The terms of a fit score, in the order of [TERM_NAMES], each in 16-bit fixed point (65,536 is one point). */
internal class TermValues(
    val q16: List<Long>,
) {
    val total: Long get() = q16.sum()

    init {
        require(q16.size == TERM_NAMES.size) { "One value for each term" }
    }
}

internal val TERM_NAMES = listOf("specificity", "genre", "tag", "studio", "decade", "anchor", "kids", "format", "tone")

/** A channel a title may air on, and how it scores there. */
internal class ChannelScore(
    val channel: String,
    val terms: TermValues,
)

/** Why a title has no home: the furthest a recipe's pool got it, and where it stopped. See [TitleCauses]. */
internal enum class Cause(
    val label: String,
    val means: String,
) {
    NoSourceMatch("no-source-match", "no recipe has a source that matches it"),
    ExcludedByRecipe("excluded-by-recipe", "a source matches it but the recipe's exclusions remove it"),
    HeldByPolicy(
        "held-by-kid-or-grown-up-hold",
        "a source matches it but the kid hold or the grown-up hold removes it",
    ),
    FilteredOrRated("filtered-or-rated", "it passes the hold but a recipe's filters or rating ceiling remove it"),
    NotAirable("not-airable", "it is in a recipe's pool but its runtime cannot air there"),
    AnimeRule("anime-rule", "it is in a recipe's pool but the anime rule keeps it out of that channel"),
    OnlyOnShelvedChannels("only-on-shelved-channels", "it is a candidate only on channels that are not on"),
}

/** One title (a series, or a film), with where it lives. */
@Suppress("LongParameterList") // A plain carrier: one field per column of the report.
internal class TitleRow(
    val id: String,
    val name: String?,
    val series: Boolean,
    val items: Int,
    val runtimeMs: Long,
    val home: ChannelScore?,
    val runnerUp: ChannelScore?,
    val guestSpots: List<String>,
    val cause: Cause?,
    val closest: List<String>,
    val shelvedLanes: List<String>,
    val fallback: List<String>,
)

/** What the selection and the homing say of one starter. */
internal class StarterStats(
    val titles: Int,
    val totalMs: Long,
    val usableMs: Long,
    val anchors: Int,
    val qualifies: Boolean,
    val score: Long?,
)

/** One starter of the catalog. [by] is who switched it on ("planner" or "viewer") when it is on. */
@Suppress("LongParameterList") // A plain carrier: one field per column of the report.
internal class StarterRow(
    val key: String,
    val number: Int?,
    val name: String,
    val on: Boolean,
    val by: String?,
    val shelfReason: String?,
    val kid: Boolean,
    val optIn: Boolean,
    val candidates: Int,
    val selection: StarterStats?,
    val onAir: StarterStats?,
)

/** Two channels that are on and air some of the same titles. */
internal class OverlapRow(
    val a: String,
    val b: String,
    val titles: Int,
    val runtimeMs: Long,
)

/** One channel that is on, for the totals: how many titles it airs. */
internal class ChannelTitles(
    val channel: String,
    val titles: Int,
)

internal class Totals(
    val titles: Int,
    val homed: Int,
    val noHomeByCause: Map<Cause, Int>,
    val titlesPerChannel: List<ChannelTitles>,
    /** How many titles air on 0, 1, 2, ... channels that are on (leftovers channels and custom channels included). */
    val channelsPerTitle: Map<Int, Int>,
)

internal class LibrarySummary(
    val items: Int,
    val titles: Int,
    val series: Int,
    val films: Int,
    val droppedByExtras: Map<String, Int>,
)

/** Everything one audit run says. */
@Suppress("LongParameterList") // A plain carrier: one field per column of the report.
internal class AuditReport(
    val scope: String,
    val catalogVersion: Int,
    val curationVersion: String,
    val extrasVersion: String,
    val library: LibrarySummary,
    val notes: List<String>,
    val starters: List<StarterRow>,
    val titles: List<TitleRow>,
    val overlaps: List<OverlapRow>,
    val totals: Totals,
)
