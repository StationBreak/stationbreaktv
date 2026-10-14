#!/usr/bin/env bash
ARG_NAME=serverAddress
adb shell am instrument -w -e "$ARG_NAME" "$ADDR" runner
