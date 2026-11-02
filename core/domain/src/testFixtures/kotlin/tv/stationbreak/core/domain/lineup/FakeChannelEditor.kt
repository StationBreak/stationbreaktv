// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import tv.stationbreak.core.domain.LibraryAccount

/**
 * A [ChannelEditor] for feature tests: it records what it was asked and answers as told. By default every change is
 * [ChangeResult.Done] and a reset is [ResetResult.Chosen].
 */
class FakeChannelEditor(
    access: LineupAccess = LineupAccess.Editable,
) : ChannelEditor {
    private val accessNow = MutableStateFlow(access)

    /** Every change asked for, in order. */
    val changes = mutableListOf<Pair<LibraryAccount, ChannelChange>>()

    /** The accounts reset, in order. */
    val resets = mutableListOf<LibraryAccount>()

    /** What [change] answers next. */
    var changeResult: ChangeResult = ChangeResult.Done

    /** What [reset] answers next. */
    var resetResult: ResetResult = ResetResult.Chosen

    fun setAccess(value: LineupAccess) {
        accessNow.value = value
    }

    override fun access(account: LibraryAccount): Flow<LineupAccess> = accessNow

    override suspend fun change(
        account: LibraryAccount,
        change: ChannelChange,
    ): ChangeResult {
        changes += account to change
        return changeResult
    }

    override suspend fun reset(account: LibraryAccount): ResetResult {
        resets += account
        return resetResult
    }
}
