#!/usr/bin/env bash
# Opens each named dialog for Render_1 (through the probe) and saves a screenshot plus a widget report.
# Usage: shoot-dialogs.sh tournament tools formats hacks ...   (see `probe dialog` in the probe plugin)
source "$(dirname "$0")/../tools/env.sh"
mkdir -p "$WORK/shots"
for name in "$@"; do
  python3 "$TESTBED/tools/rcon.py" "probe dialog Render_1 $name" > /dev/null
  sleep 1.2
  python3 "$TESTBED/client/rc.py" shot "d-$name" > "$WORK/shots/d-$name.txt" 2>&1
done
