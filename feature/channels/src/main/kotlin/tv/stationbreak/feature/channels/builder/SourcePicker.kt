// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.builder

import tv.stationbreak.core.domain.TitleHit
import tv.stationbreak.core.domain.TitleKind
import tv.stationbreak.core.domain.lineup.builder.CatalogPicker
import tv.stationbreak.core.domain.lineup.builder.DraftSource
import tv.stationbreak.core.domain.lineup.builder.Listed
import tv.stationbreak.core.domain.lineup.builder.PickerEntry

/** What the source of [tab] is: the draft's kind of source, for a [key] and [label] the picker lists. */
internal fun sourceOf(
    tab: SourceKindTab,
    key: String,
    label: String,
    kind: TitleKind? = null,
): DraftSource =
    when (tab) {
        SourceKindTab.Libraries -> DraftSource.Library(key, label)
        SourceKindTab.Collections -> DraftSource.Collection(key, label)
        SourceKindTab.Tags -> DraftSource.Tag(key, label)
        SourceKindTab.Genres -> DraftSource.Genre(key, label)
        SourceKindTab.Pick -> if (kind == TitleKind.Show) DraftSource.Show(key, label) else DraftSource.Pick(key, label)
    }

/** Whether [this] and [other] are the same choice: the same kind of source and key (a label may differ). */
internal fun DraftSource.sameAs(other: DraftSource): Boolean = this::class == other::class && key == other.key

/** Ticks [source] if the list lacks it, else removes it. */
internal fun List<DraftSource>.toggled(source: DraftSource): List<DraftSource> =
    if (any { it.sameAs(source) }) filterNot { it.sameAs(source) } else this + source

/** The picker over one tab's read: [read] is null while it runs. Every entry but a picked film follows the library. */
internal fun pickerViewOf(
    read: Listed<List<PickerEntry>>?,
    ticked: (PickerEntry) -> Boolean,
): PickerView =
    when (read) {
        null -> {
            PickerView.Loading
        }

        Listed.Unavailable -> {
            PickerView.Unavailable
        }

        is Listed.Ok -> {
            if (read.value.isEmpty()) {
                PickerView.Empty
            } else {
                PickerView.Entries(read.value.map { PickerRow(it.key, it.label, live = true, ticked = ticked(it)) })
            }
        }
    }

/** The title search's picker: nothing to list until there is enough text, then what the search found. */
internal fun searchViewOf(
    text: String,
    searching: Boolean,
    hits: Listed<List<TitleHit>>?,
    ticked: (TitleHit) -> Boolean,
): PickerView {
    val trimmed = text.trim()
    return when {
        trimmed.codePointCount(0, trimmed.length) !in CatalogPicker.SEARCH_LENGTH -> {
            PickerView.Entries(emptyList())
        }

        searching -> {
            PickerView.Loading
        }

        hits == null -> {
            PickerView.Entries(emptyList())
        }

        hits == Listed.Unavailable -> {
            PickerView.Unavailable
        }

        else -> {
            PickerView.Entries(
                (hits as Listed.Ok).value.map {
                    PickerRow(it.id.value, it.name, live = it.kind == TitleKind.Show, ticked = ticked(it))
                },
            )
        }
    }
}
