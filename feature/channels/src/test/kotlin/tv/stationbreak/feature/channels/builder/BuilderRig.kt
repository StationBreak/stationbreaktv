// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:OptIn(ExperimentalCoroutinesApi::class)

package tv.stationbreak.feature.channels.builder

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import tv.stationbreak.core.domain.InMemoryLibraryRepository
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.T0
import tv.stationbreak.core.domain.lineup.builder.CatalogPicker
import tv.stationbreak.core.domain.lineup.builder.ChannelDraft
import tv.stationbreak.core.domain.lineup.builder.ChannelPreviews
import tv.stationbreak.core.domain.lineup.builder.DraftFilters
import tv.stationbreak.core.domain.lineup.builder.DraftSource
import tv.stationbreak.core.domain.lineup.builder.FakeCatalogPicker
import tv.stationbreak.core.domain.lineup.builder.FakeChannelBuilder
import tv.stationbreak.core.domain.lineup.builder.FakeChannelPreviews
import tv.stationbreak.core.domain.lineup.builder.KeptSettings
import tv.stationbreak.core.domain.lineup.builder.Listed
import tv.stationbreak.core.domain.lineup.builder.OpenResult
import tv.stationbreak.core.domain.lineup.builder.PickerEntry
import tv.stationbreak.core.domain.lineup.builder.PlayOrder
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.UserId
import tv.stationbreak.feature.channels.FakeChannels
import tv.stationbreak.feature.channels.PanelMove
import kotlin.time.Clock
import kotlin.time.Instant

internal val CUSTOM = ChannelId("custom:mine")
internal val BUILT_IN = ChannelId("starter:movies")

internal fun libraries(n: Int): Listed<List<PickerEntry>> {
    val entries = (1..n).map { PickerEntry("lib$it", "Library $it") }
    return Listed.Ok(entries)
}

@Suppress("LongParameterList") // A test builder: every field has a default.
internal fun draftOf(
    name: String = "",
    sources: List<DraftSource> = emptyList(),
    kids: Boolean = false,
    number: Int? = 600,
    seed: Seed = Seed(1uL),
    origin: ChannelId? = null,
    order: PlayOrder? = PlayOrder.Shuffle,
    kept: KeptSettings? = null,
    filters: DraftFilters = DraftFilters(),
) = ChannelDraft(name, sources, emptyList(), filters, order, 3, 1, kids, number, kept, false, origin, seed)

/** What the builder ViewModel tests share: fakes for every port and a ViewModel on them. */
internal class BuilderRig(
    private val scope: TestScope,
    wrap: (FakeCatalogPicker) -> CatalogPicker = { it },
    wrapPreviews: (FakeChannelPreviews) -> ChannelPreviews = { it },
) {
    val account = LibraryAccount(ServerId("srv1"), UserId("u1"))
    val library = InMemoryLibraryRepository()
    val builder = FakeChannelBuilder()
    val picker = FakeCatalogPicker()
    val previews = FakeChannelPreviews()
    val channels = FakeChannels()
    val clock: Clock =
        object : Clock {
            override fun now(): Instant = Instant.fromEpochMilliseconds(T0)
        }
    val vm =
        BuilderViewModel(
            library,
            builder,
            wrap(picker),
            wrapPreviews(previews),
            channels,
            clock,
            StandardTestDispatcher(scope.testScheduler),
        )
    val ui get() = vm.ui.value
    val events = mutableListOf<BuilderEvent>()

    init {
        scope.backgroundScope.launch { library.useAccount(account) }
        scope.backgroundScope.launch { vm.events.collect { events += it } }
    }

    fun openNew() {
        vm.openNew()
        scope.runCurrent()
    }

    fun openEdit(draft: ChannelDraft = draftOf("Mine", listOf(DraftSource.Library("lib1", "Library 1")))) {
        builder.openResult = OpenResult.Opened(draft, null)
        vm.openEdit(CUSTOM)
        scope.runCurrent()
    }

    fun press(vararg moves: PanelMove) {
        moves.forEach { vm.move(it) }
        scope.runCurrent()
    }

    fun settle() = scope.runCurrent()

    fun advance(ms: Long) {
        scope.advanceTimeBy(ms)
        scope.runCurrent()
    }

    fun back() {
        vm.back()
        scope.runCurrent()
    }

    fun select() {
        vm.select()
        scope.runCurrent()
    }

    /** From a fresh Sources page: onto the first entry and ticks it. */
    fun tickFirst() {
        press(PanelMove.Right)
        select()
    }
}

internal fun builderTest(
    wrap: (FakeCatalogPicker) -> CatalogPicker = { it },
    wrapPreviews: (FakeChannelPreviews) -> ChannelPreviews = { it },
    body: suspend TestScope.(BuilderRig) -> Unit,
) = runTest {
    val rig = BuilderRig(this, wrap, wrapPreviews)
    body(rig)
}
