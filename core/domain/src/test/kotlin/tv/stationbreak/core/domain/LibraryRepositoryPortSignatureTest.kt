// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import java.lang.reflect.GenericArrayType
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F0.6 (spec 06, Unit 2): the cache port exposes only domain types. No Room, WorkManager, Android or Jellyfin type may
 * appear in a signature of [LibraryRepository], [AccountCache] or the sync-state types, because `core:domain` is plain
 * JVM and the adapters live behind it (ADR-0004, ADR-0005). [PortSignatureTest] guards every public type of the module
 * in general; this test pins the cache port's own members and names the forbidden families.
 */
class LibraryRepositoryPortSignatureTest {
    private val allowedPrefixes = listOf("java.lang.", "java.util.", "kotlin.", "kotlinx.", "tv.stationbreak.")
    private val forbiddenPrefixes = listOf("androidx.", "android.", "org.jellyfin.", "okhttp3.", "okio.")

    private fun names(type: Type): List<String> =
        when (type) {
            is Class<*> -> {
                if (type.isArray) {
                    names(type.componentType)
                } else if (type.isPrimitive) {
                    emptyList()
                } else {
                    listOf(type.name)
                }
            }

            is ParameterizedType -> {
                names(type.rawType) + type.actualTypeArguments.flatMap(::names)
            }

            is GenericArrayType -> {
                names(type.genericComponentType)
            }

            is WildcardType -> {
                (type.upperBounds + type.lowerBounds).flatMap(::names)
            }

            is TypeVariable<*> -> {
                type.bounds.flatMap(::names)
            }

            else -> {
                listOf(type.typeName)
            }
        }

    private fun signatureTypes(type: Class<*>): List<Type> =
        type.methods.flatMap { it.genericParameterTypes.toList() + it.genericReturnType }

    private fun assertDomainOnly(type: Class<*>) {
        val found = signatureTypes(type).flatMap(::names).distinct()
        assertEquals(emptyList(), found.filter { name -> forbiddenPrefixes.any(name::startsWith) }, type.name)
        assertEquals(emptyList(), found.filterNot { name -> allowedPrefixes.any(name::startsWith) }, type.name)
    }

    @Test
    fun theRepositoryPortHasTheJobsTheSpecGivesIt() {
        val methods = LibraryRepository::class.java.methods.map { it.name }.toSet()
        // The snapshot, the status flow (sync state and last success), the exclusion count and the selection.
        assertTrue(
            methods.containsAll(setOf("snapshot", "status", "excludedCount", "select", "pendingLibraries")),
            "found $methods",
        )
    }

    @Test
    fun theRepositoryPortUsesOnlyDomainTypes() {
        assertDomainOnly(LibraryRepository::class.java)
    }

    @Test
    fun theAccountCacheHookUsesOnlyDomainTypes() {
        assertDomainOnly(AccountCache::class.java)
        val methods = AccountCache::class.java.methods.map { it.name }.toSet()
        assertEquals(setOf("useAccount", "forget"), methods)
    }

    @Test
    fun theStateTypesCarryNoThrowableOrServerError() {
        // A failure is a closed enum: no cause, URL or message can reach a log through the cache's state.
        listOf(SyncState.FirstSyncRunning::class.java, SyncState.LastSyncFailed::class.java, LibraryStatus::class.java)
            .forEach { type ->
                val found = type.declaredFields.flatMap { names(it.genericType) }
                assertEquals(emptyList(), found.filter { it.contains("Throwable") || it.contains("Exception") }, type.name)
            }
    }
}
