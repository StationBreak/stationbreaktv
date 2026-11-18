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

    // #851: `?` matches one character, so a name of `?`, `*` and `.` only is as broad as `**`.
    @Test
    fun aQuestionMarkWildcardIsAKeepAll() {
        assertFlagged("-keep class ?** { *; }")
        assertEquals(1, violations("-keepnames class ?").size)
        assertEquals(1, violations("-dontwarn ?**").size)
    }

    @Test
    fun aQuestionMarkInsideANameStillNarrows() = assertEquals(emptyList(), violations("-keep class a.B? { *; }"))

    // #851: every class extends java.lang.Object, so naming it, or a pattern that matches it, limits nothing.
    @Test
    fun extendingObjectIsAKeepAll() {
        assertFlagged("-keep class * extends java.lang.Object { *; }")
        for (supertype in listOf("**.Object", "java.**", "java.lang.Obj*", "java.lang.Object*", "java.?ang.Object")) {
            assertEquals(1, violations("-keep class ** extends $supertype { *; }").size, supertype)
        }
    }

    @Test
    fun aSupertypePatternThatMissesObjectNarrows() =
        assertEquals(emptyList(), violations("-keep class ** extends java.util.* { *; }", "-keep class * extends a.**.B"))

    // #851: a specific class list narrows the rule even with a broad supertype.
    @Test
    fun aSpecificClassListNarrowsEvenWithABroadSupertype() =
        assertEquals(emptyList(), violations("-keep class com.foo.Bar extends **", "-keep class a.B implements **"))

    // #851: a package wildcard is a real limit, unlike `**` alone; only the supertype being broad too is not enough.
    @Test
    fun aPackageWildcardWithABroadSupertypePasses() = assertEquals(emptyList(), violations("-keep class com.foo.** extends **"))

    // #851, checked with R8's -printseeds on a three-class sample: `a.B,!c.D` kept a.B and x.E (every class but c.D),
    // `a.B,!c.D,x.E` kept a.B and x.E, and `!**,a.B` kept nothing. A list that ends in a negation accepts the rest.
    @Test
    fun aListEndingInANegationIsAKeepAll() {
        assertFlagged("-keep class a.B,!c.D { *; }")
        assertEquals(1, violations("-keep class !a.B,!c.D").size)
    }

    @Test
    fun aListEndingInASpecificNameNarrows() =
        assertEquals(emptyList(), violations("-keep class a.B,!c.D,x.E { *; }", "-keep class !**,a.B { *; }"))

    // The same reading for -dontwarn and -keeppackagenames: `!a.B` silences every class but a.B.
    @Test
    fun aDontwarnEndingInANegationIsFlagged() {
        assertFlagged("-dontwarn !a.B")
        assertEquals(1, violations("-dontwarn x.Y,!a.B").size)
        assertEquals(1, violations("-keeppackagenames a.b,!c.d").size)
        assertEquals(emptyList(), violations("-dontwarn !a.B,x.Y"))
    }

    // #851: R8 reads `#` as a comment anywhere on a line; `-keep class ** # @a.B` kept every class under -printseeds.
    @Test
    fun aCommentAfterARuleIsNotPartOfIt() {
        assertEquals(1, violations("-keep class ** # @a.B").size)
        assertEquals(1, violations("-keep class ** # extends a.B").size)
        assertEquals(1, violations("-dontwarn !a.B # note").size)
        assertEquals(1, violations("-keep class ** {", "    *; # only the api", "}").size)
        assertEquals(emptyList(), violations("-keep class a.B # ** everything"))
    }

    @Test
    fun theMemberFamiliesAndImplementsAlsoReadObjectAsEveryClass() {
        assertEquals(1, violations("-keepclassmembers class * extends java.lang.Object { *; }").size)
        assertEquals(1, violations("-keep class ** implements java.lang.Object").size)
    }

    // A bare `@` (whitespace before the annotation's name) never narrows the rule. R8 itself rejects `@ a.B` and
    // `! c.D` as parse errors, so neither can slip past at build time either.
    @Test
    fun aBareAtSignDoesNotNarrow() = assertFlagged("-keep @ ** class ** { *; }")

    // #851: `@interface` is the keyword for every annotation type, not an annotation that narrows the rule.
    @Test
    fun theAnnotationTypeKeywordIsNotANarrowingAnnotation() {
        assertFlagged("-keep @interface ** { *; }")
        assertEquals(emptyList(), violations("-keep @interface a.MyAnnotation { *; }"))
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

    // #909: a name of `**` and a suffix with no package part keeps classes in every package.
    @Test
    fun keepEveryInnerClass() = assertFlagged("-keep class **\$* { *; }")

    @Test
    fun keepEveryInnerClassAnyDepth() = assertFlagged("-keep class **\$** { *; }")

    @Test
    fun keepnamesEveryClassEndingInAName() = assertFlagged("-keepnames class **Foo")

    @Test
    fun aPackagedSuffixIsNotBroad() = assertEquals(emptyList(), violations("-keepnames class **.R\$*", "-keepnames class **.BuildConfig"))

    @Test
    fun aSuffixOnAMemberRuleNeedsAllMembers() =
        // kotlinx.serialization's own rule (META-INF/com.android.tools/r8/kotlinx-serialization-common.pro).
        assertEquals(
            emptyList(),
            violations("-keepclassmembers public class **\$\$serializer {", "    private ** descriptor;", "}"),
        )

    // #909: `<n>` is what the n-th wildcard of the `-if` condition matched. A condition that matches every class makes
    // the kept class any class.
    @Test
    fun ifBackreferenceToABroadConditionOnOneLine() = assertFlagged("-if class ** -keep class <1> { *; }")

    @Test
    fun ifBackreferenceToABroadConditionOnTwoLines() = assertFlagged("-if class **", "-keep class <1> {", "    *;", "}")

    @Test
    fun ifWithAKeepThatHasModifiers() = assertFlagged("-if class **", "-keep,allowobfuscation class <1>")

    @Test
    fun ifBackreferenceToTheSecondWildcard() = assertFlagged("-if class *.**", "-keepnames class <2>")

    @Test
    fun ifBackreferenceThatAddsAPackagedNamePasses() =
        // `<1>` is one package level, so this keeps `Api` in each single-level package: a named class, not every class.
        assertEquals(emptyList(), violations("-if class *.**", "-keepnames class <1>.Api"))

    @Test
    fun ifWithANarrowConditionPasses() =
        // Only classes in a.*.B… match, so whatever <2> matched comes from that bounded set.
        assertEquals(emptyList(), violations("-if class a.*.B**", "-keepnames class <2>"))

    @Test
    fun ifNarrowedByAnAnnotationPasses() =
        // kotlinx.serialization's companion and serializer rules.
        assertEquals(
            emptyList(),
            violations(
                "-if @kotlinx.serialization.Serializable class **",
                "-keep, allowshrinking, allowoptimization, allowobfuscation, allowaccessmodification class <1>",
                "-if @kotlinx.serialization.Serializable class **",
                "-keepclassmembers class <1> {",
                "    static <1>\$Companion Companion;",
                "}",
            ),
        )

    @Test
    fun ifNarrowedByASupertypePasses() =
        assertEquals(
            emptyList(),
            violations(
                "-if public class ** implements androidx.navigation.NavArgs",
                "-keepclassmembers public class <1> {",
                "    ** fromBundle(android.os.Bundle);",
                "}",
            ),
        )

    @Test
    fun aBroadConditionWithSpecificMembersPasses() =
        // Gson's rule: every class, but only the members it names.
        assertEquals(
            emptyList(),
            violations(
                "-if class *",
                "-keepclasseswithmembers,allowobfuscation class <1> {",
                "    @com.google.gson.annotations.SerializedName <fields>;",
                "}",
            ),
        )

    @Test
    fun anIfDoesNotCarryOverToALaterRule() = assertEquals(emptyList(), violations("-if class **", "-dontwarn a.B", "-keep class <1>"))

    // #909 review: names that reach every package without starting with `**`, or with a wildcard package part.
    @Test
    fun keepEveryInnerClassBehindAWildcardPackage() = assertFlagged("-keep class *.**\$* { *; }")

    @Test
    fun keepEveryInnerClassBehindTwoWildcards() = assertFlagged("-keep class **.**\$* { *; }")

    @Test
    fun keepnamesEveryInnerClassAfterAQuestionMark() = assertFlagged("-keepnames class ?**\$*")

    @Test
    fun keepnamesEveryClassEndingInANameInAnyPackage() = assertFlagged("-keepnames class **.*Foo")

    @Test
    fun aNamedPackageSuffixIsNotBroad() = assertEquals(emptyList(), violations("-keepnames class com.**\$*", "-keep class *.R"))

    // #909 review: members that nearly every class has don't narrow an `-if` condition.
    @Test
    fun ifWithAnAllMembersCondition() = assertFlagged("-if class ** { *; }", "-keep class <1> { *; }")

    @Test
    fun ifWithAConstructorConditionOnSeveralLines() =
        assertFlagged("-if class ** {", "    <init>(...);", "}", "-keep class <1> {", "    *;", "}")

    @Test
    fun ifWithANamedMemberPasses() =
        assertEquals(emptyList(), violations("-if class ** { public static final ** CREATOR; }", "-keep class <1>"))

    @Test
    fun ifWithAnAnnotatedMemberPasses() =
        // Retrofit's rule: only interfaces with a method that carries one of its HTTP annotations.
        assertEquals(
            emptyList(),
            violations("-if interface * { @retrofit2.http.* <methods>; }", "-keep,allowobfuscation interface <1>"),
        )
}
