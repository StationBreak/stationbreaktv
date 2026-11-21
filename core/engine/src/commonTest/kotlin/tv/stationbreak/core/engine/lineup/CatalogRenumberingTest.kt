// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.engine.PoolPolicy
import tv.stationbreak.core.engine.rules
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An installed lineup takes catalog 8's numbers without sharing a live number (#1002, the owner's waiver). */
class CatalogRenumberingTest {
    private val someRules = rules(listOf(Source.Genre("drama")))

    // The numbers the Kids band and the leftovers had before catalog 8.
    private val oldNumbers =
        mapOf(
            "little-farm" to 4,
            "sing-along-station" to 8,
            "once-upon" to 12,
            "saturday-morning" to 16,
            "backyard-fort" to 20,
            "critter-channel" to 24,
            "robot-factory" to 28,
            "hey-science" to 32,
            "rainy-day-matinee" to 36,
            "little-spirits" to 40,
            "lights-out" to 44,
            "pocket-heroes" to 48,
            "flipbook" to 52,
            "snowglobe" to 56,
            "giggle-garage" to 60,
            "wheels-and-whistles" to 64,
            "small-sleuths" to 68,
            "tall-tales-club" to 72,
            "spooky-sleepover" to 76,
            "tiny-chefs" to 80,
        )

    private fun starter(
        key: String,
        number: Int?,
        override: Int? = null,
        state: ChannelState = ChannelState.On,
    ) = LineupChannel(
        LineupIds.starter(key),
        number,
        override,
        null,
        state,
        ChannelSource.Starter(key, false, someRules),
        5L,
    )

    private fun leftovers(
        key: String,
        number: Int,
        rating: String?,
    ) = LineupChannel(
        LineupIds.leftovers(key),
        number,
        null,
        null,
        ChannelState.On,
        ChannelSource.Leftovers(key, rating),
        5L,
    )

    private fun custom(
        name: String,
        number: Int,
        override: Boolean = false,
    ) = LineupChannel(
        ChannelId("custom:$name"),
        if (override) null else number,
        if (override) number else null,
        null,
        ChannelState.On,
        ChannelSource.Custom(someRules),
        5L,
    )

    private fun installed(vararg extra: LineupChannel): List<LineupChannel> =
        oldNumbers.map { (key, number) -> starter(key, number) } +
            leftovers("kids-odds-and-ends", 99, "PG") +
            leftovers("odds-and-ends", 199, null) +
            extra

    private fun withCritterOverride(number: Int) =
        installed().map { if (it.id == LineupIds.starter("critter-channel")) it.copy(numberOverride = number) else it }

    private fun move(channels: List<LineupChannel>) = CatalogRenumbering.apply(channels, 7, StarterCatalog)

    private fun List<LineupChannel>.byId(id: ChannelId) = first { it.id == id }

    private fun List<LineupChannel>.number(key: String) = first { it.id.value.endsWith(":$key") }.number

    private fun assertNoSharedNumber(channels: List<LineupChannel>) {
        val numbers = channels.flatMap { listOfNotNull(it.number) }
        assertEquals(
            numbers.size,
            numbers.toSet().size,
            "a live number is shared: ${numbers.groupBy { it }.filter { it.value.size > 1 }.keys}",
        )
    }

    @Test
    fun everyInstalledChannelTakesTheNewNumberAndNoNumberIsShared() {
        val after = move(installed())
        for (recipe in StarterCatalog.recipes.filter { it.key in oldNumbers }) {
            assertEquals(recipe.number, after.number(recipe.key), recipe.key)
        }
        assertEquals(33, after.number("kids-odds-and-ends"))
        assertEquals(199, after.number("odds-and-ends"))
        assertNoSharedNumber(after)
        // The chain (Saturday Morning 16 to 6, Pocket Heroes 48 to 16) lands on each other's old numbers.
        assertEquals(6, after.number("saturday-morning"))
        assertEquals(16, after.number("pocket-heroes"))
    }

    @Test
    fun aKidsChannelKeepsItsStateAndIdentity() {
        val before = installed()
        val after = move(before)
        assertEquals(before.map { it.id }, after.map { it.id })
        assertEquals(before.map { it.state }, after.map { it.state })
        assertEquals(before.map { it.source }, after.map { it.source })
    }

    @Test
    fun aViewerNumberOnAWantedNumberSendsTheStarterToTheFirstFreeOneInItsBand() {
        // A viewer's custom kids channel sits on 6, which Saturday Morning wants now.
        val after = move(installed(custom("mine", 6)))
        assertEquals(6, after.first { it.id.value == "custom:mine" }.number)
        val saturday = checkNotNull(after.number("saturday-morning"))
        assertTrue(saturday in Band.Kids.numbers && saturday != 6, "took $saturday")
        assertNoSharedNumber(after)
        // The first free number in the band that no catalog channel has as its default.
        assertEquals(1, saturday)
    }

    @Test
    fun aViewersOwnNumberOnAStarterStays() {
        val before = withCritterOverride(31)
        val after = move(before)
        val critter = after.first { it.id == LineupIds.starter("critter-channel") }
        assertEquals(31, critter.number)
        assertEquals(10, critter.defaultNumber)
        assertNoSharedNumber(after)
    }

    @Test
    fun anOverrideThatIsTheNewDefaultBecomesTheDefault() {
        val before = withCritterOverride(10)
        val critter = move(before).first { it.id == LineupIds.starter("critter-channel") }
        assertEquals(10, critter.defaultNumber)
        assertNull(critter.numberOverride)
    }

    @Test
    fun aCustomKidsChannelAbove33MovesIntoTheKidsBandAndOthersStay() {
        val after =
            move(
                installed(
                    custom("old-kids", 50),
                    custom("new-kids", 11),
                    custom("grown", 650),
                    custom("kids-override", 70, override = true),
                ),
            )
        val oldKids = checkNotNull(after.first { it.id.value == "custom:old-kids" }.number)
        assertTrue(oldKids in Band.Kids.numbers, "moved to $oldKids")
        assertEquals(11, after.first { it.id.value == "custom:new-kids" }.number)
        assertEquals(650, after.first { it.id.value == "custom:grown" }.number)
        val override = checkNotNull(after.first { it.id.value == "custom:kids-override" }.number)
        assertTrue(override in Band.Kids.numbers, "moved to $override")
        assertNoSharedNumber(after)
        // Every channel numbered in the Kids band that was a kid before is a kid now.
        for (id in listOf("custom:old-kids", "custom:kids-override")) {
            assertEquals(PoolPolicy.KidsOnly, KidStatus.basePolicyOf(after.first { it.id.value == id }), id)
        }
    }

    @Test
    fun aStarterWithNoFreeNumberInItsBandGoesToTheShelf() {
        // Custom channels hold every Kids number but 4 (the default of Sing-Along Station, so not free either).
        val customs =
            Band.Kids.numbers
                .filter { it != 4 }
                .map { custom("c$it", it) }
        val after = move(listOf(starter("little-farm", 4)) + customs)
        val farm = after.first { it.id == LineupIds.starter("little-farm") }
        assertNull(farm.number)
        assertEquals(ChannelState.Shelf, farm.state)
        assertNoSharedNumber(after)
    }

    @Test
    fun aKidCustomThatCannotMoveKeepsItsNumberAndNoStarterTakesIt() {
        // Once Upon wants 36, where an old kid custom sits; the Kids band has no free number for that custom.
        val reserved = StarterCatalog.recipes.map { it.number } + StarterCatalog.leftovers.map { it.number }
        val fillers =
            Band.Kids.numbers
                .filter { it !in reserved }
                .map { custom("f$it", it) }
        val after = move(installed(custom("old-kid", 36)) + fillers)
        assertEquals(36, after.first { it.id.value == "custom:old-kid" }.number)
        assertTrue(after.number("once-upon") != 36, "took ${after.number("once-upon")}")
        assertNoSharedNumber(after)
    }

    @Test
    fun aLineupAlreadyOnCatalog8IsLeftAlone() {
        val before = installed(custom("old-kids", 50))
        assertEquals(before, CatalogRenumbering.apply(before, 8, StarterCatalog))
        // And so is a catalog from before the move.
        assertEquals(before, CatalogRenumbering.apply(before, 6, TestCatalog(StarterCatalog.recipes, version = 7)))
    }

    @Test
    fun theMovedKidsAndFamilyChannelsAreHeldAsTheyShouldBe() {
        val after = move(installed())
        // Kids stay kids-only; the four that left the Kids band are Family channels, still capped at PG.
        for (key in listOf("little-farm", "saturday-morning", "small-sleuths")) {
            assertEquals(PoolPolicy.KidsOnly, KidStatus.basePolicyOf(after.byId(LineupIds.starter(key))), key)
        }
        for (key in StarterCatalog.KIDS_KEYS_NOW_FAMILY) {
            val channel = after.first { it.id == LineupIds.starter(key) }
            assertEquals(PoolPolicy.GrownUp, KidStatus.basePolicyOf(channel), key)
            assertTrue(key in StarterCatalog.EVER_KID_SAFE_KEYS, key)
        }
        val kidsOdds = after.byId(LineupIds.leftovers("kids-odds-and-ends"))
        assertEquals(PoolPolicy.KidsOnly, KidStatus.basePolicyOf(kidsOdds))
    }

    // Catalogs 9 and 10 retire Lights Out, Tall Tales Club and Tiny Chefs (#995, #996). A stored copy, before the
    // planner turns it custom, is still a kids-only channel by key, whatever its number.
    @Test
    fun aRetiredKidKeyKeepsItsKidHold() {
        for ((key, number) in listOf("lights-out" to 44, "tall-tales-club" to 72, "tiny-chefs" to 80)) {
            assertEquals(PoolPolicy.KidsOnly, KidStatus.basePolicyOf(starter(key, number)), key)
        }
    }
}
