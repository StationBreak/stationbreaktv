// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.builder

import kotlinx.coroutines.CancellationException
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.lineup.ChannelNumbers
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
            number = firstFree(account, kids),
            seed = newSeed.next(),
        ).kidsNormalised()

    private suspend fun firstFree(
        account: LibraryAccount,
        kids: Boolean,
    ): Int? = (freeNumbers(account, kids) as? Listed.Ok)?.value?.firstOrNull()

    override suspend fun freeNumbers(
        account: LibraryAccount,
        kids: Boolean,
        forChannel: ChannelId?,
    ): Listed<List<Int>> {
        val lineup = lineupOf(account) ?: return Listed.Unavailable
        val free = LineupEdits.freeCustomNumbers(lineup, kids, service.catalog)
        val band = if (kids) ChannelNumbers.kids else ChannelNumbers.custom
        val own =
            lineup.channels
                .firstOrNull { it.id == forChannel }
                ?.number
                ?.takeIf { it in band }
        return Listed.Ok((free + listOfNotNull(own)).distinct().sorted())
    }

    override suspend fun open(
        account: LibraryAccount,
        id: ChannelId,
        copy: Boolean,
    ): OpenResult =
        guarded(OpenResult.Failed) {
            // Read here, not through lineupOf: a read that fails is Failed, not a channel that is not there.
            val lineup = service.loaded(account)?.lineup
            val channel = lineup?.channels?.firstOrNull { it.id == id }
            val rules = channel?.rulesToOpen(copy)
            when {
                lineup == null || channel == null -> OpenResult.NotFound
                rules == null -> OpenResult.NotCustomisable
                copy -> openedCopy(account, lineup, channel, rules)
                else -> OpenResult.Opened(editDraft(account, rules, channel), null)
            }
        }

    private suspend fun editDraft(
        account: LibraryAccount,
        rules: ChannelRules,
        channel: LineupChannel,
    ): ChannelDraft = draftOf(account, rules, channel.number).copy(origin = channel.id).kidsNormalised()

    private suspend fun openedCopy(
        account: LibraryAccount,
        lineup: Lineup,
        channel: LineupChannel,
        rules: ChannelRules,
    ): OpenResult {
        val name = channel.displayName(service.catalog)
        val kids = KidStatus.isKidChannel(channel)
        val number = LineupEdits.firstFreeCustom(lineup, kids, service.catalog)
        val draft = draftOf(account, rules.copy(seed = newSeed.next()), number)
        val kept = draft.kept?.copy(fromName = name)
        val opened = draft.copy(name = name, kids = kids, kept = kept, origin = channel.id).kidsNormalised()
        return OpenResult.Opened(opened, name)
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
            val sent = draft.kidsNormalised()
            val problem = sent.problem()
            val lineup = lineupOf(account)
            val previous = lineup?.let { originRules(it, sent.origin) }
            when {
                problem != null -> {
                    refused(BuildRefusal.Invalid(problem))
                }

                lineup == null -> {
                    BuildResult.NotEditable
                }

                sent.kept != null && previous == null -> {
                    refused(BuildRefusal.UnknownChannel)
                }

                else -> {
                    val id = ChannelId("custom:" + newId())
                    val rules = DraftRules.toRules(sent, lineup.utcOffsetMinutes, previous)
                    val number = sent.number.takeIf { sent.numberChosen }
                    edited(account, LineupEdit.AddCustom(id, rules, number, sent.kids), sent.kids, id, null)
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
            val sent = draft.kidsNormalised()
            val problem = sent.problem()
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
                    val rules = DraftRules.toRules(sent, lineup.utcOffsetMinutes, stored)
                    // A number the viewer did not pick is the channel's own, or the first free one on the other side
                    // of a kids switch: worked out in the lock. A swap needs the number it trades.
                    val number = sent.number.takeIf { sent.numberChosen || swapWith != null }
                    val edit = LineupEdit.ReplaceCustom(id, rules, number, swapWith, sent.kids)
                    edited(account, edit, sent.kids, id, sent.number)
                }
            }
        }

    override suspend fun reshuffle(
        account: LibraryAccount,
        id: ChannelId,
    ): BuildResult =
        guarded(BuildResult.Failed) {
            edited(account, LineupEdit.Reseed(id, newSeed.next()), false, id, null)
        }

    override suspend fun delete(
        account: LibraryAccount,
        id: ChannelId,
    ): BuildResult = guarded(BuildResult.Failed) { edited(account, LineupEdit.RemoveCustom(id), false, id, null) }

    private suspend fun edited(
        account: LibraryAccount,
        edit: LineupEdit,
        kids: Boolean,
        id: ChannelId,
        number: Int?,
    ): BuildResult =
        service.edit(account, edit).mapped(
            done = { BuildResult.Done(id) },
            refused = { reason ->
                val holder = (reason as? EditRefusal.NumberTaken)?.holder
                if (holder != null) {
                    refused(BuildRefusal.NumberTaken(holder, swappable(account, id, number, kids)))
                } else {
                    reason.toBuildRefusal(kids)?.let(::refused) ?: failed()
                }
            },
        ) {
            when (it) {
                Unsaved.NotEditable -> BuildResult.NotEditable
                Unsaved.SaveFailed -> BuildResult.SaveFailed
                Unsaved.Invalid -> BuildResult.Failed
            }
        }

    // Whether [id] may trade numbers with the holder of [number], in the lineup as it is now (the one that refused).
    private suspend fun swappable(
        account: LibraryAccount,
        id: ChannelId,
        number: Int?,
        kids: Boolean,
    ): Boolean {
        val lineup = lineupOf(account)
        return number != null && lineup != null && LineupEdits.canSwap(lineup, id, number, kids, service.catalog)
    }

    // The rules of the channel the draft was opened from, in the lineup as it is now.
    private fun originRules(
        lineup: Lineup,
        origin: ChannelId?,
    ): ChannelRules? {
        val from = origin ?: return null
        return when (val source = lineup.channels.firstOrNull { it.id == from }?.source) {
            is ChannelSource.Starter -> source.rules
            is ChannelSource.Custom -> source.rules
            else -> null
        }
    }

    // The engine's reasons a build can reach; a number taken is worked out by [edited]. The others cannot come from
    // these edits, and are a failure.
    private fun EditRefusal.toBuildRefusal(kids: Boolean): BuildRefusal? =
        when (this) {
            EditRefusal.UnknownChannel -> BuildRefusal.UnknownChannel

            EditRefusal.NotCustom -> BuildRefusal.NotCustom

            EditRefusal.NotShuffle -> BuildRefusal.NotShuffle

            EditRefusal.NoFreeNumber -> if (kids) BuildRefusal.KidsBandFull else BuildRefusal.NoFreeNumber

            EditRefusal.OutOfRange -> BuildRefusal.OutOfRange

            EditRefusal.LastOn -> BuildRefusal.LastOn

            is EditRefusal.NumberTaken -> null

            EditRefusal.KidsBandOnly -> BuildRefusal.KidsBand

            EditRefusal.NotSwitchable,
            EditRefusal.OptInNotConfirmed,
            EditRefusal.IdTaken,
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
fun newCustomId(): String = newCustomId(SecureRandom())

// For tests that need a known stream: the production entry point takes no random source.
internal fun newCustomId(random: Random): String {
    val bytes = ByteArray(ID_BYTES).also(random::nextBytes)
    val digits = "0123456789abcdef"
    return buildString(ID_BYTES * 2) {
        bytes.forEach {
            append(digits[(it.toInt() shr NIBBLE_BITS) and LOW_NIBBLE])
            append(digits[it.toInt() and LOW_NIBBLE])
        }
    }
}
