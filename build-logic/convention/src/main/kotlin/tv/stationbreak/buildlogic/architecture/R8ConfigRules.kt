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
    private val wildcardOnly = Regex("[*.?]+")
    private val whitespaceRun = Regex("\\s+")
    private val separators = Regex("[\\s,]+")
    private val noSpec = setOf("extends", "implements")

    // `@interface` is the keyword for annotation types, not an annotation that narrows the rule (#851).
    private val classKeywords = setOf("class", "interface", "enum", "@interface", "!interface", "!enum")

    // Every class extends it, so a supertype pattern that matches it limits nothing (#851).
    private const val EVERY_CLASS = "java.lang.Object"
    private val sectionEnd = Regex("^# End of content from (.*)$")
    private val coordinateHeader = Regex("[^\\s/]+:[^\\s/]+:\\S+")

    // The keep an `-if` applies, written with whitespace or a comma after the option (`-keep,allowobfuscation`).
    private val ifKeep = Regex("\\s(-keep\\w*[\\s,].*)", RegexOption.DOT_MATCHES_ALL)

    // R8 ends an option at whitespace or at `}` before the next one (checked with 9.3.16: `-keep class a.B {}-dontshrink`
    // and `{*;}-keep class **{*;}` both take effect). A `-` after a letter, `_`, `$` or `.` is part of a name; after anything
    // else (a digit, a quote, `>`, `,`, `)`) it may start an option, so it splits: over-splitting only checks more (#933).
    private val nextOption = Regex("(?<![A-Za-z_$.])(?=-[a-z])|(?<=\\})(?=-[a-z])")
    private val backreference = Regex("<(\\d+)>")
    private val leadingWildcards = Regex("^[*?.]+")
    private val placeholders = Regex("<init>|<fields>|<methods>|\\(\\.\\.\\.\\)")
    private val memberSeparators = Regex("[\\s;()\\[\\],]+")
    private val memberKeywords =
        setOf(
            "public",
            "private",
            "protected",
            "static",
            "final",
            "abstract",
            "native",
            "synchronized",
            "volatile",
            "transient",
            "strictfp",
            "void",
            "boolean",
            "byte",
            "char",
            "short",
            "int",
            "long",
            "float",
            "double",
        )
    private val wildcard = Regex("\\*\\*\\*|\\*\\*|\\*|\\?")
    private val keepFamily =
        setOf(
            "-keep",
            "-keepnames",
            "-keepclassmembers",
            "-keepclassmembernames",
            "-keepclasseswithmembers",
            "-keepclasseswithmembernames",
        )
    // Includes and file options read or write files; no library needs them, and the app's own rules may (#933).
    private val fileOptions =
        setOf(
            "-include",
            "-basedirectory",
            "-printconfiguration",
            "-printmapping",
            "-printseeds",
            "-printusage",
            "-dump",
            "-applymapping",
            "-obfuscationdictionary",
            "-classobfuscationdictionary",
            "-packageobfuscationdictionary",
        )
    private val alwaysForbidden =
        setOf(
            "-assumenosideeffects",
            "-assumevalues",
            "-dontobfuscate",
            "-dontoptimize",
            "-dontshrink",
            "-ignorewarnings",
        ) + fileOptions

    // The fail-closed backstop (#933): a forbidden option anywhere in a line, after a quote or glued to something that
    // the splitting did not understand. In rule code any `-` starts one; in comment text a `-` after a letter is a
    // hyphen (`re-include`).
    private val optionNames = alwaysForbidden.joinToString("|") { Regex.escape(it.removePrefix("-")) }
    private val inCode = Regex("(?<![_$.])-($optionNames)\\b")
    private val inComment = Regex("(?<![A-Za-z_$.])-($optionNames)\\b")
    private const val APP_RULES = "app/proguard-rules.pro"
    private const val MAX_RULE = 200

    // The section headers AGP prints (checked against a real configuration.txt): a Maven coordinate, the Android Gradle
    // plugin's own file, a local project, an unknown source, and the app's own rules file by its exact path. An
    // included file prints as a section named by the path as written, which is none of these.
    private val extracted = "\\(extracted file: .+\\)"
    private val libraryHeaders =
        listOf(
            Regex("[^\\s/]+:[^\\s/]+:\\S+ $extracted"),
            Regex("Android Gradle plugin \\S+ $extracted"),
            Regex("Local project :\\S+ $extracted"),
            Regex("<unknown>"),
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
            "-dontobfuscate" to listOf(APP_RULES),
        ) + fileOptions.associateWith { listOf(APP_RULES) }

    /** [appRules] is the app's own rules file, as AGP prints its header: the only section the app's allowances reach. */
    fun violations(
        configuration: String,
        appRules: String,
    ): List<R8Violation> {
        val found = mutableListOf<R8Violation>()
        val seen = mutableSetOf<String>()
        var source = "unknown"
        var open: String? = null
        var directive = StringBuilder()

        // An `-if` whose keep is on a later line (the usual layout) applies to the next directive if it is a keep (#909).
        var pendingIf: String? = null

        // Options the normal scan flagged, and what the backstop saw anywhere: (source, option) pairs.
        val reported = mutableSetOf<Pair<String, String>>()
        val backstop = mutableListOf<Triple<String, String, String>>()

        fun flush() {
            var text = directive.toString().trim()
            directive = StringBuilder()
            if (text.isEmpty()) return
            val condition = pendingIf
            pendingIf = null
            if (condition != null && text.split(separators).first() in keepFamily) {
                text = "$condition\n$text"
            } else if (text.split(separators).first() == "-if" && ifKeep.find(text) == null) {
                pendingIf = text
                return
            }
            if (!forbidden(text)) return
            val option = text.split(separators).first()
            if (allowed[option]?.any { source.startsWith(it) } != true) {
                found += R8Violation(text.lines().first().trim(), source)
                reported += source to option
            }
        }

        // `@file` reads a file of options. It is that only where a directive starts: `@a.B` after a class keyword or
        // inside a body is an annotation (`-keep @a.B class *`).
        fun startsFileInclude(): Boolean {
            val text = directive.toString().trim()
            if (text.isEmpty()) return true
            if (text.count { it == '{' } != text.count { it == '}' }) return false
            val tokens = text.split(separators)
            return tokens.first() !in keepFamily && tokens.first() != "-if" || tokens.any { it in classKeywords }
        }
        for (raw in configuration.lines()) {
            val whole = raw.trim()
            val header = sectionHeader.matchEntire(whole)
            val end = sectionEnd.matchEntire(whole)
            // R8 reads `#` as the start of a comment anywhere on a line, so `-keep class ** # @a.B` keeps every class
            // (checked with R8's -printseeds): the rule is read without it (#851). A `#` inside double quotes is text
            // (`-printconfiguration "x#y" -dontshrink` turns shrinking off, checked with 9.3.16) (#933).
            val line = if (whole.startsWith("#")) whole else uncommented(whole)
            if (header == null && end == null && source != APP_RULES) {
                // Read quote-blind: the text before the first `#`, then the whole line (#933).
                val code = whole.substringBefore('#')
                val hits = inCode.findAll(code).toList() + inComment.findAll(whole).toList()
                hits.forEach { backstop += Triple(source, it.groupValues[0], whole) }
                // No library rule needs a quote, and a quote is how a `#` or an option gets hidden from the scan.
                if (code.any { it == '"' || it == '\'' }) backstop += Triple(source, "a quote in a rule line", whole)
            }
            when {
                end != null -> {
                    flush()
                    pendingIf = null
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
                    pendingIf = null
                    val text = header.groupValues[1]
                    val shapeOk = text == appRules || libraryHeaders.any { it.matches(text) }
                    // A header of an unknown shape never reaches the app's allowance, whatever its text says.
                    val name = if (shapeOk || coordinateHeader.matches(text)) normalise(text, appRules) else "unknown header: $text"
                    if (open != null) found += R8Violation("a section header inside an open section", name)
                    open = text
                    source = name
                    // A rule file can contain a comment that looks like a header, to borrow another source's allowance.
                    // AGP always prints "(extracted file: …)" after a coordinate, and each file once (a library may have several files).
                    if (!seen.add(text)) {
                        found += R8Violation("a second section header for this source file", source)
                    } else if ("(extracted file:" !in text && coordinateHeader.matches(text)) {
                        found += R8Violation("a section header without an extracted file", source)
                    } else if (!shapeOk) {
                        found += R8Violation("a section header of an unknown shape", source)
                    }
                }

                line.startsWith("#") || line.isEmpty() -> {}

                // R8 reads `-keep class a.B -dontshrink` as two options (checked with 9.3.16), so a line is split at
                // each whitespace followed by an option and every part is checked (#933). Over-splitting only checks more.
                else -> {
                    for ((i, part) in line.split(nextOption).map { it.trim() }.filter { it.isNotEmpty() }.withIndex()) {
                        if (part.startsWith("@") && part.length > 1 && startsFileInclude() && source != APP_RULES) {
                            backstop += Triple(source, "@file", whole)
                        }
                        if (i > 0 && part.split(separators).first() in keepFamily && directive.startsWith("-if") && ifKeep.find(directive) == null) {
                            // An `-if` and its own keep stay one directive, as when they are on two lines (#909).
                            directive.append(' ').append(part)
                        } else if (part.startsWith("-")) {
                            flush()
                            directive.append(part)
                        } else {
                            // A rule's member lines and closing brace, or arguments on the line after the option.
                            directive.append('\n').append(part)
                        }
                    }
                }
            }
        }
        flush()
        for ((hitSource, option, rawLine) in backstop) {
            val trusted = hitSource == APP_RULES || allowed[option]?.any { hitSource.startsWith(it) } == true
            val violation = R8Violation(rawLine.take(MAX_RULE), hitSource)
            if (!trusted && reported.add(hitSource to option) && violation !in found) found += violation
        }
        if (open != null) found += R8Violation("a section that is never closed", source)
        return found
    }

    private fun uncommented(line: String): String {
        var quoted = false
        for ((i, c) in line.withIndex()) {
            if (c == '"') quoted = !quoted else if (c == '#' && !quoted) return line.substring(0, i).trim()
        }
        return line
    }

    private fun forbidden(directive: String): Boolean {
        val tokens = directive.substringBefore('{').split(separators).filter { it.isNotEmpty() }
        val option = tokens.first()
        val args = tokens.drop(1)
        return when {
            option in alwaysForbidden -> {
                true
            }

            option == "-dontwarn" || option == "-keeppackagenames" -> {
                broad(args)
            }

            // `-if class A -keep class ** { *; }`: the embedded keep is the rule, with its `<n>` read through the
            // condition (#909).
            option == "-if" -> {
                ifKeep.find(directive)?.let {
                    forbidden(resolved(directive.substring(0, it.range.first), it.groupValues[1]))
                } == true
            }

            option in keepFamily -> {
                keepsEverything(option, args, directive.substringAfter('{', ""))
            }

            else -> {
                false
            }
        }
    }

    // `-keep… class ** { *; }`. For -keep and -keepnames (they keep the class itself) a broad class list is enough,
    // whatever the members. For the member families it needs `*;` (or all fields and methods), or no members at all.
    // Specific member lines (`native <methods>;`) pass. An annotation narrows the rule only if it names one type.
    private fun keepsEverything(
        option: String,
        args: List<String>,
        body: String,
    ): Boolean {
        // Only an annotation naming exactly one type narrows the rule. A bare `@` (whitespace before the name) does not.
        val narrowed =
            args.any {
                it.length > 1 && it.startsWith("@") && it !in classKeywords && '*' !in it && '?' !in it &&
                    it != "@kotlin.Metadata"
            } && "@" !in args
        if (narrowed) return false
        val classKeyword = args.indexOfFirst { it in classKeywords }
        val superAt = args.indexOfFirst { it in noSpec }
        val names = args.subList(classKeyword + 1, if (superAt >= 0) superAt else args.size)
        // Both limit the rule: it is broad only when the class names and, if there is one, the supertype are (#851).
        val supertypes = if (superAt >= 0) args.drop(superAt + 1) else null
        val anySupertype = supertypes == null || broad(supertypes) || supertypes.any { matchesEveryClass(it) }
        // `**$*` or `**Foo` (every package) is broad for every option; on a member rule the members then decide
        // (kotlinx's `**$$serializer { private ** descriptor; }` passes) (#909, #933).
        if (!(broad(names) || names.any { anyPackageSuffix(it) }) || !anySupertype) return false
        if (option == "-keep" || option == "-keepnames") return true
        val members =
            body
                .substringBeforeLast('}')
                .split(';', '\n')
                .map { it.trim().replace(whitespaceRun, " ") }
                .filter { it.isNotEmpty() }
        // `*`, `* *` and `*** *` are every member; several members may share a line (#933).
        val everything = members.any { member -> member.split(' ').all { wildcardOnly.matches(it) } }
        val fields = "<fields>" in members || members.any { '(' !in it && namesNothing(it) }
        val methods = "<methods>" in members || members.any { allMethods(it) }
        return members.isEmpty() || everything || (fields && methods)
    }

    // `*(...)`, `* *(...)`, `*** *(...)`, with modifiers or primitive types before the name: every method (#933).
    private fun allMethods(member: String): Boolean {
        if (member.substringAfter('(', "").substringBefore(')').trim() != "...") return false
        val head = member.substringBefore('(').split(' ').filter { it.isNotEmpty() }
        return head.isNotEmpty() && head.all { it in memberKeywords || wildcardOnly.matches(it) }
    }

    // A name whose leading wildcards reach every package and don't stop at a package boundary names classes in every
    // package: `**$*`, `**Foo`, `*.**$*`, `?**$*`, `**.*Foo`. `**.R$*` (androidx) and `**.BuildConfig` end the run at a
    // dot, so they name one class per package and are left alone (#909).
    private fun anyPackageSuffix(name: String): Boolean {
        val run = leadingWildcards.find(name)?.value ?: return false
        return "**" in run && !run.endsWith('.') && !wildcardOnly.matches(name)
    }

    // A member line that names nothing: only modifiers, primitive types, wildcards and `<init>`/`<fields>`/`<methods>`.
    // Nearly every class has such a member (`*;`, `<init>(...);`), so it doesn't narrow an `-if` condition (#909).
    private fun namesNothing(member: String): Boolean =
        member
            .replace(placeholders, " ")
            .split(memberSeparators)
            .filter { it.isNotEmpty() }
            .all { it in memberKeywords || wildcardOnly.matches(it) }

    // The keep of an `-if`, with each `<n>` replaced by the condition's n-th wildcard when the condition matches every
    // class: then `<n>` can be any class name. A condition narrowed by an annotation, a supertype, a named class or a
    // member that names something (`CREATOR`, `@retrofit2.http.*`) only ever matches that set, so its `<n>` stay as they
    // are and are not broad (#909). A `<n>` with no wildcard behind it is read as `**`.
    private fun resolved(
        condition: String,
        keep: String,
    ): String {
        val args =
            condition
                .substringBefore('{')
                .split(separators)
                .filter { it.isNotEmpty() }
                .drop(1)
        if (!keepsEverything("-keep", args, "")) return keep
        if ('{' in condition) {
            val members = condition.substringAfter('{').substringBeforeLast('}').split(';', '\n')
            if (!members.all { namesNothing(it) }) return keep
            // Members that every class has don't narrow it; R8 also numbers their wildcards, so read every <n> as any
            // class rather than count them.
            return backreference.replace(keep, "**")
        }
        val wildcards = args.flatMap { arg -> wildcard.findAll(arg).map { it.value }.toList() }
        return backreference.replace(keep) { wildcards.getOrNull(it.groupValues[1].toInt() - 1) ?: "**" }
    }

    // A class list matches broadly when a positive name is wildcards only, or when its last name is a negation: R8 takes
    // the first entry that matches and, when none does, accepts if the last entry was negated, so `a.B,!c.D` keeps every
    // class but `c.D` (checked with R8's -printseeds). `!**,a.B` keeps only `a.B`.
    private fun broad(names: List<String>): Boolean =
        names.isEmpty() || names.any { wildcardOnly.matches(it) } || names.last().startsWith("!")

    // A supertype pattern that matches java.lang.Object (`**.Object`, `java.**`, `java.lang.Obj*`): `**` is any text,
    // `*` any text without a dot, `?` one character that is not a dot.
    private fun matchesEveryClass(pattern: String): Boolean {
        if (pattern.startsWith("!")) return false
        val regex =
            Regex("\\*\\*|\\*|\\?|[^*?]+").findAll(pattern).joinToString("") {
                when (it.value) {
                    "**" -> ".*"
                    "*" -> "[^.]*"
                    "?" -> "[^.]"
                    else -> Regex.escape(it.value)
                }
            }
        return Regex(regex).matches(EVERY_CLASS)
    }

    // The rule's origin, not the build machine: drop the extracted file's path, and trim the app's rules file to its
    // project-relative name.
    private fun normalise(
        source: String,
        appRules: String,
    ): String {
        val name = source.substringBefore(" (extracted file:")
        return if (source == appRules) APP_RULES else name
    }
}
