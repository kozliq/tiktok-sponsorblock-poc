#!/bin/sh
# Прогон настоящего SponsorManager.java на JVM: ./run.sh [число сценариев]
set -eu
here=$(cd "$(dirname "$0")" && pwd)
ext="$here/../../overlay/extensions/tiktok/src/main/java"
aj="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platforms/android-34/android.jar"
export PATH="/opt/homebrew/opt/openjdk@17/bin:$PATH"
out=$(mktemp -d)
python3 "$here/scenarios.py" "${1:-10000}" > "$out/scenarios.txt"
javac -nowarn -encoding UTF-8 --release 11 -d "$out/fake" $(find "$here/fake" -name '*.java') -cp "$aj"
javac -nowarn -encoding UTF-8 --release 11 -d "$out/app" -cp "$out/fake:$aj" \
	$(find "$ext/app/revanced/extension/tiktok/mimo" "$here/harness" -name '*.java')
# поддельные android.os/util/widget впереди android.jar: остальное (View, Activity…) берётся из заглушек SDK
java -cp "$out/app:$out/fake:$aj" app.revanced.extension.tiktok.mimo.Harness "$out/scenarios.txt"
