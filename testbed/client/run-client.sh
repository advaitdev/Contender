#!/bin/bash
# Launches a real Minecraft 1.21.11 Fabric client (UHCR's testbed driver) on a hidden Xvfb display.
# It joins the local test server as Render_1 through ViaVersion. Control it with rc.py.
# Usage: run-client.sh [--show]   (--show uses the WSLg desktop instead of Xvfb)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
source "$HERE/../tools/env.sh"
RUN="$WORK/client"
DRIVER="$HERE/driver"
mkdir -p "$RUN/game" "$RUN/artifacts"
if [ ! -f "$RUN/token" ]; then
  umask 077
  head -c 48 /dev/urandom | base64 | tr -d '\n/+=' > "$RUN/token"
fi
chmod 600 "$RUN/token"
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-c0de"
echo "$RUN_ID" > "$RUN/run-id"
cat > "$RUN/game/options.txt" <<OPTS
lang:en_us
tutorialStep:none
guiScale:${GUI_SCALE:-2}
fov:0.0
graphicsMode:1
fullscreen:false
pauseOnLostFocus:false
enableVsync:false
maxFps:60
renderDistance:6
simulationDistance:5
onboardAccessibility:false
skipMultiplayerWarning:true
joinedFirstServer:true
resourcePacks:[]
incompatibleResourcePacks:[]
OPTS
if [ "${1:-}" != "--show" ]; then
  if ! ss -xl | grep -q "@/tmp/.X11-unix/X99"; then
    (nohup "$HERE/xvfb/run-xvfb.sh" :99 > "$HERE/xvfb/xvfb.log" 2>&1 &)
    sleep 2
  fi
  export DISPLAY=:99
  export LIBGL_ALWAYS_SOFTWARE=1
fi
export JAVA_TOOL_OPTIONS="-Duhcr.testbed=true -Duhcr.testbed.runId=$RUN_ID -Duhcr.testbed.tokenFile=$RUN/token -Duhcr.testbed.actionsFile=$RUN/artifacts/actions.jsonl -Duhcr.testbed.client.port=25999 -Duhcr.testbed.client.artifacts=$RUN/artifacts"
cd "$RUN/game"
exec java -Xmx2G \
  -Dfabric.dli.config="$DRIVER/.gradle/loom-cache/launch.cfg" -Dfabric.dli.env=client \
  -Dfabric.dli.main=net.fabricmc.loader.impl.launch.knot.KnotClient \
  @"$DRIVER/build/loom-cache/argFiles/runClient" net.fabricmc.devlaunchinjector.Main \
  --username Render_1 --quickPlayMultiplayer 127.0.0.1:25565 --width 1280 --height 720
