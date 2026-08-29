#!/usr/bin/env bash
#
# verify-release.sh —— 发布包验签、摘要校验与秘密扫描（Task 3）
#
# 依据设计文档 12.2 第 4 步与 12.6「部署包验收」：部署主机预置只读公钥，安装前
# 先验签再校验摘要，并对发布包做秘密扫描，确保不含真实密码、证书私钥、JWT 私钥。
#
# 用法：
#   ./verify-release.sh --release-dir /path/oj-release-v1.0.0-linux-amd64 \
#       --public-key /secure/oj-release.pub
#
# 退出码：0 验签与摘要通过且无秘密；1 任一校验失败。
set -euo pipefail

RELEASE_DIR=""
PUBKEY=""

usage() {
  cat <<'EOF'
verify-release.sh — 发布包验签、摘要校验与秘密扫描

  --release-dir DIR  发布目录（含 manifest/SHA256SUMS 与 .sig）
  --public-key FILE  离线发布公钥（OpenSSL 公钥）
  -h|--help          显示本帮助
EOF
}

log()  { printf '[verify-release] %s\n' "$*"; }
die()  { printf '[verify-release][ERROR] %s\n' "$*" >&2; exit 1; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --release-dir)     RELEASE_DIR="${2:?--release-dir 需要一个参数}"; shift ;;
    --release-dir=*)   RELEASE_DIR="${1#*=}" ;;
    --public-key)      PUBKEY="${2:?--public-key 需要一个参数}"; shift ;;
    --public-key=*)    PUBKEY="${1#*=}" ;;
    -h|--help)         usage; exit 0 ;;
    *) die "未知参数：$1" ;;
  esac
  shift
done

[[ -n "$RELEASE_DIR" && -d "$RELEASE_DIR" ]] || die "必须指定存在的 --release-dir"
[[ -n "$PUBKEY" && -f "$PUBKEY" ]] || die "必须指定存在的 --public-key"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MANIFEST_DIR="$RELEASE_DIR/manifest"
SUMS="$MANIFEST_DIR/SHA256SUMS"
SIG="$MANIFEST_DIR/SHA256SUMS.sig"

[[ -f "$SUMS" ]] || die "缺少 SHA256SUMS"
[[ -f "$SIG" ]] || die "缺少 SHA256SUMS.sig"

# ---- 1) 验签（先验签，再校验摘要，顺序不可颠倒）----
log "验证签名"
if ! openssl dgst -sha256 -verify "$PUBKEY" -signature "$SIG" "$SUMS" >/dev/null 2>&1; then
  die "签名验证失败，发布包可能被篡改"
fi
log "签名验证通过"

# ---- 2) 校验摘要 ----
log "校验文件摘要"
( cd "$RELEASE_DIR" && sha256sum -c "$MANIFEST_DIR/SHA256SUMS" --quiet ) \
  || die "摘要校验失败，存在文件被篡改"
log "摘要校验通过"

# ---- 3) 秘密扫描（严格模式，命中即失败）----
log "运行秘密扫描"
"$SCRIPT_DIR/secret-scan.sh" --root "$RELEASE_DIR" --strict \
  || die "发布包含秘密"

log "发布包验签、摘要与秘密扫描全部通过"
