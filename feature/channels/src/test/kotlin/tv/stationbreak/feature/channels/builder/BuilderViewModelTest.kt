// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:OptIn(ExperimentalCoroutinesApi::class)

package tv.stationbreak.feature.channels.builder

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.TitleHit
import tv.stationbreak.core.domain.TitleKind
import tv.stationbreak.core.domain.lineup.builder.BuildRefusal
import tv.stationbreak.core.domain.lineup.builder.BuildResult
import tv.stationbreak.core.domain.lineup.builder.CatalogPicker
import tv.stationbreak.core.domain.lineup.builder.ChannelPreview
import tv.stationbreak.core.domain.lineup.builder.DraftProblem
import tv.stationbreak.core.domain.lineup.builder.DraftSource
import tv.stationbreak.core.domain.lineup.builder.KeptItem
import tv.stationbreak.core.domain.lineup.builder.KeptOrder
import tv.stationbreak.core.domain.lineup.builder.KeptSettings
import tv.stationbreak.core.domain.lineup.builder.Listed
import tv.stationbreak.core.domain.lineup.builder.OpenResult
import tv.stationbreak.core.domain.lineup.builder.PickerEntry
import tv.stationbreak.core.domain.lineup.builder.PlayOrder
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.Seed
import tv.stationbreak.feature.channels.PanelMove.Down
import tv.stationbreak.feature.channels.PanelMove.Left
import tv.stationbreak.feature.channels.PanelMove.Right
import tv.stationbreak.feature.channels.PanelMove.Up
import tv.stationbreak.feature.channels.summary

class BuilderViewModelTest {
    private val lib1 = DraftSource.Library("lib1", "Library 1")

    @Test
    fun nextIsEnabledOnlyWithASource() =
        builderTest { rig ->
            rig.picker.libraries = libraries(2)
            rig.openNew()
            assertFalse(rig.ui.nextEnabled)
            rig.tickFirst()
            assertTrue(rig.ui.nextEnabled)
        }

    @Test
    fun tickingAnEntryAddsAndRemovesIt() =
        builderTest { rig ->
            rig.picker.libraries = libraries(2)
            rig.openNew()
            rig.tickFirst()
            assertEquals(listOf(lib1), rig.ui.draft.sources)
            assertEquals(listOf(true, false), (rig.ui.entries as PickerView.Entries).rows.map { it.ticked })
            rig.select()
            assertEquals(emptyList<DraftSource>(), rig.ui.draft.sources)
            assertEquals(listOf(false, false), (rig.ui.entries as PickerView.Entries).rows.map { it.ticked })
        }

    @Test
    fun liveSourcesAreMarkedLive() =
        builderTest { rig ->
            val show = CanonicalId.parse("a".repeat(32))
            val film = CanonicalId.parse("b".repeat(32))
            rig.picker.libraries = libraries(1)
            rig.picker.collections = Listed.Ok(listOf(PickerEntry("c1", "Box set")))
            rig.picker.tags = Listed.Ok(listOf(PickerEntry("Christmas", "Christmas")))
            rig.picker.genres = Listed.Ok(listOf(PickerEntry("Comedy", "Comedy")))
            rig.picker.hits =
                Listed.Ok(
                    listOf(TitleHit(show, "Show", TitleKind.Show, null), TitleHit(film, "Film", TitleKind.Film, 1999)),
                )
            rig.openNew()
            rig.tickFirst()
            repeat(3) {
                rig.press(Left)
                rig.press(Down)
                rig.press(Right)
                rig.select()
            }
            rig.press(Left, Down, Right)
            assertEquals(BuilderFocus.Search, rig.ui.focus)
            rig.vm.type("fi")
            rig.advance(300)
            rig.press(Down)
            rig.select()
            rig.press(Down)
            rig.select()
            assertEquals(
                listOf(
                    DraftSource.Library("lib1", "Library 1"),
                    DraftSource.Collection("c1", "Box set"),
                    DraftSource.Tag("Christmas", "Christmas"),
                    DraftSource.Genre("Comedy", "Comedy"),
                    DraftSource.Show(show.value, "Show"),
                    DraftSource.Pick(film.value, "Film"),
                ),
                rig.ui.draft.sources,
            )
            val rows = (rig.ui.entries as PickerView.Entries).rows
            assertEquals(listOf(true, false), rows.map { it.live })
            rig.press(Left, Up, Up, Up, Up, Right)
            assertEquals(true, (rig.ui.entries as PickerView.Entries).rows.single().live)
        }

    @Test
    fun sheetRowsFollowTheDraft() =
        builderTest { rig ->
            rig.openEdit()
            val all =
                listOf(
                    SheetRow.Name,
                    SheetRow.Sources,
                    SheetRow.NarrowDown,
                    SheetRow.PlayOrder,
                    SheetRow.SeriesCap,
                    SheetRow.FilmCap,
                    SheetRow.Kids,
                    SheetRow.Number,
                    SheetRow.Preview,
                    SheetRow.Save,
                    SheetRow.Reshuffle,
                    SheetRow.Delete,
                )
            assertEquals(all, rig.ui.sheetRows)
            rig.press(Down, Down, Down)
            assertEquals(BuilderFocus.Sheet(SheetRow.PlayOrder), rig.ui.focus)
            rig.press(Right, Right)
            assertEquals(PlayOrder.InOrder, rig.ui.draft.order)
            val noCaps = all - SheetRow.SeriesCap - SheetRow.FilmCap
            assertEquals(noCaps - SheetRow.Reshuffle, rig.ui.sheetRows)
        }

    @Test
    fun theSheetOfACopyListsTheKeptSettingsAndANewOneHasNoDelete() =
        builderTest { rig ->
            val kept = KeptSettings("Movies", listOf(KeptItem.Order(KeptOrder.Chronological)))
            rig.builder.openResult =
                OpenResult.Opened(draftOf("Movies", listOf(lib1), order = null, kept = kept, origin = BUILT_IN), null)
            rig.vm.openCopy(BUILT_IN) { it }
            rig.settle()
            assertTrue(SheetRow.Kept in rig.ui.sheetRows)
            assertFalse(SheetRow.SeriesCap in rig.ui.sheetRows)
            assertFalse(SheetRow.Delete in rig.ui.sheetRows)
            assertFalse(SheetRow.Reshuffle in rig.ui.sheetRows)
        }

    @Test
    fun reshuffleIsOfferedOnlyForAShuffleSheetWithNoUnsavedChanges() =
        builderTest { rig ->
            rig.openEdit()
            assertTrue(SheetRow.Reshuffle in rig.ui.sheetRows)
            rig.press(Down, Down, Down, Down)
            assertEquals(BuilderFocus.Sheet(SheetRow.SeriesCap), rig.ui.focus)
            rig.press(Right)
            assertTrue(rig.ui.dirty)
            assertFalse(SheetRow.Reshuffle in rig.ui.sheetRows)
            rig.press(Left)
            assertFalse(rig.ui.dirty)
            assertTrue(SheetRow.Reshuffle in rig.ui.sheetRows)
            rig.press(Up, Right)
            assertEquals(PlayOrder.Rotation, rig.ui.draft.order)
            assertFalse(SheetRow.Reshuffle in rig.ui.sheetRows)
        }

    @Test
    fun emptyPickersSaySo() =
        builderTest { rig ->
            rig.picker.libraries = Listed.Ok(emptyList())
            rig.openNew()
            assertEquals(PickerView.Empty, rig.ui.entries)
        }

    @Test
    fun theLibrariesTabSaysUnavailableWhileTheOthersWork() =
        builderTest { rig ->
            rig.picker.libraries = Listed.Unavailable
            rig.picker.collections = Listed.Ok(listOf(PickerEntry("c1", "Box set")))
            rig.openNew()
            assertEquals(PickerView.Unavailable, rig.ui.entries)
            rig.press(Down)
            assertEquals(SourceKindTab.Collections, rig.ui.tab)
            assertEquals(listOf("Box set"), (rig.ui.entries as PickerView.Entries).rows.map { it.label })
        }

    @Test
    fun genresAreReadOnceAndTheLibrariesAgainWhileUnavailable() {
        var genreReads = 0
        var libraryReads = 0
        builderTest(
            wrap = { fake ->
                object : CatalogPicker by fake {
                    override suspend fun genres(account: LibraryAccount): Listed<List<PickerEntry>> {
                        genreReads++
                        return fake.genres(account)
                    }

                    override suspend fun libraries(account: LibraryAccount): Listed<List<PickerEntry>> {
                        libraryReads++
                        return fake.libraries(account)
                    }
                }
            },
        ) { rig ->
            rig.picker.libraries = Listed.Unavailable
            rig.openNew()
            assertEquals(1, libraryReads)
            rig.press(Down, Down, Down)
            rig.press(Up, Up, Up)
            assertEquals(2, libraryReads)
            rig.press(Down, Down, Down)
            assertEquals(1, genreReads)
        }
    }

    @Test
    fun aSearchWithWildcardsMatchesLiterally() =
        builderTest { rig ->
            rig.openNew()
            rig.press(Down, Down, Down, Down, Right)
            assertEquals(BuilderFocus.Search, rig.ui.focus)
            rig.vm.type("50%_*")
            rig.advance(300)
            assertEquals(listOf("50%_*"), rig.picker.searches)
        }

    @Test
    fun typingDebouncesTheSearch() =
        builderTest { rig ->
            rig.openNew()
            rig.press(Down, Down, Down, Down, Right)
            rig.vm.type("fo")
            rig.advance(100)
            rig.vm.type("foo")
            rig.advance(100)
            rig.vm.type("foob")
            rig.advance(299)
            assertEquals(emptyList<String>(), rig.picker.searches)
            rig.advance(1)
            assertEquals(listOf("foob"), rig.picker.searches)
        }

    @Test
    fun aLaterSearchCancelsAStaleSlowOne() {
        val finished = mutableListOf<String>()
        builderTest(
            wrap = { fake ->
                object : CatalogPicker by fake {
                    override suspend fun search(
                        account: LibraryAccount,
                        text: String,
                    ): Listed<List<TitleHit>> {
                        delay(1000)
                        finished += text
                        return fake.search(account, text)
                    }
                }
            },
        ) { rig ->
            rig.openNew()
            rig.press(Down, Down, Down, Down, Right)
            rig.vm.type("ab")
            rig.advance(300)
            rig.vm.type("abc")
            rig.advance(300)
            rig.advance(2000)
            assertEquals(listOf("abc"), finished)
            assertEquals(listOf("abc"), rig.picker.searches)
        }
    }

    @Test
    fun aReshuffleThatCannotReopenTheChannelShowsWhyNotSaved() =
        builderTest { rig ->
            rig.openEdit(draftOf("Mine", listOf(lib1), seed = Seed(1uL)))
            rig.advance(400)
            rig.builder.openResult = OpenResult.Failed
            rig.press(Down, Down, Down, Down, Down, Down, Down, Down, Down, Down)
            rig.select()
            assertEquals(BuilderMessage.OpenFailed, rig.ui.message)
        }

    @Test
    fun reshuffleReopensTheDraftWithTheNewSeed() =
        builderTest { rig ->
            rig.openEdit(draftOf("Mine", listOf(lib1), seed = Seed(1uL)))
            rig.advance(400)
            val before = rig.previews.drafts.size
            val fresh = draftOf("Mine", listOf(lib1), seed = Seed(2uL))
            rig.builder.openResult = OpenResult.Opened(fresh, null)
            rig.press(Down, Down, Down, Down, Down, Down, Down, Down, Down, Down)
            assertEquals(BuilderFocus.Sheet(SheetRow.Reshuffle), rig.ui.focus)
            rig.select()
            assertEquals(Seed(2uL), rig.ui.draft.seed)
            assertEquals(BuilderMessage.Reshuffled, rig.ui.message)
            assertFalse(rig.ui.dirty)
            assertTrue("reshuffle" in rig.builder.calls)
            rig.advance(400)
            assertEquals(
                Seed(2uL),
                rig.previews.drafts
                    .last()
                    .seed,
            )
            assertEquals(before + 1, rig.previews.drafts.size)
        }

    @Test
    fun theBarAndPreviewRecomputeOnceAfterAQuietPeriod() =
        builderTest { rig ->
            rig.picker.libraries = libraries(5)
            rig.previews.answer = ChannelPreview.Ready(42, 600, emptyList(), emptyList())
            rig.openNew()
            rig.advance(1000)
            rig.tickFirst()
            repeat(4) {
                rig.advance(100)
                rig.press(Down)
                rig.select()
            }
            rig.advance(399)
            assertEquals(0, rig.previews.drafts.size)
            assertNull(rig.ui.barCount)
            rig.advance(1)
            assertEquals(1, rig.previews.drafts.size)
            assertEquals(
                5,
                rig.previews.drafts
                    .single()
                    .sources.size,
            )
            assertEquals(42, rig.ui.barCount)
        }

    @Test
    fun thePreviewShowsWorkingWhileItRuns() =
        builderTest { rig ->
            rig.picker.libraries = libraries(1)
            rig.previews.answer = ChannelPreview.Ready(3, 90, emptyList(), emptyList())
            rig.openNew()
            rig.tickFirst()
            assertEquals(PreviewView.Working, rig.ui.preview)
            rig.advance(400)
            assertEquals(PreviewView.Ready(emptyList(), 3, 90, emptyList()), rig.ui.preview)
        }

    @Test
    fun anUnavailablePreviewSaysSoAndSavingStillWorks() =
        builderTest { rig ->
            rig.picker.libraries = libraries(1)
            rig.previews.answer = ChannelPreview.Unavailable
            rig.openNew()
            rig.tickFirst()
            rig.advance(400)
            assertEquals(PreviewView.Unavailable, rig.ui.preview)
            assertNull(rig.ui.barCount)
            rig.vm.next()
            rig.vm.next()
            rig.vm.save(andWatch = false)
            rig.advance(1)
            assertEquals(1, rig.builder.created.size)
        }

    @Test
    fun aDraftThatMatchesNothingShowsNothingFitsAndCanStillBeSaved() =
        builderTest { rig ->
            rig.picker.libraries = libraries(1)
            rig.previews.answer = ChannelPreview.NothingFits(emptyList())
            rig.openNew()
            rig.tickFirst()
            rig.advance(400)
            assertEquals(PreviewView.NothingFits(emptyList()), rig.ui.preview)
            rig.vm.next()
            rig.vm.next()
            rig.vm.save(andWatch = false)
            rig.advance(1)
            assertEquals(1, rig.builder.created.size)
        }
}
