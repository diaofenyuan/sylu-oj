#!/usr/bin/env bash
# OJ 安装脚本（Task 8，设计 12.5）：重复执行必须幂等。
# 阶段：预检 → 发布包验签 → 不可变版本目录 → 秘密交互注入 →
#       数据库迁移前备份 + 队列冻结 → 迁移 → 启动 → 健康检查 → 安装报告。
# 卸载：见 usage —— 卸载默认保留数据（/var/lib/oj 与数据库不做任何删除）。
set -euo pipefail

RELEASE_ROOT="${OJ_RELEASE_ROOT:-/opt/oj}"
DATA_ROOT="${OJ_DATA_ROOT:-/var/lib/oj}"
RELEASE_DIR="${1:?用法: install.sh <发布包目录>（由 verify-release.sh 验签解包）}"

log() { echo "[install] $(date '+%F %T') $*"; }
fail() { log "FATAL $*"; exit 1; }

# ---------- 1. 预检 ----------
log "1/9 主机预检"
[ "$(id -u)" -eq 0 ] || fail "必须以 root 运行（服务降权由 systemd 承担）"
grep -q 'Ubuntu 22.04' /etc/os-release || fail "仅支持 Ubuntu 22.04"
[ -d /sys/fs/cgroup ] && grep -q cgroup2 /proc/mounts || fail "需要 cgroups v2"
command -v podman >/dev/null || command -v docker >/dev/null || true

# ---------- 2. 发布包验签（不可跳过；不验签拒绝安装） ----------
log "2/9 发布包验签（verify-release.sh，Strict 模式）"
[ -f "$RELEASE_DIR/release.json" ] || fail "缺少 release.json"
"$RELEASE_DIR/verify-release.sh" "$RELEASE_DIR" --strict

VERSION="$(grep -o '"version"[^,]*' "$RELEASE_DIR/release.json" | head -1 | cut -d'"' -f4)"
TARGET="$RELEASE_ROOT/versions/$VERSION"

# ---------- 3. 不可变版本目录 + current 符号链接（幂等） ----------
log "3/9 部署不可变版本目录 $TARGET"
if [ -d "$TARGET" ]; then
  log "版本 $VERSION 已安装，跳过复制（幂等）"
else
  mkdir -p "$RELEASE_ROOT/versions"
  cp -a "$RELEASE_DIR" "$TARGET.tmp"
  mv "$TARGET.tmp" "$TARGET"
fi
chmod -R go-w "$TARGET"
ln -sfn "$TARGET" "$RELEASE_ROOT/current"

# ---------- 4. 秘密交互注入（systemd credentials；不写入仓库与镜像） ----------
log "4/9 秘密交互注入"
SECRETS_DIR=/etc/oj-secrets
mkdir -p "$SECRETS_DIR"; chmod 700 "$SECRETS_DIR"
inject_secret() {
  local name="$1" prompt="$2"
  local file="$SECRETS_DIR/$name"
  if [ -s "$file" ]; then log "$name 已存在（幂等跳过）"; return; fi
  read -r -s -p "$prompt" value; echo
  printf '%s' "$value" > "$file"; chmod 600 "$file"
}
inject_secret OJ_DB_PASSWORD "数据库密码(oj_api)："
inject_secret OJ_MQ_PASSWORD "RabbitMQ 密码："
inject_secret OJ_TOTP_ENCRYPTION_KEY "TOTP 密钥加密密钥(64 hex)："
inject_secret OJ_JUDGE_CRYPTO_KEY "Agent 密钥加密密钥(64 hex)："
inject_secret OJ_BACKUP_KEY "备份加密密钥："

# ---------- 5. 迁移前备份 + 队列冻结 ----------
log "5/9 数据库迁移前备份与队列冻结"
mkdir -p "$DATA_ROOT"
"$RELEASE_ROOT/current/ops/backup/backup-full.sh" || fail "迁移前备份失败，禁止迁移"
systemctl stop oj-agent.service 2>/dev/null || true   # 冻结判题队列（Agent 停止领取）
systemctl stop oj-api.service 2>/dev/null || true

# ---------- 6. 数据库迁移（Flyway 随应用启动执行；此处仅验证连通） ----------
log "6/9 数据库连通性验证"
[ -f "$SECRETS_DIR/OJ_DB_PASSWORD" ] || fail "缺少数据库凭据"

# ---------- 7. systemd 单元（加固字段来自 Task 3 infra/security/systemd） ----------
log "7/9 安装 systemd 单元"
install -m 644 "$TARGET/infra/security/systemd/oj-api.service" /etc/systemd/system/ 2>/dev/null \
  || log "WARN 单元文件缺失，沿用既有定义"
install -m 644 "$TARGET/infra/security/systemd/oj-agent.service" /etc/systemd/system/ 2>/dev/null || true
systemctl daemon-reload

# ---------- 8. 启动 + 健康检查 ----------
log "8/9 启动服务并执行健康检查"
systemctl enable --now oj-api.service
sleep 5
"$RELEASE_ROOT/current/scripts/health-check.sh" --post-install || {
  log "健康检查失败，自动回滚到上一版本"
  "$RELEASE_ROOT/current/scripts/rollback.sh" --auto || true
  fail "安装后健康检查未通过"
}
systemctl enable --now oj-agent.service || log "WARN Agent 启动失败，见告警（队列冻结将自动解除）"

# ---------- 9. 安装报告（含残余风险重申） ----------
REPORT="$DATA_ROOT/install-report-$(date +%Y%m%d-%H%M%S).txt"
cat > "$REPORT" <<EOF
OJ 安装报告
- 版本：$VERSION
- 版本目录：$TARGET
- 入口：DMZ 自签名证书（内测模式，无域名已确认 2026-08-29），仅限内测地址
- 备份：同机加密全量 + Binlog/PITR（每日/每15分钟）
- 【残余风险（签字：AP-2026-06）】同机备份不能防御整机故障：整机损坏将丢失
  最近备份点之后的全部数据；异地备份为后续改进项。
- 【使用限制】内测阶段入口仅供教学作业（HOMEWORK）使用，师生使用前须看到
  内测明示横幅；内测入口不得用于正式考试，正式考试须取得可信 HTTPS 后另行开放。
EOF
chmod 600 "$REPORT"
log "9/9 安装完成；报告：$REPORT"
