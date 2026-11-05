// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:OptIn(ExperimentalCoroutinesApi::class)

package tv.stationbreak.feature.channels

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import tv.stationbreak.core.domain.InMemoryLibraryRepository
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.lineup.ChangeResult
import tv.stationbreak.core.domain.lineup.ChannelChange
import tv.stationbreak.core.domain.lineup.ChannelEditor
import tv.stationbreak.core.domain.lineup.ChannelKind
import tv.stationbreak.core.domain.lineup.ChannelRepository
import tv.stationbreak.core.domain.lineup.ChannelStatus
import tv.stationbreak.core.domain.lineup.ChannelSummary
import tv.stationbreak.core.domain.lineup.FakeChannelEditor
import tv.stationbreak.core.domain.lineup.FakeSchedule
import tv.stationbreak.core.domain.lineup.HouseholdSetting
import tv.stationbreak.core.domain.lineup.PlayKey
import tv.stationbreak.core.domain.lineup.ResetResult
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.T0
import tv.stationbreak.core.domain.lineup.ShelfReason
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.UserId
import kotlin.time.Clock
import kotlin.time.Instant

internal val A = ChannelId("ch-a")
internal val B = ChannelId("ch-b")
internal val C = ChannelId("ch-c")
internal val D = ChannelId("ch-d")

@Suppress("LongParameterList") // A test builder: every field has a default.
internal fun summary(
    id: ChannelId,
    number: Int?,
    state: ChannelStatus = ChannelStatus.On,
    name: String = "Test ${id.value}",
    kind: ChannelKind = ChannelKind.Starter,
    default: Int? = number,
    kid: Boolean = false,
    optIn: Boolean = false,
    shelfReason: ShelfReason? = null,
) = ChannelSummary(
    id,
    number,
    name,
    state,
    kind,
    Instant.fromEpochMilliseconds(T0),
    default,
    kid,
    optIn,
    PlayKey("test"),
    shelfReason,
)

internal class FakeChannels(
    initial: List<ChannelSummary> = emptyList(),
) : ChannelRepository {
    val list = MutableStateFlow(initial)

    override fun channels(account: LibraryAccount): Flow<List<ChannelSummary>> = list

    override fun activeChannels(): Flow<List<ChannelSummary>> = list
}

internal class FakeHousehold : HouseholdSetting {
    val flag = MutableStateFlow(true)

    override fun useMyCollections(account: LibraryAccount): Flow<Boolean> = flag
}

/** The fake editor, which waits for a gate a test sets before it answers (a slow save or reset). */
internal class GatedEditor(
    val inner: FakeChannelEditor,
) : ChannelEditor by inner {
    var changeGate: CompletableDeferred<Unit>? = null
    var resetGate: CompletableDeferred<Unit>? = null

    override suspend fun change(
        account: LibraryAccount,
        change: ChannelChange,
    ): ChangeResult {
        changeGate?.await()
        return inner.change(account, change)
    }

    override suspend fun reset(account: LibraryAccount): ResetResult {
        resetGate?.await()
        return inner.reset(account)
    }
}

/** What the ViewModel tests share: fakes for every port and a ViewModel on them. */
internal class ChannelsRig(
    scope: TestScope,
    channels: List<ChannelSummary> = emptyList(),
) {
    val account = LibraryAccount(ServerId("srv1"), UserId("u1"))
    val library = InMemoryLibraryRepository()
    val repo = FakeChannels(channels)
    val household = FakeHousehold()
    val editor = FakeChannelEditor()
    val gated = GatedEditor(editor)
    val schedule = FakeSchedule()
    val clock: Clock =
        object : Clock {
            override fun now(): Instant = Instant.fromEpochMilliseconds(T0)
        }
    val vm =
        ChannelsViewModel(
            library,
            repo,
            household,
            gated,
            schedule,
            clock,
            StandardTestDispatcher(scope.testScheduler),
        )
    val ui get() = vm.ui.value

    suspend fun open(
        scope: TestScope,
        current: ChannelId? = null,
    ) {
        library.useAccount(account)
        vm.open(current)
        scope.runCurrent()
    }
}

internal fun channelsTest(
    channels: List<ChannelSummary> = emptyList(),
    body: suspend TestScope.(ChannelsRig) -> Unit,
) = runTest {
    val rig = ChannelsRig(this, channels)
    body(rig)
}
