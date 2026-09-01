#!/bin/bash
# Start only the judge agent (host-dev runner) with credentials from
# var/oj-dev-agent.env. Gateway is WSL-local (127.0.0.1:8080).
set -eu
REPO="${OJ_REPO_HOME:-$HOME/sylu-oj}"
LOG="$REPO/var/log"
PIDFILE="$LOG/agent.pid"
ENVFILE="$REPO/var/oj-dev-agent.env"
[ -f "$ENVFILE" ] || { echo "[agent] missing $ENVFILE (run deploy.sh first)"; exit 1; }
[ -x "$REPO/var/bin/oj-agent-linux" ] || { echo "[agent] missing binary (run deploy.sh first)"; exit 1; }

if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
  echo "[agent] already running (pid $(cat "$PIDFILE"))"
  exit 0
fi

set -a; . "$ENVFILE"; set +a
export PATH="$HOME/opt/jdk17/bin:$PATH"
setsid nohup "$REPO/var/bin/oj-agent-linux" >>"$LOG/agent.log" 2>&1 </dev/null &
echo $! > "$PIDFILE"
echo "[agent] started (pid $!, gateway=$OJ_GATEWAY_URL)"
