#!/usr/bin/env bash
# Prepares a local Paper server for the testbed: flat world, offline mode, RCON on 25575.
# Put paper.jar in the server folder first, and these plugins in server/plugins:
#   FastAsyncWorldEdit, voicechat (optional), ViaVersion + ViaBackwards (needed by the 1.21.11 render client).
set -e
source "$(dirname "$0")/env.sh"
mkdir -p "$SERVER/plugins"
cd "$SERVER"
echo "eula=true" > eula.txt
[ -f server.properties ] || cat > server.properties <<PROPS
online-mode=false
enable-rcon=true
rcon.password=test
rcon.port=25575
level-type=minecraft\:flat
spawn-protection=0
gamemode=survival
difficulty=easy
max-players=40
view-distance=8
PROPS
[ -f paper.jar ] || echo "Missing $SERVER/paper.jar (Paper 26.2)."
"$TESTBED/fakeplayers/build.sh"
echo "Server folder ready: $SERVER"
