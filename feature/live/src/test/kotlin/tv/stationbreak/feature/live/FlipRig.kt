// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import tv.stationbreak.core.model.ChannelId
import kotlin.time.Duration.Companion.seconds

val CH2 = ChannelId("ch-2")

internal class Flipping(
    val r: Rig,
    val job: Job,
    val keys: Channel<LiveCommand>,
    private val scope: TestScope,
) {
    fun send(command: LiveCommand) {
        keys.trySend(command)
        scope.runCurrent()
    }

    fun up() = send(LiveCommand.Flip(FlipDirection.Up))

    fun down() = send(LiveCommand.Flip(FlipDirection.Down))
}

/** Channels 2, 5 and 9, each with one long program (item = its number); the sitting starts on [startOn]. */
internal suspend fun TestScope.flipping(
    startOn: ChannelId = CH5,
    timings: LiveTimings = LiveTimings(),
    setup: Rig.() -> Unit = {},
): Flipping {
    val r = Rig(this, timings = timings)
    r.schedule.channel(CH2, 2, listOf(program(0, 600, 2)))
    r.schedule.channel(CH5, 5, listOf(program(0, 600, 5)))
    r.schedule.channel(CH9, 9, listOf(program(0, 600, 9)))
    r.lastChannel.save(r.account, startOn)
    // These tests are about the flip's own sequence; look-ahead has its own (LiveChannelNeighboursTest).
    r.tune.preResolveResult = { false }
    r.setup()
    val keys = Channel<LiveCommand>(Channel.UNLIMITED)
    val job = r.start(commands = keys.receiveAsFlow())
    // Past the minimum snow: the picture of the first channel is up.
    advanceTimeBy(1.seconds)
    runCurrent()
    return Flipping(r, job, keys, this)
}
