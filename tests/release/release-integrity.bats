#!/usr/bin/env bats
# tests/release/release-integrity.bats —— 发布供应链与完整性验收（Task 3）
# 校验 scripts/build-release.sh、verify-release.sh、secret-scan.sh 与 infra/images 满足
# 设计文档 12.1/12.2/12.6 的可追溯、可验签、无秘密要求。

setup() {
  ROOT="$(cd "$BATS_TEST_DIRNAME/../.." && pwd)"
  BUILD="$ROOT/scripts/build-release.sh"
  VERIFY="$ROOT/scripts/verify-release.sh"
  SCAN="$ROOT/scripts/secret-scan.sh"
  IMAGES="$ROOT/infra/images"
}

# ---------------- 交付物齐备性 ----------------

@test "存在构建/验证/扫描脚本" {
  [ -f "$BUILD" ]
  [ -f "$VERIFY" ]
  [ -f "$SCAN" ]
}

@test "存在镜像定义与工具链锁" {
  [ -f "$IMAGES/toolchains.lock" ]
  for c in ingress web api judge-agent; do
    [ -f "$IMAGES/$c.Containerfile" ]
  done
  [ -f "$IMAGES/build-vm-images.sh" ]
}

# ---------------- 供应链扫描（SBOM / 漏洞 / 秘密） ----------------

@test "build-release.sh 调用 Syft 生成 SBOM" {
  grep -qi 'syft' "$BUILD"
  grep -qi 'sbom' "$BUILD"
}

@test "build-release.sh 调用 Grype 生成漏洞报告" {
  grep -qi 'grype' "$BUILD"
}

@test "build-release.sh 调用秘密扫描（strict）" {
  grep -q 'secret-scan.sh' "$BUILD"
  grep -q '\--strict' "$BUILD"
}

@test "build-release.sh 用 openssl 对 SHA256SUMS 签名" {
  grep -q 'openssl dgst -sha256 -sign' "$BUILD"
}

@test "build-release.sh 生成 SHA256SUMS 清单" {
  grep -q 'SHA256SUMS' "$BUILD"
}

@test "build-release.sh 写入 release.json（版本/提交/摘要/SBOM）" {
  grep -q 'release.json' "$BUILD"
  grep -q 'version' "$BUILD"
  grep -q 'commit' "$BUILD"
}

# ---------------- 验签与摘要校验顺序 ----------------

@test "verify-release.sh 先验签再校验摘要" {
  grep -q 'openssl dgst -sha256 -verify' "$VERIFY"
  grep -q 'sha256sum -c' "$VERIFY"
}

@test "verify-release.sh 验证后执行秘密扫描（strict）" {
  grep -q 'secret-scan.sh' "$VERIFY"
  grep -q '\--strict' "$VERIFY"
}

@test "verify-release.sh 校验失败以非零退出" {
  grep -q 'exit 1' "$VERIFY"
}

# ---------------- 秘密扫描覆盖范围 ----------------

@test "secret-scan.sh 检测私钥材料" {
  grep -q 'PRIVATE KEY' "$SCAN"
}

@test "secret-scan.sh 检测硬编码密码/密钥" {
  grep -qEi 'password|passwd|secret' "$SCAN"
}

@test "secret-scan.sh 支持 strict 模式（命中即非零退出）" {
  grep -q 'STRICT' "$SCAN"
  grep -q 'exit 1' "$SCAN"
}

# ---------------- 固定版本与离线约束 ----------------

@test "toolchains.lock 固定 C/C++ 编译器、Python、JDK 版本" {
  grep -q 'gcc-11' "$IMAGES/toolchains.lock"
  grep -q 'g++-11' "$IMAGES/toolchains.lock"
  grep -q 'python3' "$IMAGES/toolchains.lock"
  grep -q 'openjdk-17-jdk-headless' "$IMAGES/toolchains.lock"
}

@test "toolchains.lock 每个条目含校验和列" {
  grep -qE 'sha256|TODO:ci-lock' "$IMAGES/toolchains.lock"
}

@test "基础镜像按摘要锁定（FROM ubuntu:22.04@sha256）" {
  for c in ingress web api judge-agent; do
    grep -q 'ubuntu:22.04@sha256' "$IMAGES/$c.Containerfile"
  done
}

@test "VM 镜像构建离线 debootstrap（不从运行时下载）" {
  grep -q 'debootstrap' "$IMAGES/build-vm-images.sh"
  grep -qi '离线\|offline\|file://' "$IMAGES/build-vm-images.sh"
}

# ---------------- 仓库自检：无真实秘密入库 ----------------

@test "仓库内无真实私钥材料" {
  run grep -rE '^-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----' \
    "$ROOT/infra" "$ROOT/scripts" "$ROOT/tests" 2>/dev/null
  [ "$status" -ne 0 ]
}

@test "仓库内 shell/配置无硬编码生产密码" {
  run grep -rEi 'password\s*[:=]\s*["'\''][A-Za-z0-9]{6,}["'\'']' \
    "$ROOT/infra" "$ROOT/scripts" 2>/dev/null
  [ "$status" -ne 0 ]
}
