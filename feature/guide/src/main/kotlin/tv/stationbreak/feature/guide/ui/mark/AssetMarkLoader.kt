// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui.mark

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

private const val MARKS_FOLDER = "channelmarks"

/** The sizes a bundled logo may come in. A name is `_<N>dp`: N dp at 2x, so `_32dp` is 64 px and `_64dp` is 128 px. */
internal val MarkBuckets = setOf(32, 48, 64)
private val MARK_FILE = Regex("""sl_(\d{3})_([a-z0-9-]+)_(32|48|64)dp\.png""")
private const val KEY_GROUP = 2
private const val BUCKET_GROUP = 3

/** A bucket is N dp at 2x. */
private const val BUCKET_SCALE = 2

/** The width of the box a mark is drawn in, in dp: what the bucket has to cover. */
internal const val MARK_BOX_DP = 50f

/**
 * The recipe key in a bundled logo's file name, `sl_<NNN>_<key>_<N>dp.png` with N in [MarkBuckets], or null for any
 * other name. This is the only way an asset name is ever accepted: nothing else is turned into an asset path (spec 27).
 */
internal fun markKeyOf(fileName: String): String? = MARK_FILE.matchEntire(fileName)?.groupValues?.get(KEY_GROUP)

/** The bucket (dp at 2x) in a bundled logo's file name, or null for any other name. */
internal fun markBucketOf(fileName: String): Int? =
    MARK_FILE
        .matchEntire(fileName)
        ?.groupValues
        ?.get(BUCKET_GROUP)
        ?.toInt()

/**
 * The bucket to decode for a box [boxDp] wide at [density] pixels per dp: the smallest of [available] whose pixels
 * (twice its N) cover the box, else the largest there is, and null when there is none. A key with only one size (the
 * 32 dp set today) uses it at any density, so no particular size is required.
 */
internal fun pickMarkBucket(
    available: Set<Int>,
    boxDp: Float,
    density: Float,
): Int? {
    val needed = boxDp * density
    return available.sorted().firstOrNull { it * BUCKET_SCALE >= needed } ?: available.maxOrNull()
}

/**
 * Finds the bundled logos by recipe key and decodes the size that fits [density]. Blocking: call it off the main thread
 * only.
 */
internal class AssetMarkLoader(
    private val assets: AssetManager,
    private val density: Float = 2f,
) : MarkLoader {
    // Key to its listed file names by bucket; a name is only ever one that markKeyOf accepted from the listing.
    private val index: Map<String, Map<Int, String>> by lazy {
        val found = mutableMapOf<String, MutableMap<Int, String>>()
        for (name in assets.list(MARKS_FOLDER).orEmpty()) {
            val key = markKeyOf(name)
            val bucket = markBucketOf(name)
            if (key != null && bucket != null) found.getOrPut(key) { mutableMapOf() }[bucket] = name
        }
        found
    }

    override fun keys(): Set<String> = index.keys

    override fun load(key: String): ImageBitmap? {
        val files = index[key].orEmpty()
        val fileName = pickMarkBucket(files.keys, MARK_BOX_DP, density)?.let(files::get)
        return fileName?.let(::decode)
    }

    private fun decode(fileName: String): ImageBitmap? =
        try {
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
