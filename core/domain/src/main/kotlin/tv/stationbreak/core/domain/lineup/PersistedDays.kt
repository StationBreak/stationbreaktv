// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.CancellationException
import tv.stationbreak.core.domain.DayStore
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.StoredBytes
import tv.stationbreak.core.engine.GrownUpHold
import tv.stationbreak.core.engine.KidHold
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.SchedulerSettings
import java.security.MessageDigest
import java.util.Collections
import kotlin.time.TimeSource

/** What became of one read or write of the days on disk. Numbers and a closed reason only: safe to log. */
sealed interface DaySnapshotEvent {
    /** The days came from disk: the file's size and how long reading it took. */
    data class Hit(
        val bytes: Long,
        val ms: Long,
    ) : DaySnapshotEvent

    /** No usable file: the days are built as usual. */
    data class Miss(
        val reason: DaySnapshotMiss,
    ) : DaySnapshotEvent

    /** A cold build's days were written: the file's size and how long writing it took. */
    data class Saved(
        val bytes: Long,
        val ms: Long,
    ) : DaySnapshotEvent

    /** The days could not be written; the next process builds them cold. */
    data object SaveFailed : DaySnapshotEvent
}

/** Why the days on disk were not used: no file, a file that won't read, or the first part of the key that moved. */
enum class DaySnapshotMiss {
    Absent,
    Unreadable,
    Format,
    App,
    Holds,
    Account,
    Libraries,
    Generation,
    Catalog,
    Offset,
    Lineup,
    Cutoffs,
    Channels,
}

/**
 * The built days kept on disk across processes (#480), so that a new process starts from the day the last one built
 * instead of building it cold (10 s or more on older Fire TVs). [BuiltInScheduleSource] asks it before a build and
 * gives it each cold build after.
 *
 * What is kept is the result of the day build, not its input: per channel, the day pool and why the day is empty
 * ([DaySnapshotCodec]). The days are a pure function of the lineup, the library snapshots at their cut-offs and the
 * code (ADR-0002), so a file is used only when its [DayKey] equals the key of the build it replaces, and that key holds
 * every one of them:
 * - [appBuild] is the guard for the code: it must change with every install of the app (the app gives its version code
 *   and install time, since the version code alone does not move between builds), so no build of the app ever reads
 *   days that another build made, whatever changed in the catalog, the holds, the evaluator or the scheduler;
 * - the account, the chosen libraries and the content generation, read before the build: the generation moves with
 *   every change to what `ruleSnapshot` returns, the first full collection pass (which turns `collectionsSynced` true)
 *   included. The start of the last collection pass is not in the key: every open runs a pass, and one that changed
 *   nothing must not cost the next process its days (#480);
 * - the catalog version, the lineup's UTC offset and a SHA-256 of what of the lineup the days depend on
 *   ([LineupDays.inputsOf]: every channel's state, source, ceiling and pool policy, and the leftovers' claim order),
 *   which leaves out the numbers and names that change no pool, so a renumber keeps the file;
 * - the cut-offs and every scheduled channel with its epoch number (which day), by channel ID: the file holds the days
 *   in that order too, and they are given back in the caller's (number) order.
 *
 * The key also names the cache's own [DaySnapshotCodec.VERSION] and the holds' versions ([HOLDS]). Both change only
 * with the code, so [appBuild] already covers them; they are there so that a miss says which of them moved.
 *
 * Anything that does not match, a missing, torn, corrupt or checksum-failing file, or a read fault, is a miss: the
 * caller builds as usual, and [report] is told why. A key whose write failed once (too big, or a fault) is not encoded
 * again in this process.
 *
 * A file is named by its earliest and latest cut-off and a short hash of them all, so a name stays short however many
 * day starts a lineup has. Writing one deletes the account's files of sets that are no longer wanted: all but the
 * [KEEP] latest (today's and the next day's), and any whose earliest cut-off lies further ahead of the set just written
 * than a day can be ([FUTURE_MS]: a clock that was wrong when it was written). A file holds item IDs, runtimes, dates,
 * years and episode numbers, the account's IDs, the chosen library IDs and hashes: no title, name, token or URL.
 *
 * [disabled] keeps nothing and finds nothing: the app uses it when it cannot tell its own install apart.
 */
class PersistedDays internal constructor(
    private val store: DayStore,
    private val appBuild: String,
    private val report: (DaySnapshotEvent) -> Unit,
    private val holds: List<String>,
    internal val enabled: Boolean = true,
) {
    constructor(
        store: DayStore,
        appBuild: String,
        report: (DaySnapshotEvent) -> Unit = {},
    ) : this(store, appBuild, report, HOLDS)

    // The keys whose write failed in this process: they are not encoded again.
    private val failed: MutableSet<DayKey> = Collections.synchronizedSet(HashSet())

    /** The key of the days of [scheduled], for a lineup whose day inputs are [inputs] ([LineupDays.inputsOf]). */
    @Suppress("LongParameterList") // One argument per input of the days.
    internal fun keyOf(
        account: LibraryAccount,
        libraries: Set<ItemId>,
        generation: Long,
        lineup: Lineup,
        inputs: String,
        catalogVersion: Int,
        scheduled: List<Scheduled>,
    ): DayKey {
        val inKeyOrder = inKeyOrder(scheduled)
        return DayKey(
            format = DaySnapshotCodec.VERSION,
            app = appBuild,
            holds = holds,
            server = account.server.value,
            user = account.user.value,
            libraries = libraries.map { it.value }.sorted(),
            generation = generation,
            catalog = catalogVersion,
            utcOffsetMinutes = lineup.utcOffsetMinutes,
            lineup = inputs,
            cutoffs = scheduled.map { it.cutoffMs }.distinct().sorted(),
            channels = inKeyOrder.map { it.channel.id.value },
            days = inKeyOrder.map { it.n },
        )
    }

    /**
     * The days of [scheduled] from disk if the file's key is [key], each made by [restore], in the order of
     * [scheduled]; null otherwise, and [report] is told which. Never throws but for cancellation.
     */
    internal suspend fun load(
        account: LibraryAccount,
        key: DayKey,
        scheduled: List<Scheduled>,
        restore: (Scheduled, List<ScheduleItem>, HiddenReason?) -> ChannelDay,
    ): List<ChannelDay>? {
        if (!enabled) return null
        val began = TimeSource.Monotonic.markNow()
        val stored = attempt { store.read(account, nameOf(key)) }
        val outcome =
            when (stored) {
                StoredBytes.Absent -> Read.Missed(DaySnapshotMiss.Absent)
                is StoredBytes.Present -> attempt { readDays(stored.bytes, key, inKeyOrder(scheduled), restore) }
                StoredBytes.Failed, null -> null
            } ?: Read.Missed(DaySnapshotMiss.Unreadable)
        return when (outcome) {
            is Read.Loaded -> {
                val bytes = (stored as StoredBytes.Present).bytes.size.toLong()
                report(DaySnapshotEvent.Hit(bytes, began.elapsedNow().inWholeMilliseconds))
                val byId = outcome.days.associateBy { it.channel.id }
                scheduled.map { byId.getValue(it.channel.id) }
            }

            is Read.Missed -> {
                report(DaySnapshotEvent.Miss(outcome.reason))
                null
            }
        }
    }

    /** Writes [days], built under [key], and deletes the account's files of older days. True when written. */
    internal suspend fun save(
        account: LibraryAccount,
        key: DayKey,
        days: List<ChannelDay>,
    ): Boolean {
        if (!enabled || key in failed) return false
        val began = TimeSource.Monotonic.markNow()
        val bytes = attempt { DaySnapshotCodec.encode(key, days.sortedBy { it.channel.id.value }) }
        val written = bytes != null && attempt { store.write(account, nameOf(key), bytes) } == true
        if (written) {
            report(DaySnapshotEvent.Saved(bytes.size.toLong(), began.elapsedNow().inWholeMilliseconds))
            attempt { evict(account, key) }
        } else {
            failed += key
            report(DaySnapshotEvent.SaveFailed)
        }
        return written
    }

    // Keeps the KEEP latest sets (today's and the next day's), leaving out sets dated too far ahead of [saved] and
    // names that are not this cache's.
    private suspend fun evict(
        account: LibraryAccount,
        saved: DayKey,
    ) {
        val names = store.names(account)
        val limit = saved.cutoffs.last() + FUTURE_MS
        val sets = names.mapNotNull { name -> spanOf(name)?.let { name to it } }.filter { it.second.first <= limit }
        val kept = sets.sortedWith(compareBy({ it.second.second }, { it.second.first })).takeLast(KEEP)
        (names - kept.map { it.first }.toSet()).forEach { store.delete(account, it) }
    }

    private sealed interface Read {
        class Loaded(
            val days: List<ChannelDay>,
        ) : Read

        class Missed(
            val reason: DaySnapshotMiss,
        ) : Read
    }

    private fun readDays(
        file: ByteArray,
        key: DayKey,
        scheduled: List<Scheduled>,
        restore: (Scheduled, List<ScheduleItem>, HiddenReason?) -> ChannelDay,
    ): Read {
        // The body is read only for a header that matches: a file for other inputs costs one short line.
        val header = DaySnapshotCodec.header(file) as? DaySnapshotCodec.Header.Read
        val moved = if (header == null) DaySnapshotMiss.Format else header.key.firstDifference(key)
        if (header == null || moved != null) return Read.Missed(moved ?: DaySnapshotMiss.Format)
        val body = DaySnapshotCodec.body(file, header, scheduled.size)
        return Read.Loaded(scheduled.mapIndexed { i, s -> restore(s, body[i].first, body[i].second) })
    }

    // A fault of any kind (I/O, a corrupt or torn file, an item that fails its checks, a deep stack) is null; only
    // cancellation goes through, as LineupCodec.decode does.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> attempt(block: suspend () -> T): T? =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } catch (_: StackOverflowError) {
            null
        }

    // The key's order of the channels, and the file's: by ID, so a renumber that changes no day keeps the file.
    private fun inKeyOrder(scheduled: List<Scheduled>): List<Scheduled> = scheduled.sortedBy { it.channel.id.value }

    companion object {
        /** The holds a kid and a grown-up channel's pools are narrowed by (ADR-0019, ADR-0020). */
        internal val HOLDS: List<String> = listOf(KidHold.VERSION_NAME, GrownUpHold.VERSION_NAME)

        /**
         * How many sets of cut-offs are kept per account: today's and the next day's. With one day start that is two
         * sets; a lineup with a custom channel on a second day start has two "next" sets ahead of today's (each day
         * start turns over on its own), so three are kept, and today's is never pushed out by the days ahead of it.
         */
        internal const val KEEP = 3

        /**
         * How far ahead of a set just written another set's earliest cut-off can be and still be a real day: the
         * longest epoch and the longest grace. A set further ahead was written under a clock that was wrong.
         */
        internal const val FUTURE_MS = SchedulerSettings.MAX_EPOCH_MS + SchedulerSettings.MAX_GRACE_MS

        private const val NAME_HASH = 16
        private const val NAME_PARTS = 3

        /** A file's name: `<earliest>_<latest>_<hash of all the cut-offs>`, never longer than `DayStore.MAX_NAME`. */
        internal fun nameOf(key: DayKey): String {
            val hash = sha256Hex(key.cutoffs.joinToString(",").encodeToByteArray()).take(NAME_HASH)
            return "${key.cutoffs.first()}_${key.cutoffs.last()}_$hash"
        }

        /** The earliest and latest cut-off a file name gives, or null for a name that is not one of [nameOf]'s. */
        private fun spanOf(name: String): Pair<Long, Long>? {
            val parts = name.split('_')
            val first = parts.getOrNull(0)?.toLongOrNull()
            val last = parts.getOrNull(1)?.toLongOrNull()
            return if (parts.size == NAME_PARTS && first != null && last != null) first to last else null
        }

        /** Keeps nothing and finds nothing. */
        fun disabled(): PersistedDays = PersistedDays(NoDays, "", {}, HOLDS, enabled = false)
    }
}

private const val HEX = "0123456789abcdef"
private const val NIBBLE = 4
private const val LOW = 0x0f

/** The SHA-256 of [bytes], in lower-case hex. */
internal fun sha256Hex(bytes: ByteArray): String =
    buildString {
        for (b in MessageDigest.getInstance("SHA-256").digest(bytes)) {
            append(HEX[(b.toInt() shr NIBBLE) and LOW])
            append(HEX[b.toInt() and LOW])
        }
    }

/** The store of [PersistedDays.disabled]: nothing is there, and nothing is kept. */
private object NoDays : DayStore {
    override suspend fun read(
        account: LibraryAccount,
        name: String,
    ): StoredBytes = StoredBytes.Absent

    override suspend fun write(
        account: LibraryAccount,
        name: String,
        bytes: ByteArray,
    ): Boolean = false

    override suspend fun names(account: LibraryAccount): List<String> = emptyList()

    override suspend fun delete(
        account: LibraryAccount,
        name: String,
    ): Boolean = true

    override suspend fun useAccount(account: LibraryAccount) = Unit

    override suspend fun forget(account: LibraryAccount) = Unit
}
