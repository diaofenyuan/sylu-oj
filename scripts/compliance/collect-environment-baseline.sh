#!/usr/bin/env bash
#
# collect-environment-baseline.sh
# 一键只读环境采集脚本：回填 docs/compliance/environment-baseline.md 表 A~F。
#
# 约束：
#   - 只读执行，不修改任何系统配置；
#   - 必须在生产 Ubuntu 22.04 服务器上以普通用户（必要时 sudo -n 只读探测）运行；
#   - 输出 Markdown 片段，可直接粘贴进环境基线记录。
#
set -uo pipefail

section() { printf '\n## %s\n' "$1"; }
run()     { printf '### %s\n```\n' "$1"; shift; "$@" 2>&1; printf '```\n'; }

echo '# 环境基线采集结果（自动生成）'

section '表 A：操作系统与硬件'
run '操作系统' sh -c 'cat /etc/os-release | head -n 6'
run '架构' uname -m
run '物理机判定' sh -c 'systemd-detect-virt || true'
run 'CPU 核数' lscpu
run '硬件虚拟化' sh -c "lscpu | grep -Ei 'vmx|svm' || echo '未发现 vmx/svm 标志'"
run '内存(GB)' free -g
run '磁盘' lsblk

section '表 B：虚拟化与嵌套虚拟化'
run '/dev/kvm' sh -c 'ls -l /dev/kvm 2>&1 || echo "不存在"'
run 'libvirt' sh -c 'virsh version 2>&1 || true'
run '嵌套虚拟化(Intel)' sh -c 'cat /sys/module/kvm_intel/parameters/nested 2>/dev/null || echo "无 kvm_intel"'
run '嵌套虚拟化(AMD)' sh -c 'cat /sys/module/kvm_amd/parameters/nested 2>/dev/null || echo "无 kvm_amd"'
run 'cgroups v2' sh -c 'stat -fc %T /sys/fs/cgroup'

section '表 C：主机安全加固'
run 'Secure Boot' sh -c 'mokutil --sb-state 2>&1 || true'
run '磁盘加密 LUKS' sh -c 'lsblk -f | grep -i luks || echo "未发现 LUKS 卷"'
run 'AppArmor' sh -c 'aa-status 2>&1 || true'
run '防火墙 nftables' sh -c 'nft --version 2>&1 || true'
run 'SSH PermitRootLogin' sh -c "sshd -T 2>/dev/null | grep -i permitrootlogin || echo '未获取到'"
run 'sudo 配置' sh -c 'visudo -c 2>&1 || true'

section '表 D：网络与时间同步'
run '网卡与地址' ip addr
run '时间同步 chronyc' sh -c 'chronyc sources 2>&1 || true'

section '表 E：存储与容量'
run '磁盘余量' df -h

section '表 F：业务进程与残余检查'
run '残留业务服务进程' sh -c "systemctl list-units --type=service --all 2>/dev/null | grep -iE 'pm2|node|express' || echo '未发现残留业务服务'"
run '业务进程清单' sh -c 'ps -eo user,pid,cmd --sort=user'

echo ''
echo '采集完成：以上输出请回填 docs/compliance/environment-baseline.md 各表。'
