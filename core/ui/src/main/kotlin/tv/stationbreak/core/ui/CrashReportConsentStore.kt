// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether this TV may send a crash report to the owner's endpoint (#96, Q-PRIV-1): the viewer's own consent, kept on
 * the device. Off until the viewer turns it on, and only ever asked for when [available] (the build has an endpoint).
 */
interface CrashReportConsentStore {
    /** Whether this build can send reports at all; the Settings sheet shows its switch only when it can. */
    val available: Boolean

    val enabled: StateFlow<Boolean>

    /** Saves the choice; [from] names the path that made it (a short word such as `settingsLeft`), for the log. */
    fun choose(
        enabled: Boolean,
        from: String,
    )
}

/** A preference file of its own, next to the other viewer choices. */
internal class DataStoreCrashReportConsentStore(
    data: DataStore<Preferences>,
    override val available: Boolean,
    scope: CoroutineScope,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) : CrashReportConsentStore {
    private val preference = ViewerPreference(data, KEY, { it ?: DEFAULT }, { it }, scope)

    // Without an endpoint nothing can be sent, so a consent stored by an earlier build does not count.
    override val enabled: StateFlow<Boolean> = if (available) preference.value else NEVER

    override fun choose(
        enabled: Boolean,
        from: String,
    ) {
        if (!available) return
        // The viewer's own setting and its source only, as the other stores log.
        log("crash reports choose enabled=$enabled was=${preference.value.value} from=$from")
        preference.choose(enabled)
    }

    private companion object {
        const val TAG = "SBLIVE"
        val KEY = booleanPreferencesKey("enabled")
        const val DEFAULT = false
        val NEVER: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
    }
}

private val Context.crashReportConsentFile by viewerPreferencesFile("crash-report-consent")

/**
 * The one store of the process, so a choice made in the guide reaches the crash reporter at once. The app says once, at
 * start, whether the build has an endpoint ([configure]); until then (and in a test) nothing is available.
 */
object CrashReportConsents {
    private var store: CrashReportConsentStore? = null
    private var available = false

    /** Called by the app's `onCreate`, before any screen asks for the store. */
    @Synchronized
    fun configure(available: Boolean) {
        check(store == null) { "CrashReportConsents.configure must come before the first get" }
        this.available = available
    }

    @Synchronized
    fun get(context: Context): CrashReportConsentStore =
        store ?: DataStoreCrashReportConsentStore(
            context.applicationContext.crashReportConsentFile,
            available,
            PreferenceWrites,
        ).also { store = it }
}

@Composable
fun rememberCrashReportConsentStore(): CrashReportConsentStore {
    val context = LocalContext.current
    return remember(context) { CrashReportConsents.get(context) }
}
