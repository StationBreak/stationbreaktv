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
    private val separators = Regex("[\\s,]+")
    private val noSpec = setOf("extends", "implements")
    private val classKeywords = setOf("class", "interface", "enum", "!interface", "!enum")
    private val sectionEnd = Regex("^# End of content from (.*)$")
    private val coordinateHeader = Regex("[^\\s/]+:[^\\s/]+:\\S+")
    private val ifKeep = Regex("\\s(-keep\\w*\\s.*)", RegexOption.DOT_MATCHES_ALL)
    private val keepFamily =
        setOf(
            "-keep",
            "-keepnames",
            "-keepclassmembers",
            "-keepclassmembernames",
            "-keepclasseswithmembers",
            "-keepclasseswithmembernames",
        )
    private val alwaysForbidden =
        setOf(
            "-assumenosideeffects",
            "-assumevalues",
            "-dontobfuscate",
            "-dontoptimize",
            "-dontshrink",
            "-ignorewarnings",
        )

    // Rules that are fine from a named source: a Maven "group:artifact:" prefix, or the app's own rules file. Each is
    // what the build prints today: a first-party library's own debug-stripping or constant-folding rule (Compose's
    // `-assumenosideeffects class *` only matches its own marked helpers). A new source for the same rule fails until
    // someone reads the rule and adds the source here with the reason.
    // The trust covers each allowed library as a whole, so its future rules for the same option pass too: only Google
    // (androidx) and JetBrains (kotlinx) groups are listed, so that is a bet on those two, not on the rule text.
    private val allowed: Map<String, List<String>> =
        mapOf(
            "-assumenosideeffects" to
                listOf(
                    "androidx.compose.animation:animation-android:",
                    "androidx.compose.runtime:runtime-android:",
                    "androidx.startup:startup-runtime:",
                    // DataStore (#838): strips its internal SharedCounter$Factory; androidx group.
                    "androidx.datastore:datastore-core-android:",
                    "org.jetbrains.kotlinx:kotlinx-coroutines-android:",
                ),
            "-assumevalues" to
                listOf(
                    "androidx.compose.animation:animation-android:",
                    "androidx.compose.runtime:runtime-android:",
                ),
            // Deliberate (#331): logs print class simple names.
            "-dontobfuscate" to listOf("app/proguard-rules.pro"),
        )

    fun violations(configuration: String): List<R8Violation> {
        val found = mutableListOf<R8Violation>()
        val seen = mutableSetOf<String>()
        var source = "unknown"
        var open: String? = null
        var directive = StringBuilder()

        fun flush() {
            val text = directive.toString().trim()
            directive = StringBuilder()
            if (text.isEmpty() || !forbidden(text)) return
            val option = text.split(separators).first()
            if (allowed[option]?.any { source.startsWith(it) } != true) {
                found += R8Violation(text.lines().first().trim(), source)
            }
        }
        for (raw in configuration.lines()) {
            val line = raw.trim()
            val header = sectionHeader.matchEntire(line)
            val end = sectionEnd.matchEntire(line)
            when {
                end != null -> {
                    flush()
                    // AGP closes every section with its own end marker, so a copy of the header in a rule file
                    // (a spoof) shows up as a header inside an open section, or an end with nothing open.
                    if (end.groupValues[1] != open) {
                        found += R8Violation("an end marker that does not close the open section", source)
                    }
                    open = null
                    source = "unknown"
                }

                header != null -> {
                    flush()
                    val text = header.groupValues[1]
                    if (open != null) found += R8Violation("a section header inside an open section", normalise(text))
                    open = text
                    source = normalise(text)
                    // A rule file can contain a comment that looks like a header, to borrow another source's allowance.
                    // AGP always prints "(extracted file: …)" after a coordinate, and each file once (a library may have several files).
                    if (!seen.add(text)) {
                        found += R8Violation("a second section header for this source file", source)
                    } else if ("(extracted file:" !in text && coordinateHeader.matches(text)) {
                        found += R8Violation("a section header without an extracted file", source)
                    }
                }

                line.startsWith("#") || line.isEmpty() -> {}

                line.startsWith("-") -> {
                    flush()
                    directive.append(line)
                }

                // A rule's member lines and closing brace, or arguments on the line after the option.
                else -> {
                    directive.append('\n').append(line)
                }
            }
        }
        flush()
        if (open != null) found += R8Violation("a section that is never closed", source)
        return found
    }

    private fun forbidden(directive: String): Boolean {
        val tokens = directive.substringBefore('{').split(separators).filter { it.isNotEmpty() }
        val option = tokens.first()
        val args = tokens.drop(1)
        return when {
            option in alwaysForbidden -> true

            option == "-dontwarn" || option == "-keeppackagenames" -> args.isEmpty() || args.any { wildcardOnly.matches(it) }

            // `-if class A -keep class ** { *; }` on one line: the embedded keep is the rule.
            option == "-if" -> ifKeep.find(directive)?.let { forbidden(it.groupValues[1]) } == true

            option in keepFamily -> keepsEverything(option, args, directive.substringAfter('{', ""))

            else -> false
        }
    }

    // `-keep… class ** { *; }`. For -keep and -keepnames (they keep the class itself) a wildcard-only class name is
    // enough, whatever the members. For the member families it needs `*;` (or all fields and methods), or no members at
    // all. Specific member lines (`native <methods>;`) pass. An annotation narrows the rule only if it names one type.
    private fun keepsEverything(
        option: String,
        args: List<String>,
        body: String,
    ): Boolean {
        // Only an annotation naming exactly one type narrows the rule.
        val narrowed = args.any { it.startsWith("@") && '*' !in it && '?' !in it && it != "@kotlin.Metadata" }
        if (narrowed) return false
        val classKeyword = args.indexOfFirst { it in classKeywords }
        val superAt = args.indexOfFirst { it in noSpec }
        val names = args.subList(classKeyword + 1, if (superAt >= 0) superAt else args.size)
        val broad = { name: String -> wildcardOnly.matches(name) || name.startsWith("!") }
        // With extends/implements the supertype is what limits the rule; otherwise every listed name does.
        val candidate = if (superAt >= 0) args.drop(superAt + 1).any(broad) else names.any(broad)
        if (!candidate) return false
        if (option == "-keep" || option == "-keepnames") return true
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
