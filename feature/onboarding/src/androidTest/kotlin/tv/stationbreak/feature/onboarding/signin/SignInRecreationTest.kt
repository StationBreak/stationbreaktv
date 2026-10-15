// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.signin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.InMemorySecretStore
import tv.stationbreak.core.domain.ResolvedServer
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.ServerInfo
import tv.stationbreak.core.model.ServerVersion
import tv.stationbreak.core.ui.StationBreakTheme
import tv.stationbreak.feature.onboarding.server.ConnectedServer
import tv.stationbreak.feature.onboarding.server.ServerHandOff

/** What [SignInHostActivity] shows. A view model outlives the activity's recreation, so the test hands it in. */
private object Hosted {
    var viewModel: SignInViewModel? = null
}

/** Shows the sign-in screen from its own `onCreate`, so that recreating it rebuilds the screen as MainActivity does. */
class SignInHostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val viewModel = checkNotNull(Hosted.viewModel)
        setContent {
            StationBreakTheme {
                SignInRoot(viewModel, onEditAddress = {}, onMissingServer = {}, onSignedIn = {})
            }
        }
    }
}

/**
 * A configuration change recreates MainActivity (keyboard, language, display mode), disposing the screen's composition
 * around a view model that survives. That is not leaving the screen, so a password typed and not yet submitted must
 * still be there afterwards.
 */
@RunWith(AndroidJUnit4::class)
class SignInRecreationTest {
    private val info = ServerInfo("Home Server", ServerId("srv1"), ServerVersion(12, 2, 0))
    private val https = ServerAddress(Scheme.Https, "jellyfin.example.test", 443)

    @Test
    fun aRecreatedActivityKeepsATypedPassword() {
        val server = FakeMediaServer()
        val handOff = ServerHandOff()
        handOff.put(ConnectedServer(ResolvedServer(https, info), null))
        val viewModel = SignInViewModel(server, SessionRepository(InMemorySecretStore(), server), handOff)
        Hosted.viewModel = viewModel
        ActivityScenario.launch(SignInHostActivity::class.java).use { scenario ->
            scenario.onActivity {
                viewModel.onUsernameChange("sam")
                viewModel.onPasswordChange("dummy-pass-0001")
            }

            scenario.recreate()

            scenario.onActivity {
                assertEquals("dummy-pass-0001", viewModel.state.value.password)
                assertEquals("sam", viewModel.state.value.username)
            }
        }
        Hosted.viewModel = null
    }
}
