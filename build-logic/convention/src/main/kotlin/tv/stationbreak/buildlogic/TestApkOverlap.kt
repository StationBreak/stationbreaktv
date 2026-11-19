// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

private const val MAX_LISTED = 20

/**
 * The classes both a test APK and the app carry, less the test module's own (#436). Android loads the instrumentation APK
 * first, so a class in both runs from the test APK's unminified copy and the minified app is not what the test meets.
 * Names are internal names (`okhttp3/OkHttpClient`).
 */
internal fun classOverlap(
    testApk: Set<String>,
    app: Set<String>,
    own: Set<String>,
): List<String> = (testApk intersect app).minus(own).sorted()

/** The class names a dex file defines, as internal names (`a/b/C`). */
internal fun dexClassNames(dex: ByteArray): Set<String> {
    val buffer = ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN)
    val stringIds = buffer.getInt(STRING_IDS_OFF)
    val typeIds = buffer.getInt(TYPE_IDS_OFF)
    val classDefsSize = buffer.getInt(CLASS_DEFS_SIZE)
    val classDefs = buffer.getInt(CLASS_DEFS_OFF)
    val names = HashSet<String>()
    repeat(classDefsSize) { i ->
        val typeIdx = buffer.getInt(classDefs + i * CLASS_DEF_BYTES)
        val stringIdx = buffer.getInt(typeIds + typeIdx * 4)
        var at = buffer.getInt(stringIds + stringIdx * 4)
        while (dex[at].toInt() and 0x80 != 0) at++ // the UTF-16 length, a uleb128
        at++
        val start = at
        while (dex[at].toInt() != 0) at++
        val descriptor = String(dex, start, at - start, Charsets.UTF_8)
        names += descriptor.removePrefix("L").removeSuffix(";")
    }
    return names
}

private const val STRING_IDS_OFF = 60
private const val TYPE_IDS_OFF = 68
private const val CLASS_DEFS_SIZE = 96
private const val CLASS_DEFS_OFF = 100
private const val CLASS_DEF_BYTES = 32

private fun classNamesOfJar(jar: java.io.File): Set<String> =
    ZipFile(jar).use { zip ->
        zip
            .entries()
            .asSequence()
            .map { it.name }
            .filter { it.endsWith(".class") }
            .map { it.removeSuffix(".class") }
            .toSet()
    }

/**
 * Fails the test module's `assemble` if its APK carries a copy of a class the app's R8 input has (#436). Both test
 * modules run it, so a drifted catalog pin or an AGP change that stops stripping a duplicate is loud.
 */
abstract class CheckTestApkOverlap : DefaultTask() {
    /** The directory AGP writes the test APK to. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val apkDir: DirectoryProperty

    /** The app variant's R8 input classes, one jar (the same one `tracereferences` reads as its target). */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val appClasses: ConfigurableFileCollection

    /** The test module's own compiled classes, one jar: never a hit. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val ownClasses: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val apks =
            apkDir
                .get()
                .asFile
                .walkTopDown()
                .filter { it.extension == "apk" }
                .toList()
        val testClasses = HashSet<String>()
        for (apk in apks) {
            ZipFile(apk).use { zip ->
                zip
                    .entries()
                    .asSequence()
                    .filter { it.name.matches(Regex("classes\\d*\\.dex")) }
                    .forEach { testClasses += dexClassNames(zip.getInputStream(it).readBytes()) }
            }
        }
        // A reader that finds nothing would pass for ever.
        if (testClasses.isEmpty()) throw GradleException("No classes were read from the test APK in ${apkDir.get().asFile}")
        val app = classNamesOfJar(appClasses.singleFile)
        val hits = classOverlap(testClasses, app, classNamesOfJar(ownClasses.get().asFile))
        report.get().asFile.writeText("test APK classes: ${testClasses.size}, app classes: ${app.size}, overlap: ${hits.size}\n")
        if (hits.isNotEmpty()) {
            throw GradleException(
                "The test APK carries ${hits.size} class(es) the app also has, so they would run unminified from the test " +
                    "APK (#436). Align the test module's versions with the app's. First ${minOf(hits.size, MAX_LISTED)}: " +
                    hits.take(MAX_LISTED).joinToString(", ") { it.replace('/', '.') },
            )
        }
    }
}
