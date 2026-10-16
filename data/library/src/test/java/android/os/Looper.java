// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package android.os;

/**
 * A stand-in for the stubbed android.jar's Looper, for Room's unit tests on the JVM (spec 06). Room asks whether the
 * caller is on the main thread before it starts a transaction; the stubbed class answers null and Room throws. This one has
 * a main thread that no test runs on, so every test is "off the main thread", as the app's database calls are. It sits first
 * on the test classpath, ahead of android.jar, and is never part of an APK.
 */
public final class Looper {
    private static final Thread MAIN = new Thread(() -> { }, "fake-main");
    private static final Looper INSTANCE = new Looper();

    private Looper() { }

    public static Looper getMainLooper() {
        return INSTANCE;
    }

    public Thread getThread() {
        return MAIN;
    }
}
