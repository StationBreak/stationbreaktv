// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic.architecture

/**
 * Store code stays in its flavor, and Pro has one SKU (A-REL-1, ADR-0023). A source-text tripwire over a module's
 * sources, with comments removed, so the KDoc that explains a ban does not trip it:
 * - `store-sdk-source`: Amazon's SDK namespace (`com.amazon.`) is named only by `:app`'s amazon source sets
 *   (`src/amazon*`, `src/testAmazon*`, `src/androidTestAmazon*`); Google Play's (`com.google.android.gms.`,
 *   `com.android.billingclient.`, `com.android.vending.`) nowhere. The shared code and the github flavor carry no
 *   proprietary SDK type.
 * - `sku-constant`: a declaration whose name contains `SKU` must be `const val PRO_SKU` in a file named `ProSku.kt`,
 *   declared once, and its value is written nowhere else in the module (the code names `PRO_SKU`, never the string).
 */
object FlavorIsolationRules {
    private const val APP = ":app"
    private const val SKU_FILE = "ProSku.kt"
    private const val SKU_NAME = "PRO_SKU"

    private val amazonSourceSet = Regex("""^src/(amazon|testAmazon|androidTestAmazon)""")
    private val testSourceSet = Regex("""^src/(test|androidTest|testFixtures)""")
    private val amazonNamespace = Regex("""\bcom\.amazon\.""")
    private val playNamespace = Regex("""\bcom\.(google\.android\.gms|android\.billingclient|android\.vending)\.""")
    private val skuDeclaration =
        Regex("""\b(?:const\s+)?va[lr]\s+(\w*SKU\w*)\b(?:\s*:\s*String)?\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
    private val blockComment = Regex("""/\*[\s\S]*?\*/""")
    private val lineComment = Regex("""//[^\n]*""")

    /** One message per problem: `path:line what`. [files] maps a path relative to the module (`/`) to its text. */
    fun violations(
        modulePath: String,
        files: Map<String, String>,
    ): List<String> {
        val code = files.mapValues { (_, text) -> withoutComments(text) }
        val found = mutableListOf<String>()
        for ((path, text) in code) {
            if (!path.startsWith("src/")) continue
            val mayNameAmazon = modulePath == APP && amazonSourceSet.containsMatchIn(path)
            if (!mayNameAmazon) {
                found +=
                    matches(path, text, amazonNamespace, "names Amazon's SDK outside :app's amazon source sets (A-REL-1)")
            }
            found += matches(path, text, playNamespace, "names Google Play Services or Play Billing (R-PLAT-3)")
        }
        found += skuViolations(code.filterKeys { it.startsWith("src/") && !testSourceSet.containsMatchIn(it) })
        return found
    }

    private fun skuViolations(code: Map<String, String>): List<String> {
        val declarations =
            code.flatMap { (path, text) ->
                skuDeclaration.findAll(text).map { Triple(path, lineOf(text, it.range.first), it.groupValues) }.toList()
            }
        val found = mutableListOf<String>()
        for ((path, line, groups) in declarations) {
            val name = groups[1]
            if (name != SKU_NAME || path.substringAfterLast('/') != SKU_FILE) {
                found += "$path:$line SKU `$name` must be `const val $SKU_NAME` in $SKU_FILE: Pro has one SKU"
            }
        }
        for ((path, line, _) in declarations.drop(1)) {
            found += "$path:$line a second SKU declaration: Pro has one SKU, declared once"
        }
        val values = declarations.map { it.third[2] }.filter { it.isNotEmpty() }.toSet()
        for ((path, text) in code) {
            for (value in values) {
                var from = text.indexOf("\"$value\"")
                while (from >= 0) {
                    val declaredHere = declarations.any { it.first == path && it.second == lineOf(text, from) }
                    if (!declaredHere) found += "$path:${lineOf(text, from)} writes the SKU's value: use $SKU_NAME"
                    from = text.indexOf("\"$value\"", from + 1)
                }
            }
        }
        return found
    }

    private fun matches(
        path: String,
        text: String,
        pattern: Regex,
        what: String,
    ): List<String> = pattern.findAll(text).map { "$path:${lineOf(text, it.range.first)} $what" }.toList()

    private fun lineOf(
        text: String,
        index: Int,
    ): Int = text.substring(0, index).count { it == '\n' } + 1

    // Comments become the same number of line breaks, so a match keeps its line number.
    private fun withoutComments(text: String): String =
        text
            .replace(blockComment) { match -> "\n".repeat(match.value.count { it == '\n' }) }
            .replace(lineComment, "")
}
