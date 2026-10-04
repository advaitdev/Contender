#!/usr/bin/env bash
# Builds the ContenderProbe test plugin against the server and the current Contender build, and installs it.
set -e
source "$(dirname "$0")/../tools/env.sh"
cd "$TESTBED/probe"
JAR=$(ls -t "$REPO"/target/Contender-v*.jar | grep -v original | head -1)
CP=$(find "$SERVER/libraries" "$SERVER/versions" -name "*.jar" | tr '\n' ':'):$JAR
OUT="$WORK/probe"
rm -rf "$OUT" && mkdir -p "$OUT/classes"
javac --release 25 -nowarn -cp "$CP" -d "$OUT/classes" $(find src -name '*.java')
cp resources/plugin.yml "$OUT/classes/"
jar cf "$OUT/ContenderProbe.jar" -C "$OUT/classes" .
cp "$OUT/ContenderProbe.jar" "$SERVER/plugins/"
echo built
