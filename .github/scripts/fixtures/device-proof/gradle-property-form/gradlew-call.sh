#!/usr/bin/env bash
./gradlew connectedGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.serverAddress="$ADDR"
