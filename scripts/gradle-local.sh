#!/bin/sh
# Keep build caches and Android user files inside this workspace.
set -eu
PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$PROJECT_DIR"
export GRADLE_USER_HOME="$PROJECT_DIR/.gradle-home"
export ANDROID_USER_HOME="$PROJECT_DIR/.android-user"
if [ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ]; then
    JAVA_HOME=$(/usr/libexec/java_home -v 17)
    export JAVA_HOME
fi
exec ./gradlew "$@"
