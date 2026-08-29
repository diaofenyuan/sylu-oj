#!/usr/bin/env bats
# tests/host/preflight.bats —— 预检脚本内容验收（Task 2）
# 校验 infra/host/preflight.sh 覆盖设计文档 1.2 / 12.3 要求的环境核查项。

setup() {
  ROOT="$(cd "$BATS_TEST_DIRNAME/../.." && pwd)"
  SCRIPT="$ROOT/infra/host/preflight.sh"
}

@test "存在预检脚本 infra/host/preflight.sh" {
  [ -f "$SCRIPT" ]
}

@test "检查操作系统 Ubuntu 22.04" {
  grep -q '22\.04' "$SCRIPT"
}

@test "检查 CPU 架构 x86-64" {
  grep -q 'x86_64' "$SCRIPT"
}

@test "检查 CPU 核数不少于 16" {
  grep -qE '_NPROCESSORS_ONLN|nproc' "$SCRIPT"
  grep -qE '16' "$SCRIPT"
}

@test "检查内存不少于 64 GiB" {
  grep -q 'MemTotal' "$SCRIPT"
  grep -qE '64' "$SCRIPT"
}

@test "检查磁盘容量（lsblk）" {
  grep -q 'lsblk' "$SCRIPT"
}

@test "检查 LUKS 磁盘加密" {
  grep -qi 'crypto_LUKS\|LUKS' "$SCRIPT"
}

@test "检查 Secure Boot（mokutil）" {
  grep -qi 'mokutil\|Secure Boot' "$SCRIPT"
}

@test "检查 AppArmor Enforce" {
  grep -q 'aa-status' "$SCRIPT"
  grep -qi 'enforce' "$SCRIPT"
}

@test "检查 cgroups v2" {
  grep -q 'cgroup2fs' "$SCRIPT"
}

@test "检查 KVM /dev/kvm" {
  grep -q '/dev/kvm' "$SCRIPT"
}

@test "检查嵌套虚拟化（kvm_intel/kvm_amd）" {
  grep -q 'kvm_intel' "$SCRIPT"
  grep -q 'kvm_amd' "$SCRIPT"
}

@test "检查 nftables" {
  grep -qE '(^|[^a-z])nft([^a-z]|$)' "$SCRIPT" || grep -q 'nftables' "$SCRIPT"
}

@test "检查时间同步（NTP）" {
  grep -qE 'NTPSynchronized|timedatectl|chrony' "$SCRIPT"
}

@test "检查磁盘余量（df）" {
  grep -q 'df ' "$SCRIPT"
}

@test "检查业务进程清单（旧 CodeOJ 残余）" {
  grep -qi 'codeoj\|code-oj\|pm2' "$SCRIPT"
  grep -q 'systemctl' "$SCRIPT"
}

@test "检查 RAID1（强烈建议项）" {
  grep -qi 'raid1\|mdadm' "$SCRIPT"
}

@test "检查 TPM 2.0（强烈建议项）" {
  grep -qi 'tpm' "$SCRIPT"
}

@test "输出三级分类 BLOCKING / WARN / PASS" {
  grep -q 'BLOCKING' "$SCRIPT"
  grep -q 'WARN' "$SCRIPT"
  grep -q 'PASS' "$SCRIPT"
}

@test "任一 BLOCKING 失败时以非零退出码结束" {
  grep -q 'exit 1' "$SCRIPT"
}

@test "JUDGE 不满足 KVM/嵌套虚拟化时禁止 MicroVM 模式" {
  grep -q 'MicroVM' "$SCRIPT"
}

@test "SSH 加固配置禁止 root 登录与密码认证" {
  SSHD="$ROOT/infra/host/sshd_config.d/99-oj-hardening.conf"
  [ -f "$SSHD" ]
  grep -q 'PermitRootLogin no' "$SSHD"
  grep -q 'PasswordAuthentication no' "$SSHD"
  grep -q 'AllowUsers' "$SSHD"
}
