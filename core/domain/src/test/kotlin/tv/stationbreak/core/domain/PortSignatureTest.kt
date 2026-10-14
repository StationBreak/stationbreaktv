// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import tv.stationbreak.core.model.ServerAddress
import java.io.File
import java.lang.reflect.GenericArrayType
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-0005: no Jellyfin SDK or OkHttp type may appear in the port or in any public type of core:model and
 * core:domain. This is the second guard behind `checkArchitecture`'s dependency rule (F0.3 D10): it looks at the
 * compiled signatures, so a type that arrives transitively fails too.
 */
class PortSignatureTest {
    private val allowedPrefixes = listOf("java.lang.", "java.util.", "kotlin.", "kotlinx.", "tv.stationbreak.")

    private fun isAllowed(name: String) = allowedPrefixes.any { name.startsWith(it) }

    private fun typeNames(type: Type): List<String> =
        when (type) {
            is Class<*> -> {
                if (type.isArray) {
                    typeNames(type.componentType)
                } else if (type.isPrimitive) {
                    emptyList()
                } else {
                    listOf(type.name)
                }
            }

            is ParameterizedType -> {
                typeNames(type.rawType) + type.actualTypeArguments.flatMap(::typeNames)
            }

            is GenericArrayType -> {
                typeNames(type.genericComponentType)
            }

            is WildcardType -> {
                (type.upperBounds + type.lowerBounds).flatMap(::typeNames)
            }

            is TypeVariable<*> -> {
                type.bounds.flatMap(::typeNames)
            }

            else -> {
                listOf(type.typeName)
            }
        }

    private fun disallowedIn(types: Iterable<Type>): List<String> {
        val names = types.flatMap(::typeNames)
        return names.filterNot(::isAllowed).distinct()
    }

    private fun isApi(modifiers: Int) = Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers)

    private fun apiTypesOf(type: Class<*>): List<Type> =
        buildList {
            type.genericSuperclass?.let(::add)
            addAll(type.genericInterfaces)
            for (method in type.declaredMethods.filter { isApi(it.modifiers) }) {
                add(method.genericReturnType)
                addAll(method.genericParameterTypes)
            }
            for (constructor in type.declaredConstructors.filter {
                Modifier.isPublic(
                    it.modifiers,
                ) || Modifier.isProtected(it.modifiers)
            }) {
                addAll(constructor.genericParameterTypes)
            }
            for (field in type.declaredFields.filter { isApi(it.modifiers) }) {
                add(field.genericType)
            }
        }

    /** Every class in the jar or directory [anchor] was loaded from, under [packagePrefix]. */
    private fun classesOf(
        anchor: Class<*>,
        packagePrefix: String,
    ): List<Class<*>> {
        val location =
            File(
                anchor.protectionDomain.codeSource.location
                    .toURI(),
            )
        val names =
            if (location.isDirectory) {
                location
                    .walkTopDown()
                    .filter { it.extension == "class" }
                    .map { it.relativeTo(location).path }
                    .toList()
            } else {
                JarFile(location).use { jar ->
                    jar
                        .entries()
                        .asSequence()
                        .map { it.name }
                        .filter { it.endsWith(".class") }
                        .toList()
                }
            }
        return names
            .map { it.removeSuffix(".class").replace('/', '.').replace('\\', '.') }
            .filter { it.startsWith(packagePrefix) && !it.endsWith("module-info") }
            .map { Class.forName(it, false, anchor.classLoader) }
            .filter { Modifier.isPublic(it.modifiers) || Modifier.isProtected(it.modifiers) }
    }

    @Test
    fun theMediaServerPortUsesOnlyOwnKotlinAndJdkTypes() {
        val types =
            MediaServer::class.java.methods.flatMap { it.genericParameterTypes.toList() + it.genericReturnType }
        assertEquals(emptyList(), disallowedIn(types))
    }

    @Test
    fun everyPublicTypeInCoreDomainUsesOnlyAllowedTypes() {
        val classes = classesOf(MediaServer::class.java, "tv.stationbreak.core.domain")
        assertTrue(classes.size > 5, "found ${classes.size} classes")
        for (type in classes) assertEquals(emptyList(), disallowedIn(apiTypesOf(type)), type.name)
    }

    @Test
    fun everyPublicTypeInCoreModelUsesOnlyAllowedTypes() {
        val classes = classesOf(ServerAddress::class.java, "tv.stationbreak.core.model")
        assertTrue(classes.size > 5, "found ${classes.size} classes")
        for (type in classes) assertEquals(emptyList(), disallowedIn(apiTypesOf(type)), type.name)
    }

    @Test
    fun theGuardWouldCatchAnSdkOrOkHttpType() {
        // The SDK and OkHttp aren't on this module's classpath (checkArchitecture), so test the predicate by name.
        assertFalse(isAllowed("okhttp3.HttpUrl"))
        assertFalse(isAllowed("okio.Buffer"))
        assertFalse(isAllowed("org.jellyfin.sdk.api.client.ApiClient"))
        assertTrue(isAllowed("tv.stationbreak.core.model.ServerAddress"))
        assertTrue(isAllowed("kotlin.coroutines.Continuation"))
        assertTrue(isAllowed("kotlinx.coroutines.flow.Flow"))
    }
}
