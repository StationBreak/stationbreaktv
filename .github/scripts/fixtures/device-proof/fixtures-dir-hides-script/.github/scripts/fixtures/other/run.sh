#!/usr/bin/env bash
adb shell am instrument -w -e serverAddress "$ADDR" runner
