#!/usr/bin/env bats
# tests/network/port-matrix.bats —— 端口矩阵与安全域验收（Task 2）
# 校验虚拟防火墙规则集、宿主机防火墙与 libvirt 网络/域定义满足设计文档第 2、3 节。

setup() {
  ROOT="$(cd "$BATS_TEST_DIRNAME/../.." && pwd)"
  FW="$ROOT/infra/libvirt/firewall/virtual-firewall.nft"
  HOST_NFT="$ROOT/infra/host/nftables.conf"
  NET_DIR="$ROOT/infra/libvirt/networks"
  DOM_DIR="$ROOT/infra/libvirt/domains"
}

# ---------------- 交付物齐备性 ----------------

@test "存在虚拟防火墙规则集 virtual-firewall.nft" {
  [ -f "$FW" ]
}

@test "存在宿主机防火墙 nftables.conf" {
  [ -f "$HOST_NFT" ]
}

@test "定义六个独立安全域网络（DMZ/WEB/API/DATA/JUDGE/OPS）" {
  for n in dmz web api data judge ops; do
    [ -f "$NET_DIR/oj-$n.xml" ]
  done
}

@test "定义六个安全域 VM 与虚拟防火墙 VM" {
  for d in ingress web api data judge ops firewall; do
    [ -f "$DOM_DIR/$d.xml" ]
  done
}

# ---------------- 六域隔离与默认拒绝 ----------------

@test "六个网络均为隔离二层网络（无 NAT/转发）" {
  for n in dmz web api data judge ops; do
    run grep -q "<forward mode='none'/>" "$NET_DIR/oj-$n.xml"
    [ "$status" -eq 0 ]
  done
}

@test "虚拟防火墙默认拒绝（policy drop）" {
  grep -q 'policy drop' "$FW"
}

@test "宿主机防火墙默认拒绝（policy drop）" {
  grep -q 'policy drop' "$HOST_NFT"
}

@test "宿主机不做业务网段三层转发（forward policy drop）" {
  run bash -c "grep -A2 'hook forward' '$HOST_NFT' | grep -q 'policy drop'"
  [ "$status" -eq 0 ]
}

# ---------------- 端口矩阵：允许项 ----------------

@test "校园网/VPN -> 入口 DMZ 仅 TCP 443" {
  grep -q 'dport 443' "$FW"
}

@test "DMZ -> WEB TCP 8080（Vue 静态资源反向代理）" {
  grep -q 'dport 8080' "$FW"
}

@test "DMZ -> API TCP 8443（内部 mTLS）" {
  grep -q 'dport 8443' "$FW"
}

@test "API -> MySQL TCP 3306" {
  grep -q 'dport 3306' "$FW"
}

@test "API -> Redis TCP 6379" {
  grep -q 'dport 6379' "$FW"
}

@test "API -> RabbitMQ TCP 5671（AMQP over TLS）" {
  grep -q 'dport 5671' "$FW"
}

@test "JUDGE -> API Judge Gateway TCP 8443（mTLS 主动拉取）" {
  grep -q 'dport 8443' "$FW"
}

@test "业务 VM -> OPS TLS Syslog TCP 6514" {
  grep -q 'dport 6514' "$FW"
}

@test "OPS -> 各 VM 指标采集 TCP 9100" {
  grep -q 'dport 9100' "$FW"
}

@test "管理来源 -> 各 VM SSH 22（来源受限）" {
  grep -q 'dport 22' "$FW"
}

# ---------------- 端口矩阵：禁止项 ----------------

@test "校园网/VPN 入口不暴露 SSH(22)" {
  run grep -E 'iifname "eth0".*dport 22' "$FW"
  [ "$status" -ne 0 ]
}

@test "校园网/VPN 入口不暴露 MySQL/Redis/RabbitMQ" {
  run grep -E 'iifname "eth0".*(3306|6379|5671)' "$FW"
  [ "$status" -ne 0 ]
}

@test "规则集不向外部暴露 Prometheus/Grafana 端口" {
  run grep -E '9090|3000' "$FW"
  [ "$status" -ne 0 ]
}

@test "宿主机 SSH 仅允许管理来源" {
  run bash -c "grep -E 'dport 22' '$HOST_NFT' | grep -v 'saddr @mgmt_addrs'"
  [ -z "$output" ]
}

# ---------------- JUDGE 嵌套虚拟化 ----------------

@test "JUDGE VM 使用 host-passthrough CPU（嵌套虚拟化前提）" {
  grep -q "cpu mode='host-passthrough'" "$DOM_DIR/judge.xml"
}

@test "非 JUDGE 域不使用 host-passthrough（避免不必要的 VMX/SVM 暴露）" {
  for d in ingress web api data ops firewall; do
    run grep -q "host-passthrough" "$DOM_DIR/$d.xml"
    [ "$status" -ne 0 ]
  done
}
