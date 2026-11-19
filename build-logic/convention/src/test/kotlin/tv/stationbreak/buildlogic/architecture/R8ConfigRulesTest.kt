// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class R8ConfigRulesTest {
    // A library header is believed only for an extracted file that exists under a trusted root (the Gradle user home or
    // the root project) and holds the section's text, so the tests keep real files under a temporary root.
    private val root = Files.createTempDirectory("r8rules").toRealPath()

    private fun extractedFile(
        name: String,
        content: String,
    ) = root.resolve(name).also {
        Files.writeString(it, content + "\n")
        given.add(it)
    }

    // The rules files AGP "gave R8" in these tests: only the ones made through extractedFile.
    private val given = mutableSetOf<Path>()

    // The file R8 would have read for a section with these rules; one file per distinct text.
    private fun fileWith(vararg rules: String): Path {
        val text = rules.joinToString("\n")
        return extractedFile("r${text.hashCode()}.txt", text)
    }

    private val proguardFile = fileWith("-keep class a.B")

    private fun section(
        source: String,
        vararg rules: String,
    ): String {
        // AGP prints the app's own rules file as the bare path, and a library's with the extracted file.
        val name = if (source == appRules) source else "$source (extracted file: ${fileWith(*rules)})"
        return "# The proguard configuration file for the following section is $name\n" +
            rules.joinToString("\n") + "\n# End of content from $name\n"
    }

    private val appRules = "/home/dev/stationbreaktv/app/proguard-rules.pro"

    private fun scan(config: String) = R8ConfigRules.violations(config, appRules, given.toSet())

    private fun violations(vararg rules: String) = scan(section("com.evil:lib:1.0", *rules))

    // The input lines and the one violation they must give, which differ when options share a line.
    private fun assertOnly(
        expected: String,
        vararg rules: String,
    ) = assertEquals(listOf(R8Violation(expected, "com.evil:lib:1.0")), violations(*rules))

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
        assertEquals(emptyList(), scan(config))
    }

    @Test
    fun assumenosideeffectsFromAnUnlistedSource() =
        assertFlagged("-assumenosideeffects class android.util.Log {", "    public static *** d(...);", "}")

    @Test
    fun assumenosideeffectsFromAListedSourcePasses() {
        val config = section("androidx.compose.runtime:runtime-android:1.12.1", "-assumenosideeffects public class a.B {", "}")
        assertEquals(emptyList(), scan(config))
    }

    @Test
    fun assumenosideeffectsFromDatastorePasses() {
        val config =
            section(
                "androidx.datastore:datastore-core-android:1.2.1",
                "-assumenosideeffects class androidx.datastore.core.SharedCounter\$Factory {",
                "}",
            )
        assertEquals(emptyList(), scan(config))
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
        assertEquals(emptyList(), scan(config))
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
    fun aCommentedRuleIsNotARule() = assertEquals(emptyList(), violations("# -keep class ** { *; }", "-keep class a.B"))

    // #933: the backstop reads comments too, as it cannot tell a comment from a quote it did not understand.
    @Test
    fun aForbiddenOptionNamedInACommentIsFlagged() = assertOnly("# -dontoptimize", "# -dontoptimize", "-keep class a.B")

    @Test
    fun theSourceHasNoBuildMachinePath() {
        val config = section("/home/dev/stationbreaktv/app/proguard-rules.pro", "-dontshrink")
        assertEquals(listOf(R8Violation("-dontshrink", "app/proguard-rules.pro")), scan(config))
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
        assertEquals(emptyList(), scan(config))
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
        assertEquals(listOf("a second section header for this source file"), scan(config).map { it.rule })
    }

    @Test
    fun aLibraryWithTwoRuleFilesIsFine() {
        extractedFile("one.pro", "-keep class a.B")
        extractedFile("two.pro", "-keep class a.C")
        val header = "# The proguard configuration file for the following section is a:b:1 (extracted file: $root/"
        val end = "# End of content from a:b:1 (extracted file: $root/"
        val config = "${header}one.pro)\n-keep class a.B\n${end}one.pro)\n${header}two.pro)\n-keep class a.C\n${end}two.pro)\n"
        assertEquals(emptyList(), scan(config))
    }

    @Test
    fun aCoordinateHeaderWithoutAnExtractedFileIsFlagged() {
        val config =
            "# The proguard configuration file for the following section is androidx.compose.runtime:runtime-android:1.12.1\n" +
                "-assumenosideeffects class a.B\n"
        assertEquals(1, scan(config).count { it.rule == "a section header without an extracted file" })
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
        assertEquals(1, scan(config).count { it.rule == "a section header inside an open section" })
    }

    @Test
    fun anEndMarkerWithNothingOpenIsFlagged() {
        val config = section("a:b:1") + "# End of content from a:b:1 (extracted file: $proguardFile)\n"
        assertEquals(listOf("an end marker that does not close the open section"), scan(config).map { it.rule })
    }

    @Test
    fun aSectionNeverClosedIsFlagged() {
        val config = "# The proguard configuration file for the following section is $appRules\n-keep class a.B\n"
        assertEquals(listOf("a section that is never closed"), scan(config).map { it.rule })
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

    // #933: the every-package name check applies to all six keep options; the members then decide.
    @Test
    fun everyPackageInnerClassesOnEachMemberOption() {
        val options = listOf("-keepclassmembers", "-keepclassmembernames", "-keepclasseswithmembers", "-keepclasseswithmembernames")
        for (option in options) {
            assertFlagged("$option class **\$* { *; }")
            assertEquals(emptyList(), violations("$option class **\$\$serializer { private ** descriptor; }"), option)
        }
    }

    @Test
    fun everyPackageInnerClassesWithAllFieldsAndMethods() =
        assertFlagged("-keepclassmembers class **\$* {", "    <fields>;", "    <methods>;", "}")

    // #933: R8 reads `-keep class B -dontshrink` as two options (checked with R8 9.3.16: nothing was shrunk), so each
    // option on a shared line is checked on its own.
    @Test
    fun dontshrinkAfterANarrowKeepOnTheSameLine() = assertOnly("-dontshrink", "-keep class a.B -dontshrink")

    @Test
    fun aBroadKeepAfterANarrowKeepOnTheSameLine() = assertOnly("-keep class ** { *; }", "-keep class a.B -keep class ** { *; }")

    @Test
    fun aForbiddenOptionBehindAnotherOptionOnAContinuationLine() =
        assertEquals(
            listOf(R8Violation("-dontoptimize", "com.evil:lib:1.0")),
            violations("-keep class a.B", "    -keepattributes Signature -dontoptimize"),
        )

    @Test
    fun aListedSourceStillPassesWithSeveralOptionsOnALine() {
        val config = section("androidx.compose.runtime:runtime-android:1.12.1", "-keep class a.B -assumenosideeffects class a.C {", "}")
        assertEquals(emptyList(), scan(config))
    }

    @Test
    fun anIfAndItsNarrowKeepOnOneLineStillPass() = assertEquals(emptyList(), violations("-if class a.B -keep class a.C -dontwarn a.D"))

    // #933 fix 1: R8 9.3.16 ends an option at `}` as well as at whitespace.
    @Test
    fun anOptionRightAfterAnEmptyBody() = assertOnly("-dontshrink", "-keep class a.B {}-dontshrink")

    @Test
    fun aBroadKeepRightAfterABody() = assertOnly("-keep class **{*;}", "-keep class a.B{*;}-keep class **{*;}")

    @Test
    fun anOptionRightAfterAMultiLineBody() = assertOnly("-dontshrink", "-keep class a.B {", "    *;", "}-dontshrink")

    // `-` is a name character in R8, so these are one name, not an option (checked with 9.3.16: nothing took effect).
    @Test
    fun aDashAfterALetterInARuleIsFlaggedToo() =
        assertEquals(
            listOf(
                R8Violation("-keep class a.B-dontshrink", "com.evil:lib:1.0"),
                R8Violation("-dontwarn a.B-dontoptimize", "com.evil:lib:1.0"),
            ),
            violations("-keep class a.B-dontshrink", "-dontwarn a.B-dontoptimize"),
        )

    @Test
    fun aTabBetweenTwoOptions() = assertOnly("-dontshrink", "-keep class a.B\t-dontshrink")

    // R8 reads a quoted `#` as text in `-printconfiguration`, so the option after it still counts.
    @Test
    fun aQuotedHashDoesNotHideTheRestOfTheLine() = assertFlaggedWith("-dontshrink", "-printconfiguration \"x#y\" -dontshrink")

    @Test
    fun aHashOutsideQuotesStillStartsAComment() = assertEquals(emptyList(), violations("-keep class a.B # -keep class ** { *; }"))

    @Test
    fun severalMembersOnOneLine() {
        assertFlagged("-keepclassmembers class **\$* { <fields>; <methods>; }")
        assertFlagged("-keepclassmembers class **\$* { * ; }")
        assertFlagged("-keepclassmembers class **\$* { * *; }")
        assertFlagged("-keepclassmembers class **\$* { *** *; }")
    }

    @Test
    fun narrowMembersOnOneLineStillPass() {
        assertEquals(emptyList(), violations("-keepclassmembers class **\$\$serializer { private ** descriptor; }"))
        assertEquals(emptyList(), violations("-keepclassmembers class **\$WhenMappings { <fields>; }"))
    }

    @Test
    fun anIfItsKeepAndASecondBroadKeepOnOneLine() =
        assertOnly("-keep class ** { *; }", "-if class a.B -keep class a.C -keep class ** { *; }")

    @Test
    fun aKeepAfterTheClosingBraceOfAnIfBody() = assertFlagged("-if class ** {", "    *;", "} -keep class <1> { *; }")

    // #933 fix 2: a fail-closed scan of every line, comments and quotes included, outside the app's own rules.
    @Test
    fun aSingleQuotedHashDoesNotHideAnOption() = assertOnly("-dontwarn 'x#y' -dontshrink", "-dontwarn 'x#y' -dontshrink")

    @Test
    fun aQuoteRunningAcrossALineDoesNotHideAnOption() {
        assertFlaggedWith("-keep class a.B \"a", "-keep class a.B \"a", "# b\" -dontshrink")
        assertFlaggedWith("# b\" -dontshrink", "-keep class a.B \"a", "# b\" -dontshrink")
    }

    @Test
    fun anOptionGluedAfterAQuoteADigitAndAComma() {
        assertFlaggedWith("-dontshrink", "-keep class a.B \"x\"-dontshrink")
        assertOnly("-dontshrink", "-optimizationpasses 5-dontshrink")
        assertOnly("-dontshrink", "-keep class a.B,-dontshrink")
    }

    @Test
    fun anIncludeInALibrarySection() = assertOnly("-include other.pro", "-include other.pro")

    @Test
    fun aFileIncludeInALibrarySection() = assertOnly("@other.pro", "@other.pro")

    @Test
    fun aFileIncludeAfterAnotherDirective() = assertOnly("@other.pro", "-keep class a.B", "@other.pro")

    @Test
    fun aBasedirectoryInALibrarySection() = assertOnly("-basedirectory /x", "-basedirectory /x")

    @Test
    fun aPrintconfigurationInALibrarySection() = assertOnly("-printconfiguration out.txt", "-printconfiguration out.txt")

    @Test
    fun everyMethodWildcardWithAllFields() {
        assertFlagged("-keepclassmembers class **\$* { *** *(...); <fields>; }")
        assertFlagged("-keepclassmembers class **\$* { public *(...); * *; }")
    }

    @Test
    fun annotationRulesAndBodiesAreNotFileIncludes() {
        assertEquals(emptyList(), violations("-keep @a.B class *"))
        assertEquals(emptyList(), violations("-keep", "@a.B class *"))
        assertEquals(emptyList(), violations("-keepclassmembers class a.B {", "    @androidx.annotation.Keep <methods>;", "}"))
    }

    @Test
    fun theAppMayUsePrintconfigurationAndIncludes() {
        val config =
            section("/home/dev/stationbreaktv/app/proguard-rules.pro", "-printconfiguration out.txt", "-include x.pro # -dontshrink")
        assertEquals(emptyList(), scan(config))
    }

    @Test
    fun aListedSourceStillPassesTheBackstop() {
        val config =
            section(
                "androidx.startup:startup-runtime:1.2.0",
                "-keep class a.B",
                "-assumenosideeffects class a.C {",
                "}",
                "# -assumenosideeffects",
            )
        assertEquals(emptyList(), scan(config))
    }

    // #933 fix 3: no quotes in library rules, a split at any non-name dash, and only AGP's own section headers.
    private val broadKeep = "-keep class ** { *; }"

    private fun assertFlaggedWith(
        expected: String,
        vararg rules: String,
    ) = assertEquals(true, R8Violation(expected, "com.evil:lib:1.0") in violations(*rules), violations(*rules).toString())

    @Test
    fun aSingleQuoteHidesNothing() {
        assertFlaggedWith("-dontwarn 'x#y' $broadKeep", "-dontwarn 'x#y' $broadKeep")
    }

    @Test
    fun aDoubleQuoteGluedToAnOptionHidesNothing() {
        assertFlaggedWith("-keepattributes \"x\"$broadKeep", "-keepattributes \"x\"$broadKeep")
        assertFlaggedWith(broadKeep, "-keepattributes \"x\"$broadKeep")
    }

    @Test
    fun aDigitGluedToAnOptionHidesNothing() = assertFlaggedWith(broadKeep, "-optimizationpasses 5$broadKeep")

    @Test
    fun anUnclosedQuoteAcrossTwoLinesHidesNothing() {
        assertFlaggedWith("-keepattributes \"x", "-keepattributes \"x", broadKeep)
        assertFlaggedWith(broadKeep, "-keepattributes \"x", broadKeep)
    }

    @Test
    fun aQuoteAfterTheFirstHashIsOnlyAComment() = assertEquals(emptyList(), violations("-keep class a.B # it's a \"quoted\" licence"))

    @Test
    fun theAppMayUseQuotes() = assertEquals(emptyList(), scan(section(appRules, "-printconfiguration \"x y\"")))

    @Test
    fun aDashAfterALetterInRuleCodeIsFlagged() {
        assertFlaggedWith("-keepattributes Signature-dontshrink", "-keepattributes Signature-dontshrink")
        assertFlaggedWith("-renamesourcefileattribute SourceFile-dontshrink", "-renamesourcefileattribute SourceFile-dontshrink")
    }

    @Test
    fun aHyphenInACommentIsNotAnOption() = assertEquals(emptyList(), violations("# re-include the file", "-keep class a.B"))

    @Test
    fun moreFileOptionsInALibrarySection() {
        for (option in listOf("-printmapping", "-printseeds", "-printusage", "-dump", "-applymapping", "-obfuscationdictionary")) {
            assertOnly("$option x.txt", "$option x.txt")
            assertEquals(emptyList(), scan(section(appRules, "$option x.txt")), option)
        }
        assertOnly("-classobfuscationdictionary x.txt", "-classobfuscationdictionary x.txt")
        assertOnly("-packageobfuscationdictionary x.txt", "-packageobfuscationdictionary x.txt")
    }

    @Test
    fun aBackstopViolationIsTheRawLineCutTo200Characters() {
        val line = "-keep class a.B \"" + "x".repeat(300)
        val found = violations(line).single()
        assertEquals(200, found.rule.length)
        assertEquals(line.take(200), found.rule)
    }

    // An included file prints as its own top-level section, named by the path as written (checked with R8 9.3.16).
    @Test
    fun aHeaderEndingInTheAppRulesNameIsNotTheApp() {
        val config = section("/evil/app/proguard-rules.pro", "-dontobfuscate")
        val found = scan(config)
        assertEquals(listOf("a section header of an unknown shape", "-dontobfuscate"), found.map { it.rule })
        assertEquals(true, found.all { it.source.startsWith("unknown header: ") })
    }

    @Test
    fun aBarePathHeaderIsFlagged() {
        val config =
            "# The proguard configuration file for the following section is other.pro\n" +
                "-keep class C\n# End of content from other.pro\n"
        assertEquals(listOf("a section header of an unknown shape"), scan(config).map { it.rule })
    }

    @Test
    fun aBareAppRulesHeaderGetsNoAppTrust() {
        val config =
            "# The proguard configuration file for the following section is app/proguard-rules.pro\n" +
                "-dontobfuscate\n# End of content from app/proguard-rules.pro\n"
        assertEquals(listOf("a section header of an unknown shape", "-dontobfuscate"), scan(config).map { it.rule })
    }

    @Test
    fun theHeaderShapesAgpPrintsPass() {
        fun block(
            header: String,
            rule: String,
        ): String {
            // A library's header names the file holding its text; the others have none.
            val full = if (header.startsWith("<") || header == appRules) header else "$header (extracted file: ${fileWith(rule)})"
            return "# The proguard configuration file for the following section is $full\n$rule\n# End of content from $full\n"
        }
        val config =
            block("androidx.core:core:1.2.0", "-keep class a.B") +
                block("Android Gradle plugin 9.5.1", "-keep class a.C") +
                block("Local project :::feature:live", "-keep class a.D") +
                block("<unknown>", "-keep class a.E") +
                block(appRules, "-dontobfuscate")
        assertEquals(emptyList(), scan(config))
    }

    // #933 fix 4: a library header is believed only for a real extracted file under a trusted root.
    private fun headerOnly(header: String) =
        "# The proguard configuration file for the following section is $header\n-keep class a.B\n# End of content from $header\n"

    @Test
    fun aCoordinateHeaderWithARelativeMissingOrOutsidePathIsFlagged() {
        val outside = Files.createTempFile("r8outside", ".txt")
        for (path in listOf("rel/proguard.txt", "$root/missing.txt", outside.toString(), "$root/../${outside.fileName}")) {
            val found = scan(headerOnly("a:b:1 (extracted file: $path)"))
            assertEquals(listOf("a section header of an unknown shape"), found.map { it.rule }, path)
        }
    }

    @Test
    fun theOtherLibraryHeaderShapesNeedARealFileToo() {
        for (header in listOf("Android Gradle plugin 9.5.1", "Local project :::feature:live")) {
            assertEquals(emptyList(), scan(headerOnly("$header (extracted file: $proguardFile)")), header)
            assertEquals(
                listOf("a section header of an unknown shape"),
                scan(headerOnly("$header (extracted file: rel/x.txt)")).map { it.rule },
                header,
            )
        }
    }

    @Test
    fun aRealShapedHeaderUnderTheRootPasses() = assertEquals(emptyList(), scan(headerOnly("a:b:1 (extracted file: $proguardFile)")))

    @Test
    fun aKeywordGluedToItsClassNamesIsFlagged() {
        assertOnly("-keep class**{*;}", "-keep class**{*;}")
        assertOnly("-keepclassmembers interface**{*;}", "-keepclassmembers interface**{*;}")
        assertOnly("-if class a.B -keep class**", "-if class a.B -keep class**")
    }

    @Test
    fun aNonAsciiSpaceOrControlCharacterIsFlagged() {
        assertFlaggedWith("-keep class a.B\u2003{ *; }", "-keep class a.B\u2003{ *; }")
        assertFlaggedWith("-keep class a.\u2028B", "-keep class a.\u2028B")
        assertEquals(emptyList(), scan(section(appRules, "-keep class a.B \u2003 # app only")))
    }

    @Test
    fun aFileIncludeAfterWhitespaceOnTheSameLine() = assertOnly("-keep class a.B @other.pro", "-keep class a.B @other.pro")

    // Dagger's own consumer rule: an annotation after an option that is not a keep is not a file include.
    @Test
    fun anAnnotationAfterANonKeepOptionIsNotAnInclude() =
        assertEquals(emptyList(), violations("-identifiernamestring @dagger.internal.IdentifierNameString class ** {", "    *;", "}"))

    // #933 fix 5: the named file must hold the section's text, not just exist.
    private fun forgedHeader(
        file: Path,
        vararg rules: String,
    ) = "androidx.compose.runtime:runtime-android:0 (extracted file: $file)".let { header ->
        "# The proguard configuration file for the following section is $header\n${rules.joinToString("\n")}\n" +
            "# End of content from $header\n"
    }

    @Test
    fun aForgedHeaderNamingARealFileWithOtherContentIsFlagged() {
        val found = scan(forgedHeader(proguardFile, "-assumenosideeffects class a.B"))
        assertEquals(listOf("a section header of an unknown shape", "-assumenosideeffects class a.B"), found.map { it.rule })
    }

    @Test
    fun aHeaderWhoseFileHoldsTheSectionPasses() {
        val file = fileWith("-assumenosideeffects class a.B")
        assertEquals(emptyList(), scan(forgedHeader(file, "-assumenosideeffects class a.B").replace(":0 (", ":1.12.1 (")))
    }

    @Test
    fun theFileMayDifferInLineEndingsTrailingSpacesAndEdgeBlankLines() {
        val file =
            extractedFile("crlf.txt", "").also { Files.writeString(it, "\r\n-keep class a.B  \r\n-keep class a.C\t\r\n\r\n") }
        assertEquals(emptyList(), scan(forgedHeader(file, "-keep class a.B", "-keep class a.C")))
    }

    @Test
    fun aDotDotPathBackUnderTheRootWithOtherContentIsFlagged() {
        Files.createDirectories(root.resolve("sub"))
        val path = "$root/sub/../${proguardFile.fileName}"
        val found = scan(forgedHeader(Path.of(path), "-assumenosideeffects class a.B"))
        assertEquals(listOf("a section header of an unknown shape", "-assumenosideeffects class a.B"), found.map { it.rule })
    }

    @Test
    fun aGluedKeywordInAnIfConditionIsFlagged() {
        assertOnly("-if class**", "-if class**", "-keep class <1>")
        assertOnly("-if class** -keep class <1>", "-if class** -keep class <1>")
        assertEquals(emptyList(), violations("-if class a.B", "-keep class <1>"))
    }

    // #933 fix 6: a header may name only a file AGP gave R8.
    @Test
    fun aMatchingFileUnderTheRootThatWasNotGivenToR8IsFlagged() {
        val stray = root.resolve("stray.txt").also { Files.writeString(it, "-keep class a.B\n") }
        val found = scan(headerOnly("a:b:1 (extracted file: $stray)"))
        assertEquals(listOf("a section header of an unknown shape"), found.map { it.rule })
    }

    @Test
    fun theSamePathInTwoHeadersIsNotTrusted() {
        val config = headerOnly("a:b:1 (extracted file: $proguardFile)") + headerOnly("a:c:1 (extracted file: $proguardFile)")
        assertEquals(List(2) { "a section header of an unknown shape" }, scan(config).map { it.rule })
    }

    // The generated keep rules for the minified security test run (#436): one bare-path section, trusted as a library's.

    private val generated = root.resolve("generated-keep-rules.pro")

    private fun generatedSection(vararg rules: String): String {
        Files.writeString(generated, rules.joinToString("\n") + "\n")
        return "# The proguard configuration file for the following section is $generated\n" +
            rules.joinToString("\n") + "\n# End of content from $generated\n"
    }

    private fun scanWithGenerated(config: String) = R8ConfigRules.violations(config, appRules, given.toSet(), generated)

    @Test
    fun theGeneratedRulesFileIsTrustedAsItsOwnPathAndText() {
        val config = generatedSection("-keep class a.B {", "  public void m();", "}")
        assertEquals(emptyList(), scanWithGenerated(config))
    }

    @Test
    fun theGeneratedPathIsAnUnknownShapeWhenTheBuildHasNoGeneratedRules() {
        val config = generatedSection("-keep class a.B")
        assertEquals(listOf("a section header of an unknown shape"), scan(config).map { it.rule })
    }

    @Test
    fun anotherBarePathIsStillAnUnknownShapeWithGeneratedRules() {
        val other = root.resolve("other.pro").also { Files.writeString(it, "-keep class a.B\n") }
        val config =
            "# The proguard configuration file for the following section is $other\n-keep class a.B\n" +
                "# End of content from $other\n"
        assertEquals(listOf("a section header of an unknown shape"), scanWithGenerated(config).map { it.rule })
    }

    @Test
    fun theGeneratedSectionMustHoldTheFilesText() {
        val config = generatedSection("-keep class a.B")
        Files.writeString(generated, "-keep class c.D\n")
        assertEquals(listOf("a section header of an unknown shape"), scanWithGenerated(config).map { it.rule })
    }

    @Test
    fun theGeneratedSectionGetsNoAppAllowance() {
        // -dontobfuscate is allowed from the app's rules file only: a generated file that carries it is flagged.
        val found = scanWithGenerated(generatedSection("-dontobfuscate"))
        assertEquals(listOf(R8Violation("-dontobfuscate", "keep rules generated by tracereferences (#436)")), found)
    }

    @Test
    fun theGeneratedSectionIsHeldToTheBroadKeepCheck() {
        val found = scanWithGenerated(generatedSection("-keep class ** { *; }"))
        assertEquals(listOf("-keep class ** { *; }"), found.map { it.rule })
    }

    @Test
    fun theGeneratedPathCannotBeTheAppRulesFile() {
        val config = R8ConfigRules.violations(generatedSection("-dontobfuscate"), generated.toString(), given.toSet(), generated)
        // Trusted as the app's own path, not as generated rules: the app's allowance is the one in force, so the
        // violation list cannot show a generated-rules source.
        assertEquals(emptyList(), config.filter { it.source.startsWith("keep rules generated") })
    }

    @Test
    fun aLinkOrAnOversizeFileIsNotRead() {
        val link = root.resolve("link.txt").also { Files.createSymbolicLink(it, proguardFile) }
        given.add(link)
        assertEquals(listOf("a section header of an unknown shape"), scan(headerOnly("a:b:1 (extracted file: $link)")).map { it.rule })
        val big = extractedFile("big.txt", "x".repeat((1 shl 20) + 1))
        assertEquals(listOf("a section header of an unknown shape"), scan(headerOnly("a:b:1 (extracted file: $big)")).map { it.rule })
    }
}
