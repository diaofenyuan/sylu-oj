#!/usr/bin/env bats
# tests/ops/lifecycle.bats —— 入口/监控/备份/生命周期验收（Task 8）

setup() {
  ROOT="$(cd "$BATS_TEST_DIRNAME/../.." && pwd)"
  INGRESS="$ROOT/infra/ingress"
  OPS="$ROOT/ops"
  SCRIPTS="$ROOT/scripts"
  RUNBOOKS="$ROOT/docs/runbooks"
}

# ---------------- 入口 ----------------

@test "存在入口 Nginx 配置与自签证书脚本" {
  [ -f "$INGRESS/nginx.conf" ]
  [ -f "$INGRESS/gen-selfsigned-cert.sh" ]
}

@test "入口启用 TLS1.2+/安全头/内测明示头" {
  C="$INGRESS/nginx.conf"
  grep -q 'TLSv1.2 TLSv1.3' "$C"
  grep -q 'Strict-Transport-Security' "$C"
  grep -q 'Content-Security-Policy' "$C"
  grep -q 'X-Content-Type-Options' "$C"
  grep -q 'X-Frame-Options "DENY"' "$C"
  grep -q 'X-OJ-Stage "internal-beta"' "$C"
}

@test "入口限流与请求体限制" {
  C="$INGRESS/nginx.conf"
  grep -q 'limit_req_zone' "$C"
  grep -q 'zone=oj_login' "$C"
  grep -q 'zone=oj_submit' "$C"
  grep -q 'client_max_body_size' "$C"
}

@test "入口拒绝判题网关/内部接口/管理端点，只转发 Web/API" {
  C="$INGRESS/nginx.conf"
  grep -q 'location /api/judge/v1/ { return 403; }' "$C"
  grep -q 'location /internal/     { return 403; }' "$C"
  grep -q 'location /actuator/     { return 403; }' "$C"
  grep -q 'proxy_pass https://api-vm:8443' "$C"
}

# ---------------- 监控与日志 ----------------

@test "告警规则覆盖日志断流/队列积压/证书临期/磁盘/判题异常/分发异常/降级" {
  R="$OPS/monitoring/alert.rules.yml"
  grep -q 'OjLogStreamInterrupted' "$R"
  grep -q 'OjJudgeQueueBacklog' "$R"
  grep -q 'OjIngressCertExpiring' "$R"
  grep -q 'OjDiskNearlyFull' "$R"
  grep -q 'OjJudgeSeRateHigh' "$R"
  grep -q 'OjTestcaseMismatch' "$R"
  grep -q 'OjSandboxFallbackActive' "$R"
  grep -q 'OjAgentsOffline\|OjJudgeAgentsOffline' "$R"
}

@test "日志断流告警阈值为 5 分钟（P1）" {
  R="$OPS/monitoring/alert.rules.yml"
  grep -q 'absent(vector_heartbeat_total\[5m\])' "$R"
  grep -q 'severity: P1' "$R"
}

@test "Prometheus 抓取独立管理端口 9091" {
  grep -q 'api-vm:9091' "$OPS/monitoring/prometheus.yml"
}

@test "日志经 TLS 发送 OPS 且采集端脱敏" {
  V="$OPS/logging/vector.toml"
  grep -q 'tls.enabled = true' "$V"
  grep -q 'REDACTED' "$V"
}

@test "应用暴露判题域指标（actuator + 自定义指标）" {
  M="$ROOT/app/api/src/main/java/oj/judge/JudgeMetrics.java"
  [ -f "$M" ]
  grep -q 'oj_judge_tasks_pending' "$M"
  grep -q 'oj_sandbox_mode_info' "$M"
  grep -q 'oj_testcase_mismatch_total' "$M"
  grep -q 'management' "$ROOT/app/api/src/main/resources/application.yml"
  grep -q 'micrometer-registry-prometheus' "$ROOT/app/api/pom.xml"
}

# ---------------- 备份与恢复演练 ----------------

@test "全量备份：加密 + 校验和 + 保留期清理 + 指标" {
  B="$OPS/backup/backup-full.sh"
  grep -q 'openssl enc -aes-256-cbc' "$B"
  grep -q 'sha256sum' "$B"
  grep -q 'RETENTION_DAYS' "$B"
  grep -q 'oj_backup_last_success_timestamp' "$B"
  # 无密钥拒绝明文备份
  grep -q '拒绝明文备份' "$B"
}

@test "Binlog PITR 归档：幂等 + 加密 + 校验和" {
  B="$OPS/backup/archive-binlog.sh"
  grep -q 'mysqlbinlog' "$B"
  grep -q '.last-archived' "$B"
  grep -q 'openssl enc -aes-256-cbc' "$B"
}

@test "恢复演练：校验和先行、隔离实例、应用级抽查、证据归档" {
  B="$OPS/backup/restore-drill.sh"
  grep -q 'sha256sum -c' "$B"
  grep -q '127.0.0.1' "$B"
  grep -q 'CHECK TABLE' "$B"
  grep -q 'restore-drill-' "$B"
}

@test "Runbook 明确同机备份残余风险与导出不入长期备份" {
  R="$RUNBOOKS/backup-and-restore.md"
  grep -q 'AP-2026-06' "$R"
  grep -q '不能防御整机故障' "$R"
  grep -q '不纳入长期备份\|不进入长期备份' "$R"
  grep -q '按季度' "$R"
}

# ---------------- 安装/升级/回滚/健康检查 ----------------

@test "install.sh：幂等/验签/秘密注入/迁移前备份/队列冻结/健康检查/安装报告" {
  S="$SCRIPTS/install.sh"
  grep -q '重复执行必须幂等' "$S"
  grep -q 'verify-release.sh' "$S"
  grep -q 'versions/' "$S"
  grep -q 'read -r -s -p' "$S"
  grep -q 'backup-full.sh' "$S"
  grep -q 'systemctl stop oj-agent' "$S"
  grep -q 'health-check.sh' "$S"
  grep -q 'install-report' "$S"
}

@test "install.sh 安装报告重申同机备份残余风险与内测限制" {
  grep -q '不能防御整机故障' "$SCRIPTS/install.sh"
  grep -q '不得用于正式考试' "$SCRIPTS/install.sh"
}

@test "upgrade.sh：升级前备份 + 冻结 + 符号链接切换 + 失败自动回滚" {
  S="$SCRIPTS/upgrade.sh"
  grep -q 'backup-full.sh' "$S"
  grep -q 'ln -sfn' "$S"
  grep -q '自动回滚' "$S"
}

@test "rollback.sh：先冻结队列，--restore-db 显式确认才动数据库" {
  S="$SCRIPTS/rollback.sh"
  grep -q 'systemctl stop oj-agent' "$S"
  grep -q -- '--restore-db' "$S"
  grep -q '默认保留数据' "$S"
}

@test "health-check.sh：API/指标/队列/证书/磁盘/备份新鲜度" {
  S="$SCRIPTS/health-check.sh"
  grep -q 'actuator/health' "$S"
  grep -q 'oj_judge_tasks_pending' "$S"
  grep -q 'ingress.crt' "$S"
  grep -q 'df --output=pcent' "$S"
  grep -q 'oj_backup.prom' "$S"
}
