#!/bin/bash
# Stop the whole OJ stack inside WSL.
set -u
REPO="${OJ_REPO_HOME:-$HOME/sylu-oj}"
LOG="$REPO/var/log"
for f in agent web backend; do
  pf="$LOG/$f.pid"
  if [ -f "$pf" ]; then
    pid=$(cat "$pf")
    if kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null
      echo "[stop] $f (pid $pid)"
    fi
    rm -f "$pf"
  fi
done
# vite preview spawns npm->node children; make sure nothing keeps the ports
pkill -f 'vite preview' 2>/dev/null && echo "[stop] vite preview"
pkill -f 'oj-api-0.4.0.jar' 2>/dev/null && echo "[stop] backend jar"
pkill -x oj-agent-linux 2>/dev/null && echo "[stop] agent"
echo "[stop] done"
