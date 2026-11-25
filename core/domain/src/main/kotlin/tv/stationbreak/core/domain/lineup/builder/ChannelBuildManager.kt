// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.builder

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import tv.stationbreak.core.domain.FreeTier
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.ProEntitlement
import tv.stationbreak.core.domain.lineup.BandNeeded
import tv.stationbreak.core.domain.lineup.ChannelNumbers
import tv.stationbreak.core.domain.lineup.FamilyTier
import tv.stationbreak.core.domain.lineup.LineupEvent
import tv.stationbreak.core.domain.lineup.LineupService
import tv.stationbreak.core.domain.lineup.Unsaved
import tv.stationbreak.core.domain.lineup.displayName
import tv.stationbreak.core.domain.lineup.lockedCustomIds
import tv.stationbreak.core.domain.lineup.mapped
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.EditRefusal
import tv.stationbreak.core.engine.lineup.KidStatus
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.engine.lineup.LineupEdit
import tv.stationbreak.core.engine.lineup.LineupEdits
import tv.stationbreak.core.engine.lineup.LineupIds
import tv.stationbreak.core.engine.lineup.viewerCustomCount
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.rules.ChannelRules
import java.security.SecureRandom
import java.util.Random

/**
 * Builds channels through [LineupService], under its lock (spec 22): every number is worked out inside the edit, and
 * [newSeed] is drawn only for a blank draft, a copy and a reshuffle, never by a create or a save. [newId] gives the
 * unique part of a new channel's ID. [labels] gives the words a viewer sees for the sources of a channel being opened,
 * and [studioNames] the spelling shown for each studio its rules hold folded (`SourceLabels.studios`). Without [pro] a
 * lineup may hold [FreeTier.CUSTOM_CHANNELS] custom channels, and creating one more is refused (#793).
 */
@Suppress("TooManyFunctions") // The port's eight, and the steps they share.
class ChannelBuildManager(
    private val service: LineupService,
    private val pro: ProEntitlement,
    private val newSeed: NewSeed,
    private val newId: () -> String,
    labels: suspend (LibraryAccount, List<DraftSource>) -> Map<DraftSource, String>,
    studioNames: StudioNames,
) : ChannelBuilder {
    // The one reading of rules into words, shared with a channel's properties.
    private val reader = DraftReader(labels, studioNames) { service.reportEvent(LineupEvent.BuildFailed) }

    override suspend fun blank(
        account: LibraryAccount,
        tier: FamilyTier?,
    ): ChannelDraft =
        ChannelDraft(
            name = "",
            sources = emptyList(),
            leaveOut = emptyList(),
            filters = DraftFilters(),
            tier = tier,
            number = firstFree(account, tier),
            seed = newSeed.next(),
        ).tierNormalised()

    private suspend fun firstFree(
        account: LibraryAccount,
        tier: FamilyTier?,
    ): Int? = (freeNumbers(account, tier) as? Listed.Ok)?.value?.firstOrNull()

    override suspend fun freeNumbers(
        account: LibraryAccount,
        tier: FamilyTier?,
        forChannel: ChannelId?,
    ): Listed<List<Int>> {
        val lineup = lineupOf(account) ?: return Listed.Unavailable
        val free = LineupEdits.freeCustomNumbers(lineup, tier?.band, service.catalog)
        val band = tier?.numbers ?: ChannelNumbers.custom
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
                copy && channel.isAdultBuiltIn() && !pro.pro.value -> OpenResult.NeedsPro
                !pro.pro.value && id in lineup.channels.lockedCustomIds() -> OpenResult.NeedsPro
                copy -> openedCopy(account, lineup, channel, rules)
                else -> OpenResult.Opened(editDraft(account, rules, channel), null)
            }
        }

    private suspend fun editDraft(
        account: LibraryAccount,
        rules: ChannelRules,
        channel: LineupChannel,
    ): ChannelDraft {
        val draft = reader.draftOf(account, rules, channel.number)
        // The rules hold the channel's own name, not the built-in it was copied from, which is not stored: name nobody.
        val kept = draft.kept?.copy(fromName = null)
        return draft.copy(origin = channel.id, kept = kept, tier = tierOf(channel)).tierNormalised()
    }

    private suspend fun openedCopy(
        account: LibraryAccount,
        lineup: Lineup,
        channel: LineupChannel,
        rules: ChannelRules,
    ): OpenResult {
        val name = channel.displayName(service.catalog)
        // A copy of a built-in is a Tots channel when the built-in is a kid channel, else a grown-up one.
        val tier = FamilyTier.Tots.takeIf { KidStatus.isKidChannel(channel) }
        val number = LineupEdits.firstFreeCustom(lineup, tier?.band, service.catalog)
        val draft = reader.draftOf(account, rules.copy(seed = newSeed.next()), number)
        val kept = draft.kept?.copy(fromName = name)
        val opened =
            draft.copy(name = name, tier = tier, kept = kept, origin = channel.id, adult = channel.isAdultBuiltIn())
        return OpenResult.Opened(opened.tierNormalised(), name)
    }

    // The tier of a custom channel being edited: the band its number is in; a kid channel by its ID (a retired kid
    // starter kept as Custom) whose number is in no young band is Tots, so a stored Kids or Teens number is kept.
    private fun tierOf(channel: LineupChannel): FamilyTier? =
        FamilyTier.of(channel.number) ?: FamilyTier.Tots.takeIf { KidStatus.isKidChannel(channel) }

    // A copy is of a built-in channel's rules; an edit is of a custom channel's own.
    private fun LineupChannel.rulesToOpen(copy: Boolean): ChannelRules? =
        when (val source = source) {
            is ChannelSource.Starter -> source.rules.takeIf { copy }
            is ChannelSource.Custom -> source.rules.takeIf { !copy }
            else -> null
        }

    override suspend fun create(
        account: LibraryAccount,
        draft: ChannelDraft,
    ): BuildResult =
        guarded(BuildResult.Failed) {
            val sent = draft.tierNormalised()
            val problem = sent.problem()
            val lineup = lineupOf(account)
            val previous = lineup?.let { originRules(it, sent.origin) }
            val adultOrigin = lineup?.isAdultOrigin(sent.origin) == true
            when {
                problem != null -> {
                    refused(BuildRefusal.Invalid(problem))
                }

                adultOrigin && !pro.pro.value -> {
                    refused(BuildRefusal.NeedsPro)
                }

                lineup == null -> {
                    BuildResult.NotEditable
                }

                sent.kept != null && previous == null -> {
                    refused(BuildRefusal.UnknownChannel)
                }

                else -> {
                    val id = LineupIds.custom(newId())
                    val target = RulesTarget.NewChannel(previous, adultOrigin)
                    val rules = DraftRules.toRules(sent, lineup.utcOffsetMinutes, target)
                    val number = sent.number.takeIf { sent.numberChosen }
                    val limit = if (pro.pro.value) null else FreeTier.CUSTOM_CHANNELS
                    // No limit was set because Pro was on: Pro must still be on in the lock (#1063).
                    edited(account, LineupEdit.AddCustom(id, rules, number, sent.tier?.band, limit), sent.tier, id) {
                        limit != null || pro.pro.value
                    }
                }
            }
        }

    override suspend fun save(
        account: LibraryAccount,
        id: ChannelId,
        draft: ChannelDraft,
    ): BuildResult =
        guarded(BuildResult.Failed) {
            val sent = draft.tierNormalised()
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
                    val rules = DraftRules.toRules(sent, lineup.utcOffsetMinutes, RulesTarget.Edit(stored))
                    // A number the viewer did not pick is the channel's own, or the first free one of the other band
                    // after a tier change: worked out in the lock.
                    val number = sent.number.takeIf { sent.numberChosen }
                    val edit = LineupEdit.ReplaceCustom(id, rules, number, band = sent.tier?.band)
                    edited(account, edit, sent.tier, id, unlocked(id))
                }
            }
        }

    override suspend fun reshuffle(
        account: LibraryAccount,
        id: ChannelId,
    ): BuildResult =
        guarded(BuildResult.Failed) {
            edited(account, LineupEdit.Reseed(id, newSeed.next()), null, id, unlocked(id))
        }

    override suspend fun delete(
        account: LibraryAccount,
        id: ChannelId,
    ): BuildResult =
        guarded(BuildResult.Failed) {
            edited(account, LineupEdit.RemoveCustom(id), null, id, unlocked(id))
        }

    // A custom channel locked after a refund (ADR-0023, #99) is kept, not changed or deleted until Pro is back. Asked
    // in the lineup's lock (#1063), on the lineup the edit would change.
    private fun unlocked(id: ChannelId): (Lineup) -> Boolean = { pro.pro.value || id !in it.channels.lockedCustomIds() }

    override fun canAddCustom(account: LibraryAccount): Flow<Boolean> =
        flow {
            service.loaded(account)
            emitAll(
                combine(pro.pro, service.lineup(account)) { isPro, held ->
                    val lineup = held?.lineup
                    if (lineup == null) isPro else isPro || lineup.viewerCustomCount() < FreeTier.CUSTOM_CHANNELS
                },
            )
        }.distinctUntilChanged()

    // [allowed] is asked in the lineup's lock: false is [BuildRefusal.NeedsPro].
    private suspend fun edited(
        account: LibraryAccount,
        edit: LineupEdit,
        tier: FamilyTier?,
        id: ChannelId,
        allowed: (Lineup) -> Boolean,
    ): BuildResult =
        service.edit(account, edit, allowed = allowed).mapped(
            done = { BuildResult.Done(id) },
            refused = { reason ->
                val holder = (reason as? EditRefusal.NumberTaken)?.holder
                if (holder != null) {
                    refused(BuildRefusal.NumberTaken(holder))
                } else {
                    reason.toBuildRefusal(tier)?.let(::refused) ?: failed()
                }
            },
        ) {
            when (it) {
                Unsaved.NotEditable -> BuildResult.NotEditable
                Unsaved.SaveFailed -> BuildResult.SaveFailed
                Unsaved.Invalid -> BuildResult.Failed
                Unsaved.Denied -> refused(BuildRefusal.NeedsPro)
            }
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
    private fun EditRefusal.toBuildRefusal(tier: FamilyTier?): BuildRefusal? =
        when (this) {
            EditRefusal.UnknownChannel -> BuildRefusal.UnknownChannel

            EditRefusal.NotCustom -> BuildRefusal.NotCustom

            EditRefusal.NotShuffle -> BuildRefusal.NotShuffle

            EditRefusal.NoFreeNumber -> noFreeNumber(tier)

            EditRefusal.OutOfRange -> BuildRefusal.OutOfRange

            EditRefusal.LastOn -> BuildRefusal.LastOn

            is EditRefusal.NumberTaken -> null

            EditRefusal.KidsBandOnly -> BuildRefusal.KidsBand

            is EditRefusal.OutsideBand -> BuildRefusal.OutsideTierBand(FamilyTier.ofBand(band))

            is EditRefusal.BelowBand -> BuildRefusal.BelowBand(BandNeeded.of(needs))

            EditRefusal.CustomLimit -> BuildRefusal.CustomLimit

            EditRefusal.NotSwitchable,
            EditRefusal.OptInNotConfirmed,
            EditRefusal.IdTaken,
            -> null
        }

    private fun noFreeNumber(tier: FamilyTier?): BuildRefusal =
        when (tier) {
            null -> BuildRefusal.NoFreeNumber
            FamilyTier.Tots -> BuildRefusal.KidsBandFull
            else -> BuildRefusal.TierBandFull(tier)
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

/** The spelling to show for each studio name a channel's rules hold (see [SourceLabels.studios]). */
typealias StudioNames = suspend (LibraryAccount, Collection<String>) -> Map<String, String>
