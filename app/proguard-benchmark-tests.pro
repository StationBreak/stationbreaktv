# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
#
# Extra R8 rules for the benchmark APK only when it is built for the security instrumented tests (#436,
# `-Pstationbreak.testBuildType=benchmark`). Never part of the release build, nor of the benchmark APK the device runs
# measure. The test APK runs inside the app's process and finds these classes in the app APK, so they must survive
# the app's own shrinking although the shipped app never calls them:
#
# - Hilt's runtime, which the Hilt test runner (HiltTestApplication) needs: without it the process crashes at start
#   (ClassNotFoundException: dagger.hilt.internal.GeneratedComponentManagerHolder).
# - The libraries the test APK shares with the app (Kotlin, coroutines, OkHttp and okio for MockWebServer).
# - The public API of the app classes the five security tests and their fakes call directly.
#
# What stays real: R8 still optimises the bodies of every method, and shrinks and optimises all the app's other
# code, the trust and secret-storage paths the tests drive included.

-keep class dagger.** { *; }
-keep class javax.inject.** { *; }
-keep class hilt_aggregated_deps.** { *; }
-keep class **_HiltModules* { *; }
-keep class **_Factory { *; }
-keep class **_MembersInjector { *; }
-keep class **_GeneratedInjector { *; }
-keep @dagger.Module class * { *; }
-keep @dagger.hilt.InstallIn class * { *; }

-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

-keep class tv.stationbreak.core.domain.** { public *; }
-keep class tv.stationbreak.core.model.** { public *; }
-keep class tv.stationbreak.app.net.AndroidSystemTrust { public *; }
-keep class tv.stationbreak.data.jellyfin.AdapterLog { public *; }
-keep class tv.stationbreak.data.jellyfin.RedactingAdapterLog { public *; }
-keep class tv.stationbreak.data.jellyfin.JellyfinMediaServer { public *; }
-keep class tv.stationbreak.data.jellyfin.trust.PinnedHttpClients { public *; }
-keep class tv.stationbreak.data.settings.keystore.KeystoreSecretStore { public *; }
-keep class tv.stationbreak.data.library.FirstSyncRunner { public *; }
-keep class tv.stationbreak.data.library.RoomLibraryRepository { public *; }
-keep class tv.stationbreak.data.library.SyncVerdict { *; }
