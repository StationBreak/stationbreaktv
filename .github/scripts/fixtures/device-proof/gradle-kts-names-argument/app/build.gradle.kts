plugins { id("stationbreak.android-app") }
android {
    defaultConfig {
        testInstrumentationRunnerArguments["serverAddress"] = System.getenv("MEDIA_URL") ?: ""
    }
}
