// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.stationbreak.core.domain.details.LogoCache
import tv.stationbreak.core.model.ItemLogo

/**
 * Where the details panel gets a logo, decoded: the guide asks, and the answer is the picture or null ("no logo": none
 * held and none fetched, a server that would not say, or bytes that are not a usable image). It never throws, apart
 * from the cancellation of the caller, which also cancels any request nobody else waits on.
 */
internal fun interface GuideLogos {
    suspend fun load(logo: ItemLogo): ImageBitmap?

    /** The decoded logo if one is held in memory right now, else null: no waiting, no request. */
    fun cached(logo: ItemLogo): ImageBitmap? = null

    companion object {
        /** No logos: the panel always shows the text title. */
        val None = GuideLogos { null }
    }
}

/** Opens the bytes of an image into a picture, or null when they are not one the guide will draw. */
internal fun interface LogoDecoder {
    fun decode(bytes: ByteArray): ImageBitmap?
}

/**
 * The [GuideLogos] of the app: [cache] for the bytes (disk, then the server), [decoder] off the main thread, and the
 * last [DecodedLogos.CAPACITY] pictures decoded kept in [decoded], so a cell seen again shows its logo at once. Every
 * logo that is loaded is kept, whether or not the highlight is still on its cell when it arrives.
 */
internal class CachedGuideLogos(
    private val cache: LogoCache,
    private val decoder: LogoDecoder = AndroidLogoDecoder,
    private val cpu: CoroutineDispatcher = Dispatchers.Default,
    private val decoded: DecodedLogos = DecodedLogos(),
) : GuideLogos {
    override fun cached(logo: ItemLogo): ImageBitmap? = cache.account()?.let { decoded.get(it, logo) }

    override suspend fun load(logo: ItemLogo): ImageBitmap? {
        val account = cache.account()
        return if (account == null) null else decoded.get(account, logo) ?: decode(account, logo)
    }

    // The bytes (disk, then the server), opened off the main thread and kept for the next time.
    private suspend fun decode(
        account: String,
        logo: ItemLogo,
    ): ImageBitmap? {
        val bytes = cache.logo(logo) ?: return null
        return withContext(cpu) { decoder.decode(bytes) }?.also { decoded.put(account, logo, it) }
    }
}

/**
 * A small least-recently-used memory of decoded logos, for one account at a time: a get or put under another account
 * than the last one empties it first, so nothing decoded for an account that left is ever handed to the next.
 */
internal class DecodedLogos(
    private val capacity: Int = CAPACITY,
) {
    init {
        require(capacity >= 1) { "A cache holds at least one logo" }
    }

    private val lock = Any()
    private var account: String? = null
    private val held = LinkedHashMap<ItemLogo, ImageBitmap>(capacity, 1f, true)

    fun get(
        account: String,
        logo: ItemLogo,
    ): ImageBitmap? = synchronized(lock) { forAccount(account)[logo] }

    fun put(
        account: String,
        logo: ItemLogo,
        image: ImageBitmap,
    ) = synchronized(lock) {
        val map = forAccount(account)
        map[logo] = image
        while (map.size > capacity) map.remove(map.keys.first())
    }

    // Called with the lock held: the memory as it is for [account], emptied if it was another's.
    private fun forAccount(account: String): LinkedHashMap<ItemLogo, ImageBitmap> {
        if (this.account != account) {
            held.clear()
            this.account = account
        }
        return held
    }

    companion object {
        /** How many decoded logos are kept: a screenful of rows and the ones just scrolled past. */
        const val CAPACITY = 24
    }
}

/**
 * Decodes with [BitmapFactory], after reading the size alone: the bytes are the server's, so an image that claims more
 * than a logo ever is (it asked for 192 pixels tall) is refused before any pixel is made, which a few kilobytes of
 * compressed image could otherwise turn into a very large bitmap.
 */
internal object AndroidLogoDecoder : LogoDecoder {
    internal const val MAX_WIDTH = 2_048
    internal const val MAX_HEIGHT = 512
    internal const val MAX_PIXELS = 1_000_000L

    override fun decode(bytes: ByteArray): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        val usable =
            width in 1..MAX_WIDTH && height in 1..MAX_HEIGHT && width.toLong() * height <= MAX_PIXELS
        return if (usable) BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() else null
    }
}
