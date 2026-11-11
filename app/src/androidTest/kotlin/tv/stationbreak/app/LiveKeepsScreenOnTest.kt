// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import android.view.KeyEvent
import android.view.WindowManager
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.app.di.MediaServerModule
import tv.stationbreak.app.di.PlayerModule
import tv.stationbreak.app.nav.AppNavHost
import tv.stationbreak.core.domain.AccountCache
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.InMemorySecretStore
import tv.stationbreak.core.domain.InMemoryServerSettings
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.SecretStore
import tv.stationbreak.core.domain.SelectionStore
import tv.stationbreak.core.domain.ServerRecord
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.ServerSettings
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.model.AccessToken
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.LibraryKind
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.ServerInfo
import tv.stationbreak.core.model.ServerVersion
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.UserId
import tv.stationbreak.core.playback.VideoPlayerFactory
import tv.stationbreak.core.ui.StationBreakTheme
import tv.stationbreak.feature.channels.ui.ChannelsTags
import tv.stationbreak.feature.guide.ui.GuideTags
import tv.stationbreak.feature.live.ui.LiveTags
import java.io.FileInputStream
import javax.inject.Singleton
import kotlin.time.Clock

/** One scripted world for this test's graph. */
private object KeepOnWorld {
    val server = FakeMediaServer()
    val store = InMemorySecretStore()
    var settings = InMemoryServerSettings()
}

/**
 * Live TV keeps the screen awake in the real app graph (#374, ADR-0021): the system's record of the activity's window
 * carries FLAG_KEEP_SCREEN_ON on the live screen, with the guide open, and after leaving to Channels or Settings and
 * coming back. The window flag is what the system reads; the view flag the screen sets only counts once it is there.
 */
@HiltAndroidTest
@UninstallModules(MediaServerModule::class, PlayerModule::class)
@RunWith(AndroidJUnit4::class)
class LiveKeepsScreenOnTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val selection = SelectionResumeRule(atStart = false)

    @get:Rule(order = 2)
    val compose = createAndroidComposeRule<HiltTestActivity>()

    @Module
    @InstallIn(SingletonComponent::class)
    object ScriptedModule {
        @Provides
        @Singleton
        fun mediaServer(): MediaServer = KeepOnWorld.server

        @Provides
        @Singleton
        fun secretStore(): SecretStore = KeepOnWorld.store

        @Provides
        @Singleton
        fun serverSettings(): ServerSettings = KeepOnWorld.settings

        @Provides
        fun selectionStore(): SelectionStore = KeepOnWorld.settings

        @Provides
        @Singleton
        fun sessions(
            store: SecretStore,
            server: MediaServer,
            library: AccountCache,
        ): SessionRepository = SessionRepository(store, server, library)

        @Provides
        fun clock(): Clock = Clock.System

        @Provides
        fun players(): VideoPlayerFactory = LiveTestBindings.players
    }

    private val info = ServerInfo("Home Server", ServerId("srv1"), ServerVersion(12, 2, 0))
    private val https = ServerAddress(Scheme.Https, "jellyfin.example.test", 443)
    private val session = Session(UserId("user1"), AccessToken("dummy-token-0001"), info, https, null, false)
    private val movies = Library(ItemId("movies"), "Movies", LibraryKind.Movies)

    @Before
    fun script() {
        KeepOnWorld.settings = InMemoryServerSettings()
        with(KeepOnWorld.server) {
            resumeResult = ServerResult.Success(session)
            librariesResult = ServerResult.Success(listOf(movies))
            withEmptyLibraries()
        }
        runBlocking {
            KeepOnWorld.store
                .snapshot()
                .keys
                .forEach { KeepOnWorld.store.clear(it) }
        }
        hilt.inject()
        forgetTheActiveAccounts()
        signInAsTheAppDoes(KeepOnWorld.store, KeepOnWorld.server, session)
        KeepOnWorld.settings = choiceOf(ServerRecord(https), setOf(movies.id), session.account())
        runTheFirstSyncAsTheWorkerDoes(KeepOnWorld.settings, KeepOnWorld.store, KeepOnWorld.server)
        selection.resume()
        assertFalse("control: before the app's content is set the window does not keep the screen on", systemFlag())
        compose.setContent { StationBreakTheme { AppNavHost() } }
        compose.waitForIdle()
    }

    private fun count(tag: String): Int = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    private fun awaitExists(tag: String) = compose.waitUntil(TIMEOUT_MS) { count(tag) > 0 }

    private fun press(keyCode: Int) {
        compose.awaitWindowFocus()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(keyCode)
        compose.waitForIdle()
    }

    // The flags on the activity's own Window object. Not what the system holds: the view root adds the flag to its
    // own copy of the attributes, so this reads false while the system's record has it (measured on API 25).
    private fun windowObjectFlag(): Boolean {
        var on = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            on = compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
        }
        return on
    }

    // A flags field reads "fl=#81810180" (hex, on API 25) or lists names (API 30).
    private fun keepsScreenOn(line: String): Boolean {
        val flags = line.substringAfter("fl=", "").substringBefore(" ")
        val bit = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.toLong()
        return when {
            flags.isEmpty() -> false
            flags.startsWith("#") -> flags.drop(1).toLong(16) and bit != 0L
            else -> "KEEP_SCREEN_ON" in line
        }
    }

    // The flags the system holds for this activity's window, which the view root has pushed there.
    private fun systemFlag(): Boolean {
        val dump =
            InstrumentationRegistry
                .getInstrumentation()
                .uiAutomation
                .executeShellCommand("dumpsys window windows")
                .let { FileInputStream(it.fileDescriptor).bufferedReader().readText() }
        val mine =
            dump
                .split("Window #")
                .filter { it.lineSequence().firstOrNull()?.contains("HiltTestActivity") == true }
        return mine.any { block -> block.lineSequence().any(::keepsScreenOn) }
    }

    private fun assertKeptOn(step: String) {
        var system = false
        try {
            compose.waitUntil(TIMEOUT_MS) {
                system = systemFlag()
                system
            }
        } catch (_: ComposeTimeoutException) {
            // Reported below.
        }
        assertTrue("$step: system window lacks KEEP_SCREEN_ON (the Window object says ${windowObjectFlag()})", system)
    }

    @Test
    fun theWindowKeepsTheScreenOnThroughTheLiveScreensRoutes() {
        awaitExists(LiveTags.NO_CHANNELS)
        assertKeptOn("arrival on live")

        press(KeyEvent.KEYCODE_BACK)
        awaitExists(GuideTags.EMPTY)
        assertKeptOn("guide open")

        press(KeyEvent.KEYCODE_BACK)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitExists(ChannelsTags.TITLE)
        press(KeyEvent.KEYCODE_BACK)
        awaitExists(GuideTags.EMPTY)
        assertKeptOn("back from management")
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
