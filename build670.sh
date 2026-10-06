#!/usr/bin/env bash
set -euo pipefail
cd /home/simon/simon-voice-ime-wt671-int
export JAVA_HOME=/home/simon/.local/jdk/jdk-17.0.2
export GRADLE_USER_HOME="/home/simon/simon-voice-ime-wt671/out/gradle-home"
export TMPDIR="$PWD/out/tmp"
export JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$TMPDIR -Duser.home=$PWD/out/tool-home"
export ANDROID_USER_HOME="$PWD/out/tool-home/.android"
mkdir -p "$TMPDIR" "$ANDROID_USER_HOME"
./gradlew --init-script test-libs.init.gradle --offline --no-daemon --max-workers=2 -Dorg.gradle.jvmargs="-Xmx1536m -Djava.io.tmpdir=$TMPDIR -Duser.home=$PWD/out/tool-home" -x buildPhoneZhuyinIndex -x buildPhoneChewingJni -x buildPhoneRime "$@"
