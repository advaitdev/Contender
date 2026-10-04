#!/usr/bin/env bash
# Starts the test server in the background and waits until it's ready.
source "$(dirname "$0")/env.sh"
cd "$SERVER"
if pgrep -f "Dcontender-test-serve[r]" >/dev/null; then echo "already running"; exit 0; fi
: > server.log
nohup java -Dcontender-test-server -Dcontender.debug=true -Xms2G -Xmx4G -XX:+UseG1GC ${CONTENDER_JAVA_OPTS:-} -jar paper.jar --nogui > server.log 2>&1 &
for i in $(seq 1 90); do
  if grep -q 'Done (' server.log; then echo "ready after ~$((i*2))s"; exit 0; fi
  if ! pgrep -f "Dcontender-test-serve[r]" >/dev/null; then echo "server exited"; tail -40 server.log; exit 1; fi
  sleep 2
done
echo "timeout"; tail -40 server.log; exit 1
