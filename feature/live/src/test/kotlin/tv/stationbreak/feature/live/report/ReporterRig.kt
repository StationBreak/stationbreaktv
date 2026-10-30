// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.report

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.model.AccessToken
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.MediaSourceId
import tv.stationbreak.core.model.PlayMethod
import tv.stationbreak.core.model.PlaySessionId
import tv.stationbreak.core.model.PlaybackReport
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.ServerInfo
import tv.stationbreak.core.model.ServerVersion
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.UserId
import tv.stationbreak.core.model.UserItemState
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

internal data class Call(
    val kind: String,
    val atMs: Long,
    val item: String,
    val posMs: Long = -1,
    val failed: Boolean = false,
    val paused: Boolean = false,
)

internal class RecordingServer(
    private val now: () -> Long,
    private val inner: FakeMediaServer = FakeMediaServer(),
) : MediaServer by inner {
    val calls = mutableListOf<Call>()
    val userDataQueue = ArrayDeque<ServerResult<UserItemState>>()
    var stopHangs = false
    var progressHangs = false
    var userDataThrows = false
    var progressResult: ServerResult<Unit> = ServerResult.Success(Unit)

    override suspend fun reportStart(
        session: Session,
        report: PlaybackReport,
    ): ServerResult<Unit> {
        calls += Call("start", now(), report.itemId.value, report.position.inWholeMilliseconds)
        return ServerResult.Success(Unit)
    }

    override suspend fun reportProgress(
        session: Session,
        report: PlaybackReport,
    ): ServerResult<Unit> {
        calls +=
            Call("progress", now(), report.itemId.value, report.position.inWholeMilliseconds, paused = report.isPaused)
        if (progressHangs) awaitCancellation()
        return progressResult
    }

    override suspend fun reportStopped(
        session: Session,
        report: PlaybackReport,
        failed: Boolean,
    ): ServerResult<Unit> {
        calls += Call("stopped", now(), report.itemId.value, report.position.inWholeMilliseconds, failed)
        if (stopHangs) awaitCancellation()
        return ServerResult.Success(Unit)
    }

    override suspend fun userData(
        session: Session,
        itemId: ItemId,
    ): ServerResult<UserItemState> {
        calls += Call("userdata", now(), itemId.value)
        check(!userDataThrows) { "boom" }
        return userDataQueue.removeFirstOrNull() ?: inner.userDataResult
    }

    override suspend fun markPlayed(
        session: Session,
        itemId: ItemId,
    ): ServerResult<Unit> {
        calls += Call("played", now(), itemId.value)
        return ServerResult.Success(Unit)
    }

    fun set(state: UserItemState) {
        inner.userDataResult = ServerResult.Success(state)
    }
}

internal class Rig(
    val test: TestScope,
    config: ReportingConfig = ReportingConfig(),
    scope: CoroutineScope = test.backgroundScope,
) {
    val server = RecordingServer({ test.testScheduler.currentTime })
    val lines = mutableListOf<String>()
    val clock =
        object : Clock {
            override fun now(): Instant = Instant.fromEpochMilliseconds(test.testScheduler.currentTime)
        }
    val reporter = PlaybackReporter(scope, server, SESSION, clock, config) { lines += it }

    val calls get() = server.calls

    fun at(ms: Long) {
        val delta = ms - test.testScheduler.currentTime
        if (delta > 0) test.advanceTimeBy(delta)
        test.runCurrent()
    }

    fun kinds() = calls.map { it.kind }
}

internal fun key(
    item: String,
    block: Long = 0,
    channel: String = "ch1",
) = AiringKey(ChannelId(channel), block, ItemId(item))

internal fun target(
    item: String,
    runtime: Duration? = 45.minutes,
) = ReportTarget(ItemId(item), MediaSourceId("src-$item"), PlaySessionId("ps"), PlayMethod.DirectPlay, runtime)

@Suppress("LongParameterList") // Test helper: every default is one thing a test may vary.
internal fun Rig.visit(
    sitting: Long,
    item: String,
    block: Long = 0,
    runtime: Duration? = 45.minutes,
    channel: String = "ch1",
    fromMs: Long = 0,
) = reporter.visit(sitting, key(item, block, channel), target(item, runtime), fromMs)

internal val SESSION =
    Session(
        UserId("u"),
        AccessToken("fake-token"),
        ServerInfo("Home", ServerId("srv1"), ServerVersion(12, 2, 0)),
        ServerAddress(Scheme.Https, "jellyfin.example", 443),
        null,
        false,
    )
