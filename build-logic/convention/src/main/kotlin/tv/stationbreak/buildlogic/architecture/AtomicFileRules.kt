// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic.architecture

/**
 * `android.util.AtomicFile` is not used in `:data:settings` (#818): on API 25 its `.bak` scheme can leave the only copy
 * truncated when a write fails, so the module writes `<name>.new`, syncs, renames and syncs the directory instead
 * (`StagedFiles`). A source-text tripwire over the module's shipped sources, with
 * comments removed, so the KDoc that explains the ban does not trip it.
 */
object AtomicFileRules {
    private const val MODULE = ":data:settings"

    // Every source set that ships (main, debug, release, flavours), not the tests.
    private val shippedSource = Regex("""^src/(?!test|androidTest|testFixtures)""")
    private val atomicFile = Regex("""\bAtomicFile\b""")
    private val blockComment = Regex("""/\*[\s\S]*?\*/""")
    private val lineComment = Regex("""//[^\n]*""")

    /** One message per match: `path:line what`. [relativePath] is relative to the module, with `/` separators. */
    fun violations(
        modulePath: String,
        relativePath: String,
        text: String,
    ): List<String> {
        if (modulePath != MODULE || !shippedSource.containsMatchIn(relativePath)) return emptyList()
        // Comments become the same number of line breaks, so a match keeps its line number.
        val code =
            text
                .replace(blockComment) { match -> "\n".repeat(match.value.count { it == '\n' }) }
                .replace(lineComment, "")
        return atomicFile
            .findAll(code)
            .map { code.substring(0, it.range.first).count { c -> c == '\n' } + 1 }
            .map { line -> "$relativePath:$line android.util.AtomicFile is not allowed in $MODULE" }
            .toList()
    }
}
