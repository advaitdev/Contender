#!/usr/bin/env bash
# Stops the render client (if running), starts it again and waits until it has joined. GUI_SCALE=3 to change scale.
source "$(dirname "$0")/../tools/env.sh"
cd "$TESTBED/client"
pkill -f "username Render_[1]" 2>/dev/null; sleep 2
mkdir -p "$WORK/client"
(nohup ./run-client.sh > "$WORK/client/client.log" 2>&1 &)
sleep 3
timeout 280 python3 rc.py ready 270 > /dev/null && echo "client ready"
