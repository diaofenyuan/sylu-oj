#!/bin/bash
# One-time / refresh deployment of the whole OJ stack into WSL (Ubuntu 24.04).
# Everything runs inside WSL: JDK/Node/Go are installed user-level under ~/opt
# (no sudo required). Windows browser reaches the stack via WSL localhost
# forwarding: web http://localhost:5173, api http://localhost:8080.
set -euo pipefail

REPO="${OJ_REPO_HOME:-$HOME/sylu-oj}"
OPT="$HOME/opt"
SOURCE="${1:-/mnt/d/python_play_do/sylu-oj}"   # local git source (fast, no network needed)
mkdir -p "$OPT" "$REPO/var" "$REPO/var/log" "$REPO/var/bin"

log() { echo -e "[deploy] $*"; }

# ---------- 1. clone / update source ----------
if [ ! -d "$REPO/.git" ]; then
  log "cloning $SOURCE -> $REPO"
  git clone -q "$SOURCE" "$REPO"
else
  log "repo exists, fetching updates"
  git -C "$REPO" fetch -q origin || git -C "$REPO" fetch -q "$SOURCE"
  git -C "$REPO" reset -q --hard origin/main 2>/dev/null || true
fi

# ---------- 2. toolchains (user-level, no sudo) ----------
if [ ! -x "$OPT/jdk17/bin/java" ]; then
  log "installing Temurin JDK 17 -> ~/opt/jdk17"
  curl -sSL -o "$OPT/jdk17.tar.gz" "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
  tar xzf "$OPT/jdk17.tar.gz" -C "$OPT" && rm "$OPT/jdk17.tar.gz"
  mv -T "$OPT"/jdk-* "$OPT/jdk17" 2>/dev/null || mv -T "$OPT"/eclipse-jdk-* "$OPT/jdk17"
fi

if [ ! -x "$OPT/node20/bin/node" ]; then
  log "installing Node 20 LTS -> ~/opt/node20"
  curl -sSL -o "$OPT/node.tar.gz" "https://nodejs.org/dist/v20.18.1/node-v20.18.1-linux-x64.tar.gz"
  tar xzf "$OPT/node.tar.gz" -C "$OPT" && rm "$OPT/node.tar.gz"
  mv -T "$OPT"/node-v20*-linux-x64 "$OPT/node20"
fi

if [ ! -x "$OPT/go/bin/go" ]; then
  log "installing Go -> ~/opt/go"
  for url in \
    "https://go.dev/dl/go1.24.0.linux-amd64.tar.gz" \
    "https://golang.google.cn/dl/go1.24.0.linux-amd64.tar.gz" \
    "https://mirrors.aliyun.com/golang/go1.24.0.linux-amd64.tar.gz"; do
    log "  trying $url"
    if curl -sSL --connect-timeout 10 -o "$OPT/go.tar.gz" "$url"; then
      [ -s "$OPT/go.tar.gz" ] && break
    fi
  done
  tar xzf "$OPT/go.tar.gz" -C "$OPT" && rm "$OPT/go.tar.gz"
fi

# ---------- 3. backend ----------
log "building backend jar"
cd "$REPO/app/api"
JAVA_HOME="$OPT/jdk17" sh mvnw -q -DskipTests package
log "backend: $(ls target/oj-api-*.jar)"

# ---------- 4. frontend ----------
log "installing web deps + building dist"
export PATH="$OPT/node20/bin:$PATH"
cd "$REPO/app/web"
npm install --no-audit --no-fund >/dev/null 2>&1
npm run build >/dev/null 2>&1
log "frontend: dist/ built"

# ---------- 5. judge agent (native build, no /mnt/d 9p issues) ----------
if [ ! -x "$REPO/var/bin/oj-agent-linux" ]; then
  log "building judge agent (native go)"
  export PATH="$OPT/go/bin:$PATH"
  export GOFLAGS=-mod=mod
  export GOPROXY="${GOPROXY:-https://goproxy.cn,direct}"
  cd "$REPO/judge/agent"
  go build -o "$REPO/var/bin/oj-agent-linux" ./src
fi
log "agent: $REPO/var/bin/oj-agent-linux"

# ---------- 6. agent credentials ----------
# Reuse the dev agent secret registered in the (copied) H2 database if present,
# otherwise register a fresh agent via the dev-internal endpoint.
if [ ! -f "$REPO/var/oj-dev-agent.env" ]; then
  if [ -f "$SOURCE/var/oj-dev-agent.env" ]; then
    cp "$SOURCE/var/oj-dev-agent.env" "$REPO/var/oj-dev-agent.env"
    log "agent env copied from $SOURCE"
  else
    log "registering new dev agent"
    resp=$(curl -s -X POST http://127.0.0.1:8080/api/judge/v1/agents/register \
      -H 'X-Internal-Token: dev-internal-only' -H 'Content-Type: application/json' \
      -d '{"agentId":"wsl-agent","displayName":"WSL native agent"}')
    id=$(echo "$resp" | sed -n 's/.*"agentId":"\([^"]*\)".*/\1/p')
    sec=$(echo "$resp" | sed -n 's/.*"secret":"\([^"]*\)".*/\1/p')
    { echo "OJ_AGENT_ID=$id"; echo "OJ_AGENT_SECRET=$sec"; } > "$REPO/var/oj-dev-agent.env"
  fi
  # gateway is local inside WSL
  sed -i 's#^OJ_GATEWAY_URL=.*#OJ_GATEWAY_URL=http://127.0.0.1:8080/api/judge/v1#' "$REPO/var/oj-dev-agent.env"
  grep -q '^OJ_GATEWAY_URL=' "$REPO/var/oj-dev-agent.env" || echo "OJ_GATEWAY_URL=http://127.0.0.1:8080/api/judge/v1" >> "$REPO/var/oj-dev-agent.env"
  grep -q '^OJ_POLICY_FILE=' "$REPO/var/oj-dev-agent.env" || echo "OJ_POLICY_FILE=$REPO/judge/sandbox/language-policy.dev.yaml" >> "$REPO/var/oj-dev-agent.env"
  grep -q '^OJ_SANDBOX_PREFERRED=' "$REPO/var/oj-dev-agent.env" || echo "OJ_SANDBOX_PREFERRED=host-dev" >> "$REPO/var/oj-dev-agent.env"
fi
sed -i 's#^OJ_POLICY_FILE=.*#OJ_POLICY_FILE='"$REPO"'/judge/sandbox/language-policy.dev.yaml#' "$REPO/var/oj-dev-agent.env"

log "done. start the stack: bash $REPO/scripts/wsl/start-all.sh"
