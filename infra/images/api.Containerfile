# api.Containerfile —— API 区 Spring Boot 模块化单体
#
# 对应设计文档 4 节：认证与账号、教学组织、题库、作业/组卷、提交管理、判题调度、
# Judge Gateway、公告与审计模块位于 API VM。本镜像仅携带 JDK 运行时与制品 JAR，
# 不含 C/C++ 编译器（判题编译由 JUDGE 区沙箱完成）。
#
# 构建约束：基础镜像按摘要锁定；非 root（oj-api）；JAR 由 CI 构建后 COPY；
# 运行时目录只读，读写路径经 systemd 白名单限定。

ARG BASE_DIGEST
FROM ubuntu:22.04@sha256:${BASE_DIGEST}

ARG JDK_VERSION=17.0.12+7-1~22.04.1

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      openjdk-17-jre-headless=${JDK_VERSION} \
      ca-certificates \
 && rm -rf /var/lib/apt/lists/*

RUN useradd --system --uid 2003 --home /nonexistent --shell /usr/sbin/nologin oj-api
USER oj-api

# 不可变制品：/opt/oj 只读（ProtectSystem=strict + ReadOnlyPaths=/opt/oj）
COPY --chown=root:root oj-api.jar /opt/oj/app/oj-api.jar
COPY --chown=root:root db-migrations/ /opt/oj/app/db-migrations/

# 运行数据目录（ReadWritePaths=/var/lib/oj）由 systemd 挂载
EXPOSE 8443
ENTRYPOINT ["java", "-jar", "/opt/oj/app/oj-api.jar"]
