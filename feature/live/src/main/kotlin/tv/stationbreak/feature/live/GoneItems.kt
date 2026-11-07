// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.CoroutineScope
import tv.stationbreak.core.domain.lineup.ChannelOnAir
import tv.stationbreak.core.model.CanonicalId

/**
 * A program that left the library, with nothing to fill its slot (spec 26, #633): the ident card with its reason, at
 * once, until the block ends. Unlike [deadItem] there is no "Technical difficulties" slate, no move on to the next show
 * (the sitting does not drift), no stand-ins and no lookup: the library has already said it is gone. [gone] is the
 * missing program, kept for the signature's sake and for a reader of the call.
 */
@Suppress("UnusedParameter") // Names which program is gone; nothing is looked up for it.
internal suspend fun Sitting.goneItem(
    scope: CoroutineScope,
    onAir: ChannelOnAir,
    gone: CanonicalId,
): Exit? = holdIdent(scope, onAir, onAir.now.endMs, CardNote.NotInLibrary)
