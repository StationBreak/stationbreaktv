// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic.architecture

/**
 * The durable write (stage, sync, rename) lives once, in `:data:settings` `StagedFiles.kt`. A second copy is a second
 * place to get the fsync order wrong, so any shipped source that holds both a sync primitive and a rename primitive is
 * refused elsewhere. A rename alone (a cache, or setting a file aside) or a sync alone is fine. A source-text tripwire
 * with comments removed.
 */
object StagedWriteRules {
    private const val ALLOWED_MODULE = ":data:settings"
    private const val ALLOWED_FILE = "StagedFiles.kt"

    // Every source set that ships (main, debug, release, flavours), not the tests.
    private val shippedSource = Regex("""^src/(?!test|androidTest|testFixtures)""")
    private val sync = Regex("""\bfd\.sync\(\)|\bgetFD\(\)\.sync\(\)|\.force\(true\)|\bOs\.fsync\(""")
    private val rename = Regex("""\brenameTo\(|\bFiles\.move\(|\bOs\.rename\(""")
    private val blockComment = Regex("""/\*[\s\S]*?\*/""")
    private val lineComment = Regex("""//[^\n]*""")

    /** One message per file: `path:line what`, at the first sync. [relativePath] is relative to the module. */
    fun violations(
        modulePath: String,
        relativePath: String,
        text: String,
    ): List<String> {
        if (!shippedSource.containsMatchIn(relativePath)) return emptyList()
        if (modulePath == ALLOWED_MODULE && relativePath.endsWith(ALLOWED_FILE)) return emptyList()
        // Comments become the same number of line breaks, so a match keeps its line number.
        val code =
            text
                .replace(blockComment) { match -> "\n".repeat(match.value.count { it == '\n' }) }
                .replace(lineComment, "")
        val first = sync.find(code) ?: return emptyList()
        if (!rename.containsMatchIn(code)) return emptyList()
        val line = code.substring(0, first.range.first).count { it == '\n' } + 1
        return listOf("$relativePath:$line syncs and renames a file; call StagedFiles instead of a second staged writer")
    }
}
