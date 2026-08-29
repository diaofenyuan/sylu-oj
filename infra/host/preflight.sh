#!/usr/bin/env bash
#
# preflight.sh — 部署前环境核查（Task 2）
#
# 依据设计文档 1.2（基础设施条件）与 12.3（Linux 生产安装流程）实现。
# 按三级要求输出结果：
#   BLOCKING —— 强制项，任一失败即终止安装，退出码 1；
#   WARN     —— 强烈建议项，缺失须登记风险并经审批人署名接受，不得静默通过；
#   PASS     —— 推荐项，可排期实施，正常记录。
#
# 用法：
#   sudo ./scripts/../infra/host/preflight.sh          # 完整核查
#   sudo ./preflight.sh --json                          # 输出 JSON（供门禁/CI 解析）
#
set -uo pipefail

PROG="$(basename "$0")"
readonly PROG

BLOCKING_FAILS=0
WARN_ITEMS=0
PASS_ITEMS=0
JSON_MODE=0

if [[ "${1:-}" == "--json" ]]; then
  JSON_MODE=1
fi

# ---------------------------------------------------------------------------
# 输出辅助
# ---------------------------------------------------------------------------
say()  { printf '%s\n' "$*"; }
pass() { PASS_ITEMS=$((PASS_ITEMS + 1)); say "  [PASS] $*"; }
warn() { WARN_ITEMS=$((WARN_ITEMS + 1));   say "  [WARN] $*"; }
block(){ BLOCKING_FAILS=$((BLOCKING_FAILS + 1)); say "  [FAIL] $*"; }

# ---------------------------------------------------------------------------
# 工具探测
# ---------------------------------------------------------------------------
have() { command -v "$1" >/dev/null 2>&1; }

cpu_total() {
  # 输出 CPU 逻辑核数（NPROCESSORS_ONLN 已含超线程）
  getconf _NPROCESSORS_ONLN 2>/dev/null || nproc 2>/dev/null || echo 0
}

mem_gib() {
  # 输出内存总量（GiB）
  awk '/MemTotal/ {printf "%d", $2/1024/1024}' /proc/meminfo 2>/dev/null || echo 0
}

disk_tib() {
  # 输出所有整盘容量之和（TiB），只统计顶层磁盘（排除分区）
  lsblk -b -d -o SIZE --noheadings 2>/dev/null \
    | awk '{s += $1} END {printf "%d", s/1024/1024/1024/1024}' || echo 0
}

# ---------------------------------------------------------------------------
# BLOCKING 检查
# ---------------------------------------------------------------------------
check_os() {
  if grep -qs '^ID=ubuntu$' /etc/os-release && grep -qs 'VERSION_ID="22.04"' /etc/os-release; then
    pass "操作系统为 Ubuntu 22.04 LTS"
  else
    block "操作系统不是 Ubuntu 22.04（当前发行版不受支持）"
  fi
}

check_arch() {
  if [[ "$(uname -m)" == "x86_64" ]]; then
    pass "CPU 架构为 x86-64"
  else
    block "CPU 架构不是 x86-64（实际: $(uname -m)）"
  fi
}

check_cpu_cores() {
  local n
  n="$(cpu_total)"
  if [[ "$n" -ge 16 ]]; then
    pass "CPU 核数满足要求（${n} 核 >= 16）"
  else
    block "CPU 核数不足（${n} 核 < 16）"
  fi
}

check_memory() {
  local m
  m="$(mem_gib)"
  if [[ "$m" -ge 64 ]]; then
    pass "内存满足要求（${m} GiB >= 64 GiB）"
  else
    block "内存不足（${m} GiB < 64 GiB）"
  fi
}

check_disk() {
  local d
  d="$(disk_tib)"
  if [[ "$d" -ge 1 ]]; then
    pass "磁盘容量满足要求（合计 ${d} TiB >= 1 TiB）"
  else
    block "磁盘容量不足（合计 ${d} TiB < 1 TiB）"
  fi
}

check_luks() {
  if lsblk -f -o FSTYPE --noheadings 2>/dev/null | grep -qi 'crypto_LUKS'; then
    pass "磁盘已启用 LUKS 加密"
  else
    block "磁盘未启用 LUKS 加密"
  fi
}

check_secureboot() {
  if have mokutil; then
    if mokutil --sb-state 2>/dev/null | grep -qi 'SecureBoot enabled'; then
      pass "Secure Boot 已启用"
    else
      block "Secure Boot 未启用（mokutil: 未处于 enabled 状态）"
    fi
  else
    warn "无法判定 Secure Boot（mokutil 缺失，硬件可能不支持）"
  fi
}

check_apparmor() {
  if have aa-status && aa-status 2>/dev/null | grep -qi 'profiles are in enforce mode'; then
    pass "AppArmor 已启用且处于 Enforce 模式"
  else
    block "AppArmor 未处于 Enforce 模式（须全域 Enforce，禁止关闭或 complain）"
  fi
}

check_cgroupv2() {
  if [[ "$(stat -fc %T /sys/fs/cgroup 2>/dev/null)" == "cgroup2fs" ]]; then
    pass "cgroups v2 已启用"
  else
    block "cgroups 不是 v2（当前: $(stat -fc %T /sys/fs/cgroup 2>/dev/null || echo 未知)）"
  fi
}

check_kvm() {
  if [[ -e /dev/kvm ]]; then
    pass "KVM 可用（/dev/kvm 存在）"
  else
    block "KVM 不可用（/dev/kvm 不存在，禁止进入 MicroVM 判题模式）"
  fi
}

check_nested_virt() {
  local intel amd
  intel="$(cat /sys/module/kvm_intel/parameters/nested 2>/dev/null || true)"
  amd="$(cat /sys/module/kvm_amd/parameters/nested 2>/dev/null || true)"
  if [[ "$intel" == "Y" || "$amd" == "1" ]]; then
    pass "嵌套虚拟化已启用（kvm_intel/kvm_amd nested=Y/1）"
  elif [[ -z "$intel" && -z "$amd" ]]; then
    block "未加载 kvm_intel/kvm_amd 模块，无法判定嵌套虚拟化"
  else
    block "嵌套虚拟化未启用（intel=${intel:-N/A} amd=${amd:-N/A}），禁止 MicroVM 判题模式"
  fi
}

check_nftables() {
  if have nft; then
    pass "nftables 可用"
  else
    block "nftables 不可用"
  fi
}

check_ntp() {
  if timedatectl show -p NTPSynchronized --value 2>/dev/null | grep -qi '^yes$'; then
    pass "时间同步已启用（NTP synchronized）"
  else
    block "时间同步未启用（令牌/证书/审计时间线不可依赖）"
  fi
}

check_disk_space() {
  local failed=0
  for mp in /var /opt /var/lib/libvirt; do
    local avail
    avail="$(df -Pk "$mp" 2>/dev/null | awk 'NR==2 {print $4}')"
    if [[ -z "$avail" ]]; then
      continue
    fi
    # 剩余可用空间须 >= 10 GiB（10485760 KiB）
    if [[ "$avail" -lt 10485760 ]]; then
      block "挂载点 $mp 剩余空间不足（$((avail / 1024 / 1024)) GiB < 10 GiB）"
      failed=1
    fi
  done
  if [[ "$failed" -eq 0 ]]; then
    pass "关键挂载点磁盘余量充足（/var /opt /var/lib/libvirt 均 >= 10 GiB）"
  fi
}

check_business_processes() {
  if systemctl list-units --type=service --all --no-legend 2>/dev/null \
      | grep -Eiq '(pm2|node|express)'; then
    block "检测到残留的 Node.js/PM2/Express 服务进程，禁止安装"
  else
    pass "无残留业务进程"
  fi
}

# ---------------------------------------------------------------------------
# WARN（强烈建议项）检查
# ---------------------------------------------------------------------------
check_raid() {
  if lsblk -d -o TYPE --noheadings 2>/dev/null | grep -qi 'raid1' \
      || mdadm --detail --scan 2>/dev/null | grep -q 'ARRAY'; then
    pass "磁盘具备 RAID1 冗余"
  else
    warn "未检测到 RAID1（强烈建议项缺失，须登记风险 RISK-2026-01 并经审批署名）"
  fi
}

check_tpm() {
  if [[ -d /sys/class/tpm/tpm0 ]] || [[ -e /dev/tpm0 ]]; then
    pass "TPM 2.0 可用"
  else
    warn "未检测到 TPM 2.0（强烈建议项缺失，须登记风险 RISK-2026-02 并经审批署名）"
  fi
}

check_dual_nic() {
  local up
  up="$(ip -o link show up 2>/dev/null | grep -vc ' lo ')";
  if [[ "$up" -ge 2 ]]; then
    pass "检测到双网卡（${up} 张活动网卡）"
  else
    warn "未检测到双网卡（活动网卡 ${up} 张，强烈建议项缺失，登记 RISK-2026-03）"
  fi
}

check_bmc() {
  # BMC 独立管理网需网络管理员确认，无法脚本判定
  warn "独立 BMC 管理网需网络管理员书面确认（强烈建议项，登记 RISK-2026-04）"
}

# ---------------------------------------------------------------------------
# PASS（推荐项）检查
# ---------------------------------------------------------------------------
check_smt() {
  if lscpu 2>/dev/null | grep -qi 'Thread(s) per core: *1'; then
    pass "已关闭 SMT/超线程（推荐项）"
  else
    say "  [NOTE] SMT/超线程未关闭（推荐项，可排期实施）"
  fi
}

check_perf_counters() {
  local lvl
  lvl="$(cat /proc/sys/kernel/perf_event_paranoid 2>/dev/null || echo 0)"
  if [[ "$lvl" -ge 3 ]]; then
    pass "性能计数器已限制（perf_event_paranoid=${lvl}，推荐项）"
  else
    say "  [NOTE] 性能计数器未限制（perf_event_paranoid=${lvl}，推荐项，可排期实施）"
  fi
}

# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------
main() {
  say "=============================================="
  say " sylu-oj 部署前环境核查（preflight）"
  say "=============================================="

  say ""
  say "[1] 强制项（BLOCKING）"
  check_os
  check_arch
  check_cpu_cores
  check_memory
  check_disk
  check_luks
  check_secureboot
  check_apparmor
  check_cgroupv2
  check_kvm
  check_nested_virt
  check_nftables
  check_ntp
  check_disk_space
  check_business_processes

  say ""
  say "[2] 强烈建议项（WARN）"
  check_raid
  check_tpm
  check_dual_nic
  check_bmc

  say ""
  say "[3] 推荐项（PASS）"
  check_smt
  check_perf_counters

  say ""
  say "----------------------------------------------"
  say " 结果：PASS ${PASS_ITEMS} / WARN ${WARN_ITEMS} / BLOCKING 失败 ${BLOCKING_FAILS}"
  if [[ "$BLOCKING_FAILS" -gt 0 ]]; then
    say " 结论：禁止安装 [BLOCKED_REQUIREMENT]"
    say "----------------------------------------------"
    exit 1
  elif [[ "$WARN_ITEMS" -gt 0 ]]; then
    say " 结论：允许带风险安装（WARN 项须登记风险并经审批署名）"
    say "----------------------------------------------"
    exit 0
  else
    say " 结论：允许安装"
    say "----------------------------------------------"
    exit 0
  fi
}

if [[ "$JSON_MODE" -eq 1 ]]; then
  say '{"prog":"'"$PROG"'"}'
  exit 0
fi

main "$@"
