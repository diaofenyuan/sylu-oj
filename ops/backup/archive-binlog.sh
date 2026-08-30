#!/usr/bin/env bash
# MySQL Binlog 归档（PITR，Task 8）：随全量备份执行，
# 将自上次归档以来的 binlog 增量拉取并加密保存，用于任意时点恢复。
# cron（每 15 分钟）：*/15 * * * * oj /opt/oj/current/ops/backup/archive-binlog.sh
set -euo pipefail

BACKUP_DIR="${OJ_BACKUP_DIR:-/var/lib/oj/backups}"
BINLOG_DIR="$BACKUP_DIR/binlogs"
DB_HOST="${OJ_DB_HOST:-data-vm}"
DB_PORT="${OJ_DB_PORT:-3306}"
DB_USER="${OJ_DB_USER:-oj_backup}"
CURSOR="$BINLOG_DIR/.last-archived"

log() { echo "[binlog] $(date '+%F %T') $*"; }
[ -n "${OJ_BACKUP_KEY:-}" ] || { log "FATAL 缺少 OJ_BACKUP_KEY"; exit 1; }
[ -n "${OJ_DB_PASSWORD:-}" ] || { log "FATAL 缺少 OJ_DB_PASSWORD"; exit 1; }

mkdir -p "$BINLOG_DIR"
chmod 700 "$BINLOG_DIR"

# SHOW BINARY LOGS 枚举远端 binlog；仅归档上次游标之后的新文件
MAPFILE="$(mktemp)"
MYSQL_PWD="$OJ_DB_PASSWORD" mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" -e \
  "SHOW BINARY LOGS" --batch --skip-column-names > "$MAPFILE"

LAST="$(cat "$CURSOR" 2>/dev/null || echo "")"
ARCHIVED=0
while IFS=$'\t' read -r name size _; do
  [ -n "$name" ] || continue
  if [ -n "$LAST" ] && [ "$name" \< "$LAST" ]; then continue; fi
  # 已归档跳过（幂等）
  [ -f "$BINLOG_DIR/$name.gz.enc" ] && continue
  TMP="$(mktemp)"
  MYSQLBINLOG_PWD="$OJ_DB_PASSWORD" mysqlbinlog --read-from-remote-server --raw \
    -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" "$name" 2>/dev/null || true
  # mysqlbinlog --raw 输出到当前目录，改用管道落盘：
  MYSQLBINLOG_PWD="$OJ_DB_PASSWORD" mysqlbinlog --read-from-remote-server \
    -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" "$name" > "$TMP" 2>/dev/null \
    || { rm -f "$TMP"; log "WARN 拉取 $name 失败，下轮重试"; continue; }
  gzip -c "$TMP" | openssl enc -aes-256-cbc -pbkdf2 -iter 200000 \
    -pass env:OJ_BACKUP_KEY -out "$BINLOG_DIR/$name.gz.enc"
  sha256sum "$BINLOG_DIR/$name.gz.enc" > "$BINLOG_DIR/$name.gz.enc.sha256"
  rm -f "$TMP"
  echo "$name" > "$CURSOR"
  ARCHIVED=$((ARCHIVED + 1))
done < "$MAPFILE"
rm -f "$MAPFILE"

log "本轮归档 $ARCHIVED 个 binlog 分片（PITR 起点为最近一次全量）"

# 与全量一致的保留期
RETENTION_DAYS="${OJ_BACKUP_RETENTION_DAYS:-14}"
find "$BINLOG_DIR" -name '*.gz.enc' -mtime "+$RETENTION_DAYS" -delete
find "$BINLOG_DIR" -name '*.sha256' -mtime "+$RETENTION_DAYS" -delete
