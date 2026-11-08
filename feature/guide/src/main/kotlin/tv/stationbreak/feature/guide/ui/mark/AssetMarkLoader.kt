// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui.mark

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

private const val MARKS_FOLDER = "channelmarks"
private val MARK_FILE = Regex("""sl_(\d{3})_([a-z0-9-]+)_32dp\.png""")

/**
 * The recipe key in a bundled logo's file name, `sl_<NNN>_<key>_32dp.png`, or null for any other name. This is the only
 * way an asset name is ever accepted: nothing else is turned into an asset path (spec 27).
 */
internal fun markKeyOf(fileName: String): String? = MARK_FILE.matchEntire(fileName)?.groupValues?.get(2)

/** Finds the bundled logos by recipe key and decodes them. Blocking: call it off the main thread only. */
internal class AssetMarkLoader(
    private val assets: AssetManager,
) : MarkLoader {
    // Key to the exact listed file name; a name is only ever one that markKeyOf accepted from the listing.
    private val index: Map<String, String> by lazy {
        val names = assets.list(MARKS_FOLDER).orEmpty()
        buildMap { for (name in names) markKeyOf(name)?.let { put(it, name) } }
    }

    override fun keys(): Set<String> = index.keys

    override fun load(key: String): ImageBitmap? {
        val fileName = index[key] ?: return null
        return try {
            val options =
                BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inScaled = false
                }
            val decoded = assets.open("$MARKS_FOLDER/$fileName").use { BitmapFactory.decodeStream(it, null, options) }
            decoded?.asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }
}
