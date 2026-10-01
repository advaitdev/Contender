#!/bin/bash
# Full round robin with 6 fighting bots, a director-chosen hacker, sabotages, a spectator and a disconnect.
source "$(dirname "$0")/env.sh"
cd "$TESTBED"
R=tools/rcon.py
L=$(wc -l < "$SERVER/server.log")
python3 $R "cancelall" "probe gamecancel" "bots spawn Alice Bob Carl Dana Eve Finn Dir" "op Dir" "sleep:3" "role Dir director" "probe hackers Alice" "probe hackmode director" "probe plan reach 3.6" "probe sabotageconfig true 30 3" "probe tournament arena1 sword 1 Alice Bob Carl Dana Eve Finn" "sleep:2" "bots fight on Alice Bob Carl Dana Eve Finn" "sleep:4" "probe watchmatch Dir 0" "probe sabotage double_health Alice" "sleep:2" "probe sabotage lag_spike Alice" "sleep:5" > /dev/null
sleep 10
python3 $R "bots remove Carl" "sleep:6" "bots spawn Carl" "sleep:3" "bots fight on Carl" > /dev/null
for i in $(seq 1 30); do
  sleep 15
  OUT=$(python3 $R "probe trounds" | grep -c "FINISHED")
  echo "t=$((i*15))s finished=$OUT"
  [ "$OUT" -ge 15 ] && break
done
python3 $R "probe trounds" "probe registry" | grep -v "^$" | grep "pts=\|free\|PLAYING\|WATCHING"
echo "--- errors"
tools/errors.sh $L 80 | grep -v "ContenderProbe\|NoSuchElement"
