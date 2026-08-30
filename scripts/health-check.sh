#!/usr/bin/env bash
# OJ 健康检查（Task 8）：安装后/升级后/巡检共用。
# 退出码非零即不健康（install/upgrade 据此自动回滚）。
set -euo pipefail

POST_INSTALL=false
[ "${1:-}" = "--post-install" ] && POST_INSTALL=true

API_HOST="${OJ_API_HOST:-api-vm}"
API_PORT="${OJ_API_PORT:-8443}"
MGMT_HOST="${OJ_API_HOST:-api-vm}"
MGMT_PORT="${OJ_MGMT_PORT:-9091}"
MQ_HOST="${OJ_MQ_HOST:-api-vm}"

log() { echo "[health] $(date '+%F %T') $*"; }
FAILED=0
check() {
  local name="$1" rc="$2"
  if [ "$rc" -eq 0 ]; then log "OK   $name"; else log "FAIL $name"; FAILED=1; fi
}

# 1. API 进程与业务端口
systemctl is-active --quiet oj-api.service; check "oj-api 服务运行中" $?

# 2. 管理端口健康（show-details=never，仅状态码）
HTTP_CODE="$(curl -s -o /dev/null -w '%{http_code}' "http://$MGMT_HOST:$MGMT_PORT/actuator/health" || echo 000)"
[ "$HTTP_CODE" = "200" ]; check "API 健康端点（$HTTP_CODE）" $?

# 3. Prometheus 指标可抓取
curl -sf "http://$MGMT_HOST:$MGMT_PORT/actuator/prometheus" | grep -q 'oj_judge_tasks_pending'; \
  check "判题指标暴露" $?

# 4. RabbitMQ 端口（5671 TLS）
timeout 5 bash -c "</dev/tcp/$MQ_HOST/5671" 2>/dev/null; check "RabbitMQ TLS 端口" $?

# 5. 判题队列状态（积压阈值：pending > 50 视为异常）
PENDING="$(
  curl -sf "http://$MGMT_HOST:$MGMT_PORT/actuator/prometheus" \
    | awk '/^oj_judge_tasks_pending/ {print $2; exit}'
)"
if [ -n "${PENDING:-}" ]; then
  awk -v p="$PENDING" 'BEGIN { exit (p > 50) ? 1 : 0 }'
  check "判题队列积压（pending=$PENDING）" $?
else
  log "WARN 未读取到队列指标（curl/指标异常）"
  [ "$POST_INSTALL" = false ] && FAILED=1 || true
fi

# 6. 入口 TLS 证书剩余有效期（>14 天）
if command -v openssl >/dev/null && [ -f /etc/oj-ingress/tls/ingress.crt ]; then
  END_EPOCH="$(date -d "$(openssl x509 -in /etc/oj-ingress/tls/ingress.crt -noout -enddate | cut -d= -f2)" +%s)"
  NOW_EPOCH="$(date +%s)"
  [ $(( (END_EPOCH - NOW_EPOCH) / 86400 )) -gt 14 ]; check "入口证书有效期" $?
fi

# 7. 磁盘余量（>15%）
df --output=pcent / | tail -1 | tr -dc 0-9 | awk '{ exit ($1 > 85) ? 1 : 0 }'; check "磁盘余量" $?

# 8. 备份新鲜度（最近成功 < 25 小时，metrics 由 backup-full.sh 写入）
if [ -f "${OJ_NODE_EXPORTER_TEXTFILE:-/var/lib/node_exporter/oj_backup.prom}" ]; then
  LAST="$(awk '{print $2}' "${OJ_NODE_EXPORTER_TEXTFILE:-/var/lib/node_exporter/oj_backup.prom}" | head -1)"
  [ -n "$LAST" ] && [ $(( $(date +%s) - ${LAST%.*} )) -lt 90000 ]; check "备份新鲜度" $?
else
  log "WARN 备份指标文件缺失（尚未执行过备份）"
fi

if [ "$FAILED" -ne 0 ]; then
  log "健康检查未通过"
  exit 1
fi
log "健康检查全部通过"
