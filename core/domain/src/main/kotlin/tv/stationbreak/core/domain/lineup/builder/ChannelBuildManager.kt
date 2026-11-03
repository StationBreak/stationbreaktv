// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.builder

import kotlinx.coroutines.CancellationException
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.lineup.LineupEvent
import tv.stationbreak.core.domain.lineup.LineupService
import tv.stationbreak.core.domain.lineup.Unsaved
import tv.stationbreak.core.domain.lineup.displayName
import tv.stationbreak.core.domain.lineup.mapped
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.EditRefusal
import tv.stationbreak.core.engine.lineup.KidStatus
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.engine.lineup.LineupEdit
import tv.stationbreak.core.engine.lineup.LineupEdits
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.rules.ChannelRules
import java.security.SecureRandom
import java.util.Random

/**
 * Builds channels through [LineupService], under its lock (spec 22): every number is worked out inside the edit, and
 * [newSeed] is drawn only for a blank draft, a copy and a reshuffle, never by a create or a save. [newId] gives the
 * unique part of a new channel's ID. [labels] gives the words a viewer sees for the sources of a channel being opened.
 */
@Suppress("TooManyFunctions") // The port's seven, and the steps they share.
class ChannelBuildManager(
    private val service: LineupService,
    private val newSeed: NewSeed,
    private val newId: () -> String,
    private val labels: suspend (LibraryAccount, List<DraftSource>) -> Map<DraftSource, String>,
) : ChannelBuilder {
    override suspend fun blank(
        account: LibraryAccount,
        kids: Boolean,
    ): ChannelDraft =
        ChannelDraft(
            name = "",
            sources = emptyList(),
            leaveOut = emptyList(),
            filters = DraftFilters(),
            kids = kids,
            number = numberFor(account, kids),
            seed = newSeed.next(),
        )

    override suspend fun numberFor(
        account: LibraryAccount,
        kids: Boolean,
    ): Int? = lineupOf(account)?.let { LineupEdits.firstFreeCustom(it, kids, service.catalog) }

    override suspend fun open(
        account: LibraryAccount,
        id: ChannelId,
        copy: Boolean,
    ): OpenResult {
        val lineup = lineupOf(account)
        val channel = lineup?.channels?.firstOrNull { it.id == id }
        val rules = channel?.rulesToOpen(copy)
        if (lineup == null || channel == null || rules == null) {
            return if (channel == null) OpenResult.NotFound else OpenResult.NotCustomisable
        }
        val name = channel.displayName(service.catalog)
        return if (copy) {
            val kids = KidStatus.isKidChannel(channel)
            val number = LineupEdits.firstFreeCustom(lineup, kids, service.catalog)
            val draft = draftOf(account, rules.copy(seed = newSeed.next()), number)
            val kept = draft.kept?.copy(fromName = name, sourceId = id)
            OpenResult.Opened(draft.copy(name = name, kids = kids, kept = kept), name)
        } else {
            val draft = draftOf(account, rules, channel.number)
            OpenResult.Opened(draft.copy(kept = draft.kept?.copy(sourceId = id)), null)
        }
    }

    // A copy is of a built-in channel's rules; an edit is of a custom channel's own.
    private fun LineupChannel.rulesToOpen(copy: Boolean): ChannelRules? =
        when (val source = source) {
            is ChannelSource.Starter -> source.rules.takeIf { copy }
            is ChannelSource.Custom -> source.rules.takeIf { !copy }
            else -> null
        }

    // The words come first, because they are looked up with a suspend call and the conversion to a draft is not.
    private suspend fun draftOf(
        account: LibraryAccount,
        rules: ChannelRules,
        number: Int?,
    ): ChannelDraft {
        val bare = DraftRules.fromRules(rules, number) { it.label }
        val words = guarded(emptyMap()) { labels(account, (bare.sources + bare.leaveOut).distinct()) }
        return DraftRules.fromRules(rules, number) { words[it] ?: it.key }
    }

    override suspend fun create(
        account: LibraryAccount,
        draft: ChannelDraft,
    ): BuildResult =
        guarded(BuildResult.Failed) {
            val problem = draft.problem()
            val lineup = lineupOf(account)
            val previous = lineup?.let { keptFrom(it, draft) }
            when {
                problem != null -> {
                    refused(BuildRefusal.Invalid(problem))
                }

                lineup == null -> {
                    BuildResult.NotEditable
                }

                draft.kept != null && previous == null -> {
                    refused(BuildRefusal.UnknownChannel)
                }

                else -> {
                    val id = ChannelId("custom:" + newId())
                    val rules = DraftRules.toRules(draft, lineup.utcOffsetMinutes, previous)
                    val number = draft.number.takeIf { draft.numberChosen }
                    edited(account, LineupEdit.AddCustom(id, rules, number, draft.kids), draft.kids, id)
                }
            }
        }

    override suspend fun save(
        account: LibraryAccount,
        id: ChannelId,
        draft: ChannelDraft,
        swapWith: ChannelId?,
    ): BuildResult =
        guarded(BuildResult.Failed) {
            val problem = draft.problem()
            val lineup = lineupOf(account)
            val channel = lineup?.channels?.firstOrNull { it.id == id }
            val stored = (channel?.source as? ChannelSource.Custom)?.rules
            when {
                problem != null -> {
                    refused(BuildRefusal.Invalid(problem))
                }

                lineup == null -> {
                    BuildResult.NotEditable
                }

                channel == null -> {
                    refused(BuildRefusal.UnknownChannel)
                }

                stored == null -> {
                    refused(BuildRefusal.NotCustom)
                }

                else -> {
                    val rules = DraftRules.toRules(draft, lineup.utcOffsetMinutes, stored)
                    // A number the viewer did not pick is the channel's own, or the first free one on the other side
                    // of a kids switch: worked out in the lock. A swap needs the number it trades.
                    val number = draft.number.takeIf { draft.numberChosen || swapWith != null }
                    val edit = LineupEdit.ReplaceCustom(id, rules, number, swapWith, draft.kids)
                    edited(account, edit, draft.kids, id)
                }
            }
        }

    override suspend fun reshuffle(
        account: LibraryAccount,
        id: ChannelId,
    ): BuildResult = guarded(BuildResult.Failed) { edited(account, LineupEdit.Reseed(id, newSeed.next()), false, id) }

    override suspend fun delete(
        account: LibraryAccount,
        id: ChannelId,
    ): BuildResult = guarded(BuildResult.Failed) { edited(account, LineupEdit.RemoveCustom(id), false, id) }

    private suspend fun edited(
        account: LibraryAccount,
        edit: LineupEdit,
        kids: Boolean,
        id: ChannelId,
    ): BuildResult =
        service.edit(account, edit).mapped(
            done = { BuildResult.Done(id) },
            refused = { reason -> reason.toBuildRefusal(kids)?.let(::refused) ?: failed() },
        ) {
            when (it) {
                Unsaved.NotEditable -> BuildResult.NotEditable
                Unsaved.SaveFailed -> BuildResult.SaveFailed
                Unsaved.Invalid -> BuildResult.Failed
            }
        }

    // The rules a copied draft's kept settings were read from, in the lineup as it is now.
    private fun keptFrom(
        lineup: Lineup,
        draft: ChannelDraft,
    ): ChannelRules? {
        val from = draft.kept?.sourceId ?: return null
        return when (val source = lineup.channels.firstOrNull { it.id == from }?.source) {
            is ChannelSource.Starter -> source.rules
            is ChannelSource.Custom -> source.rules
            else -> null
        }
    }

    // The engine's reasons that a build can reach; the others cannot happen from these edits, and are a failure.
    private fun EditRefusal.toBuildRefusal(kids: Boolean): BuildRefusal? =
        when (this) {
            EditRefusal.UnknownChannel -> BuildRefusal.UnknownChannel

            EditRefusal.NotCustom -> BuildRefusal.NotCustom

            EditRefusal.NoFreeNumber -> if (kids) BuildRefusal.KidsBandFull else BuildRefusal.NoFreeNumber

            EditRefusal.OutOfRange -> BuildRefusal.OutOfRange

            EditRefusal.LastOn -> BuildRefusal.LastOn

            is EditRefusal.NumberTaken -> BuildRefusal.NumberTaken(holder)

            EditRefusal.NotSwitchable,
            EditRefusal.OptInNotConfirmed,
            EditRefusal.IdTaken,
            EditRefusal.KidsBandOnly,
            -> null
        }

    private fun refused(reason: BuildRefusal): BuildResult = BuildResult.Refused(reason)

    private fun failed(): BuildResult {
        service.reportEvent(LineupEvent.BuildFailed)
        return BuildResult.Failed
    }

    // The lineup as the service holds it, or null when there is none (or the store fails, which is reported).
    private suspend fun lineupOf(account: LibraryAccount): Lineup? = guarded(null) { service.loaded(account)?.lineup }

    // Any failure other than cancellation (a store that throws, though none should) is [fallback], and reported: the
    // service saves before it publishes, so nothing it has not saved is ever shown.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> guarded(
        fallback: T,
        block: suspend () -> T,
    ): T =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            service.reportEvent(LineupEvent.BuildFailed)
            fallback
        }
}

private const val ID_BYTES = 16
private const val LOW_NIBBLE = 0xF
private const val NIBBLE_BITS = 4

/** The unique part of a new custom channel's ID: 128 random bits as 32 lower-case hex digits. */
fun newCustomId(random: Random = SecureRandom()): String {
    val bytes = ByteArray(ID_BYTES).also(random::nextBytes)
    val digits = "0123456789abcdef"
    return buildString(ID_BYTES * 2) {
        bytes.forEach {
            append(digits[(it.toInt() shr NIBBLE_BITS) and LOW_NIBBLE])
            append(digits[it.toInt() and LOW_NIBBLE])
        }
    }
}
