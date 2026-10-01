#!/usr/bin/env bash
# Prints warnings, errors and stack traces from the server log, from an optional line number.
# Usage: errors.sh [fromLine] [maxLines]
source "$(dirname "$0")/env.sh"
FROM=${1:-1}
tail -n +"$FROM" "$SERVER/server.log" | grep -n -E 'WARN|ERROR|Exception|at [a-z]+\.|Caused by' \
  | grep -v -E 'voicechat\] Running in offline mode|No key layers|UserConnection for logging-in|newer plugin version|OFFLINE/INSECURE|attempt to authenticate|hackers to connect|online-mode' \
  | head -${2:-80}
