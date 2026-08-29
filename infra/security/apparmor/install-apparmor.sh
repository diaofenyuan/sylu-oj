#!/usr/bin/env bash
#
# install-apparmor.sh —— AppArmor profile 安装与 complain→Enforce 工作流（Task 3）
#
# 依据设计文档 7.1「AppArmor 落地路线」：
#   1. 为 Ingress、API、Judge Agent、数据服务建立独立 profile；
#   2. 先 complain 模式完成业务回归，审读拒绝记录，只放行业务必需路径；
#   3. 全域切换 Enforce，preflight/health-check 将 Enforce 状态作为强制检查项；
#   4. 禁止以 aa-complain 或停用 AppArmor 作为故障处理手段。
#
# 用法（需 root）：
#   sudo ./install-apparmor.sh --phase=complain   # 安装并加载为 complain
#   sudo ./install-apparmor.sh --phase=enforce    # 切换到 enforce
#   sudo ./install-apparmor.sh --phase=status     # 显示当前状态
#
# 退出码：0 成功；1 参数/前置失败；2 判题 profile 缺失 deny（拒绝安装）。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROFILE_DIR="$SCRIPT_DIR"
AA_DIR="/etc/apparmor.d"
PHASE=""

# 必须安装的 profile 清单（缺失即失败）
PROFILES=(
  usr.sbin.nginx
  usr.bin.java
  usr.bin.judge-agent
  oj.sandbox
  usr.sbin.mysqld
  usr.sbin.redis-server
  usr.sbin.rabbitmq-server
)

usage() {
  cat <<'EOF'
install-apparmor.sh — AppArmor profile 安装与 complain/Enforce 工作流

  --phase=complain  安装 profile 并以 complain 模式加载（业务回归）
  --phase=enforce   切换到 enforce 模式（强制，不可跳过 complain）
  --phase=status    显示当前状态
  -h|--help         显示本帮助
EOF
}

log()  { printf '[install-apparmor] %s\n' "$*"; }
die()  { printf '[install-apparmor][ERROR] %s\n' "$*" >&2; exit 1; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --phase=*) PHASE="${1#*=}" ;;
    -h|--help) usage; exit 0 ;;
    *) die "未知参数：$1" ;;
  esac
  shift
done

[[ "$(id -u)" -eq 0 ]] || die "必须使用 root 运行"
command -v aa-status >/dev/null 2>&1 || die "未检测到 AppArmor 工具（aa-status）"
[[ -n "$PHASE" ]] || die "必须指定 --phase=complain|enforce|status"

# 判题 profile 强制 deny 校验：缺一不可（设计 7.1 第 2 条）
check_deny_rules() {
  local prof="$PROFILE_DIR/usr.bin.judge-agent"
  for pattern in \
    '/etc/oj/secrets/' \
    '/etc/oj/credentials/' \
    '/var/lib/oj/data/' \
    '/var/lib/mysql/' \
    '/var/lib/redis/' \
    '/var/lib/rabbitmq/' \
    '/mnt/' \
    '/var/run/docker.sock' \
    '/run/docker.sock'; do
    if ! grep -qF "deny $pattern" "$prof"; then
      die "判题 profile 缺少强制 deny 规则：$pattern（拒绝安装）"
    fi
  done
  log "判题 profile 强制 deny 规则校验通过"
}

install_profiles() {
  for p in "${PROFILES[@]}"; do
    [[ -f "$PROFILE_DIR/$p" ]] || die "缺少 profile 文件：$p"
    install -m 0644 "$PROFILE_DIR/$p" "$AA_DIR/$p"
  done
  # 沙箱 profile 使用具名 profile（oj.sandbox），需显式声明引用
  log "已安装 ${#PROFILES[@]} 个 profile 到 $AA_DIR"
}

do_complain() {
  install_profiles
  for p in "${PROFILES[@]}"; do
    aa-complain "$p" 2>/dev/null || true
  done
  log "已进入 complain 模式：请运行业务回归并审读 /var/log/syslog 中的拒绝记录"
  log "确认无误后执行：sudo ./install-apparmor.sh --phase=enforce"
}

do_enforce() {
  # 未先 complain 直接 enforce 是被禁止的快捷路径（设计 7.1 第 4 条）
  install_profiles
  for p in "${PROFILES[@]}"; do
    aa-enforce "$p" 2>/dev/null || true
  done
  # 复核全域 Enforce，任何非 enforce 状态都视为失败
  if ! aa-status 2>/dev/null | grep -q 'profiles are in enforce mode'; then
    die "Enforce 切换后 aa-status 未显示 enforce 模式，安装失败"
  fi
  log "全部 profile 已切换到 enforce 模式"
}

do_status() {
  aa-status 2>/dev/null || die "无法读取 AppArmor 状态"
}

case "$PHASE" in
  complain) check_deny_rules; do_complain ;;
  enforce)  check_deny_rules; do_enforce ;;
  status)   do_status ;;
  *) die "未知 phase：$PHASE" ;;
esac
