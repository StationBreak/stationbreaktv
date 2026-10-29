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
import java.security.MessageDigest
import kotlin.time.Instant
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
    CollectionPass,
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
 * - the cache's own [DaySnapshotCodec.VERSION];
 * - [appBuild], which must change with every install of the app (the app gives its version code and install time,
 *   since the version code alone does not move between builds), so new code never reads days that old code built;
 * - the kids' and grown-up holds' versions ([HOLDS]), which are code too but are named for the record;
 * - the account, the chosen libraries, the content generation and the last completed collection pass: as for
 *   `SnapshotCache`, these two numbers cover everything `ruleSnapshot` returns, and they are read before the build;
 * - the catalog version, the lineup's UTC offset and a SHA-256 of the whole stored lineup (every channel's rules,
 *   state, number and name);
 * - the cut-offs and every scheduled channel with its epoch number (which day).
 *
 * Anything that does not match, a missing, torn or corrupt file, or a read fault, is a miss: the caller builds as
 * usual, and [report] is told why. Files are named by their cut-offs and kept only for the [KEEP] latest sets of
 * cut-offs per account (today's and the next day's); writing one deletes the rest. A file holds item IDs, runtimes,
 * dates, years and episode numbers, the account's IDs, the chosen library IDs and a hash: no title, name, token or URL.
 */
class PersistedDays internal constructor(
    private val store: DayStore,
    private val appBuild: String,
    private val report: (DaySnapshotEvent) -> Unit,
    private val holds: List<String>,
) {
    constructor(
        store: DayStore,
        appBuild: String,
        report: (DaySnapshotEvent) -> Unit = {},
    ) : this(store, appBuild, report, HOLDS)

    @Suppress("LongParameterList") // One argument per input of the days.
    internal fun keyOf(
        account: LibraryAccount,
        libraries: Set<ItemId>,
        generation: Long,
        collectionPass: Instant?,
        lineup: Lineup,
        catalogVersion: Int,
        scheduled: List<Scheduled>,
    ): DayKey =
        DayKey(
            format = DaySnapshotCodec.VERSION,
            app = appBuild,
            holds = holds,
            server = account.server.value,
            user = account.user.value,
            libraries = libraries.map { it.value }.sorted(),
            generation = generation,
            collectionPass = collectionPass?.toString(),
            catalog = catalogVersion,
            utcOffsetMinutes = lineup.utcOffsetMinutes,
            lineup = sha256(LineupCodec.encode(lineup)),
            cutoffs = scheduled.map { it.cutoffMs }.distinct().sorted(),
            channels = scheduled.map { it.channel.id.value },
            days = scheduled.map { it.n },
        )

    /**
     * The days of [scheduled] from disk if the file's key is [key], each made by [restore]; null otherwise, and
     * [report] is told which. Never throws but for cancellation.
     */
    internal suspend fun load(
        account: LibraryAccount,
        key: DayKey,
        scheduled: List<Scheduled>,
        restore: (Scheduled, List<ScheduleItem>, HiddenReason?) -> ChannelDay,
    ): List<ChannelDay>? {
        val began = TimeSource.Monotonic.markNow()
        val stored = attempt { store.read(account, nameOf(key)) }
        val outcome =
            when (stored) {
                StoredBytes.Absent -> Read.Missed(DaySnapshotMiss.Absent)
                is StoredBytes.Present -> attempt { readDays(stored.bytes, key, scheduled, restore) }
                StoredBytes.Failed, null -> null
            } ?: Read.Missed(DaySnapshotMiss.Unreadable)
        return when (outcome) {
            is Read.Loaded -> {
                val bytes = (stored as StoredBytes.Present).bytes.size.toLong()
                report(DaySnapshotEvent.Hit(bytes, began.elapsedNow().inWholeMilliseconds))
                outcome.days
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
        val began = TimeSource.Monotonic.markNow()
        val bytes = attempt { DaySnapshotCodec.encode(key, days) }
        if (bytes == null || attempt { store.write(account, nameOf(key), bytes) } != true) {
            report(DaySnapshotEvent.SaveFailed)
            return false
        }
        report(DaySnapshotEvent.Saved(bytes.size.toLong(), began.elapsedNow().inWholeMilliseconds))
        attempt { evict(account) }
        return true
    }

    // Keeps the KEEP latest sets of cut-offs (today's and the next day's). A name that is not a set of cut-offs goes.
    private suspend fun evict(account: LibraryAccount) {
        val names = store.names(account)
        val days = names.mapNotNull { name -> cutoffsOf(name)?.let { name to it } }
        val kept = days.sortedWith { a, b -> compareCutoffs(a.second, b.second) }.takeLast(KEEP).map { it.first }
        (names - kept.toSet()).forEach { store.delete(account, it) }
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
        val body = DaySnapshotCodec.body(file, header.bodyAt, scheduled.size)
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

    internal companion object {
        /** The holds a kid and a grown-up channel's pools are narrowed by (ADR-0019, ADR-0020). */
        val HOLDS: List<String> = listOf(KidHold.VERSION_NAME, GrownUpHold.VERSION_NAME)

        /**
         * How many sets of cut-offs are kept per account: today's and the next day's. With one day start that is two
         * sets; a lineup with a custom channel on a second day start has two "next" sets ahead of today's (each day
         * start turns over on its own), so three are kept, and today's is never pushed out by the days ahead of it.
         */
        const val KEEP = 3

        /** A file is named by its cut-offs: digits and `_` only. */
        fun nameOf(key: DayKey): String = key.cutoffs.joinToString("_")

        private fun cutoffsOf(name: String): List<Long>? =
            name
                .split('_')
                .map { it.toLongOrNull() ?: return null }
                .takeIf { it.isNotEmpty() }

        // Later days are greater: by the latest cut-off, then the earliest, then the rest in turn.
        private fun compareCutoffs(
            a: List<Long>,
            b: List<Long>,
        ): Int {
            val byLast = a.last().compareTo(b.last())
            val byEach = a.zip(b).map { (x, y) -> x.compareTo(y) }.firstOrNull { it != 0 }
            return if (byLast != 0) byLast else byEach ?: a.size.compareTo(b.size)
        }

        private const val HEX = "0123456789abcdef"
        private const val NIBBLE = 4
        private const val LOW = 0x0f

        private fun sha256(bytes: ByteArray): String =
            buildString {
                for (b in MessageDigest.getInstance("SHA-256").digest(bytes)) {
                    append(HEX[(b.toInt() shr NIBBLE) and LOW])
                    append(HEX[b.toInt() and LOW])
                }
            }
    }
}
