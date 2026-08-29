#!/usr/bin/env bats
# tests/security/apparmor-systemd.bats —— AppArmor 与 systemd 加固验收（Task 3）
# 校验 infra/security/apparmor/ 与 infra/security/systemd/ 满足设计文档 7.1 与 12.3 第 5 步。

setup() {
  ROOT="$(cd "$BATS_TEST_DIRNAME/../.." && pwd)"
  AA="$ROOT/infra/security/apparmor"
  SD="$ROOT/infra/security/systemd"
}

# ---------------- 交付物齐备性 ----------------

@test "存在 AppArmor profile 目录与安装脚本" {
  [ -d "$AA" ]
  [ -f "$AA/install-apparmor.sh" ]
}

@test "覆盖 Ingress/API/Judge Agent/数据服务 profile" {
  for p in usr.sbin.nginx usr.bin.java usr.bin.judge-agent oj.sandbox \
           usr.sbin.mysqld usr.sbin.redis-server usr.sbin.rabbitmq-server; do
    [ -f "$AA/$p" ]
  done
}

@test "存在七个 systemd 加固 drop-in" {
  for s in oj-ingress oj-web oj-api oj-judge-agent oj-mysql oj-redis oj-rabbitmq; do
    [ -f "$SD/$s.service.d/10-hardening.conf" ]
  done
}

# ---------------- complain → Enforce 工作流 ----------------

@test "安装脚本支持 complain 与 enforce 两个阶段" {
  grep -q 'complain' "$AA/install-apparmor.sh"
  grep -q 'enforce' "$AA/install-apparmor.sh"
}

@test "安装脚本包含 aa-complain 与 aa-enforce" {
  grep -q 'aa-complain' "$AA/install-apparmor.sh"
  grep -q 'aa-enforce' "$AA/install-apparmor.sh"
}

@test "Enforce 后以 aa-status 断言 enforce 模式" {
  grep -q 'aa-status' "$AA/install-apparmor.sh"
  grep -q 'enforce mode' "$AA/install-apparmor.sh"
}

# ---------------- 判题 profile 强制 deny（设计 6.2 / 7.1 第 2 条） ----------------

@test "Judge Agent profile 禁止密钥目录" {
  grep -q 'deny /etc/oj/secrets/' "$AA/usr.bin.judge-agent"
  grep -q 'deny /etc/oj/credentials/' "$AA/usr.bin.judge-agent"
}

@test "Judge Agent profile 禁止其他服务数据" {
  grep -q 'deny /var/lib/oj/data/' "$AA/usr.bin.judge-agent"
  grep -q 'deny /var/lib/mysql/' "$AA/usr.bin.judge-agent"
  grep -q 'deny /var/lib/redis/' "$AA/usr.bin.judge-agent"
  grep -q 'deny /var/lib/rabbitmq/' "$AA/usr.bin.judge-agent"
}

@test "Judge Agent profile 禁止宿主机目录与 Docker Socket" {
  grep -q 'deny /mnt/' "$AA/usr.bin.judge-agent"
  grep -q 'deny /var/run/docker.sock' "$AA/usr.bin.judge-agent"
  grep -q 'deny /run/docker.sock' "$AA/usr.bin.judge-agent"
}

@test "判题沙箱 profile 禁止网络与能力（无网卡/能力清零）" {
  grep -q 'deny network' "$AA/oj.sandbox"
  grep -q 'deny capability' "$AA/oj.sandbox"
  grep -q 'deny /proc/' "$AA/oj.sandbox"
  grep -q 'deny /sys/' "$AA/oj.sandbox"
}

@test "数据服务 profile 禁止访问应用密钥目录" {
  grep -q 'deny /etc/oj/secrets/' "$AA/usr.sbin.mysqld"
  grep -q 'deny /etc/oj/secrets/' "$AA/usr.sbin.redis-server"
  grep -q 'deny /etc/oj/secrets/' "$AA/usr.sbin.rabbitmq-server"
}

# ---------------- systemd 加固字段（设计 12.3 第 5 步） ----------------

@test "每个服务启用 NoNewPrivileges=true" {
  for s in oj-ingress oj-web oj-api oj-judge-agent oj-mysql oj-redis oj-rabbitmq; do
    grep -q '^NoNewPrivileges=true' "$SD/$s.service.d/10-hardening.conf"
  done
}

@test "每个服务启用 ProtectSystem=strict" {
  for s in oj-ingress oj-web oj-api oj-judge-agent oj-mysql oj-redis oj-rabbitmq; do
    grep -q '^ProtectSystem=strict' "$SD/$s.service.d/10-hardening.conf"
  done
}

@test "每个服务启用 PrivateTmp=true" {
  for s in oj-ingress oj-web oj-api oj-judge-agent oj-mysql oj-redis oj-rabbitmq; do
    grep -q '^PrivateTmp=true' "$SD/$s.service.d/10-hardening.conf"
  done
}

@test "每个服务限制地址族 RestrictAddressFamilies" {
  for s in oj-ingress oj-web oj-api oj-judge-agent oj-mysql oj-redis oj-rabbitmq; do
    grep -q '^RestrictAddressFamilies=' "$SD/$s.service.d/10-hardening.conf"
  done
}

@test "每个服务使用独立服务用户 User=" {
  for s in oj-ingress oj-web oj-api oj-judge-agent oj-mysql oj-redis oj-rabbitmq; do
    grep -q '^User=' "$SD/$s.service.d/10-hardening.conf"
  done
}

@test "每个服务能力清零 CapabilityBoundingSet=（空集）" {
  for s in oj-ingress oj-web oj-api oj-judge-agent oj-mysql oj-redis oj-rabbitmq; do
    grep -q '^CapabilityBoundingSet=$' "$SD/$s.service.d/10-hardening.conf"
  done
}

@test "业务目录运行时只读 ReadOnlyPaths 包含 /opt/oj" {
  for s in oj-ingress oj-web oj-api oj-judge-agent oj-mysql oj-redis oj-rabbitmq; do
    grep -q '^ReadOnlyPaths=.*\/opt\/oj' "$SD/$s.service.d/10-hardening.conf"
  done
}

@test "每个服务配置资源上限（MemoryMax/TasksMax/LimitNOFILE）" {
  for s in oj-ingress oj-web oj-api oj-judge-agent oj-mysql oj-redis oj-rabbitmq; do
    grep -q '^MemoryMax=' "$SD/$s.service.d/10-hardening.conf"
    grep -q '^TasksMax=' "$SD/$s.service.d/10-hardening.conf"
    grep -q '^LimitNOFILE=' "$SD/$s.service.d/10-hardening.conf"
  done
}

@test "Judge Agent 阻断 Docker Socket 与数据区路径（InaccessiblePaths）" {
  grep -q 'InaccessiblePaths=.*docker.sock' "$SD/oj-judge-agent.service.d/10-hardening.conf"
  grep -q 'InaccessiblePaths=.*/var/lib/mysql' "$SD/oj-judge-agent.service.d/10-hardening.conf"
}
