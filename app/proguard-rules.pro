# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
#
# R8 rules for the release build (and benchmark, which inits from it). The libraries (Hilt, Room, Media3, OkHttp,
# kotlinx-serialization, the Jellyfin SDK, AndroidX) ship their own consumer rules; add a rule here only where the
# minified build is shown to break without it, and say why.

# Class and method names stay readable: logs and diagnostics print class simple names (#331), and a stack trace names
# its frames. Shrinking and optimisation still apply, so a trace's line numbers and inlined frames need the build's
# mapping.txt (R8 writes instruction offsets for lines and a map ID for the source file).
-dontobfuscate

# core:domain's ThreadCpu reads the JVM's thread CPU time on the JVM and catches the LinkageError where
# java.lang.management is absent, as on Android; the app passes Android's own clock (BuildProbes).
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.ThreadMXBean
