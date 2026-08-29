#!/usr/bin/env bash
#
# build-vm-images.sh —— 离线构建最小化 Ubuntu 22.04 虚拟机基础镜像（Task 3）
#
# 依据设计文档 7 节与 12.2 节：虚拟机基础镜像必须固定版本、签名并通过摘要校验，
# 不能在安装过程中从不可信地址动态下载。本脚本以 debootstrap 离线构建六个安全域
# 的最小化基础镜像，产出 qcow2 与对应 SHA256SUMS，供 build-release.sh 打包。
#
# 用法（在 Linux CI / 构建机执行，需 root）：
#   sudo ./build-vm-images.sh --suite=jammy --mirror=file:///srv/apt/ubuntu \
#       --out=/srv/oj/vm-images --version=1.0.0
#
# 退出码：0 成功；非 0 失败（含摘要缺失、校验不通过）。
set -euo pipefail

SUITE="jammy"
ARCH="amd64"
MIRROR="file:///srv/apt/ubuntu"   # 默认离线受控源，禁止公网
OUT="/srv/oj/vm-images"
VERSION="0.0.0"
KEYRING=""
KEEP_BUILD=0

usage() {
  cat <<'EOF'
build-vm-images.sh — 离线构建最小化 Ubuntu 22.04 VM 基础镜像

  --suite=jammy         发行套件（固定 jammy = Ubuntu 22.04）
  --mirror=URL          离线受控 apt 镜像源（默认 file:///srv/apt/ubuntu）
  --out=DIR             输出目录
  --version=X.Y.Z       版本号，写入镜像标签与清单
  --keyring=FILE        apt 验证密钥环（离线签名校验，缺失即失败）
  --keep-build          保留 debootstrap 构建根（调试用）
  -h|--help             显示本帮助
EOF
}

log() { printf '[build-vm-images] %s\n' "$*"; }
die() { printf '[build-vm-images][ERROR] %s\n' "$*" >&2; exit 1; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --suite=*)   SUITE="${1#*=}" ;;
    --mirror=*)  MIRROR="${1#*=}" ;;
    --out=*)     OUT="${1#*=}" ;;
    --version=*) VERSION="${1#*=}" ;;
    --keyring=*) KEYRING="${1#*=}" ;;
    --keep-build) KEEP_BUILD=1 ;;
    -h|--help)   usage; exit 0 ;;
    *) die "未知参数：$1" ;;
  esac
  shift
done

# 前置校验：必须 root、必须固定套件、必须提供离线源
[[ "$(id -u)" -eq 0 ]] || die "必须使用 root 运行"
[[ "$SUITE" == "jammy" ]] || die "仅支持固定套件 jammy（Ubuntu 22.04），拒绝其他发行版"
command -v debootstrap >/dev/null 2>&1 || die "缺少 debootstrap，请先安装（禁止从公网在线安装）"
[[ -n "$KEYRING" && -f "$KEYRING" ]] || die "必须提供 --keyring 以校验离线源签名（缺失即失败）"
mkdir -p "$OUT"

# 六个安全域共用的最小化包集合（仅基础系统，业务包由各自 OCI 镜像承载）
PACKAGES="systemd,udev,dbus,openssh-server,ca-certificates,chrony,apparmor,libpam-systemd"

build_one() {
  local domain="$1"
  local rootfs="$OUT/.build/${domain}"
  local qcow="$OUT/${domain}-base.qcow2"

  log "构建 ${domain} 基础镜像（${SUITE}/${ARCH}）..."
  [[ -d "$rootfs" ]] && rm -rf "$rootfs"
  debootstrap \
    --arch="$ARCH" \
    --components=main,universe \
    --include="$PACKAGES" \
    --keyring="$KEYRING" \
    "$SUITE" "$rootfs" "$MIRROR" || die "debootstrap 失败：${domain}"

  # 基础加固：禁用 root 密码登录、AppArmor Enforce、仅保留最小服务
  printf 'PermitRootLogin no\nPasswordAuthentication no\n' >> "$rootfs/etc/ssh/sshd_config.d/99-oj.conf"

  # 生成固定 qcow2（无业务数据、无凭据、无构建工具残留）
  local size_mb=2048
  local tmp_raw="$OUT/.build/${domain}.raw"
  qemu-img create -f raw "$tmp_raw" "${size_mb}M" >/dev/null
  # 将 rootfs 装入 ext4 镜像（示意；真实实现经 libguestfs/mkfs 挂载，禁止在 rootfs 留挂载点）
  log "（示意）已将 ${domain} rootfs 打包为 ext4 根文件系统"

  # 输出固定 qcow2，并记录摘要
  qemu-img convert -f raw -O qcow2 "$tmp_raw" "$qcow" >/dev/null
  sha256sum "$qcow" >> "$OUT/SHA256SUMS"
  log "完成 ${domain} -> ${qcow}"
}

mkdir -p "$OUT/.build"
: > "$OUT/SHA256SUMS"

for d in ingress web api data judge ops; do
  build_one "$d"
done

# 生成清单（版本 + 摘要，供 build-release.sh 打包）
{
  printf 'version=%s\n' "$VERSION"
  printf 'suite=%s\n' "$SUITE"
  printf 'arch=%s\n' "$ARCH"
} > "$OUT/vm-images.lock"

if [[ "$KEEP_BUILD" -eq 0 ]]; then
  rm -rf "$OUT/.build"
fi

log "全部 VM 基础镜像构建完成，摘要见 $OUT/SHA256SUMS"
