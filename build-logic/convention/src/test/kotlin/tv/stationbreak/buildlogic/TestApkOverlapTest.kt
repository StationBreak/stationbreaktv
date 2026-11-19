// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TestApkOverlapTest {
    @Test
    fun aClassInBothIsAHit() {
        assertEquals(
            listOf("okhttp3/OkHttpClient"),
            classOverlap(setOf("okhttp3/OkHttpClient", "a/T"), setOf("okhttp3/OkHttpClient", "b/X")),
        )
    }

    @Test
    fun noOverlapPasses() {
        assertEquals(emptyList(), classOverlap(setOf("a/T"), setOf("b/X")))
    }

    @Test
    fun aClassOfTheModulesOwnThatTheAppAlsoHasIsAHit() {
        // Own test classes are never in the app's R8 input; one that is would shadow the minified copy.
        assertEquals(
            listOf("tv/stationbreak/app/SecretFixtures"),
            classOverlap(setOf("tv/stationbreak/app/SecretFixtures"), setOf("tv/stationbreak/app/SecretFixtures")),
        )
    }

    @Test
    fun theDexReaderFindsTheDefinedClasses() {
        assertEquals(setOf("a/b/C"), dexClassNames(dexWithOneClass("La/b/C;")))
    }

    // The layout of a real tracereferences run (from the build log): the first line has a "Warning: " prefix.
    @Suppress("ktlint:standard:max-line-length")
    private val realThirdPartyOutput =
        "Warning: Missing class android.test.AndroidTestCase (referenced from: void androidx.test.internal.runner.junit3.AndroidTestResult.run(junit.framework.TestCase))\n" +
            "Missing method void android.test.AndroidTestCase.setContext(android.content.Context) (referenced from: void androidx.test.internal.runner.junit3.AndroidTestResult.run(junit.framework.TestCase))\n" +
            "Missing method android.app.Instrumentation\$ActivityResult androidx.test.internal.runner.hidden.ExposedInstrumentationApi.execStartActivity(android.content.Context, android.os.IBinder, int) (referenced from: android.app.Instrumentation\$ActivityResult androidx.test.runner.MonitoringInstrumentation.execStartActivity(android.content.Context))\n" +
            "Missing class com.google.errorprone.annotations.CanIgnoreReturnValue (referenced from: java.lang.Object androidx.test.internal.util.Checks.checkNotNull(java.lang.Object) and 3 other contexts)\n" +
            "Missing class org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement (referenced from: javax.net.ssl.X509TrustManager okhttp3.tls.internal.TlsUtil.newTrustManager(java.lang.String) and 3 other contexts)\n"

    @Test
    fun thirdPartyMissingNamesAreOnlyWarningsEvenWhenOurClassesReferToThem() {
        assertEquals(emptyList(), missingOwnDefinitions(realThirdPartyOutput))
        assertFalse(unreadableMissingReport(realThirdPartyOutput))
    }

    @Test
    fun ourOwnOrOkHttpDefinitionsMissingFailInTheRealLayout() {
        val output =
            realThirdPartyOutput +
                "Missing class tv.stationbreak.core.domain.Port (referenced from: a.B)\n" +
                "Missing method void okhttp3.OkHttpClient.newCall(okhttp3.Request) (referenced from: a.B)\n" +
                "  Missing field int tv.stationbreak.app.Y.z (referenced from: a.B)\n"
        assertEquals(3, missingOwnDefinitions(output).size)
    }

    @Test
    fun aMissingReportInAnUnknownFormatIsLoud() {
        assertTrue(unreadableMissingReport("Error: the definition of a.B is Missing from the target"))
    }

    @Test
    fun fewKeepRulesAreCountedForTheFloor() {
        val rules = (1..99).joinToString("\n") { "-keep class a.C$it {\n}" }
        assertEquals(99, keepRuleCount(rules))
        assertEquals(100, keepRuleCount(rules + "\n-keep interface a.D"))
        assertEquals(100, MIN_KEEP_RULES)
    }

    // A dex with one class definition: header, one string id, one type id, one class def, the string data.
    private fun dexWithOneClass(descriptor: String): ByteArray {
        val stringIdsAt = 112
        val typeIdsAt = stringIdsAt + 4
        val classDefsAt = typeIdsAt + 4
        val dataAt = classDefsAt + 32
        val bytes = ByteArray(dataAt)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(60, stringIdsAt)
        buffer.putInt(68, typeIdsAt)
        buffer.putInt(96, 1)
        buffer.putInt(100, classDefsAt)
        buffer.putInt(stringIdsAt, dataAt)
        buffer.putInt(typeIdsAt, 0)
        buffer.putInt(classDefsAt, 0)
        val data = ByteArrayOutputStream()
        data.write(bytes)
        data.write(descriptor.length)
        data.write(descriptor.toByteArray())
        data.write(0)
        return data.toByteArray()
    }
}
