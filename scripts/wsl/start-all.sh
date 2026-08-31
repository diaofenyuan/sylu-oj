#!/bin/bash
# Start the whole OJ stack inside WSL: backend :8080, web :5173, judge agent.
# Idempotent: already-running components are left alone.
set -u
REPO="${OJ_REPO_HOME:-$HOME/sylu-oj}"
OPT="$HOME/opt"
LOG="$REPO/var/log"
mkdir -p "$LOG"

start_bg() { # name, pidfile, command...
  local name="$1" pidfile="$2"; shift 2
  if [ -f "$pidfile" ] && kill -0 "$(cat "$pidfile")" 2>/dev/null; then
    echo "[start] $name already running (pid $(cat "$pidfile"))"
    return 0
  fi
  setsid nohup "$@" >"$LOG/$name.log" 2>&1 </dev/null &
  echo $! > "$pidfile"
  echo "[start] $name started (pid $!)"
}

# backend (cwd matters: application-dev.yml uses relative ./var/oj-dev paths;
# must run inside the WSL-native repo, never on /mnt/d via 9p)
cd "$REPO/app/api"
start_bg backend "$LOG/backend.pid" env \
  JAVA_HOME="$OPT/jdk17" "$OPT/jdk17/bin/java" -jar \
  "$REPO/app/api/target/oj-api-0.4.0.jar" --spring.profiles.active=dev

# web (vite preview serves built dist, proxies /api to localhost:8080)
start_bg web "$LOG/web.pid" env \
  PATH="$OPT/node20/bin:$PATH" "$OPT/node20/bin/npm" --prefix "$REPO/app/web" \
  run preview -- --port 5173 --strictPort

echo "[start] waiting for backend health..."
for i in $(seq 1 60); do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/api/auth/login -X POST 2>/dev/null || true)
  [ "$code" != "000" ] && { echo "[start] backend is up (http $code)"; break; }
  [ "$i" = 60 ] && echo "[start] WARN backend not responding yet, check $LOG/backend.log"
  sleep 1
done

# agent credentials: register against this WSL-local database on first start
ENVFILE="$REPO/var/oj-dev-agent.env"
if ! grep -q '^OJ_AGENT_SECRET=' "$ENVFILE" 2>/dev/null; then
  resp=$(curl -s -X POST http://127.0.0.1:8080/api/judge/v1/agents/register \
    -H 'X-Internal-Token: dev-internal-only' -H 'Content-Type: application/json' \
    -d '{"agentId":"wsl-agent","displayName":"WSL native agent"}')
  id=$(echo "$resp" | sed -n 's/.*"agentId":"\([^"]*\)".*/\1/p')
  sec=$(echo "$resp" | sed -n 's/.*"secret":"\([^"]*\)".*/\1/p')
  if [ -n "$id" ] && [ -n "$sec" ]; then
    sed -i '/^OJ_AGENT_ID=/d; /^OJ_AGENT_SECRET=/d' "$ENVFILE"
    { echo "OJ_AGENT_ID=$id"; echo "OJ_AGENT_SECRET=$sec"; } >> "$ENVFILE"
    echo "[start] registered judge agent ($id)"
  else
    echo "[start] WARN agent registration failed: $resp"
  fi
fi

# judge agent
"$REPO/scripts/wsl/start-agent.sh"

echo "[start] web   -> http://localhost:5173"
echo "[start] api   -> http://localhost:8080"
