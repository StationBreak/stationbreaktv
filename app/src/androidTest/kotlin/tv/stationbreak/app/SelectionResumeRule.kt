// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * The library selection's resume, as the app's start runs it (spec 26, Part 4), for every instrumented test that
 * builds the real graph. The tests run under `HiltTestApplication`, so `StationBreakApp.onCreate` never runs there, and
 * without the resume the selection's gate stays shut: every selection operation and every library worker (the first
 * sync, stay current, the add pass) waits on it for ever.
 *
 * Order it after `HiltAndroidRule`, whose graph it reads, and before any rule that launches an activity. By default it
 * resumes before the test, as the app's start does before any screen. A test that seeds an earlier run of the app
 * first (its settings and library cache, before this run's start) passes `atStart = false` and calls [resume] where its
 * app starts; the seeds themselves never wait on the gate. [resume] runs once per test, however often it is called, and
 * such a test that ends without calling it fails.
 */
class SelectionResumeRule(
    private val atStart: Boolean = true,
) : TestRule {
    private var resumed = false

    /** Resumes the selection as the app's start does, once per test. */
    fun resume() {
        if (resumed) return
        resumed = true
        val graph =
            EntryPointAccessors.fromApplication(
                ApplicationProvider.getApplicationContext<Application>(),
                StationBreakApp.AppStartEntryPoint::class.java,
            )
        runBlocking { resumeSelection({ graph.selectionResume() }, graph.resumeGate()) {} }
    }

    override fun apply(
        base: Statement,
        description: Description,
    ): Statement =
        object : Statement() {
            override fun evaluate() {
                resumed = false
                if (atStart) resume()
                // A test that fails reports its own failure (it is thrown from here as it is).
                base.evaluate()
                // A test that defers the resume and never makes it would wait on the gate silently: it fails here.
                if (!resumed) {
                    throw AssertionError("${description.displayName} never called SelectionResumeRule.resume()")
                }
            }
        }
}
