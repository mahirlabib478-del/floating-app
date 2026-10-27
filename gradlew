#!/usr/bin/env sh
set -eu

GRADLE_VERSION=8.10.2
BASE="$HOME/.gradle/floatclip"
DIST="$BASE/gradle-$GRADLE_VERSION"

if command -v gradle >/dev/null 2>&1; then
    exec gradle "$@"
fi

if [ ! -x "$DIST/bin/gradle" ]; then
    mkdir -p "$BASE"
    ZIP="$BASE/gradle-$GRADLE_VERSION-bin.zip"
    if ! command -v curl >/dev/null 2>&1 || ! command -v unzip >/dev/null 2>&1; then
        echo "Please install Gradle 8.10.2, curl, and unzip, then retry." >&2
        exit 1
    fi
    curl -fL --retry 3 "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -o "$ZIP"
    unzip -q -o "$ZIP" -d "$BASE"
fi

exec "$DIST/bin/gradle" "$@"
