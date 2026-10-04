#!/usr/bin/env bash
# Stops the test server (cleanly over RCON, killing it after a minute).
source "$(dirname "$0")/env.sh"
python3 "$TESTBED/tools/rcon.py" stop >/dev/null 2>&1
for i in $(seq 1 60); do
  if ! pgrep -f "Dcontender-test-serve[r]" >/dev/null; then echo stopped; exit 0; fi
  sleep 1
done
pkill -9 -f "Dcontender-test-serve[r]"; echo killed
