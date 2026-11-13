// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.Ordering
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The one-time rewrite (docs/adr/0026-studio-fold-2.md, Versioning): once the format 4 gate has passed, a stored lineup
 * whose rules a load upgrades is saved once, in the background, through the normal save; a second start finds nothing
 * to upgrade and saves nothing, and a lineup with no studio feature is never saved for it. Each load logs the rules
 * formats it holds (counts only).
 */
class RulesRewriteTest {
    private val library = CountingLibrary().apply { seed(fillingLibrary()) }
    private val store = RecordingStore()
    private val clock =
        object : Clock {
            override fun now(): Instant = NOON
        }
    private val formats = mutableListOf<RulesFormats>()

    private fun service(): LineupService {
        val seams = LineupSeams(TestCatalog, rulesFormats = { formats += it })
        return LineupService(store, library, clock, { 0 }, {}, seams)
    }

    private val studio3 = at3("Studio", Source.Studio("Studio A"))
    private val plain = rules("Plain", Source.Genre("Drama"))

    private fun at3(
        name: String,
        source: Source,
        ordering: Ordering? = null,
    ) = ChannelRules(3, name, Seed(name.hashCode().toULong()), listOf(source), null, null, ordering, timing(), null)

    private fun custom(
        id: String,
        number: Int,
        rules: ChannelRules,
    ) = channel(id, number, ChannelState.On, ChannelSource.Custom(rules))

    private suspend fun stored(lineup: Lineup) {
        store.inner.save(ACCOUNT, LineupCodec.encode(lineup))
    }

    private fun saves() = store.calls.count { it == "save" }

    private fun rulesOnDisk(): List<Int> {
        // Read as written: the gate's own upgrade must not hide what the file says.
        val bytes = checkNotNull(store.inner.peek(ACCOUNT))
        val loaded = assertIs<LineupCodec.Decoded.Loaded>(LineupCodec.decode(bytes, gatePassed = false))
        return loaded.lineup.channels.map { (it.source as ChannelSource.Custom).rules.formatVersion }
    }

    private suspend fun start(): LineupService =
        service().apply {
            checkNotNull(loaded(ACCOUNT))
            awaitRewrites()
        }

    @Test
    fun aStoredFormat3StudioChannelIsSavedOnceAt4AndASecondStartSavesNothing() =
        runBlocking<Unit> {
            stored(lineupOf(custom("custom:studio", 600, studio3), custom("custom:plain", 601, plain)))

            val first = start()

            assertEquals(1, saves(), "saved once")
            assertEquals(listOf(4, 1), rulesOnDisk())
            val held = checkNotNull(first.loaded(ACCOUNT)).lineup.channels[0].source
            assertEquals(4, assertIs<ChannelSource.Custom>(held).rules.formatVersion, "memory holds what was saved")
            assertEquals(listOf(RulesFormats(v1 = 1, v4 = 1, upgraded = 1)), formats)

            start()

            assertEquals(1, saves(), "a second start finds nothing to upgrade")
            assertEquals(RulesFormats(v1 = 1, v4 = 1, upgraded = 0), formats.last())
        }

    @Test
    fun aLineupWithNoStudioFeatureIsNeverSavedForIt() =
        runBlocking<Unit> {
            val noStudios = at3("Curated", Source.Genre("Drama"), Ordering(OrderingMode.Curated))
            stored(lineupOf(custom("custom:curated", 600, noStudios), custom("custom:plain", 601, plain)))

            start()
            start()

            assertEquals(0, saves())
            assertEquals(listOf(3, 1), rulesOnDisk())
            assertEquals(RulesFormats(v1 = 1, v3 = 1), formats.last())
        }

    @Test
    fun theFormatsAreLoggedOnceForEachLoad() =
        runBlocking<Unit> {
            stored(lineupOf(custom("custom:plain", 601, plain)))
            val service = start()

            service.loaded(ACCOUNT)
            service.loaded(ACCOUNT)

            assertEquals(listOf(RulesFormats(v1 = 1)), formats)
        }
}
