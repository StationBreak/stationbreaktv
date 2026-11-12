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
internal class StubBitmap : ImageBitmap {
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

/**
 * The logos the panel is offered, with what a test needs to hold one back or fail it. Like the real loader it keeps
 * what it has loaded, and answers a held logo at once.
 */
private class FakeLogos : GuideLogos {
    val image: ImageBitmap = StubBitmap()
    val asked = mutableListOf<ItemLogo>()
    private val held = mutableMapOf<ItemLogo, ImageBitmap>()
    var takes = 0L
    var gate: CompletableDeferred<Unit>? = null
    var failure: Exception? = null
    var cancelled = 0
    var finished = 0

    override fun cached(logo: ItemLogo): ImageBitmap? = held[logo]

    override suspend fun load(logo: ItemLogo): ImageBitmap? {
        held[logo]?.let { return it }
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
        held[logo] = image
        return image
    }
}

/**
 * The details panel's logo (#723): it replaces the text title whenever it arrives while the highlight is still on its
 * cell, never on another cell; one that arrives after the highlight has moved is kept for the next visit; and a cell
 * whose details and logo are both held shows the whole panel at once.
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
    fun aLogoReplacesTheTitleOnceTheFocusHasRestedAndItArrives() =
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
    fun aLogoThatArrivesWhileTheHighlightStaysIsShownHoweverLongItTook() =
        guideTest { vm ->
            offered.takes = 900
            withLogo()
            vm.open(CH7)
            runCurrent()
            after(200)

            after(899)
            assertNull(vm.shown().logo)
            after(1)

            assertSame(offered.image, vm.shown().logo)
        }

    @Test
    fun aLogoThatArrivesAfterTheHighlightMovedIsKeptAndShownToNoOne() =
        guideTest { vm ->
            offered.takes = 150
            withLogo()
            vm.open(CH7)
            runCurrent()
            after(200)
            after(50)
            vm.move(GuideDirection.Right, repeat = false, atMs = 0)

            after(100)

            assertEquals("the request was left to finish", 1, offered.finished)
            assertEquals(0, offered.cancelled)
            assertEquals(min(90), vm.shown().cell.startMs)
            assertNull("not shown on the cell the highlight moved to", vm.shown().logo)
            assertSame("kept for the next visit", offered.image, offered.cached(logo))
        }

    @Test
    fun aHighlightThatMovesOnLeavesTheRequestRunningAndNeverShowsItOnAnotherCell() =
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

            assertEquals(1, offered.finished)
            assertEquals(0, offered.cancelled)
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
    fun aCellWhoseDetailsAndLogoAreHeldIsPublishedWithItsLogoAtOnce() =
        guideTest { vm ->
            withLogo()
            vm.open(CH7)
            runCurrent()
            after(300)
            vm.move(GuideDirection.Right, repeat = false, atMs = 0)
            after(300)
            assertEquals(1, offered.asked.size)

            vm.move(GuideDirection.Left, repeat = false, atMs = 0)

            // Nothing was advanced after the move: no rest, no text title first, and no request for the logo again.
            val shown = vm.shown()
            assertEquals(min(60), shown.cell.startMs)
            assertNotNull(shown.item)
            assertFalse(shown.loading)
            assertSame(offered.image, shown.logo)
            after(1000)
            assertEquals(1, offered.asked.size)
            assertSame(offered.image, vm.shown().logo)
        }

    @Test
    fun aCellWhoseDetailsAreHeldButNotItsLogoShowsTheTextTitleThenTheLogoAfterTheRest() =
        guideTest { vm ->
            offered.failure = IllegalStateException("not an image")
            withLogo()
            vm.open(CH7)
            runCurrent()
            after(300)
            vm.move(GuideDirection.Right, repeat = false, atMs = 0)
            after(300)
            offered.failure = null
            vm.move(GuideDirection.Left, repeat = false, atMs = 0)

            // Its details are held and shown at once; its logo is not held, so it is asked for after the rest.
            assertNotNull(vm.shown().item)
            assertNull(vm.shown().logo)
            after(200)

            assertSame(offered.image, vm.shown().logo)
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
}
