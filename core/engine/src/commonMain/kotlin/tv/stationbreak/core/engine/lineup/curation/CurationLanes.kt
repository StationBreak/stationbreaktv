// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup.curation

import tv.stationbreak.core.engine.SourceMatcher
import tv.stationbreak.core.engine.lineup.AnchorKind
import tv.stationbreak.core.engine.lineup.FormatPromise
import tv.stationbreak.core.engine.lineup.NameMatcher
import tv.stationbreak.core.engine.lineup.Recipe
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.foldNameAscii
import tv.stationbreak.core.model.schedule.ScheduleItem
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
 * One channel's working state. Its candidates [cand] are title indices, ascending; [ms], [score], [anchor], [home]
 * and [guest] run parallel to them. [pool] is the airable pool in canonical-ID order, and [itemPos] gives the
 * candidate position of each of its items, or -1 when the item's title is not a candidate here.
 */
internal class Lane(
    val channel: CurationChannel,
    val pool: List<ScheduleItem>,
) {
    var itemPos = IntArray(0)
    var cand = IntArray(0)
    var ms = LongArray(0)
    var score = LongArray(0)
    var anchor = BooleanArray(0)
    var home = BooleanArray(0)
    var guest = BooleanArray(0)

    val kid: Boolean get() = channel.kid

    /** A member airs here: every candidate of a kid channel, the homes and guests of a grown-up one. */
    fun isMember(pos: Int): Boolean = kid || home[pos] || guest[pos]
}

/**
 * Builds the lanes of [channels] (sorted by number, then ID): the airable pool (A4), the candidates after the anime
 * rule, each candidate's hours, anchors and fit score, each worked out once.
 */
internal class LaneBuilder(
    private val snapshot: RuleSnapshot,
    channels: List<CurationChannel>,
) {
    val lanes: Array<Lane> = Array(channels.size) { Lane(channels[it], airable(channels[it])) }
    private val entries = HashMap<CanonicalId, RuleItem>()

    /** The titles of every airable pool item the snapshot holds, ascending by ID. */
    val titles: Array<FoldedTitle>

    /** The position of a title ID in [titles]. A lookup only. */
    val titleIndex = HashMap<CanonicalId, Int>()

    // Indexed by title; each lane leaves them as it found them (0 and -1), so one pair serves every lane.
    private val scratchMs: LongArray
    private val scratchPos: IntArray

    init {
        titles = foldTitles()
        scratchMs = LongArray(titles.size)
        scratchPos = IntArray(titles.size)
        scratchPos.fill(-1)
        for (lane in lanes) fill(lane)
    }

    private fun foldTitles(): Array<FoldedTitle> {
        val all = ArrayList<ScheduleItem>()
        val wanted = HashSet<CanonicalId>()
        for (lane in lanes) {
            all.addAll(lane.pool)
            for (item in lane.pool) wanted.add(item.id)
        }
        for (entry in snapshot.items) if (entry.item.id in wanted) entries[entry.item.id] = entry
        val byId = Titles.of(snapshot, all)
        val ids = ArrayList(byId.keys)
        ids.sortWith(ID_ORDER)
        val out = ArrayList<FoldedTitle>(ids.size)
        for (i in ids.indices) {
            titleIndex[ids[i]] = i
            out.add(foldTitle(byId.getValue(ids[i])))
        }
        return out.toTypedArray()
    }

    private fun fill(lane: Lane) {
        val pool = lane.pool
        val itemTitle = IntArray(pool.size)
        val touched = IntArray(pool.size)
        var count = 0
        for (i in pool.indices) {
            val t = titleOf(pool[i])
            itemTitle[i] = if (t >= 0 && admits(lane.channel, titles[t].title)) t else -1
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
        for (p in cand.indices) {
            scratchPos[cand[p]] = p
            ms[p] = scratchMs[cand[p]]
        }
        lane.itemPos = IntArray(pool.size)
        for (i in pool.indices) lane.itemPos[i] = if (itemTitle[i] >= 0) scratchPos[itemTitle[i]] else -1
        for (t in cand) {
            scratchPos[t] = -1
            scratchMs[t] = 0L
        }
        lane.cand = cand
        lane.ms = ms
        lane.anchor = anchorsOf(lane)
        lane.score = scoresOf(lane)
        lane.home = BooleanArray(count)
        lane.guest = BooleanArray(count)
    }

    // The title of an item the snapshot holds, by its snapshot entry's group, or -1.
    private fun titleOf(item: ScheduleItem): Int {
        val entry = entries[item.id] ?: return -1
        return titleIndex[entry.item.groupId] ?: -1
    }

    // anchors(n): an item matched by a Collection, Series or Item source of the rules, or a studio matching a Studio
    // anchor of the theme. Anchors resolve into exactly those source kinds; a Studio source cannot tell an anchor
    // studio from a theme studio, so studio anchors are read from the theme through the same fold.
    private fun anchorsOf(lane: Lane): BooleanArray {
        val anchor = BooleanArray(lane.cand.size)
        val idSources = idSourcesOf(lane.channel.rules.sources)
        if (idSources.isNotEmpty()) markIdAnchors(lane, SourceMatcher(idSources, snapshot), anchor)
        val studioAnchors = studioAnchorsOf(lane.channel.recipe)
        if (studioAnchors.isNotEmpty()) {
            for (p in lane.cand.indices) {
                anchor[p] = anchor[p] || matchesAny(studioAnchors, titles[lane.cand[p]].studios)
            }
        }
        return anchor
    }

    private fun markIdAnchors(
        lane: Lane,
        matcher: SourceMatcher,
        anchor: BooleanArray,
    ) {
        for (i in lane.pool.indices) {
            val pos = lane.itemPos[i]
            if (pos < 0 || anchor[pos]) continue
            val entry = entries.getValue(lane.pool[i].id)
            val seriesId = entry.item.seriesId
            anchor[pos] = matcher.matches(entry, if (seriesId == null) null else snapshot.series[seriesId])
        }
    }

    // score(n, t) for t in cand(n), with toneMs and candMs over cand(n).
    private fun scoresOf(lane: Lane): LongArray {
        val recipe = lane.channel.recipe
        val genres = foldedDistinct(recipe.themeGenres)
        val tags = foldedDistinct(recipe.themeTags)
        val studios = recipe.theme?.themeStudios.orEmpty()
        val promise = recipe.theme?.format
        val toneMs = LongArray(TONES)
        var candMs = 0L
        for (p in lane.cand.indices) {
            toneMs[titles[lane.cand[p]].tone] += lane.ms[p]
            candMs += lane.ms[p]
        }
        val score = LongArray(lane.cand.size)
        for (p in lane.cand.indices) {
            val t = titles[lane.cand[p]]
            val inputs =
                FitInputs(
                    genreHits = hits(genres, t.genres),
                    tagHits = hits(tags, t.tags),
                    themeStudio = matchesAny(studios, t.studios),
                    guestOnly = recipe.guestOnly,
                    anchor = lane.anchor[p],
                    kidOnKid = t.title.madeForKids && lane.kid,
                    formatFits = formatFits(promise, t.title),
                    toneMs = toneMs[t.tone],
                    candidateMs = candMs,
                )
            score[p] = fitScoreQ16(lane.cand.size, inputs)
        }
        return score
    }
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

// The anime rule: an anime-only channel keeps only anime; a grown-up channel that is not anime-only drops it.
private fun admits(
    channel: CurationChannel,
    title: Title,
): Boolean {
    val anime = title.format == TitleFormat.Anime
    return when {
        channel.recipe.theme?.animeOnly == true -> anime
        !channel.kid -> !anime
        else -> true
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

private fun idSourcesOf(sources: List<Source>): List<Source> {
    val out = ArrayList<Source>()
    for (s in sources) if (s is Source.Collection || s is Source.Series || s is Source.Item) out.add(s)
    return out
}

private fun studioAnchorsOf(recipe: Recipe): List<NameMatcher> {
    val out = ArrayList<NameMatcher>()
    for (a in recipe.theme?.anchors.orEmpty()) if (a.kind == AnchorKind.Studio) out.add(a.match)
    return out
}

private fun foldedDistinct(names: List<String>): List<String> {
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

private fun matchesAny(
    matchers: List<NameMatcher>,
    foldedNames: List<String>,
): Boolean {
    for (name in foldedNames) for (m in matchers) if (m.matches(name)) return true
    return false
}
