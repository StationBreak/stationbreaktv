// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup.curation

import tv.stationbreak.core.engine.SourceMatcher
import tv.stationbreak.core.engine.lineup.AnchorKind
import tv.stationbreak.core.engine.lineup.FormatPromise
import tv.stationbreak.core.engine.lineup.NameMatcher
import tv.stationbreak.core.engine.lineup.QualifyingBar
import tv.stationbreak.core.engine.lineup.Recipe
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.foldNameAscii
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.rules.AdultMark
import tv.stationbreak.core.rules.Source

/** The number of tones: made for kids or not, adult animation or not, times the three formats. */
internal const val TONES = 12
private const val TONE_KID = 6
private const val TONE_ADULT = 3

private val ITEM_ORDER = Comparator<ScheduleItem> { a, b -> a.id.value.compareTo(b.id.value) }

private val ID_ORDER = Comparator<CanonicalId> { a, b -> a.value.compareTo(b.value) }

/** A title with its names folded once, the way a theme's names are, and its tone as a number below [TONES]. */
internal class FoldedTitle(
    val title: Title,
    val genres: List<String>,
    val tags: List<String>,
    val studios: List<String>,
    val tone: Int,
)

/**
 * One channel's candidates, worked out once and then only read. [cand] holds title indices, ascending; [ms], [score]
 * and [anchor] run parallel to it. [pool] is the airable pool in canonical-ID order, and [itemPos] gives the candidate
 * position of each of its items, or -1 when the item's title is not a candidate here. [candTone] is the hours of each
 * tone over the candidates and [candMs] their sum. The home and guest marks belong to the run, not to the lane.
 */
@Suppress("LongParameterList") // A plain carrier: one field per array the run reads.
internal class Lane(
    val channel: CurationChannel,
    val pool: List<ScheduleItem>,
    val itemPos: IntArray,
    val cand: IntArray,
    val ms: LongArray,
    val score: LongArray,
    val anchor: BooleanArray,
    val candTone: LongArray,
    val candMs: Long,
) {
    val kid: Boolean = channel.kid
    val guestOnly: Boolean = channel.recipe.guestOnly

    /**
     * The recipe's qualifying bar. A kid lane still needs only [CurationDefaults.MIN_TITLES_KIDS] titles (see
     * `Curation.statsOf`), whatever its bar says; a kid recipe always has the standard one.
     */
    val bar: QualifyingBar = channel.recipe.qualifyingBar
}

/**
 * Builds the lanes of [channels] (sorted by number, then ID): the airable pool (A4), the candidates after the anime
 * rule, each candidate's hours, anchors and fit score, each worked out once.
 */
internal class LaneBuilder(
    private val snapshot: RuleSnapshot,
    channels: List<CurationChannel>,
) {
    private val pools: Array<List<ScheduleItem>> = Array(channels.size) { airable(channels[it]) }
    private val entries = HashMap<CanonicalId, RuleItem>()

    /** The position of a title ID in [titles]. A lookup only. */
    val titleIndex = HashMap<CanonicalId, Int>()

    /** The titles of every airable pool item the snapshot holds, ascending by ID. */
    val titles: Array<FoldedTitle> = foldTitles()

    /** The airable hours of each title over every pool, each item once, before any channel's own filter (#1153). */
    val titleMs =
        LongArray(titles.size).also { total ->
            for (item in distinctItems(pools)) {
                val t = titleOf(item)
                if (t >= 0) total[t] += item.runtimeMs
            }
        }

    // Indexed by title; each lane leaves them as it found them (0 and -1), so one pair serves every lane.
    private val scratchMs = LongArray(titles.size)
    private val scratchPos = IntArray(titles.size).also { it.fill(-1) }

    val lanes: Array<Lane> = Array(channels.size) { build(channels[it], pools[it]) }

    private fun foldTitles(): Array<FoldedTitle> {
        val distinct = distinctItems(pools)
        val wanted = HashSet<CanonicalId>(distinct.size * 2)
        for (item in distinct) wanted.add(item.id)
        for (entry in snapshot.items) if (entry.item.id in wanted) entries[entry.item.id] = entry
        val byId = Titles.of(snapshot, distinct)
        val ids = ArrayList(byId.keys)
        ids.sortWith(ID_ORDER)
        val out = ArrayList<FoldedTitle>(ids.size)
        for (i in ids.indices) {
            titleIndex[ids[i]] = i
            out.add(foldTitle(byId.getValue(ids[i])))
        }
        return out.toTypedArray()
    }

    private fun build(
        channel: CurationChannel,
        pool: List<ScheduleItem>,
    ): Lane {
        val anchored = anchoredGroups(snapshot, channel, pool)
        val itemTitle = IntArray(pool.size)
        val touched = IntArray(pool.size)
        var count = 0
        for (i in pool.indices) {
            val t = titleOf(pool[i])
            itemTitle[i] = if (t >= 0 && admits(channel, titles[t].title, anchored)) t else -1
            if (itemTitle[i] < 0) continue
            if (scratchPos[t] < 0) {
                scratchPos[t] = 0
                touched[count++] = t
            }
            scratchMs[t] += pool[i].runtimeMs
        }
        val cand = touched.copyOf(count)
        cand.sort()
        val ms = LongArray(count)
        val candTone = LongArray(TONES)
        var candMs = 0L
        for (p in cand.indices) {
            scratchPos[cand[p]] = p
            ms[p] = scratchMs[cand[p]]
            candTone[titles[cand[p]].tone] += ms[p]
            candMs += ms[p]
        }
        val itemPos = IntArray(pool.size)
        for (i in pool.indices) itemPos[i] = if (itemTitle[i] >= 0) scratchPos[itemTitle[i]] else -1
        for (t in cand) {
            scratchPos[t] = -1
            scratchMs[t] = 0L
        }
        val anchor = anchorsOf(channel, pool, itemPos, cand)
        val score = scoresOf(channel, cand, anchor, candTone, candMs)
        return Lane(channel, pool, itemPos, cand, ms, score, anchor, candTone, candMs)
    }

    // The title of an item the snapshot holds, by its snapshot entry's group, or -1.
    private fun titleOf(item: ScheduleItem): Int {
        val entry = entries[item.id] ?: return -1
        return titleIndex[entry.item.groupId] ?: -1
    }

    // anchors(n): an item matched by a Collection, Series or Item source of the rules, or a studio matching a Studio
    // anchor of the theme. Anchors resolve into exactly those source kinds; a Studio source cannot tell an anchor
    // studio from a theme studio, so studio anchors are read from the theme through the same fold.
    private fun anchorsOf(
        channel: CurationChannel,
        pool: List<ScheduleItem>,
        itemPos: IntArray,
        cand: IntArray,
    ): BooleanArray {
        val anchor = BooleanArray(cand.size)
        val idSources = idSourcesOf(channel.rules.sources)
        if (idSources.isNotEmpty()) {
            val matcher = SourceMatcher(idSources, snapshot)
            for (i in pool.indices) {
                val pos = itemPos[i]
                if (pos < 0 || anchor[pos]) continue
                val entry = entries.getValue(pool[i].id)
                val seriesId = entry.item.seriesId
                anchor[pos] = matcher.matches(entry, if (seriesId == null) null else snapshot.series[seriesId])
            }
        }
        val studioAnchors = studioAnchorsOf(channel.recipe)
        if (studioAnchors.isNotEmpty()) {
            for (p in cand.indices) anchor[p] = anchor[p] || matchesAny(studioAnchors, titles[cand[p]].studios)
        }
        return anchor
    }

    // The theme's inputs to a channel's scores, folded once.
    private class ThemeInputs(
        recipe: Recipe,
    ) {
        val genres = foldedDistinct(recipe.themeGenres)
        val tags = foldedDistinct(recipe.themeTags)
        val studios = recipe.theme?.themeStudios.orEmpty()
        val promise = recipe.theme?.format
    }

    // score(n, t) for t in cand(n): the channel's specificity once, plus each title's points (no allocation).
    private fun scoresOf(
        channel: CurationChannel,
        cand: IntArray,
        anchor: BooleanArray,
        candTone: LongArray,
        candMs: Long,
    ): LongArray {
        val theme = ThemeInputs(channel.recipe)
        val guestOnly = channel.recipe.guestOnly
        val specificity = specificityQ16(cand.size)
        val score = LongArray(cand.size)
        for (p in cand.indices) {
            val t = titles[cand[p]]
            score[p] = specificity +
                fitPointsQ16(
                    hits(theme.genres, t.genres),
                    hits(theme.tags, t.tags),
                    matchesAny(theme.studios, t.studios),
                    guestOnly,
                    anchor[p],
                    t.title.madeForKids && channel.kid,
                    formatFits(theme.promise, t.title),
                    candTone[t.tone],
                    candMs,
                )
        }
        return score
    }

    private val themes = HashMap<Lane, ThemeInputs>()

    /**
     * The terms of the score of candidate [position] of [lane], from the inputs [scoresOf] scored it with (for the
     * lineup audit; it allocates, so the per-title path never calls it). The terms must add up to the score the lane
     * holds: if the inputs ever drift apart this fails, on any snapshot.
     */
    internal fun termsOf(
        lane: Lane,
        position: Int,
    ): FitTerms {
        val theme = themes.getOrPut(lane) { ThemeInputs(lane.channel.recipe) }
        val title = titles[lane.cand[position]]
        val inputs =
            FitInputs(
                hits(theme.genres, title.genres),
                hits(theme.tags, title.tags),
                matchesAny(theme.studios, title.studios),
                lane.guestOnly,
                lane.anchor[position],
                title.title.madeForKids && lane.kid,
                formatFits(theme.promise, title.title),
                lane.candTone[title.tone],
                lane.candMs,
            )
        val terms = fitTermsQ16(lane.cand.size, inputs)
        check(terms.total == lane.score[position]) { "The score's terms do not add up to the lane's score" }
        return terms
    }
}

/**
 * The items of [pools], each once (the first time it is met), so the title build reads every item once however many
 * channels share it.
 */
internal fun distinctItems(pools: Array<List<ScheduleItem>>): List<ScheduleItem> {
    val seen = HashSet<CanonicalId>()
    val out = ArrayList<ScheduleItem>()
    for (pool in pools) for (item in pool) if (seen.add(item.id)) out.add(item)
    return out
}

// A4: only what can air is curated or claimed, in canonical-ID order.
private fun airable(channel: CurationChannel): List<ScheduleItem> {
    val out = ArrayList<ScheduleItem>(channel.pool.size)
    for (item in channel.pool) if (channel.settings.isAirable(item.runtimeMs, item.kind)) out.add(item)
    out.sortWith(ITEM_ORDER)
    return out
}

// tone(t) = (made for kids, adult animation and not for kids, format), as one number.
private fun foldTitle(title: Title): FoldedTitle {
    val genres = ArrayList<String>(title.genres.size)
    for (g in title.genres) genres.add(foldNameAscii(g))
    val tags = ArrayList<String>(title.tags.size)
    for (t in title.tags) tags.add(foldNameAscii(t))
    val studios = ArrayList<String>(title.studios.size)
    for (s in title.studios) studios.add(NameMatcher.fold(s))
    val kid = if (title.madeForKids) TONE_KID else 0
    val adult = if (!title.madeForKids && title.adultAnimation) TONE_ADULT else 0
    return FoldedTitle(title, genres, tags, studios, kid + adult + title.format.ordinal)
}

// The anime rule: an anime-only channel keeps only anime; a grown-up channel that is not anime-only drops it. After
// Hours is exempt (#1078): an Explicit title, anime or not, airs there or nowhere. Then the adult-signal rule
// (catalog 17, #1154) for a channel that asks for it.
internal fun admits(
    channel: CurationChannel,
    title: Title,
    anchored: Set<CanonicalId> = emptySet(),
): Boolean {
    val anime = title.format == TitleFormat.Anime
    return when {
        channel.recipe.key == AdultMark.AFTER_HOURS_KEY -> true
        channel.recipe.theme?.animeOnly == true -> anime
        !channel.kid && anime -> false
        else -> !wantsAdultSignal(channel.recipe) || hasAdultSignal(channel.recipe, title, anchored)
    }
}

// Films promise a title that is not a series; short series promise a series; no promise gives no bonus.
private fun formatFits(
    promise: FormatPromise?,
    title: Title,
): Boolean =
    when (promise) {
        FormatPromise.Films -> !title.isSeries
        FormatPromise.ShortSeries -> title.isSeries
        null -> false
    }

internal fun idSourcesOf(sources: List<Source>): List<Source> {
    val out = ArrayList<Source>()
    for (s in sources) if (s is Source.Collection || s is Source.Series || s is Source.Item) out.add(s)
    return out
}

private fun studioAnchorsOf(recipe: Recipe): List<NameMatcher> {
    val out = ArrayList<NameMatcher>()
    for (a in recipe.theme?.anchors.orEmpty()) if (a.kind == AnchorKind.Studio) out.add(a.match)
    return out
}

internal fun foldedDistinct(names: List<String>): List<String> {
    val out = ArrayList<String>(names.size)
    for (n in names) {
        val f = foldNameAscii(n)
        if (f !in out) out.add(f)
    }
    return out
}

private fun hits(
    wanted: List<String>,
    have: List<String>,
): Int {
    var n = 0
    for (w in wanted) if (w in have) n++
    return n
}

internal fun matchesAny(
    matchers: List<NameMatcher>,
    foldedNames: List<String>,
): Boolean {
    for (name in foldedNames) for (m in matchers) if (m.matches(name)) return true
    return false
}
