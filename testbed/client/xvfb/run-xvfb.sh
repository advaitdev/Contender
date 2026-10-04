#!/usr/bin/env bash
# Starts a hidden X display for the render client. Usage: run-xvfb.sh [display]
D=${1:-:99}
cd "$(dirname "$0")"
export LD_LIBRARY_PATH=$PWD/root/usr/lib/x86_64-linux-gnu
exec ./Xvfb-local $D -screen 0 1600x900x24 -nolisten tcp -xkbdir /usr/share/X11/xkb
