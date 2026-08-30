#!/usr/bin/env bash
# OJ 升级脚本（Task 8）：新版本目录部署 + 迁移前备份 + 队列冻结 +
# 切换 current 符号链接 + 健康检查；失败自动回滚。
set -euo pipefail

RELEASE_ROOT="${OJ_RELEASE_ROOT:-/opt/oj}"
NEW_RELEASE_DIR="${1:?用法: upgrade.sh <新发布包目录>}"

log() { echo "[upgrade] $(date '+%F %T') $*"; }
fail() { log "FATAL $*"; exit 1; }

[ "$(id -u)" -eq 0 ] || fail "必须以 root 运行"
[ -f "$NEW_RELEASE_DIR/release.json" ] || fail "缺少 release.json"
"$NEW_RELEASE_DIR/verify-release.sh" "$NEW_RELEASE_DIR" --strict

VERSION="$(grep -o '"version"[^,]*' "$NEW_RELEASE_DIR/release.json" | head -1 | cut -d'"' -f4)"
TARGET="$RELEASE_ROOT/versions/$VERSION"
PREVIOUS="$(readlink -f "$RELEASE_ROOT/current")"
log "当前版本：$(basename "$PREVIOUS") → 目标：$VERSION"

# 1. 不可变版本目录（幂等）
if [ ! -d "$TARGET" ]; then
  mkdir -p "$RELEASE_ROOT/versions"
  cp -a "$NEW_RELEASE_DIR" "$TARGET.tmp"; mv "$TARGET.tmp" "$TARGET"
  chmod -R go-w "$TARGET"
fi

# 2. 迁移前备份 + 队列冻结（升级窗口内任务不领取、结果不写入）
log "迁移前备份 + 队列冻结"
"$RELEASE_ROOT/current/ops/backup/backup-full.sh" || fail "升级前备份失败，禁止升级"
systemctl stop oj-agent.service 2>/dev/null || true
systemctl stop oj-api.service 2>/dev/null || true

# 3. 切换版本并启动
ln -sfn "$TARGET" "$RELEASE_ROOT/current"
systemctl daemon-reload
systemctl start oj-api.service
sleep 5

# 4. 健康检查；失败自动回滚（返回非零，由调用方决定重试）
if ! "$TARGET/scripts/health-check.sh" --post-install; then
  log "健康检查失败，自动回滚到 $(basename "$PREVIOUS")"
  systemctl stop oj-api.service
  ln -sfn "$PREVIOUS" "$RELEASE_ROOT/current"
  systemctl start oj-api.service
  systemctl start oj-agent.service 2>/dev/null || true
  fail "升级失败已回滚"
fi
systemctl start oj-agent.service 2>/dev/null || log "WARN Agent 启动失败，见告警"
log "升级完成：$VERSION（上一版本 $PREVIOUS 保留，可随时 rollback.sh 回滚）"
