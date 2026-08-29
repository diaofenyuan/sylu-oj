#!/usr/bin/env bash
#
# secret-scan.sh —— 发布包秘密扫描（Task 3）
#
# 依据设计文档 12.2 第 3 步（依赖、镜像、密钥和恶意文件扫描）与第 7 节
# （密钥、数据库密码和 JWT 私钥不得进入发布包）。发布包不得包含：
#   - 真实密码、TLS 私钥、JWT 私钥、数据库密码；
#   - 旧 CodeOJ 文件（旧 JWT 密钥、后门、部署目录）。
#
# 用法：
#   ./secret-scan.sh --root <发布目录> [--strict] [--tool=gitleaks|trufflehog]
#   --strict 存在命中即退出码 1（供 CI 门禁使用）
#
# 退出码：0 未发现；1 发现命中（--strict）；2 参数错误。
set -euo pipefail

ROOT=""
STRICT=0
TOOL="builtin"

usage() {
  cat <<'EOF'
secret-scan.sh — 发布包秘密扫描

  --root DIR        待扫描目录（必填）
  --strict          存在命中即退出码 1
  --tool NAME       builtin | gitleaks | trufflehog（默认 builtin）
  -h|--help         显示本帮助
EOF
}

log()  { printf '[secret-scan] %s\n' "$*"; }
warn() { printf '[secret-scan][HIT] %s\n' "$*"; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --root)      ROOT="${2:?--root 需要一个参数}"; shift ;;
    --root=*)    ROOT="${1#*=}" ;;
    --strict)    STRICT=1 ;;
    --tool)      TOOL="${2:?--tool 需要一个参数}"; shift ;;
    --tool=*)    TOOL="${1#*=}" ;;
    -h|--help)   usage; exit 0 ;;
    *) echo "未知参数：$1" >&2; exit 2 ;;
  esac
  shift
done

[[ -n "$ROOT" && -d "$ROOT" ]] || { echo "必须指定存在的 --root 目录" >&2; exit 2; }

HITS=0

record() { HITS=$((HITS + 1)); warn "$1"; }

# 1) 私钥材料（PEM/OpenSSH 私钥头）
scan_private_keys() {
  local f
  while IFS= read -r -d '' f; do
    # 跳过示例与文档，但 .pem/.key 若含 PRIVATE KEY 仍命中
    if grep -qE '^-----BEGIN (RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----' "$f" 2>/dev/null; then
      record "私钥材料：$f"
    fi
  done < <(find "$ROOT" -type f -print0)
}

# 2) 硬编码密码 / JWT 私钥（仅检查非示例、非文档配置）
scan_hardcoded_secrets() {
  local f
  while IFS= read -r -d '' f; do
    case "$f" in
      *.example|*.md|*.txt|*.json.sig|*.sig) continue ;;
    esac
    if grep -qEi '(password|passwd|secret|jwt[_-]?(secret|private)|private[_-]?key)\s*[:=]\s*["'\''][^"'\''[:space:]]+["'\'']' "$f" 2>/dev/null; then
      record "疑似硬编码凭据：$f"
    fi
  done < <(find "$ROOT" -type f -print0)
}

# 3) 旧 CodeOJ 文件与标记：按文件/目录名识别旧产物（旧代码、旧密钥、旧部署目录）。
#    仅匹配名称，不做全文"codeoj"字符串扫描——否则会误伤 preflight.sh 等
#    专门用于"检测/清理旧 CodeOJ"的加固脚本本身。
scan_legacy_codeoj() {
  local p
  while IFS= read -r -d '' p; do
    record "旧 CodeOJ 文件/目录：$p"
  done < <(find "$ROOT" \( \
      -iname '*codeoj*' \
      -o -iname '*code-oj*' \
      -o -iname '*code_oj*' \
      -o -iname 'ecosystem.config.*' \
      -o -iname '.pm2*' \) -print0 2>/dev/null)
}

# 可选外部工具（若安装了 gitleaks/trufflehog）
scan_external() {
  case "$TOOL" in
    gitleaks)
      if command -v gitleaks >/dev/null 2>&1; then
        gitleaks detect --source "$ROOT" --no-git --report-format json >/dev/null 2>&1 && return 0
        record "gitleaks 报告命中"
      else
        log "未安装 gitleaks，跳过"
      fi
      ;;
    trufflehog)
      if command -v trufflehog >/dev/null 2>&1; then
        trufflehog filesystem "$ROOT" --no-update --fail 2>/dev/null && return 0
        record "trufflehog 报告命中"
      else
        log "未安装 trufflehog，跳过"
      fi
      ;;
    builtin) : ;;
    *) echo "未知 --tool：$TOOL" >&2; exit 2 ;;
  esac
}

scan_private_keys
scan_hardcoded_secrets
scan_legacy_codeoj
scan_external

if [[ "$HITS" -gt 0 ]]; then
  log "发现 ${HITS} 处命中"
  if [[ "$STRICT" -eq 1 ]]; then
    exit 1
  fi
else
  log "未发现秘密或旧 CodeOJ 文件"
fi

exit 0
