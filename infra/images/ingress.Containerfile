# ingress.Containerfile —— 入口区（DMZ）Nginx + WAF + L7 反向代理
#
# 对应设计文档 2.1 入口区：唯一接受校园网与 VPN 来源 HTTPS 的区域，不存储业务
# 数据、数据库凭据、JWT 私钥和运维凭据；TLS 私钥是本区唯一高价值资产，受文件
# 权限、ProtectSystem=strict 与只读挂载保护。
#
# 构建约束：
#   - 基础镜像按摘要锁定（CI 注入 <digest>），禁止 latest；
#   - 运行时非 root，监听 8443 由 systemd 端口转发到 443 由宿主机/IPtables 处理；
#   - 不携带任何编译工具与构建凭据。
#
# 用法（CI 离线构建，先解析摘要再构建）：
#   buildah build --build-arg BASE_DIGEST=sha256:... -f ingress.Containerfile -t oj/ingress:VERSION .

ARG BASE_DIGEST
FROM ubuntu:22.04@sha256:${BASE_DIGEST}

ARG NGINX_VERSION=1.18.0-6ubuntu14.5
ARG MODSEC_VERSION=1.0.2-3ubuntu1

# 仅安装运行时必需组件；apt 源指向学校受控镜像（发布包内 offline 源），禁止外网。
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      nginx=${NGINX_VERSION} \
      libnginx-mod-security=${MODSEC_VERSION} \
      ca-certificates \
      openssl \
 && rm -rf /var/lib/apt/lists/*

# ModSecurity 默认以 DetectionOnly 起步，业务回归后切换为 Enforce（见 release-process.md）
RUN mkdir -p /etc/nginx/modsecurity /var/run/nginx \
 && printf 'SecRuleEngine On\nSecAuditEngine RelevantOnly\nInclude /etc/nginx/modsecurity/crs.conf\n' \
      > /etc/nginx/modsecurity/modsecurity.conf

# 非 root 运行
RUN useradd --system --uid 2001 --home /nonexistent --shell /usr/sbin/nologin oj-ingress
USER oj-ingress

# 只读运行目录；TLS 私钥经 systemd LoadCredential 注入 /run/oj-ingress/cred，不写入镜像层
COPY --chown=root:root nginx.conf /etc/nginx/nginx.conf
EXPOSE 8443
ENTRYPOINT ["nginx", "-g", "daemon off;"]
