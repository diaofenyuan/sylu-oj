# judge-agent.Containerfile —— 判题区 Judge Agent + 沙箱启动器
#
# 对应设计文档 6 节：Judge Agent 以非 root 的 judge 用户运行；每个提交使用独立
# MicroVM（Firecracker/KVM）或降级 rootless Podman + gVisor。Agent 仅通过 mTLS
# 主动拉取任务，无入站管理端口，不直连数据区。
#
# 构建约束：基础镜像按摘要锁定；非 root（judge，uid 2004）；沙箱运行时
# firecracker/runsc 为固定版本（见 toolchains.lock）；不包含任何数据区凭据。

ARG BASE_DIGEST
FROM ubuntu:22.04@sha256:${BASE_DIGEST}

ARG FIRECRACKER_VERSION=1.7.0
ARG RUNSC_VERSION=release-20240805.0

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      libseccomp2 \
      ca-certificates \
 && rm -rf /var/lib/apt/lists/*

# 固定版本沙箱运行时（由 CI 从受控制品库离线复制，禁止运行时下载）
COPY --chown=root:root bin/firecracker-v${FIRECRACKER_VERSION} /usr/local/bin/firecracker
COPY --chown=root:root bin/runsc-${RUNSC_VERSION} /usr/local/bin/runsc

RUN useradd --system --uid 2004 --home /nonexistent --shell /usr/sbin/nologin judge
USER judge

# Agent 二进制与语言策略（seccomp/AppArmor 配置由 systemd 与 host profile 提供）
COPY --chown=root:root judge-agent /opt/oj/judge/judge-agent
COPY --chown=root:root language-policy.yaml /opt/oj/judge/language-policy.yaml

# 判题沙箱临时写层与结果回传走 vsock/Unix Socket，无业务网络路径
EXPOSE 8443
ENTRYPOINT ["/opt/oj/judge/judge-agent"]
