// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic.architecture

/** One forbidden rule in the merged R8 configuration: the rule's first line, and the file or artifact it came from. */
data class R8Violation(
    val rule: String,
    val source: String,
)

/**
 * #437: library consumer rules are merged into the app's R8 configuration, so a dependency bump can add a rule that
 * silently weakens the release build. Scans the merged configuration AGP prints
 * (`outputs/mapping/<variant>/configuration.txt`), where each section opens with
 * `# The proguard configuration file for the following section is <source>`.
 */
object R8ConfigRules {
    private val sectionHeader = Regex("^# The proguard configuration file for the following section is (.*)$")
    private val wildcardOnly = Regex("[*.]+")
    private val noSpec = setOf("extends", "implements")

    // Rules that are fine from a named source: a Maven "group:artifact:" prefix, or the app's own rules file. Each is
    // what the build prints today: a first-party library's own debug-stripping or constant-folding rule (Compose's
    // `-assumenosideeffects class *` only matches its own marked helpers). A new source for the same rule fails until
    // someone reads the rule and adds the source here with the reason.
    private val allowed: Map<String, List<String>> =
        mapOf(
            "-assumenosideeffects" to
                listOf(
                    "androidx.compose.animation:animation-android:",
                    "androidx.compose.runtime:runtime-android:",
                    "androidx.startup:startup-runtime:",
                    "org.jetbrains.kotlinx:kotlinx-coroutines-android:",
                ),
            // Deliberate (#331): logs print class simple names.
            "-dontobfuscate" to listOf("app/proguard-rules.pro"),
        )

    fun violations(configuration: String): List<R8Violation> {
        val found = mutableListOf<R8Violation>()
        var source = "unknown"
        var directive = StringBuilder()

        fun flush() {
            val text = directive.toString().trim()
            directive = StringBuilder()
            if (text.isEmpty() || !forbidden(text)) return
            val option = text.substringBefore(' ').substringBefore(',')
            if (allowed[option]?.any { source.startsWith(it) } != true) {
                found += R8Violation(text.lines().first().trim(), source)
            }
        }
        for (raw in configuration.lines()) {
            val line = raw.trim()
            val header = sectionHeader.matchEntire(line)
            when {
                header != null -> {
                    flush()
                    source = normalise(header.groupValues[1])
                }

                line.startsWith("#") || line.isEmpty() -> {}

                line.startsWith("-") -> {
                    flush()
                    directive.append(line)
                }

                // A rule's member lines and closing brace.
                else -> {
                    directive.append('\n').append(line)
                }
            }
        }
        flush()
        return found
    }

    private fun forbidden(directive: String): Boolean {
        val head = directive.substringBefore('{').trim()
        val option = head.substringBefore(' ').substringBefore(',')
        val args = head.substringAfter(' ', "").split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
        return when {
            option in setOf("-assumenosideeffects", "-dontobfuscate", "-dontoptimize", "-dontshrink") -> true
            option == "-dontwarn" -> args.isEmpty() || args.any { wildcardOnly.matches(it) }
            option.startsWith("-keep") -> keepsEverything(args, directive.substringAfter('{', ""))
            else -> false
        }
    }

    // `-keep… class ** { *; }`: a wildcard-only class name with no members listed keeps every class; with `*;` (or
    // all fields and methods) it keeps every member too. Specific member lines (`native <methods>;`) pass.
    // shortcut: `class * extends X` is not caught as a keep-all (it is narrower); a rule that reads `-keep class ** ` plus
    // modifiers other than extends/implements is. Revisit if a real rule slips past.
    private fun keepsEverything(
        args: List<String>,
        body: String,
    ): Boolean {
        val className = args.lastOrNull() ?: return false
        // An annotation on the class (`@androidx.annotation.Keep class *`) narrows it to the annotated ones.
        if (args.any { it.startsWith("@") }) return false
        if (!wildcardOnly.matches(className) || args.dropLast(1).lastOrNull() in noSpec) return false
        val members =
            body
                .substringBeforeLast('}')
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        return members.isEmpty() || "*;" in members || ("<fields>;" in members && "<methods>;" in members)
    }

    // The rule's origin, not the build machine: drop the extracted file's path, and trim the app's rules file to its
    // project-relative name.
    private fun normalise(source: String): String {
        val name = source.substringBefore(" (extracted file:")
        return if (name.endsWith("/app/proguard-rules.pro")) "app/proguard-rules.pro" else name
    }
}
