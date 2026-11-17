// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic.architecture

import kotlin.test.Test
import kotlin.test.assertEquals

class R8ConfigRulesTest {
    private fun section(
        source: String,
        vararg rules: String,
    ) = "# The proguard configuration file for the following section is $source (extracted file: /x/y/proguard.txt)\n" +
        rules.joinToString("\n") + "\n# End of content from $source (extracted file: /x/y/proguard.txt)\n"

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
    fun assumenosideeffectsFromDatastorePasses() {
        val config =
            section(
                "androidx.datastore:datastore-core-android:1.2.1",
                "-assumenosideeffects class androidx.datastore.core.SharedCounter\$Factory {",
                "}",
            )
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

    @Test
    fun aTabAfterTheOption() = assertFlagged("-dontwarn\t**")

    @Test
    fun argumentsOnTheNextLine() = assertEquals(1, violations("-dontwarn", "**").size)

    @Test
    fun assumevaluesFromAnUnlistedSource() = assertFlagged("-assumevalues class a.B {", "    int x return 1;", "}")

    @Test
    fun assumevaluesFromComposePasses() {
        val config = section("androidx.compose.runtime:runtime-android:1.12.1", "-assumevalues class a.B {", "}")
        assertEquals(emptyList(), R8ConfigRules.violations(config))
    }

    @Test
    fun ignorewarnings() = assertFlagged("-ignorewarnings")

    @Test
    fun keepWithOnlyMethodsOfEveryClass() = assertFlagged("-keep class ** {", "    <methods>;", "}")

    @Test
    fun keepWithPublicMembersOfEveryClass() = assertFlagged("-keep class * { public *; }")

    @Test
    fun keepWithInitOfEveryClass() = assertFlagged("-keep class ** {", "    <init>(...);", "}")

    @Test
    fun keepnamesOfEveryClass() = assertFlagged("-keepnames class **")

    @Test
    fun wildcardAnnotationDoesNotNarrow() = assertFlagged("-keep @** class * { *; }")

    @Test
    fun metadataAnnotationDoesNotNarrow() = assertFlagged("-keep @kotlin.Metadata class *")

    @Test
    fun keepclassmembersWithSpecificMembersStillPasses() =
        assertEquals(emptyList(), violations("-keepclassmembers class ** {", "    <init>(...);", "}"))

    @Test
    fun aSecondHeaderForTheSameSourceIsFlagged() {
        val source = "androidx.compose.runtime:runtime-android:1.12.1"
        val config = section(source) + section(source)
        assertEquals(listOf("a second section header for this source file"), R8ConfigRules.violations(config).map { it.rule })
    }

    @Test
    fun aLibraryWithTwoRuleFilesIsFine() {
        val header = "# The proguard configuration file for the following section is a:b:1 (extracted file: /x/"
        val end = "# End of content from a:b:1 (extracted file: /x/"
        val config = "${header}one.pro)\n-keep class a.B\n${end}one.pro)\n${header}two.pro)\n-keep class a.C\n${end}two.pro)\n"
        assertEquals(emptyList(), R8ConfigRules.violations(config))
    }

    @Test
    fun aCoordinateHeaderWithoutAnExtractedFileIsFlagged() {
        val config =
            "# The proguard configuration file for the following section is androidx.compose.runtime:runtime-android:1.12.1\n" +
                "-assumenosideeffects class a.B\n"
        assertEquals(1, R8ConfigRules.violations(config).count { it.rule == "a section header without an extracted file" })
    }

    @Test
    fun keeppackagenamesWithNoArguments() = assertFlagged("-keeppackagenames")

    @Test
    fun keeppackagenamesOfAPackagePasses() = assertEquals(emptyList(), violations("-keeppackagenames com.a.**"))

    @Test
    fun anIfOnOneLineEvaluatesItsKeep() = assertFlagged("-if class a.B -keep class ** { *; }")

    @Test
    fun anIfWithANarrowKeepPasses() = assertEquals(emptyList(), violations("-if class a.B -keep class a.C"))

    @Test
    fun aPartialWildcardAnnotationDoesNotNarrow() {
        assertFlagged("-keep @kotlin.** class * { *; }")
        assertEquals(1, violations("-keep @**.Metadata class *").size)
        assertEquals(1, violations("-keep @kotlin.Meta* class *").size)
    }

    @Test
    fun aWildcardSupertypeIsAKeepAll() {
        assertFlagged("-keep class * extends **")
        assertEquals(1, violations("-keep class * implements **").size)
    }

    @Test
    fun aSpecificSupertypeNarrows() = assertEquals(emptyList(), violations("-keep class * extends a.B", "-keep class ** implements a.C"))

    @Test
    fun aClassListWithAWildcardOrANegationIsAKeepAll() {
        assertFlagged("-keep class **,!a.B")
        assertEquals(1, violations("-keep class !a.B").size)
    }

    @Test
    fun aHeaderInsideAnOpenSectionIsFlagged() {
        val spoof =
            "# The proguard configuration file for the following section is androidx.startup:startup-runtime:1.2.0 (extracted file: /z)"
        val config = section("com.evil:lib:1.0", spoof, "-assumenosideeffects class a.B")
        assertEquals(1, R8ConfigRules.violations(config).count { it.rule == "a section header inside an open section" })
    }

    @Test
    fun anEndMarkerWithNothingOpenIsFlagged() {
        val config = section("a:b:1") + "# End of content from a:b:1 (extracted file: /x/y/proguard.txt)\n"
        assertEquals(listOf("an end marker that does not close the open section"), R8ConfigRules.violations(config).map { it.rule })
    }

    @Test
    fun aSectionNeverClosedIsFlagged() {
        val config = "# The proguard configuration file for the following section is app/proguard-rules.pro\n-keep class a.B\n"
        assertEquals(listOf("a section that is never closed"), R8ConfigRules.violations(config).map { it.rule })
    }
}
