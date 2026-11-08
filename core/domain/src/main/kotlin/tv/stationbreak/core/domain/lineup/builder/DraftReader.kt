// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.builder

import kotlinx.coroutines.CancellationException
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.rules.ChannelRules

/**
 * Reads rules into a [ChannelDraft] with the words a viewer sees: the labels of their sources, from the libraries, and
 * the spelling of their studios. The builder opens a channel through it, and a channel's properties read the same
 * rules through it, so a source or a studio is worded one way. A lookup that fails costs the words, not the draft:
 * [onLookupFailed] is told, and the sources show their keys.
 */
internal class DraftReader(
    private val labels: suspend (LibraryAccount, List<DraftSource>) -> Map<DraftSource, String>,
    private val studioNames: StudioNames,
    private val onLookupFailed: () -> Unit = {},
) {
    /** The draft of [rules] at [number]. */
    suspend fun draftOf(
        account: LibraryAccount,
        rules: ChannelRules,
        number: Int?,
    ): ChannelDraft {
        // The words come first, because they are looked up with a suspend call and the conversion to a draft is not.
        val bare = DraftRules.fromRules(rules, number) { it.label }
        val words = guarded(emptyMap()) { labels(account, (bare.sources + bare.leaveOut).distinct()) }
        val draft = DraftRules.fromRules(rules, number) { words[it] ?: it.key }
        return withStudioNames(account, draft)
    }

    /** [names] (studios as the rules hold them, maybe folded) in the spelling the library shows, in order. */
    suspend fun spelled(
        account: LibraryAccount,
        names: List<String>,
    ): List<String> {
        if (names.isEmpty()) return names
        val shown = guarded(emptyMap()) { studioNames(account, names.distinct()) }
        return names.map { shown[it] ?: it }
    }

    // A studio the rules hold folded is shown by a spelling the library holds. Only the kept lines change: the rules
    // that are saved come from the stored ones.
    private suspend fun withStudioNames(
        account: LibraryAccount,
        draft: ChannelDraft,
    ): ChannelDraft {
        val kept = draft.kept
        val names =
            kept
                ?.items
                .orEmpty()
                .mapNotNull { studioOf(it) }
                .distinct()
        if (kept == null || names.isEmpty()) return draft
        val shown = guarded(emptyMap()) { studioNames(account, names) }
        val items =
            kept.items.map { item ->
                when (item) {
                    is KeptItem.StudioSource -> KeptItem.StudioSource(shown[item.name] ?: item.name)
                    is KeptItem.StudioExclusion -> KeptItem.StudioExclusion(shown[item.name] ?: item.name)
                    else -> item
                }
            }
        return draft.copy(kept = kept.copy(items = items))
    }

    private fun studioOf(item: KeptItem): String? =
        when (item) {
            is KeptItem.StudioSource -> item.name
            is KeptItem.StudioExclusion -> item.name
            else -> null
        }

    private suspend fun <T> guarded(
        fallback: T,
        block: suspend () -> T,
    ): T =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            onLookupFailed()
            fallback
        }
}
