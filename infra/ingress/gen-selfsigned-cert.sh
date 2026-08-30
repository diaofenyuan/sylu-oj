#!/usr/bin/env bash
# 生成 DMZ 入口自签名证书（Task 8，内测模式）。
# 无域名已确认（2026-08-29）：内测阶段使用自签名证书，仅限内测地址访问；
# 师生首次访问会看到证书告警，须配合内测明示横幅使用，不得用于正式考试。
# 取得可信 HTTPS（域名 + DNS-01 / 校内 CA / 学校可信入口）后按
# docs/runbooks/ingress-and-monitoring.md 替换证书并归档本脚本的产出。
set -euo pipefail

OUT_DIR="${1:-/etc/oj-ingress/tls}"
DAYS="${2:-397}"
VALID_IPS="${OJ_INGRESS_IPS:-192.168.30.10}"   # 入口 VM 内网 IP，逗号分隔

mkdir -p "$OUT_DIR"
chmod 700 "$OUT_DIR"

SAN=""
IFS=',' read -ra IPS <<< "$VALID_IPS"
for i in "${!IPS[@]}"; do
  SAN+="IP:${IPS[$i]},"
done

SUBJ="/C=CN/O=SYLU-OJ/OU=Internal Beta/CN=oj-ingress-internal-beta"
cat > "$OUT_DIR/san.cnf" <<EOF
[req]
distinguished_name = dn
req_extensions = ext
prompt = no
[dn]
C = CN
O = SYLU-OJ
OU = Internal Beta
CN = oj-ingress-internal-beta
[ext]
subjectAltName = ${SAN%,}
EOF

# 一次性自签名证书：私钥不出 DMZ，权限收紧，不入仓库
openssl req -x509 -newkey rsa:3072 -nodes \
  -keyout "$OUT_DIR/ingress.key" \
  -out "$OUT_DIR/ingress.crt" \
  -days "$DAYS" -config "$OUT_DIR/san.cnf" \
  -addext "keyUsage = digitalSignature, keyEncipherment" \
  -addext "extendedKeyUsage = serverAuth" \
  -addext "basicConstraints = critical,CA:FALSE"

chmod 600 "$OUT_DIR/ingress.key"
chmod 644 "$OUT_DIR/ingress.crt"
rm -f "$OUT_DIR/san.cnf"

# 内测证书带指纹与到期日期，供监控（证书临期告警）与安装报告引用
openssl x509 -in "$OUT_DIR/ingress.crt" -noout -fingerprint -sha256 \
  -enddate | tee "$OUT_DIR/ingress.meta"
echo "自签名入口证书已生成于 $OUT_DIR（内测模式；到期日见 ingress.meta）"
