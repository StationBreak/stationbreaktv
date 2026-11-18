// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import org.jellyfin.sdk.model.api.ItemFields
import kotlin.test.assertTrue

/**
 * Every name in a `fields` list is one of Jellyfin's `ItemFields` (#775), as the SDK's generated enum lists them.
 * A name that isn't one is ignored but logs an exception on the server for every call (`PremiereDate` did).
 */
fun assertItemFields(names: List<String>) {
    val known = ItemFields.entries.map { it.serialName }.toSet()
    val unknown = names.filterNot { it in known }
    assertTrue(unknown.isEmpty(), "not Jellyfin ItemFields: $unknown")
}
