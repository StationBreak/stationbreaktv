// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.settings.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.domain.FakeProEntitlement
import tv.stationbreak.core.domain.kids.FakeKidsLock
import tv.stationbreak.core.domain.store.FakeStoreEntitlement
import tv.stationbreak.core.domain.store.ProOffer
import tv.stationbreak.core.domain.store.PurchaseOutcome
import tv.stationbreak.core.ui.StationBreakTheme
import tv.stationbreak.feature.settings.ProViewModel
import java.util.concurrent.Executors

/**
 * The Pro screen (#99) over real key events: Buy and Restore with their words, the price as the store gave it, Pro
 * owned (no Buy), Kids Mode (no action), and the GitHub build's Support page (the address as text, no Buy).
 */
@RunWith(AndroidJUnit4::class)
class ProScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val pool = Executors.newSingleThreadExecutor()
    private val work = pool.asCoroutineDispatcher()
    private var done = 0
    private val store = FakeStoreEntitlement(initial = false)
    private val pro = FakeProEntitlement(initial = false)
    private val kids = FakeKidsLock()

    private fun show(offer: ProOffer = ProOffer.Store) {
        val vm = ProViewModel(store, pro, kids, offer, work)
        compose.setContent {
            StationBreakTheme {
                Box(Modifier.requiredSize(WIDTH_DP.dp, HEIGHT_DP.dp).focusable()) {
                    ProRoot(vm, onDone = { done++ })
                }
            }
        }
        until { exists(SettingsTags.PRO_TITLE) }
    }

    @After
    fun tearDown() {
        pool.shutdownNow()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun until(condition: () -> Boolean) {
        compose.waitUntil(TIMEOUT_MS, condition)
        compose.waitForIdle()
    }

    private fun exists(tag: String): Boolean =
        compose
            .onAllNodes(hasTestTag(tag), useUnmergedTree = true)
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
            .isNotEmpty()

    @Test
    fun itShowsTheStoresPrice_andBuyHasTheFocus() {
        show()
        compose.onNodeWithTag(SettingsTags.PRO_TITLE, useUnmergedTree = true).assertTextEquals("StationBreak Pro")
        compose.onNodeWithTag(SettingsTags.PRO_PRICE, useUnmergedTree = true).assertTextEquals("$19.99, one time")
        compose.onNodeWithTag(SettingsTags.PRO_BUY, useUnmergedTree = true).assertIsSelected()
    }

    @Test
    fun withProOwnedItSaysSo_andOffersNoBuyAndNoRestore() {
        pro.set(true)
        show()
        until { exists(SettingsTags.PRO_OWNED) }
        assertEquals(false, exists(SettingsTags.PRO_BUY))
        assertEquals(false, exists(SettingsTags.PRO_RESTORE))
    }

    @Test
    fun cancelledSaysNothingWasBought() {
        store.next = PurchaseOutcome.Cancelled
        show()
        press(Key.DirectionCenter)
        until { exists(SettingsTags.PRO_STATUS) }
        compose.onNodeWithTag(SettingsTags.PRO_STATUS, useUnmergedTree = true).assertTextEquals("Nothing was bought.")
    }

    @Test
    fun restoreWithNothingFoundSaysSo() {
        show()
        press(Key.DirectionDown)
        compose.onNodeWithTag(SettingsTags.PRO_RESTORE, useUnmergedTree = true).assertIsSelected()
        press(Key.DirectionCenter)
        until { exists(SettingsTags.PRO_STATUS) }
        compose
            .onNodeWithTag(SettingsTags.PRO_STATUS, useUnmergedTree = true)
            .assertTextEquals("No Pro purchase was found on this account.")
    }

    @Test
    fun kidsModeOffersNoAction() {
        kids.lock()
        show()
        until { exists(SettingsTags.PRO_STATUS) }
        compose.onNodeWithTag(SettingsTags.PRO_STATUS, useUnmergedTree = true).assertTextEquals("Kids Mode is on.")
        assertEquals(false, exists(SettingsTags.PRO_BUY))
    }

    @Test
    fun theGithubBuildShowsSupportAsText_andNoBuy() {
        show(ProOffer.Support("https://example.test/support"))
        compose.onNodeWithTag(SettingsTags.PRO_TITLE, useUnmergedTree = true).assertTextEquals("Support StationBreak")
        compose
            .onNodeWithTag(SettingsTags.SUPPORT_URL, useUnmergedTree = true)
            .assertTextEquals("https://example.test/support")
        assertEquals(false, exists(SettingsTags.PRO_BUY))
        assertEquals(false, exists(SettingsTags.PRO_RESTORE))
    }

    @Test
    fun backFinishesOnce() {
        show()
        press(Key.Back)
        press(Key.Back)
        assertEquals(1, done)
    }

    private companion object {
        const val WIDTH_DP = 960
        const val HEIGHT_DP = 540
        const val TIMEOUT_MS = 5_000L
    }
}
