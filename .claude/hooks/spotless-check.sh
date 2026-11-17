#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
#
# Claude Code Stop hook. Non-destructive: runs spotlessCheck (ktlint + the SPDX header),
# never spotlessApply. Skips when this checkout has no changed Kotlin or Gradle Kotlin
# file, so a docs-only turn does not start Gradle.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

changed="$(
  {
    git diff --name-only HEAD -- '*.kt' '*.kts'
    git ls-files --others --exclude-standard -- '*.kt' '*.kts'
  } | sort -u
)"
if [ -z "$changed" ]; then
  exit 0
fi

# CONTRIBUTING.md: the build needs JDK 17. Prefer a JDK 17 already on the machine
# when JAVA_HOME is unset or points at something else, so the hook matches CI.
if ! "${JAVA_HOME:-}/bin/java" -version 2>&1 | grep -q 'version "17\.'; then
  for candidate in \
    "${HOME}/.local/share/stationbreak-toolchain/jdk-17" \
    /usr/lib/jvm/java-17-openjdk-amd64 \
    /usr/lib/jvm/java-17-openjdk
  do
    if [ -x "$candidate/bin/java" ]; then
      export JAVA_HOME="$candidate"
      export PATH="$JAVA_HOME/bin:$PATH"
      break
    fi
  done
fi

if ! ./gradlew spotlessCheck --quiet; then
  echo "spotlessCheck failed. Run ./gradlew spotlessApply and keep the result. Do not skip the check." >&2
  exit 2
fi
