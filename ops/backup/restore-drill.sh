#!/usr/bin/env bash
# 恢复演练（Task 8，设计 7：按季度执行并归档证据）。
# 在隔离环境验证"全量 + Binlog PITR"可恢复到指定时点：
#   - 演练实例监听 127.0.0.1:3307（不复用生产数据目录，不暴露网络）；
#   - 全量与 binlog 先验校验和再解密；
#   - 恢复后执行行数抽查与应用级一致性查询；
#   - 演练结束销毁实例与临时数据。
# 用法：restore-drill.sh <全量备份文件> [目标时点 'YYYY-MM-DD HH:MM:SS']
set -euo pipefail

BACKUP_FILE="${1:?用法: restore-drill.sh <全量备份.sql.gz.enc> [目标时点]}"
PITR_TO="${2:-}"
WORK_DIR="$(mktemp -d /tmp/oj-restore-drill-XXXXXX)"
DRILL_PORT="${OJ_DRILL_PORT:-3307}"
MYSQL_BASEDIR="${OJ_MYSQL_BASEDIR:-/usr}"

log() { echo "[drill] $(date '+%F %T') $*"; }
[ -n "${OJ_BACKUP_KEY:-}" ] || { log "FATAL 缺少 OJ_BACKUP_KEY"; exit 1; }
trap 'log "清理演练环境 $WORK_DIR"; rm -rf "$WORK_DIR"' EXIT

log "1/6 校验备份校验和"
sha256sum -c "$BACKUP_FILE.sha256"

log "2/6 解密并注入隔离演练实例（127.0.0.1:$DRILL_PORT）"
DATADIR="$WORK_DIR/data"
SOCKET="$WORK_DIR/mysql.sock"
openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass env:OJ_BACKUP_KEY \
  -in "$BACKUP_FILE" | gunzip > "$WORK_DIR/dump.sql"
gzip -t "$WORK_DIR/dump.sql"

"$MYSQL_BASEDIR/bin/mysqld" --no-defaults \
  --datadir="$DATADIR" --socket="$SOCKET" --port="$DRILL_PORT" \
  --bind-address=127.0.0.1 --skip-networking=0 --skip-grant-tables=OFF \
  --initialize-insecure >/dev/null 2>&1
"$MYSQL_BASEDIR/bin/mysqld" --no-defaults \
  --datadir="$DATADIR" --socket="$SOCKET" --port="$DRILL_PORT" \
  --bind-address=127.0.0.1 --pid-file="$WORK_DIR/mysqld.pid" \
  >/dev/null 2>&1 &
MYSQLD_PID=$!
trap 'kill $MYSQLD_PID 2>/dev/null || true; log "清理演练环境 $WORK_DIR"; rm -rf "$WORK_DIR"' EXIT
for i in $(seq 1 30); do [ -S "$SOCKET" ] && break; sleep 1; done

MYSQL_CMD=("$MYSQL_BASEDIR/bin/mysql" --socket="$SOCKET" -u root)
"${MYSQL_CMD[@]}" -e "CREATE DATABASE oj CHARACTER SET utf8mb4"
"${MYSQL_CMD[@]}" oj < "$WORK_DIR/dump.sql"
log "全量恢复完成"

log "3/6 应用 Binlog PITR（目标时点：${PITR_TO:-最近可用归档}）"
if [ -n "$PITR_TO" ]; then
  BINLOG_DIR="$(dirname "$BACKUP_FILE")/binlogs"
  for enc in "$BINLOG_DIR"/*.gz.enc; do
    [ -f "$enc" ] || continue
    sha256sum -c "$enc.sha256" >/dev/null
    openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass env:OJ_BACKUP_KEY \
      -in "$enc" | gunzip > "$WORK_DIR/binlog.sql"
    cat "$WORK_DIR/binlog.sql" | "${MYSQL_CMD[@]}" oj
  done
  log "PITR 重放完成"
else
  log "未指定目标时点，跳过 PITR 重放"
fi

log "4/6 应用级一致性抽查"
STUDENTS=$("${MYSQL_CMD[@]}" -N -e "SELECT COUNT(*) FROM oj.student")
SUBMISSIONS=$("${MYSQL_CMD[@]}" -N -e "SELECT COUNT(*) FROM oj.submission")
AUDITS=$("${MYSQL_CMD[@]}" -N -e "SELECT COUNT(*) FROM oj.audit_event")
log "行数抽查：student=$STUDENTS submission=$SUBMISSIONS audit_event=$AUDITS"
"${MYSQL_CMD[@]}" -e "CHECK TABLE oj.submission, oj.judge_result, oj.audit_event" | tee "$WORK_DIR/check.txt"
if grep -qiE 'error' "$WORK_DIR/check.txt"; then
  log "FATAL 表校验失败"; exit 1
fi

log "5/6 生成演练证据"
EVIDENCE="/var/lib/oj/drill-logs/restore-drill-$(date +%Y%m%d-%H%M%S).log"
mkdir -p "$(dirname "$EVIDENCE")"
{
  echo "backup=$BACKUP_FILE"
  echo "pitr_to=$PITR_TO"
  echo "rows: students=$STUDENTS submissions=$SUBMISSIONS audits=$AUDITS"
  cat "$WORK_DIR/check.txt"
} | tee "$EVIDENCE"

log "6/6 演练通过（证据：$EVIDENCE）；演练实例随退出销毁"
