// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:OptIn(ExperimentalCoroutinesApi::class)

package tv.stationbreak.feature.channels.builder

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.builder.BuildRefusal
import tv.stationbreak.core.domain.lineup.builder.BuildResult
import tv.stationbreak.core.domain.lineup.builder.DraftSource
import tv.stationbreak.core.domain.lineup.builder.OpenResult
import tv.stationbreak.core.model.ChannelId

/** The copy flow for After Hours, always adult content (#984): the domain decides, the screen follows. */
class AdultCopyTest {
    private val afterHours = ChannelId("starter:after-hours")
    private val lib1 = DraftSource.Library("lib1", "Library 1")

    private fun adultDraft() = draftOf("After Hours", listOf(lib1), origin = afterHours).copy(adult = true)

    @Test
    fun aFreeViewerWhoOpensACopyOfAfterHoursGetsTheProLineAndNoDraft() =
        builderTest { rig ->
            rig.builder.openResult = OpenResult.NeedsPro
            rig.vm.openCopy(afterHours) { "$it (copy)" }
            rig.settle()

            assertEquals(BuilderMessage.NeedsPro, rig.ui.message)
            assertFalse(rig.ui.ready)
            assertTrue(rig.builder.created.isEmpty())
        }

    @Test
    fun aProViewerGetsAnAdultCopyWithNoQuestion() =
        builderTest { rig ->
            rig.builder.openResult = OpenResult.Opened(adultDraft(), "After Hours")
            rig.vm.openCopy(afterHours) { "$it (copy)" }
            rig.settle()

            assertTrue(rig.ui.ready)
            assertTrue(rig.ui.draft.adult)
            assertEquals(BuilderMode.Copy(afterHours), rig.ui.mode)
            assertEquals(null, rig.ui.message)
        }

    @Test
    fun editingTheCopyNeverClearsTheAdultFlagAndSavingSendsIt() =
        builderTest { rig ->
            rig.builder.openResult = OpenResult.Opened(adultDraft(), "After Hours")
            rig.vm.openCopy(afterHours) { "$it (copy)" }
            rig.settle()
            rig.vm.type("Night owls")
            rig.vm.next()
            rig.vm.next()
            rig.vm.save(andWatch = false)
            rig.advance(1)

            assertTrue(rig.ui.draft.adult)
            assertTrue(
                rig.builder.created
                    .single()
                    .adult,
            )
        }

    @Test
    fun aSaveOfACopyRefusedForProShowsTheProLine() =
        builderTest { rig ->
            rig.builder.openResult = OpenResult.Opened(adultDraft(), "After Hours")
            rig.vm.openCopy(afterHours) { it }
            rig.settle()
            rig.builder.buildResult = BuildResult.Refused(BuildRefusal.NeedsPro)
            rig.vm.save(andWatch = false)
            rig.advance(1)

            assertEquals(BuilderMessage.NeedsPro, rig.ui.message)
        }

    @Test
    fun theFreeCapStillShowsItsLineForACopyOfAfterHours() =
        builderTest { rig ->
            rig.builder.openResult = OpenResult.Opened(adultDraft(), "After Hours")
            rig.vm.openCopy(afterHours) { it }
            rig.settle()
            rig.builder.buildResult = BuildResult.Refused(BuildRefusal.CustomLimit)
            rig.vm.save(andWatch = false)
            rig.advance(1)

            assertEquals(BuilderMessage.CustomLimit, rig.ui.message)
        }
}
