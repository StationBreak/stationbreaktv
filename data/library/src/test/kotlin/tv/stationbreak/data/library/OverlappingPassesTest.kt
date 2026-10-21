// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.core.domain.CollectionSource
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.model.CollectionInfo
import tv.stationbreak.core.model.CollectionMember
import tv.stationbreak.core.model.CollectionMemberKind
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.data.library.SyncWorld.session
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.hours

/**
 * The real overlap behind the failed-pass count: the first sync records its success (`finishSync`) and only then runs
 * its collection pass, and the stay-current runner, whose gate the first sync does not take, starts as soon as it sees
 * that success. Both then run a collection pass at once. The second must not take the first, still running, for a pass
 * that died.
 */
class OverlappingPassesTest {
    private val rig = StayCurrentRig()

    @After
    fun close() = rig.close()

    @Test
    @Suppress("InjectDispatcher") // A test: nothing to inject into.
    fun overlappingPassesDoNotCountAHealthyPassAsFailed() =
        runBlocking {
            val box = ItemId("0000000000000000000000000000b0b0")
            val source = CollectionSource()
            source.collections += CollectionInfo(box, "A collection")
            val film = CollectionMember(ItemId("0000000000000000000000000000f1f1"), CollectionMemberKind.Movie)
            source.members[box] = mutableListOf(film)
            rig.server.collectionSource = source
            rig.sessions.save(session)
            rig.fake.resumeResult = ServerResult.Success(session)
            rig.repository.useAccount(ALICE)

            // One earlier pass already ended without completing, so a second miscount would reach the limit of two.
            rig.repository.beginCollectionPass(ALICE)
            rig.repository.abandonCollectionPass(ALICE)
            assertEquals(1, rig.repository.failedCollectionPasses(ALICE))

            // Every collection call waits, so a pass that starts stays running until it is released.
            val inFirstPass = CountDownLatch(1)
            val release = CountDownLatch(1)
            rig.server.respondToCollections = {
                inFirstPass.countDown()
                release.await()
                null
            }
            val firstSync =
                async(Dispatchers.Default) {
                    SyncWorld.engine(rig.repository, rig.server, rig.clock).run(session, ALICE, setOf(MOVIES))
                }
            inFirstPass.await()
            rig.clock.instant += 1.hours

            // The success is recorded, so the stay-current runner sees a library to top up and runs its own pass.
            val onOpen = SyncPlan(incremental = false, reconcile = true)
            val stayCurrent = async(Dispatchers.Default) { rig.runner.run(onOpen) }
            delay(1_000)

            try {
                assertEquals(
                    "the second pass must not count the first, still running, as dead",
                    1,
                    rig.repository.failedCollectionPasses(ALICE),
                )
            } finally {
                release.countDown()
            }
            firstSync.await()
            stayCurrent.await()
            assertEquals("both passes completed", 0, rig.repository.failedCollectionPasses(ALICE))
        }
}
