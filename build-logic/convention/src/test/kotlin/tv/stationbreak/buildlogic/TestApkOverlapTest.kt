// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals

class TestApkOverlapTest {
    @Test
    fun aClassInBothIsAHit() {
        assertEquals(
            listOf("okhttp3/OkHttpClient"),
            classOverlap(setOf("okhttp3/OkHttpClient", "a/T"), setOf("okhttp3/OkHttpClient", "b/X"), emptySet()),
        )
    }

    @Test
    fun noOverlapPasses() {
        assertEquals(emptyList(), classOverlap(setOf("a/T"), setOf("b/X"), emptySet()))
    }

    @Test
    fun theModulesOwnClassesAreNotHits() {
        assertEquals(emptyList(), classOverlap(setOf("a/T"), setOf("a/T"), setOf("a/T")))
    }

    @Test
    fun theDexReaderFindsTheDefinedClasses() {
        assertEquals(setOf("a/b/C"), dexClassNames(dexWithOneClass("La/b/C;")))
    }

    @Test
    fun ourOwnOrOkHttpDefinitionsMissingFail() {
        val output =
            """
            Warning: Missing class androidx.annotation.NonNull (referenced from: a.B.f)
            Missing method void android.test.X.set(android.content.Context) (referenced from: tv.stationbreak.app.Y.z)
            Missing class tv.stationbreak.core.domain.Port (referenced from: a.B)
            Missing method void okhttp3.OkHttpClient.newCall() (referenced from: a.B)
            """.trimIndent()
        assertEquals(2, missingOwnDefinitions(output).size)
    }

    @Test
    fun thirdPartyMissingNamesAreOnlyWarnings() {
        val output = "Missing class com.google.errorprone.annotations.InlineMe (referenced from: tv.stationbreak.app.Y)"
        assertEquals(emptyList(), missingOwnDefinitions(output))
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
