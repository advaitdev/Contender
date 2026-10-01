#!/usr/bin/env bash
# Builds the FakePlayers test plugin (server-side bots that can walk, fight, use items and reconnect).
set -e
source "$(dirname "$0")/../tools/env.sh"
cd "$TESTBED/fakeplayers"
CP=$(find "$SERVER/libraries" "$SERVER/versions" -name "*.jar" | tr '\n' ':')
OUT="$WORK/fakeplayers"
rm -rf "$OUT" && mkdir -p "$OUT/classes"
javac --release 25 -nowarn -cp "$CP" -d "$OUT/classes" $(find src -name "*.java")
cp resources/plugin.yml "$OUT/classes/"
printf 'paperweight-mappings-namespace: mojang\n' > "$OUT/MANIFEST.MF"
jar cfm "$OUT/FakePlayers.jar" "$OUT/MANIFEST.MF" -C "$OUT/classes" .
cp "$OUT/FakePlayers.jar" "$SERVER/plugins/"
echo built FakePlayers.jar
