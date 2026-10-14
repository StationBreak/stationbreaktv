// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic

import app.cash.licensee.LicenseeExtension
import app.cash.licensee.UnusedAction
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Every runtime dependency must carry a GPL-3.0-compatible license (ADR-0003). A license Licensee can't identify
 * fails the build. Extend the list only when a real dependency needs it; per-dependency overrides go here too, each
 * with a `// reason:` comment. Applied by every module whose runtime classpath ships in the app (`app`, and each
 * `data:*` module that brings a third-party SDK).
 */
class LicenseePlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("app.cash.licensee")
            extensions.configure<LicenseeExtension> {
                allow("Apache-2.0")
                allow("MIT")
                allow("BSD-2-Clause")
                allow("BSD-3-Clause")
                allow("LGPL-2.1-or-later")
                allow("LGPL-3.0-only")
                allow("LGPL-3.0-or-later")
                // The Jellyfin SDK's POMs name its license "GNU Lesser General Public License v3.0" with this URL
                // and no SPDX id, so Licensee can't identify it by name. The URL is the GNU text of LGPL-3.0, which
                // is on the list above (ADR-0005, ASSUMPTIONS A-LIC-1). Owner reviews this entry (BACKLOG S0.1.2).
                allowUrl("https://www.gnu.org/licenses/lgpl-3.0.txt") {
                    because("jellyfin-sdk-kotlin is LGPL-3.0 (ADR-0005); its POM carries a name Licensee doesn't map")
                }
                // slf4j-api and slf4j-nop (the SLF4J binding `app` ships, ADR-0006) are MIT, but their POMs give the license
                // as this URL and no SPDX id, so Licensee can't identify them by name.
                allowUrl("https://opensource.org/license/mit") {
                    because("slf4j-api and slf4j-nop are MIT; their POMs name the license by this URL only")
                }
                // Entries are listed ahead of need, so one that no dependency uses yet isn't an error.
                unusedAction(UnusedAction.IGNORE)
            }
        }
}
