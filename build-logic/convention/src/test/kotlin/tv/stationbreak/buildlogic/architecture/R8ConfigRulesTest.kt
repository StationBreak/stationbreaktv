// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic.architecture

import kotlin.test.Test
import kotlin.test.assertEquals

class R8ConfigRulesTest {
    private fun section(
        source: String,
        vararg rules: String,
    ) = "# The proguard configuration file for the following section is $source (extracted file: /x/y/proguard.txt)\n" +
        rules.joinToString("\n") + "\n"

    private fun violations(vararg rules: String) = R8ConfigRules.violations(section("com.evil:lib:1.0", *rules))

    private fun assertFlagged(vararg rules: String) =
        assertEquals(listOf(R8Violation(rules.first(), "com.evil:lib:1.0")), violations(*rules))

    @Test
    fun aCleanConfigPasses() {
        val config =
            section(
                "com.good:lib:1.0",
                "-keep class com.good.Api { public *; }",
                "-dontwarn org.checkerframework.**",
                "-dontwarn androidx.**",
            ) +
                section("x:y:1", "-keepclasseswithmembers class * {", "    native <methods>;", "}") +
                section("x:z:1", "-keepclassmembers class ** {", "    @androidx.lifecycle.OnLifecycleEvent <methods>;", "}")
        assertEquals(emptyList(), R8ConfigRules.violations(config))
    }

    @Test
    fun assumenosideeffectsFromAnUnlistedSource() =
        assertFlagged("-assumenosideeffects class android.util.Log {", "    public static *** d(...);", "}")

    @Test
    fun assumenosideeffectsFromAListedSourcePasses() {
        val config = section("androidx.compose.runtime:runtime-android:1.12.1", "-assumenosideeffects public class a.B {", "}")
        assertEquals(emptyList(), R8ConfigRules.violations(config))
    }

    @Test
    fun wildcardDontwarn() = assertFlagged("-dontwarn **")

    @Test
    fun wildcardDontwarnInAList() = assertFlagged("-dontwarn a.B,**")

    @Test
    fun bareDontwarn() = assertFlagged("-dontwarn")

    @Test
    fun dontobfuscateFromALibrary() = assertFlagged("-dontobfuscate")

    @Test
    fun dontobfuscateFromTheAppPasses() {
        val config = section("/home/dev/stationbreaktv/app/proguard-rules.pro", "-dontobfuscate")
        assertEquals(emptyList(), R8ConfigRules.violations(config))
    }

    @Test
    fun dontoptimize() = assertFlagged("-dontoptimize")

    @Test
    fun dontshrink() = assertFlagged("-dontshrink")

    @Test
    fun keepEverything() = assertFlagged("-keep class ** { *; }")

    @Test
    fun keepEverythingOverSeveralLines() = assertFlagged("-keep,allowobfuscation class * {", "    *;", "}")

    @Test
    fun keepAllClassesWithNoMembers() = assertFlagged("-keep class **")

    @Test
    fun keepAllFieldsAndMethods() = assertFlagged("-keepclassmembers class ** {", "    <fields>;", "    <methods>;", "}")

    @Test
    fun anAnnotatedKeepIsNarrow() = assertEquals(emptyList(), violations("-keep @androidx.annotation.Keep class * {", "    *;", "}"))

    @Test
    fun aCommentedRuleIsNotARule() = assertEquals(emptyList(), violations("# -dontoptimize", "-keep class a.B"))

    @Test
    fun theSourceHasNoBuildMachinePath() {
        val config = section("/home/dev/stationbreaktv/app/proguard-rules.pro", "-dontshrink")
        assertEquals(listOf(R8Violation("-dontshrink", "app/proguard-rules.pro")), R8ConfigRules.violations(config))
    }
}
