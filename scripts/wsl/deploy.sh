#!/bin/bash
# One-time / refresh deployment of the whole OJ stack into WSL (Ubuntu 24.04).
# Everything runs inside WSL: JDK/Node/Go are installed user-level under ~/opt
# (no sudo required). Windows browser reaches the stack via WSL localhost
# forwarding: web http://localhost:5173, api http://localhost:8080.
set -euo pipefail

REPO="${OJ_REPO_HOME:-$HOME/sylu-oj}"
OPT="$HOME/opt"
SOURCE="${1:-/mnt/d/python_play_do/sylu-oj}"   # local git source (fast, no network needed)
mkdir -p "$OPT"

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
mkdir -p "$REPO/var" "$REPO/var/log" "$REPO/var/bin"

# ---------- 2. toolchains (user-level, no sudo) ----------
dl() { # dl <output-file> <url...> — first non-empty download wins
  local out="$1"; shift
  for url in "$@"; do
    log "  trying $url"
    if curl -fSL --http1.1 --retry 2 --connect-timeout 15 -o "$out" "$url"; then
      [ -s "$out" ] && return 0
    fi
    rm -f "$out"
  done
  return 1
}

if [ ! -x "$OPT/jdk17/bin/java" ]; then
  log "installing Temurin JDK 17 -> ~/opt/jdk17"
  tuna="https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/x64/linux"
  fname=$(curl -sSL --connect-timeout 15 "$tuna/" | grep -oE 'OpenJDK17U-jdk_x64_linux_hotspot_[^"]*\.tar\.gz' | sort -V | tail -1 || true)
  [ -n "$fname" ] || fname="OpenJDK17U-jdk_x64_linux_hotspot_17.0.20.1_1.tar.gz"
  dl "$OPT/jdk17.tar.gz" \
    "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse" \
    "$tuna/$fname"
  tar xzf "$OPT/jdk17.tar.gz" -C "$OPT" && rm "$OPT/jdk17.tar.gz"
  mv -T "$OPT"/jdk-* "$OPT/jdk17" 2>/dev/null || mv -T "$OPT"/eclipse-jdk-* "$OPT/jdk17"
fi

if [ ! -x "$OPT/node20/bin/node" ]; then
  log "installing Node 20 LTS -> ~/opt/node20"
  dl "$OPT/node.tar.gz" \
    "https://nodejs.org/dist/v20.18.1/node-v20.18.1-linux-x64.tar.gz" \
    "https://registry.npmmirror.com/-/binary/node/v20.18.1/node-v20.18.1-linux-x64.tar.gz"
  tar xzf "$OPT/node.tar.gz" -C "$OPT" && rm "$OPT/node.tar.gz"
  mv -T "$OPT"/node-v20*-linux-x64 "$OPT/node20"
fi

if [ ! -x "$OPT/go/bin/go" ]; then
  log "installing Go -> ~/opt/go"
  dl "$OPT/go.tar.gz" \
    "https://go.dev/dl/go1.24.0.linux-amd64.tar.gz" \
    "https://golang.google.cn/dl/go1.24.0.linux-amd64.tar.gz" \
    "https://mirrors.aliyun.com/golang/go1.24.0.linux-amd64.tar.gz"
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

# ---------- 6. agent env ----------
# Never reuse credentials from another machine: they are tied to that
# machine's H2 database and yield gateway 401 here. start-all.sh registers
# this agent against the WSL-local database on first start.
env="$REPO/var/oj-dev-agent.env"
touch "$env"
sed -i 's#^OJ_GATEWAY_URL=.*#OJ_GATEWAY_URL=http://127.0.0.1:8080/api/judge/v1#' "$env"
sed -i 's#^OJ_POLICY_FILE=.*#OJ_POLICY_FILE='"$REPO"'/judge/sandbox/language-policy.dev.yaml#' "$env"
grep -q '^OJ_GATEWAY_URL=' "$env" || echo "OJ_GATEWAY_URL=http://127.0.0.1:8080/api/judge/v1" >> "$env"
grep -q '^OJ_POLICY_FILE=' "$env" || echo "OJ_POLICY_FILE=$REPO/judge/sandbox/language-policy.dev.yaml" >> "$env"
grep -q '^OJ_SANDBOX_PREFERRED=' "$env" || echo "OJ_SANDBOX_PREFERRED=host-dev" >> "$env"

# Windows-committed shell scripts lose the exec bit; restore it
chmod +x "$REPO"/scripts/wsl/*.sh 2>/dev/null || true

log "done. start the stack: bash $REPO/scripts/wsl/start-all.sh"
