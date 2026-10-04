#!/usr/bin/env bash
# Builds Contender and the probe, stops the server, installs the jars and starts it again. Fails loudly.
set -e
source "$(dirname "$0")/env.sh"
cd "$REPO"
if ! mvn -B -q package -DskipTests > "$WORK/build.log" 2>&1; then
  grep -E "ERROR|error:" "$WORK/build.log" | head -40
  echo "BUILD FAILED"; exit 1
fi
JAR=$(ls -t target/Contender-v*.jar | grep -v original | head -1)
"$TESTBED/probe/build.sh" >/dev/null
"$TESTBED/tools/stop.sh" >/dev/null
rm -f "$SERVER"/plugins/Contender-v*.jar
cp "$JAR" "$SERVER/plugins/"
"$TESTBED/tools/start.sh"
