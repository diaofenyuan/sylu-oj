#!/usr/bin/env bash
# 同机加密全量备份（Task 8，设计 7：同机备份不能防御整机故障——
# 该残余风险由项目负责人书面接受（AP-2026-06），并在安装报告中重申）。
#
# 流程：mysqldump 全量 → gzip → AES-256-CBC 加密（密钥经 OJ_BACKUP_KEY 注入，
# 不落盘）→ SHA256 校验和 → 保留期清理 → node_exporter textfile 指标。
# cron（每日 02:30）：30 2 * * * oj /opt/oj/current/scripts/../ops/backup/backup-full.sh
set -euo pipefail

BACKUP_DIR="${OJ_BACKUP_DIR:-/var/lib/oj/backups}"
DB_HOST="${OJ_DB_HOST:-data-vm}"
DB_PORT="${OJ_DB_PORT:-3306}"
DB_NAME="${OJ_DB_NAME:-oj}"
DB_USER="${OJ_DB_USER:-oj_backup}"
RETENTION_DAYS="${OJ_BACKUP_RETENTION_DAYS:-14}"
METRICS_FILE="${OJ_NODE_EXPORTER_TEXTFILE:-/var/lib/node_exporter/oj_backup.prom}"
STAMP="$(date +%Y%m%d-%H%M%S)"

log() { echo "[backup] $(date '+%F %T') $*"; }

if [ -z "${OJ_BACKUP_KEY:-}" ]; then
  log "FATAL 缺少 OJ_BACKUP_KEY（备份加密密钥），拒绝明文备份"
  exit 1
fi
if [ -z "${OJ_DB_PASSWORD:-}" ]; then
  log "FATAL 缺少 OJ_DB_PASSWORD"
  exit 1
fi

mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"

write_metric() {
  local success="$1"
  {
    echo "# TYPE oj_backup_last_success_timestamp gauge"
    echo "oj_backup_last_success_timestamp ${success}"
  } > "$METRICS_FILE.tmp"
  mv "$METRICS_FILE.tmp" "$METRICS_FILE"
}

trap 'rc=$?; if [ $rc -ne 0 ]; then log "FATAL 全量备份失败 rc=$rc（P2 告警条件）"; fi; exit $rc' EXIT

TMP_PLAIN="$(mktemp -p "$BACKUP_DIR" dump-XXXXXX.sql)"
trap 'rm -f "$TMP_PLAIN"' EXIT

log "开始 mysqldump 全量（$DB_HOST:$DB_PORT/$DB_NAME）"
MYSQLDUMP_PWD="$OJ_DB_PASSWORD" mysqldump \
  --no-tablespaces --single-transaction --set-gtid-purged=OFF \
  --triggers --routines --events \
  -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" "$DB_NAME" > "$TMP_PLAIN"

# 导出文件与备份内容不进入本清单的长期备份（含源代码的导出目录整体排除，见设计 5.4）
ARCHIVE="$BACKUP_DIR/oj-full-$STAMP.sql.gz.enc"
gzip -c "$TMP_PLAIN" | openssl enc -aes-256-cbc -pbkdf2 -iter 200000 \
  -pass env:OJ_BACKUP_KEY -out "$ARCHIVE"
chmod 600 "$ARCHIVE"

sha256sum "$ARCHIVE" > "$ARCHIVE.sha256"
log "全量备份完成：$ARCHIVE"
log "校验和：$(awk '{print $1}' "$ARCHIVE.sha256")"

# 立即验证：解密可读且 gzip 完整性通过
openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass env:OJ_BACKUP_KEY \
  -in "$ARCHIVE" | gzip -t
log "备份可解密且流完整"

# 保留期清理
find "$BACKUP_DIR" -name 'oj-full-*.sql.gz.enc' -mtime "+$RETENTION_DAYS" -delete
find "$BACKUP_DIR" -name '*.sha256' -mtime "+$RETENTION_DAYS" -delete
log "保留期 $RETENTION_DAYS 天清理完成"

write_metric "$(date +%s)"
log "备份指标已更新：$METRICS_FILE"
