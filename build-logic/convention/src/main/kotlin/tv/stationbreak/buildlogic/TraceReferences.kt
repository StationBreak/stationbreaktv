// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/** The `.class` files of a jar, by entry name. Other entries (resources, `module-info`, multi-release copies) are never code R8 reads. */
private fun isClass(name: String) = name.endsWith(".class") && !name.startsWith("META-INF/") && name != "module-info.class"

/**
 * Packs the classes AGP hands a scoped-artifact consumer (`toGet`: jars and class directories) into one jar, first
 * copy of a class winning. R8's `tracereferences` reads archives, not directories (#436).
 */
abstract class ClassesJar : DefaultTask() {
    @get:InputFiles
    @get:Classpath
    abstract val jars: ListProperty<RegularFile>

    @get:InputFiles
    @get:Classpath
    abstract val dirs: ListProperty<Directory>

    @get:OutputFile
    abstract val output: RegularFileProperty

    @TaskAction
    fun pack() {
        val seen = HashSet<String>()
        ZipOutputStream(
            output
                .get()
                .asFile
                .outputStream()
                .buffered(),
        ).use { out ->
            fun add(
                name: String,
                bytes: () -> ByteArray,
            ) {
                if (isClass(name) && seen.add(name)) {
                    out.putNextEntry(ZipEntry(name))
                    out.write(bytes())
                    out.closeEntry()
                }
            }
            for (dir in dirs.get()) {
                val root = dir.asFile
                root.walkTopDown().filter { it.isFile }.forEach { file ->
                    add(file.relativeTo(root).invariantSeparatorsPath) { file.readBytes() }
                }
            }
            for (jar in jars.get()) {
                ZipFile(jar.asFile).use { zip ->
                    zip.entries().asSequence().filter { !it.isDirectory }.forEach { entry ->
                        add(entry.name) { zip.getInputStream(entry).use { it.readBytes() } }
                    }
                }
            }
        }
    }
}

/**
 * R8's `tracereferences --keep-rules` (#436): the rules that keep exactly what the [source] classes reference in the
 * [target]. A class of the source that the target also has (a library both APKs carry) is the target's own, so it is
 * left out of the source: the test APK runs on the app's copy.
 */
abstract class TraceReferencesKeepRules
    @Inject
    constructor(
        private val exec: ExecOperations,
    ) : DefaultTask() {
        /** R8's own jar (the one the Android Gradle plugin runs). */
        @get:Classpath
        abstract val r8: ConfigurableFileCollection

        // One jar per security test module. The first copy of a class wins. A file collection, so the task depends on what builds it.
        @get:InputFiles
        @get:PathSensitive(PathSensitivity.NONE)
        abstract val source: ConfigurableFileCollection

        @get:InputFile
        @get:PathSensitive(PathSensitivity.NONE)
        abstract val target: RegularFileProperty

        /** `android.jar`: what neither side has to be traced into. */
        @get:InputFiles
        @get:PathSensitive(PathSensitivity.NONE)
        abstract val libraries: ConfigurableFileCollection

        @get:OutputFile
        abstract val keepRules: RegularFileProperty

        @TaskAction
        fun trace() {
            val targetClasses =
                ZipFile(target.get().asFile).use { zip ->
                    zip
                        .entries()
                        .asSequence()
                        .map { it.name }
                        .toSet()
                }
            val traced = temporaryDir.resolve("source-only.jar")
            val seen = HashSet<String>()
            ZipOutputStream(traced.outputStream().buffered()).use { out ->
                for (jar in source.files.sortedBy { it.path }) {
                    ZipFile(jar).use { zip ->
                        for (entry in zip.entries()) {
                            if (isClass(entry.name) && entry.name !in targetClasses && seen.add(entry.name)) {
                                out.putNextEntry(ZipEntry(entry.name))
                                zip.getInputStream(entry).use { it.copyTo(out) }
                                out.closeEntry()
                            }
                        }
                    }
                }
            }
            val messages = java.io.ByteArrayOutputStream()
            exec.javaexec {
                standardOutput = messages
                errorOutput = messages
                classpath = r8
                mainClass.set("com.android.tools.r8.tracereferences.TraceReferences")
                args("--keep-rules")
                // The test libraries name optional things neither APK has: annotations, hidden platform API,
                // android.test.*. Nothing to keep there; a class the target lacks is only a warning.
                args("--map-diagnostics:MissingDefinitionsDiagnostic", "error", "warning")
                libraries.files.forEach { args("--lib", it.absolutePath) }
                args("--source", traced.absolutePath)
                args("--target", target.get().asFile.absolutePath)
                args("--output", keepRules.get().asFile.absolutePath)
            }
            val output = messages.toString(Charsets.UTF_8)
            logger.warn(output)
            val ours = missingOwnDefinitions(output)
            if (ours.isNotEmpty()) {
                throw GradleException(
                    "tracereferences cannot find our own code in the app's R8 input, so the trace is partial (#436): " +
                        ours.take(MAX_MISSING).joinToString("; "),
                )
            }
            val rules = keepRuleCount(keepRules.get().asFile.readText())
            if (rules < MIN_KEEP_RULES) {
                throw GradleException(
                    "The generated keep rules hold $rules -keep rules, fewer than $MIN_KEEP_RULES: a partial trace (#436)",
                )
            }
        }
    }

private const val MAX_MISSING = 10
internal const val MIN_KEEP_RULES = 100
private val ownNames = Regex("(?<![A-Za-z0-9_$])(tv\\.stationbreak|okhttp3)\\.")

/**
 * The missing definitions tracereferences reported that are ours or OkHttp's. Third-party names (annotations, hidden
 * platform API) are only warnings; a class of ours the target lacks means the target is not the whole app. Only the
 * part of a line before "(referenced from" names the missing thing.
 */
internal fun missingOwnDefinitions(output: String): List<String> =
    output
        .lines()
        .map { it.removePrefix("Warning: ").removePrefix("Error: ") }
        .filter { it.startsWith("Missing ") }
        .map { it.substringBefore(" (referenced from") }
        .filter { ownNames.containsMatchIn(it) }

internal fun keepRuleCount(rules: String): Int = rules.lines().count { it.startsWith("-keep") }
