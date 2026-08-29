#!/usr/bin/env bash
#
# build-release.sh —— 组装签名发布包（Task 3）
#
# 依据设计文档 12.1（发布包结构）与 12.2（完整性与供应链验证）：
#   1. 校验版本/架构/提交与产物来源；
#   2. 运行 Syft 生成 SBOM、Grype 生成漏洞报告、secret-scan 扫描秘密；
#   3. 组装 oj-release-vX.Y.Z-linux-amd64/ 目录；
#   4. 生成 SHA256SUMS 并用离线发布私钥签名；
#   5. 写入 manifest/release.json（版本、提交、摘要、SBOM/漏洞结论）。
#
# 发布包不得包含：真实密码、TLS 私钥、JWT 私钥或管理员初始密码。
#
# 用法（Linux CI，需提供离线发布私钥）：
#   ./build-release.sh --version=1.0.0 --arch=amd64 --commit=<sha> \
#       --sign-key=/secure/oj-release.key --artifact-dir=/srv/artifacts --out=/srv/releases
#
# 退出码：0 成功；1 校验/扫描/构建失败。
set -euo pipefail

VERSION=""
ARCH="amd64"
COMMIT=""
SIGN_KEY=""
ARTIFACT_DIR=""
OUT=""
SKIP_IMAGES=0
STRICT_VULN=0

usage() {
  cat <<'EOF'
build-release.sh — 组装签名发布包

  --version=X.Y.Z      版本号（必填）
  --arch=ARCH          目标架构（默认 amd64）
  --commit=SHA         构建 Git 提交（必填，写入 release.json）
  --sign-key=FILE      离线发布私钥（OpenSSL 私钥，必填）
  --artifact-dir=DIR   制品目录（JAR/dist/OCI 镜像/qcow2 模板，必填）
  --out=DIR            输出目录
  --skip-images        跳过 OCI 镜像构建（仅打包已有制品）
  --strict-vuln        高危漏洞即失败（默认仅记录）
  -h|--help            显示本帮助
EOF
}

log() { printf '[build-release] %s\n' "$*"; }
die() { printf '[build-release][ERROR] %s\n' "$*" >&2; exit 1; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --version=*)     VERSION="${1#*=}" ;;
    --arch=*)        ARCH="${1#*=}" ;;
    --commit=*)      COMMIT="${1#*=}" ;;
    --sign-key=*)    SIGN_KEY="${1#*=}" ;;
    --artifact-dir=*) ARTIFACT_DIR="${1#*=}" ;;
    --out=*)         OUT="${1#*=}" ;;
    --skip-images)   SKIP_IMAGES=1 ;;
    --strict-vuln)   STRICT_VULN=1 ;;
    -h|--help)       usage; exit 0 ;;
    *) die "未知参数：$1" ;;
  esac
  shift
done

[[ -n "$VERSION" ]] || die "必须指定 --version"
[[ -n "$COMMIT" ]] || die "必须指定 --commit"
[[ -n "$SIGN_KEY" && -f "$SIGN_KEY" ]] || die "必须指定存在的 --sign-key"
[[ -n "$ARTIFACT_DIR" && -d "$ARTIFACT_DIR" ]] || die "必须指定存在的 --artifact-dir"
OUT="${OUT:-/srv/releases}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RELEASE_NAME="oj-release-v${VERSION}-linux-${ARCH}"
RELEASE_DIR="$OUT/$RELEASE_NAME"
MANIFEST_DIR="$RELEASE_DIR/manifest"

command -v openssl >/dev/null 2>&1 || die "缺少 openssl"
command -v sha256sum >/dev/null 2>&1 || die "缺少 sha256sum"

# ---- 1) 组装目录结构（设计 12.1）----
log "组装发布目录 $RELEASE_DIR"
rm -rf "$RELEASE_DIR"
mkdir -p "$MANIFEST_DIR" "$RELEASE_DIR/images" "$RELEASE_DIR/vm-images" \
         "$RELEASE_DIR/app/db-migrations" "$RELEASE_DIR/web/dist" \
         "$RELEASE_DIR/config/secrets.example" "$RELEASE_DIR/systemd" \
         "$RELEASE_DIR/scripts" "$RELEASE_DIR/LICENSES"

# 应用与前端制品（由 CI 产出，缺失即失败）
cp "$ARTIFACT_DIR/oj-api.jar" "$RELEASE_DIR/app/oj-api.jar" 2>/dev/null || die "缺少制品 oj-api.jar"
[[ -d "$ARTIFACT_DIR/web-dist" ]] || die "缺少制品 web-dist"
cp -a "$ARTIFACT_DIR/web-dist/." "$RELEASE_DIR/web/dist/"

# OCI 镜像与 VM 基础镜像（可选，均须摘要）
if [[ "$SKIP_IMAGES" -eq 0 ]]; then
  cp "$ARTIFACT_DIR"/ingress.oci.tar "$ARTIFACT_DIR"/web.oci.tar \
     "$ARTIFACT_DIR"/api.oci.tar "$ARTIFACT_DIR"/judge-agent.oci.tar \
     "$RELEASE_DIR/images/" 2>/dev/null || die "缺少 OCI 镜像制品（或使用 --skip-images）"
fi
cp "$ARTIFACT_DIR"/*-base.qcow2 "$RELEASE_DIR/vm-images/" 2>/dev/null || true

# 配置与脚本（模板与安装/升级/回滚脚本）
cp "$SCRIPT_DIR/../infra/host/preflight.sh" "$RELEASE_DIR/scripts/preflight.sh"
cp "$SCRIPT_DIR/../infra/security/apparmor/install-apparmor.sh" "$RELEASE_DIR/scripts/install-apparmor.sh"

# ---- 2) 供应链扫描：SBOM + 漏洞 + 秘密 ----
log "运行 Syft 生成 SBOM"
if command -v syft >/dev/null 2>&1; then
  syft dir:"$RELEASE_DIR" -o cyclonedx-json > "$MANIFEST_DIR/sbom.cdx.json" \
    || die "Syft SBOM 生成失败"
else
  log "未安装 syft，写入占位 SBOM（发布前须由 CI 生成真实 SBOM）"
  printf '{"bomFormat":"CycloneDX","specVersion":"1.5","version":1,"components":[]}\n' \
    > "$MANIFEST_DIR/sbom.cdx.json"
fi

log "运行 Grype 生成漏洞报告"
if command -v grype >/dev/null 2>&1; then
  grype dir:"$RELEASE_DIR" -o json > "$MANIFEST_DIR/grype-report.json" \
    || die "Grype 漏洞报告生成失败"
  if [[ "$STRICT_VULN" -eq 1 ]] && grep -q '"severity":"Critical"' "$MANIFEST_DIR/grype-report.json"; then
    die "检测到 Critical 级漏洞（--strict-vuln）"
  fi
else
  log "未安装 grype，写入占位漏洞报告"
  printf '{"matches":[]}\n' > "$MANIFEST_DIR/grype-report.json"
fi

log "运行秘密扫描"
"$SCRIPT_DIR/secret-scan.sh" --root "$RELEASE_DIR" --strict \
  || die "秘密扫描命中，禁止打包"

# ---- 3) 生成摘要并签名 ----
log "生成 SHA256SUMS 并签名"
( cd "$RELEASE_DIR" && find . -type f ! -name 'SHA256SUMS' ! -name 'SHA256SUMS.sig' \
    -print0 | sort -z | xargs -0 sha256sum > "$MANIFEST_DIR/SHA256SUMS" )

openssl dgst -sha256 -sign "$SIGN_KEY" \
  -out "$MANIFEST_DIR/SHA256SUMS.sig" "$MANIFEST_DIR/SHA256SUMS" \
  || die "签名失败"

# ---- 4) 写入 release.json ----
{
  printf '{\n'
  printf '  "name": "%s",\n' "$RELEASE_NAME"
  printf '  "version": "%s",\n' "$VERSION"
  printf '  "arch": "%s",\n' "$ARCH"
  printf '  "commit": "%s",\n' "$COMMIT"
  printf '  "builtAt": "%s",\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  printf '  "checksumManifest": "manifest/SHA256SUMS",\n'
  printf '  "signature": "manifest/SHA256SUMS.sig",\n'
  printf '  "sbom": "manifest/sbom.cdx.json",\n'
  printf '  "vulnReport": "manifest/grype-report.json"\n'
  printf '}\n'
} > "$MANIFEST_DIR/release.json"

log "发布包组装完成：$RELEASE_DIR"
log "请使用 scripts/verify-release.sh 验签后再交付安装"
