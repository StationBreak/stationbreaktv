// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.model.ItemDetails
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemLogo

/** A picture with nothing in it: the guide only ever holds and hands on the logo, so the stub need not draw. */
private class StubBitmap : ImageBitmap {
    override val colorSpace: ColorSpace get() = ColorSpaces.Srgb
    override val config = ImageBitmapConfig.Argb8888
    override val hasAlpha = true
    override val height = 10
    override val width = 20

    override fun prepareToDraw() = Unit

    override fun readPixels(
        buffer: IntArray,
        startX: Int,
        startY: Int,
        width: Int,
        height: Int,
        bufferOffset: Int,
        stride: Int,
    ) = Unit
}

/** The logos the panel is offered, with what a test needs to hold one back or fail it. */
private class FakeLogos : GuideLogos {
    val image: ImageBitmap = StubBitmap()
    val asked = mutableListOf<ItemLogo>()
    var takes = 0L
    var gate: CompletableDeferred<Unit>? = null
    var failure: Exception? = null
    var cancelled = 0
    var finished = 0

    override suspend fun load(logo: ItemLogo): ImageBitmap? {
        asked += logo
        try {
            gate?.await()
            if (takes > 0) delay(takes)
        } catch (e: CancellationException) {
            cancelled++
            throw e
        }
        failure?.let { throw it }
        finished++
        return image
    }
}

/**
 * The details panel's logo (#723): it replaces the text title only when it is in hand within 150 ms of the focus
 * resting on the cell, it is never another cell's, and one that comes late is finished (so it is kept) and not shown.
 */
class GuideLogoTest : GuideRig() {
    private val logo = ItemLogo(ItemId("series1"), "tag1")
    private val offered = FakeLogos()

    init {
        logos = offered
    }

    private fun withLogo() {
        server.itemDetailsResult =
            ServerResult.Success(ItemDetails(ItemId("i"), "About.", 2001, listOf("Drama"), null, logo = logo))
    }

    private fun GuideViewModel.shown() = checkNotNull(panel.value)

    @Test
    fun aHeldLogoReplacesTheTitleOnceTheFocusHasRested() =
        guideTest { vm ->
            withLogo()
            vm.open(CH7)
            runCurrent()
            after(199)
            assertNull("the text title comes first", vm.shown().logo)

            after(1)

            assertSame(offered.image, vm.shown().logo)
            assertEquals(listOf(logo), offered.asked)
        }

    @Test
    fun anItemWithNoLogoIsNeverAskedFor() =
        guideTest { vm ->
            vm.open(CH7)
            runCurrent()

            after(1000)

            assertNotNull(vm.shown().item)
            assertNull(vm.shown().logo)
            assertTrue(offered.asked.isEmpty())
        }

    @Test
    fun aLogoWithinTheWindowIsShown() =
        guideTest { vm ->
            offered.takes = 140
            withLogo()
            vm.open(CH7)
            runCurrent()
            after(200)

            after(139)
            assertNull(vm.shown().logo)
            after(1)

            assertSame(offered.image, vm.shown().logo)
        }

    @Test
    fun aLogoThatComesLateIsFinishedButNotShown() =
        guideTest { vm ->
            offered.takes = 300
            withLogo()
            vm.open(CH7)
            runCurrent()
            after(200)

            after(151)
            assertNull(vm.shown().logo)
            after(200)

            // It arrived (and a real loader has kept it for the next time), but the viewer is not shown a swap.
            assertEquals(1, offered.finished)
            assertNull(vm.shown().logo)
        }

    @Test
    fun theTimeTheDetailsTookIsTakenFromTheLogosWindow() =
        guideTest { vm ->
            offered.takes = 60
            withLogo()
            val gate = CompletableDeferred<Unit>()
            server.itemDetailsGate = gate
            vm.open(CH7)
            runCurrent()
            after(200)
            // Settled at 200 ms; the details take 100 ms, so 50 ms of the window is left for a logo that takes 60.
            after(100)
            gate.complete(Unit)
            runCurrent()

            after(200)

            assertEquals(1, offered.finished)
            assertNull(vm.shown().logo)
        }

    @Test
    fun aLogoThatFitsWhatTheDetailsLeftOfTheWindowIsShown() =
        guideTest { vm ->
            offered.takes = 40
            withLogo()
            val gate = CompletableDeferred<Unit>()
            server.itemDetailsGate = gate
            vm.open(CH7)
            runCurrent()
            after(200)
            after(100)
            gate.complete(Unit)
            runCurrent()

            after(40)

            assertSame(offered.image, vm.shown().logo)
        }

    @Test
    fun aHighlightThatMovesOnCancelsTheRequestAndNeverShowsItOnAnotherCell() =
        guideTest { vm ->
            val gate = CompletableDeferred<Unit>()
            offered.gate = gate
            withLogo()
            vm.open(CH7)
            runCurrent()
            after(200)
            assertEquals(1, offered.asked.size)

            vm.move(GuideDirection.Right, repeat = false, atMs = 0)
            gate.complete(Unit)
            runCurrent()

            assertEquals("the first cell's request was cancelled", 1, offered.cancelled)
            assertEquals(0, offered.finished)
            assertEquals(min(90), vm.shown().cell.startMs)
            assertNull(vm.shown().logo)
        }

    @Test
    fun aFastScrollNeverShowsALogoThatBelongsToAnotherCell() =
        guideTest { vm ->
            offered.takes = 10
            withLogo()
            vm.open(CH7)
            runCurrent()
            after(200)
            // The first cell's logo would land at 210 ms; the focus has moved at 205 ms, before it.
            after(5)
            vm.move(GuideDirection.Right, repeat = false, atMs = 0)
            after(5)

            val shown = vm.shown()
            assertEquals(min(90), shown.cell.startMs)
            assertNull(shown.logo)
        }

    @Test
    fun aLoaderThatFailsLeavesTheTextTitleAndTheDetails() =
        guideTest { vm ->
            offered.failure = IllegalStateException("not an image")
            withLogo()
            vm.open(CH7)
            runCurrent()

            after(500)

            assertNull(vm.shown().logo)
            assertNotNull(vm.shown().item)
            assertFalse(vm.shown().loading)
            assertEquals(1, offered.asked.size)
        }

    @Test
    fun aCellWhoseDetailsAreHeldGetsItsLogoAfterTheRestToo() =
        guideTest { vm ->
            withLogo()
            vm.open(CH7)
            runCurrent()
            after(300)
            vm.move(GuideDirection.Right, repeat = false, atMs = 0)
            after(300)
            vm.move(GuideDirection.Left, repeat = false, atMs = 0)

            // Back on the first cell: its details are held and shown at once, its logo only once the focus has rested.
            assertNotNull(vm.shown().item)
            assertNull(vm.shown().logo)
            after(200)

            assertSame(offered.image, vm.shown().logo)
        }
}
