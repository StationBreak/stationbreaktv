// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import android.view.WindowManager
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
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
import java.util.concurrent.TimeUnit
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
 * carries FLAG_KEEP_SCREEN_ON on the live screen, with the guide open, and after leaving to Channels and coming back.
 * The window flag is what the system reads; the view flag the screen sets only counts once it is there.
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

    // Innermost, so a hang fails this test and does not hold up the rest of the run.
    @get:Rule(order = 3)
    val timeout: Timeout = Timeout(TEST_TIMEOUT_S, TimeUnit.SECONDS)

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
        // As the other navigation tests do: the settings the graph binds are final before hilt.inject().
        KeepOnWorld.settings = choiceOf(ServerRecord(https), setOf(movies.id), session.account())
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
        runTheFirstSyncAsTheWorkerDoes(KeepOnWorld.settings, KeepOnWorld.store, KeepOnWorld.server)
        selection.resume()
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

    // `dumpsys window windows`, read once; the stream owns the file descriptor and closes it.
    private fun windowDump(): String =
        ParcelFileDescriptor
            .AutoCloseInputStream(
                InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys window windows"),
            ).bufferedReader()
            .use { it.readText() }

    /**
     * Whether the system's record of this activity's focused window carries FLAG_KEEP_SCREEN_ON; null while that
     * window does not have focus, so a finished window of an earlier test is never the one read.
     */
    private fun systemKeepsScreenOn(): Boolean? = focusedAttributes(windowDump())?.let(::keepsScreenOn)

    // The attributes line of this activity's focused window, or null while it has no focus.
    private fun focusedAttributes(dump: String): String? {
        val focused = FOCUS.find(dump)?.groupValues?.get(1)
        val block = dump.split("Window #").firstOrNull { it.lineSequence().first().contains("Window{$focused ") }
        return block?.lineSequence()?.firstOrNull { FLAGS.containsMatchIn(it) }
    }

    // API 25 prints the flags as hex ("fl=#81810180"); later versions print their names.
    private fun keepsScreenOn(attrs: String): Boolean {
        val flags =
            FLAGS
                .find(attrs)
                ?.groupValues
                ?.get(1)
                .orEmpty()
        val value = flags.removePrefix("#").toLongOrNull(16)
        if (flags.startsWith("#")) assertNotNull("the window flags are not hex: $attrs", value)
        return if (flags.startsWith("#")) {
            (value ?: 0L) and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.toLong() != 0L
        } else {
            "KEEP_SCREEN_ON" in attrs
        }
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

    private fun assertKeptOn(step: String) {
        val end = System.currentTimeMillis() + TIMEOUT_MS
        var system = systemKeepsScreenOn()
        while (system != true && System.currentTimeMillis() < end) {
            Thread.sleep(POLL_MS)
            system = systemKeepsScreenOn()
        }
        assertTrue(
            "$step: the focused window lacks KEEP_SCREEN_ON (system: $system; Window object: ${windowObjectFlag()})",
            system == true,
        )
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
        const val TEST_TIMEOUT_S = 60L
        const val POLL_MS = 250L
        val FOCUS = Regex("""mCurrentFocus=Window\{(\w+) \S+ \S*HiltTestActivity""")
        val FLAGS = Regex("""\bfl=(\S+)""")
    }
}
