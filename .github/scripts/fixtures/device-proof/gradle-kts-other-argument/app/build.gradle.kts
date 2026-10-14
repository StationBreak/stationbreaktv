plugins { id("stationbreak.android-app") }
android {
    defaultConfig {
        testInstrumentationRunnerArguments["notPackage"] = "tv.stationbreak.app.slow"
    }
}
