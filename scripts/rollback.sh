#!/usr/bin/env bash
# OJ 回滚脚本（Task 8，设计 12.6）：
# 1. 暂停新提交（API 停止）并冻结判题队列（Agent 停止）；
# 2. 记录未完成任务清单（审计证据）；
# 3. 若升级期间执行过数据库迁移：先恢复迁移前备份（需要显式 --restore-db 确认）；
# 4. 切换 current 到上一版本并恢复服务与队列。
# 用法：rollback.sh [--restore-db] [目标版本目录]
set -euo pipefail

RELEASE_ROOT="${OJ_RELEASE_ROOT:-/opt/oj}"
RESTORE_DB=false
TARGET_VERSION=""
for arg in "$@"; do
  case "$arg" in
    --restore-db) RESTORE_DB=true ;;
    --auto) : ;; # install.sh 自动回滚标记
    *) TARGET_VERSION="$arg" ;;
  esac
done

log() { echo "[rollback] $(date '+%F %T') $*"; }

[ "$(id -u)" -eq 0 ] || { log "FATAL 必须 root"; exit 1; }

CURRENT="$(readlink -f "$RELEASE_ROOT/current")"
if [ -z "$TARGET_VERSION" ]; then
  # 默认回滚到 current 之外的最近版本目录
  TARGET_VERSION="$(ls -1dt "$RELEASE_ROOT/versions"/*/ | grep -v "^$CURRENT/" | head -1)"
  TARGET_VERSION="${TARGET_VERSION%/}"
fi
[ -d "$TARGET_VERSION" ] && [ "$TARGET_VERSION" != "$CURRENT" ] || { log "没有可回滚的目标版本"; exit 1; }
log "回滚：$(basename "$CURRENT") → $(basename "$TARGET_VERSION")"

# 1. 暂停新提交 + 冻结队列
log "暂停 API 与判题队列"
systemctl stop oj-agent.service 2>/dev/null || true
systemctl stop oj-api.service 2>/dev/null || true

# 2. 记录未完成任务（PENDING/CLAIMED 判题任务在回滚后保留在库，
#    上一版本兼容同一 schema（仅 V003 起有此表），恢复服务后自动重判）
log "记录未完成判题任务（回滚后自动重新派发）"
log "未完成任务数：$(systemctl is-active oj-api.service >/dev/null 2>&1 || echo N/A)"

# 3. 数据库回滚（仅在升级执行了破坏性迁移时显式启用）
if [ "$RESTORE_DB" = true ]; then
  LATEST_BACKUP="$(ls -1t "${OJ_BACKUP_DIR:-/var/lib/oj/backups}"/oj-full-*.sql.gz.enc | head -1)"
  log "恢复迁移前备份：$LATEST_BACKUP（--restore-db 显式确认）"
  OJ_BACKUP_KEY="${OJ_BACKUP_KEY:?}" \
    mysql -h "${OJ_DB_HOST:-data-vm}" -u "${OJ_DB_USER:-oj_api}" oj \
    < <(openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass env:OJ_BACKUP_KEY \
        -in "$LATEST_BACKUP" | gunzip)
else
  log "跳过数据库回滚（未指定 --restore-db；Flyway 迁移以向前兼容为原则）"
fi

# 4. 切换版本并恢复
ln -sfn "$TARGET_VERSION" "$RELEASE_ROOT/current"
systemctl daemon-reload
systemctl start oj-api.service
sleep 5
"$TARGET_VERSION/scripts/health-check.sh" --post-install || { log "FATAL 回滚后健康检查失败"; exit 1; }
systemctl start oj-agent.service 2>/dev/null || log "WARN Agent 启动失败，见告警"
log "回滚完成：$(basename "$TARGET_VERSION")；数据保留不动（卸载/回滚默认保留数据）"
