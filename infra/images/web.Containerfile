# web.Containerfile —— Web 区 Vue 静态资源 Nginx
#
# 对应设计文档 2.1 Web 区：仅提供 Vue 静态资源，只接收入口区反向代理请求，
# 不访问数据区。静态资源在 CI 构建阶段生成并 COPY 进镜像，运行时无任何出网。
#
# 构建约束：基础镜像按摘要锁定；非 root；仅静态文件，无 Node.js 运行时。

ARG BASE_DIGEST
FROM ubuntu:22.04@sha256:${BASE_DIGEST}

ARG NGINX_VERSION=1.18.0-6ubuntu14.5

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      nginx=${NGINX_VERSION} \
      ca-certificates \
 && rm -rf /var/lib/apt/lists/*

RUN useradd --system --uid 2002 --home /nonexistent --shell /usr/sbin/nologin oj-web
USER oj-web

# dist/ 由 CI 从 Vue 构建产物复制（app/web 构建出的纯静态资源）
COPY --chown=root:root dist/ /var/www/oj/dist/
COPY --chown=root:root nginx-web.conf /etc/nginx/conf.d/default.conf
EXPOSE 8080
ENTRYPOINT ["nginx", "-g", "daemon off;"]
