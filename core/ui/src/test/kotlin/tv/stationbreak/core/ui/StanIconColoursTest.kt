// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.Locale

/** The Stan icon is a VectorDrawable and cannot reference [BrandColors]; its colour literals must match them. */
class StanIconColoursTest {
    // Gradle runs a module's unit tests with the module directory as the working directory.
    private val inkIcon = File("src/main/res/drawable/stan_icon_ink.xml")
    private val paperIcon = File("src/main/res/drawable/stan_icon_paper.xml")

    private fun hex(color: Color) = String.format(Locale.ROOT, "#%06X", color.toArgb() and 0xFFFFFF)

    private fun coloursOf(icon: File) =
        Regex("(?:fillColor|strokeColor)=\"(#[0-9A-Fa-f]{6})\"")
            .findAll(icon.readText())
            .map { it.groupValues[1].uppercase(Locale.ROOT) }
            .toSet()

    private val brand get() = setOf(hex(BrandColors.Ink), hex(BrandColors.Cream), hex(BrandColors.Orange))

    @Test
    fun theInkIconUsesExactlyTheBrandInkCreamAndOrange() {
        assertEquals(brand, coloursOf(inkIcon))
    }

    @Test
    fun thePaperIconUsesExactlyTheBrandInkCreamAndOrange() {
        assertEquals(brand, coloursOf(paperIcon))
    }

    @Test
    fun thePaperIconIsACreamTileAndTheInkIconAnInkOne() {
        // The first path is the rounded square: the tile that the two icons trade.
        fun tile(icon: File) = Regex("fillColor=\"(#[0-9A-Fa-f]{6})\"").find(icon.readText())!!.groupValues[1]
        assertEquals(hex(BrandColors.Ink), tile(inkIcon).uppercase(Locale.ROOT))
        assertEquals(hex(BrandColors.Cream), tile(paperIcon).uppercase(Locale.ROOT))
    }
}
