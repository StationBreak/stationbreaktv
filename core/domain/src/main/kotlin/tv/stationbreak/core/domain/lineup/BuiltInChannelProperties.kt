// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.domain.lineup.builder.DraftReader
import tv.stationbreak.core.domain.lineup.builder.DraftSource
import tv.stationbreak.core.domain.lineup.builder.PlaysRow
import tv.stationbreak.core.domain.lineup.builder.RulesDescription
import tv.stationbreak.core.domain.lineup.builder.StudioNames
import tv.stationbreak.core.domain.lineup.builder.playsRowsOf
import tv.stationbreak.core.engine.lineup.Anchor
import tv.stationbreak.core.engine.lineup.AnchorKind
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.engine.lineup.NameMatcher
import tv.stationbreak.core.engine.lineup.Recipe
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.rules.ChannelRules
import kotlin.time.Instant

/**
 * The [ChannelPropertiesReader] built into the app: the channel and its recipe from [service]'s lineup and catalog,
 * the summary the Channels screen shows (so the two agree), the hidden reason from [schedule], and the words for its
 * sources from [labels] and [studioNames] (the lookups the builder's open uses, through the same [DraftReader]).
 *
 * What the channel holds comes from [holdings], the same reader the guide's channel box uses. It reads the guide's
 * [ChannelIdentity] of every channel too, from the same recipe, rules and lookups, so the box and the sheet agree.
 *
 * It takes no lineup lock and writes nothing. A lookup that fails costs the words (a source shows its key), as in the
 * builder, and an unreadable schedule costs the hidden reason; neither fails the read.
 */
class BuiltInChannelProperties(
    private val service: LineupService,
    private val library: LibraryRepository,
    private val schedule: ScheduleSource,
    labels: suspend (LibraryAccount, List<DraftSource>) -> Map<DraftSource, String>,
    studioNames: StudioNames,
    private val holdings: ChannelHoldingsReader,
) : ChannelPropertiesReader,
    ChannelIdentityReader {
    private val reader = DraftReader(labels, studioNames)

    override suspend fun read(
        id: ChannelId,
        at: Instant,
    ): ChannelProperties? {
        val account = library.activeAccount().first()
        val channel =
            account?.let {
                service
                    .loaded(it)
                    ?.lineup
                    ?.channels
                    ?.firstOrNull { c -> c.id == id }
            }
        val summary = account?.let { service.channels(it).first().firstOrNull { s -> s.id == id } }
        return if (account == null || channel == null || summary == null) {
            null
        } else {
            propertiesOf(account, channel, summary, at)
        }
    }

    // The last whole map, with the lineup and summaries it was read from: a reopen with no change looks nothing up.
    @Volatile private var identitiesRead: Pair<Any, Map<ChannelId, ChannelIdentity>>? = null

    override fun identities(): Flow<Map<ChannelId, ChannelIdentity>> =
        flow {
            val account = library.activeAccount().first()
            val channels =
                account
                    ?.let { service.loaded(it) }
                    ?.lineup
                    ?.channels
                    .orEmpty()
            val summaries = account?.let { service.channels(it).first() }.orEmpty().associateBy { it.id }
            val key = Triple(account, channels, summaries)
            val kept = identitiesRead?.takeIf { it.first == key }?.second
            if (account == null || kept != null) {
                emit(kept.orEmpty())
                return@flow
            }
            val known = channels.mapNotNull { c -> summaries[c.id]?.let { c to identityOf(c, it) } }
            emit(known.associate { (c, identity) -> c.id to identity })
            val whole =
                known.associate { (c, identity) ->
                    c.id to identity.copy(plays = if (identity.personality == null) firstPlaysRow(account, c) else null)
                }
            identitiesRead = key to whole
            emit(whole)
        }

    // Everything but the plays row: no lookups.
    private fun identityOf(
        channel: LineupChannel,
        summary: ChannelSummary,
    ) = ChannelIdentity(
        kind = summary.kind,
        kid = summary.kid,
        ceiling = channel.rules()?.maxRating ?: leftoversCeiling(channel),
        personality = starterRecipe(channel)?.personality?.takeIf { it.isNotBlank() },
        plays = null,
    )

    // The first row of what [channel] plays, as the sheet lists them; its sources are named by the lookups.
    private suspend fun firstPlaysRow(
        account: LibraryAccount,
        channel: LineupChannel,
    ): PlaysRow? {
        val description = channel.rules()?.let { describe(account, it, channel) }
        return playsRowsOf(description, leftoversCeiling = leftoversCeiling(channel)).firstOrNull()
    }

    private suspend fun propertiesOf(
        account: LibraryAccount,
        channel: LineupChannel,
        summary: ChannelSummary,
        at: Instant,
    ): ChannelProperties {
        val rules = channel.rules()
        val description = rules?.let { describe(account, it, channel) }
        val hidden = hiddenAt(channel.id, at)
        return ChannelProperties(
            summary = summary,
            personality = starterRecipe(channel)?.personality,
            description = description,
            anchors =
                starterRecipe(channel)
                    ?.theme
                    ?.anchors
                    .orEmpty()
                    .map { it.shown() },
            timing = rules?.let(::timingOf),
            leftoversCeiling = leftoversCeiling(channel),
            hidden = hidden,
            holdings = holdings.readOrNull(channel.id, at),
        )
    }

    private suspend fun describe(
        account: LibraryAccount,
        rules: ChannelRules,
        channel: LineupChannel,
    ): RulesDescription {
        val described = RulesDescription.of(rules, reader.draftOf(account, rules, channel.number))
        // A studio filter is spelled as the library spells it, like the studio sources and exclusions.
        val studios = reader.spelled(account, described.narrowing.studios)
        return described.copy(narrowing = described.narrowing.copy(studios = studios))
    }

    private fun starterRecipe(channel: LineupChannel): Recipe? {
        val starter = channel.source as? ChannelSource.Starter ?: return null
        return service.catalog.recipes.firstOrNull { it.key == starter.recipeKey }
    }

    // While the key is in the catalog the planner takes the ceiling from it; the stored one counts once it has left.
    private fun leftoversCeiling(channel: LineupChannel): String? {
        val leftovers = channel.source as? ChannelSource.Leftovers ?: return null
        return service.catalog.leftovers
            .firstOrNull { it.key == leftovers.recipeKey }
            ?.maxRating ?: leftovers.maxRating
    }

    // The channel is not on the air (shelf, hidden), or the schedule is not readable: no reason either way.
    private suspend fun hiddenAt(
        id: ChannelId,
        at: Instant,
    ): HiddenReason? =
        try {
            schedule.airings(at).firstOrNull { it.id == id }?.hidden
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

    private fun timingOf(rules: ChannelRules) =
        ChannelTiming(
            dayStart = rules.timing.broadcastDay.effectiveStart,
            blockLength = rules.timing.effectiveBlockLength.takeIf { it > 1 },
            scheduleCode = scheduleCodeOf(rules.seed),
        )
}

// A read that fails costs the holdings, not the properties.
private suspend fun ChannelHoldingsReader.readOrNull(
    id: ChannelId,
    at: Instant,
): ChannelHoldings? =
    try {
        read(id, at)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

private fun Anchor.shown() = ThemeAnchor(kind.shown(), name(match))

private fun AnchorKind.shown() =
    when (this) {
        AnchorKind.Collection -> ThemeAnchorKind.Collection
        AnchorKind.Title -> ThemeAnchorKind.Title
        AnchorKind.Studio -> ThemeAnchorKind.Studio
    }

private fun name(matcher: NameMatcher): String =
    when (matcher) {
        is NameMatcher.Exact -> matcher.name
        is NameMatcher.Prefix -> matcher.name
        is NameMatcher.Contains -> matcher.name
        is NameMatcher.Word -> matcher.name
    }

// The rules a viewer can read: a starter's and a custom channel's own. Leftovers and unreadable channels have none.
private fun LineupChannel.rules(): ChannelRules? =
    when (val source = source) {
        is ChannelSource.Starter -> source.rules
        is ChannelSource.Custom -> source.rules
        is ChannelSource.Leftovers -> null
        is ChannelSource.Unusable -> null
    }
