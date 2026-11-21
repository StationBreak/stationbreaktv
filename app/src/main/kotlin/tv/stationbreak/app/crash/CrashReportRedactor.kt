// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.crash

/**
 * Takes what must not leave the TV out of a crash report's text (#96): sign-in tokens and keys, URLs, server host
 * names, IP addresses, e-mail addresses and the 32-character ids the server hands out. The report is a stack trace,
 * so the risk is in exception messages (`Unable to resolve host "nas.example"`, a URL an HTTP error quotes, a header
 * an error prints); a stack frame (`at pkg.Class.method(File.kt:12)`) and a class name carry code, not data, and are
 * kept so the report stays useful.
 *
 * Anything inside quotes, a file path, a distinguished name (`CN=...`) and what a parse error quotes of the server's
 * JSON also go: they are where a title, a channel or user name or a library path would turn up.
 *
 * It errs on the side of removing too much: anything shaped like a host name that is not a class name goes, and so
 * does an opaque run of 24 or more letters, digits, `_` and `-` that has both a letter and a digit.
 */
internal object CrashReportRedactor {
    const val SECRET = "[secret]"
    const val URL = "[url]"
    const val HOST = "[host]"
    const val ADDRESS = "[address]"
    const val EMAIL = "[email]"
    const val ID = "[id]"
    const val TEXT = "[text]"
    const val PATH = "[path]"

    // `at pkg.Class.method(File.kt:12)`: only code, so it is left alone.
    private val frame = Regex("""at [^\s()]+\([\w$.<>-]+(?: Source| Method)?(?::\d+)?\)""")

    private val authorization = Regex("""(?i)\b((?:x-emby-|x-mediabrowser-)?authorization)\b\s*[:=].*""")
    private val url = Regex("""(?i)\b[a-z][a-z0-9+.-]*://[^\s"'<>]+""")
    private val keyed =
        Regex(
            """(?i)\b(api[_-]?key|access[_-]?token|auth[_-]?token|token|password|passwd|pw|secret|x-emby-token)\b""" +
                """(\s*["']?\s*[=:]\s*)("[^"]*"|'[^']*'|[^\s&,;"']+)""",
        )
    private val email = Regex("""[\w.+-]+@[\w-]+(?:\.[\w-]+)+""")
    private val guid = Regex("""(?i)\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b""")
    private val hex32 = Regex("""(?i)(?<![0-9a-z])[0-9a-f]{32}(?![0-9a-z])""")
    private val ipv4 = Regex("""(?<![\w.])\d{1,3}(?:\.\d{1,3}){3}(?::\d{1,5})?(?!\w|\.\d)""")
    private val ipv6 =
        Regex(
            """\[[0-9a-fA-F:.]+](?::\d{1,5})?|(?<![\w:])(?:[0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}(?![\w:])""" +
                """|(?<![\w:])(?:[0-9a-fA-F]{0,4}:){1,6}:[0-9a-fA-F]{0,4}(?![\w:])""",
        )

    // What a response-parse error quotes of the server's JSON (titles, paths, user names): all of it goes.
    private val jsonExcerpt = Regex("""(?i)(JSON input:?).*""")
    private val quoted = Regex(""""[^"\n]+"|'[^'\n]+'""")
    private val path = Regex("""(?<![\w:/.])/(?:[^\s/"'\[\]]+/)+[^\s"'\[\]]*""")
    private val distinguishedName = Regex("""(?i)\b(CN|O|OU|C|L|ST|DC|UID)=[^,\n]+""")
    private val bareHost =
        Regex("""(?i)(\bcommunication to\s+)[^\s,:/\[]+|(\bhostname\s+)(?!\[)[^\s,:/\[]+(?=\s+not verified)""")
    private val namedHost =
        Regex("""(?i)(\bhost(?:name)?\s*[=:]\s*|\bresolve host\s+|\bconnect(?:ing)? to\s+/?)("?)[^\s",:/\[]+\2""")
    private val dottedHost =
        Regex("""(?<![\w.-])(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)+[A-Za-z][A-Za-z0-9-]+(?![\w-])(?::\d{1,5})?""")
    private val opaque =
        Regex("""(?<![\w-])(?=[A-Za-z0-9_-]*\d)(?=[A-Za-z0-9_-]*[A-Za-z])[A-Za-z0-9_-]{24,}(?![\w-])""")

    // Packages whose names are code, not hosts; a token under one counts as a class name when its last part is one.
    private val codePackages =
        listOf(
            "tv.stationbreak.",
            "java.",
            "javax.",
            "kotlin.",
            "kotlinx.",
            "android.",
            "androidx.",
            "okhttp3.",
            "okio.",
            "org.jellyfin.",
            "org.acra.",
            "org.json.",
            "dagger.",
            "com.android.",
            "com.google.",
            "libcore.",
            "dalvik.",
        )

    fun redact(text: String): String =
        text
            .split("\n")
            .joinToString("\n") { line -> if (frame.matches(line.trim())) line else redactLine(line) }

    private fun redactLine(line: String): String {
        var out = line
        out = jsonExcerpt.replace(out) { "${it.groupValues[1]} $TEXT" }
        out = authorization.replace(out) { "${it.groupValues[1]}: $SECRET" }
        out = url.replace(out, URL)
        out = keyed.replace(out) { "${it.groupValues[1]}${it.groupValues[2]}$SECRET" }
        out = email.replace(out, EMAIL)
        out = guid.replace(out, ID)
        out = hex32.replace(out, ID)
        out = ipv4.replace(out, ADDRESS)
        out = ipv6.replace(out, ADDRESS)
        out = bareHost.replace(out) { "${it.groupValues[1]}${it.groupValues[2]}$HOST" }
        out = namedHost.replace(out) { "${it.groupValues[1]}$HOST" }
        out = dottedHost.replace(out) { if (isClassName(it.value)) it.value else HOST }
        out = distinguishedName.replace(out) { "${it.groupValues[1]}=$TEXT" }
        out = path.replace(out, PATH)
        out = quoted.replace(out) { "${it.value.first()}$TEXT${it.value.last()}" }
        return opaque.replace(out, SECRET)
    }

    // `java.net.UnknownHostException`, `tv.stationbreak.app.Foo$Bar`: a code package and a last part that starts with
    // a capital and is not all capitals (`.COM` is a top-level domain).
    private fun isClassName(token: String): Boolean {
        val last = token.substringAfterLast('.')
        val classLike = last.first().isUpperCase() && last.any { it.isLowerCase() || it.isDigit() || it == '$' }
        return classLike && codePackages.any { token.startsWith(it) }
    }
}
