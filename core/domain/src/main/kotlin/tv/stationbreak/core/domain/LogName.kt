// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

/**
 * The class's own name for a log line: "Watching" for `LiveState.Watching`, and "" for an anonymous class.
 *
 * Read from the binary name, not `simpleName`: on Android `getSimpleName()` of a nested class needs its InnerClasses
 * attribute, which R8 drops from every class no keep rule names (a `-keepattributes` rule does not bring it back), so a
 * minified build would print "LiveState$Watching" (#441). Names stay readable there (`-dontobfuscate`).
 */
fun Class<*>.logName(): String {
    val last = name.substringAfterLast('.').substringAfterLast('$')
    return if (last.firstOrNull()?.isDigit() == true) "" else last
}

/** [logName] of this value's class. */
fun Any.logName(): String = javaClass.logName()
