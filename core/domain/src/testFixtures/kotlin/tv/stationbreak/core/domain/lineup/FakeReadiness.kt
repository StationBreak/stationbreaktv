// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import tv.stationbreak.core.model.ChannelId

/** A [ChannelReadiness] a test sets by hand. */
class FakeReadiness : ChannelReadiness {
    val held = MutableStateFlow<Set<ChannelId>>(emptySet())
    val shown = MutableStateFlow<ReadyNotice?>(null)

    override val pending: Flow<Set<ChannelId>> get() = held

    override val notice: StateFlow<ReadyNotice?> get() = shown
}
