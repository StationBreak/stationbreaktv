# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
#
# R8 rules for the instrumented-test APK when the tests run against the minified benchmark build (#436,
# `-Pstationbreak.testBuildType=benchmark`). Never part of the app's own configuration. Each line names one
# annotation that a test library refers to and does not ship; R8 stops on it otherwise (missing_rules.txt).

# Hilt's testing runtime (OnComponentReadyRunner).
-dontwarn com.google.auto.value.AutoValue
# androidx.test's runner.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.MustBeClosed
# okhttp-tls, used by the pinned-trust tests.
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
