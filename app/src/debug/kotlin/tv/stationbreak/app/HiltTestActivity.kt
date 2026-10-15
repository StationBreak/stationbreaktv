// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * An empty Hilt-enabled host for instrumented tests that set their own content (the compose rule's `setContent`),
 * where [MainActivity] would already have set its own. It lives in the debug source set, not `androidTest`, because
 * `ActivityScenario` launches activities in the app under test, so the activity must be in the app's own APK and
 * manifest. Declared in `src/debug/AndroidManifest.xml`; release builds don't have it.
 */
@AndroidEntryPoint
class HiltTestActivity : ComponentActivity()
