# infra/security/systemd —— systemd 服务加固（Task 3）

实现设计文档 7 节与 12.3 第 5 步：为每个服务启用 `NoNewPrivileges`、`ProtectSystem=strict`、
`PrivateTmp`、`RestrictAddressFamilies`、资源限制和独立服务用户，业务目录运行时只读。

## 交付物

每个服务一个 systemd drop-in（`*.service.d/10-hardening.conf`），在安装时合并到对应单元，
不覆盖发行版自带单元文件：

| drop-in | 服务用户 | 说明 |
| --- | --- | --- |
| `oj-ingress.service.d/10-hardening.conf` | `oj-ingress` | 入口 Nginx + WAF |
| `oj-web.service.d/10-hardening.conf` | `oj-web` | Web 区静态资源 Nginx |
| `oj-api.service.d/10-hardening.conf` | `oj-api` | Spring Boot API |
| `oj-judge-agent.service.d/10-hardening.conf` | `judge` | Judge Agent + 沙箱启动器 |
| `oj-mysql.service.d/10-hardening.conf` | `oj-mysql` | 数据区 MySQL |
| `oj-redis.service.d/10-hardening.conf` | `oj-redis` | 数据区 Redis |
| `oj-rabbitmq.service.d/10-hardening.conf` | `oj-rabbitmq` | 数据区 RabbitMQ |

## 通用加固字段（每单元必含）

| 字段 | 取值/含义 |
| --- | --- |
| `NoNewPrivileges=true` | 禁止子进程通过 setuid/能力提升权限 |
| `ProtectSystem=strict` | `/usr`、`/boot`、`/etc` 只读 |
| `PrivateTmp=true` | 独立私有 `/tmp` |
| `ProtectHome=true` | 隐藏 `/home`、`/root`、`/run/user` |
| `PrivateDevices=true` | 独立最小 `/dev` |
| `RestrictAddressFamilies=` | 白名单地址族（见各单元） |
| `CapabilityBoundingSet=` | 空集，能力清零 |
| `ReadOnlyPaths=/opt/oj` | 业务制品目录运行时只读 |
| `ReadWritePaths=/var/lib/oj` | 唯一可写业务数据路径 |
| `MemoryMax` / `CPUQuota` / `TasksMax` / `LimitNOFILE` | 资源上限（压测后调整，不用于取消隔离） |

## 与 AppArmor / 目录权限的关系

systemd 加固与 `infra/security/apparmor/` 的 profile 是**互补的两层**：systemd 负责进程
启动时的命名空间/能力/只读挂载，AppArmor 负责运行时的路径级强制访问控制。业务目录权限由
`install.sh` 按独立服务用户分配（`/opt/oj` 归 root，业务用户只读；`/var/lib/oj` 归业务用户）。

## 资源配额说明

`MemoryMax`、`CPUQuota` 起始值为部署基线，须按设计 1.2 容量表与压测结果调整；判题区
`oj-judge-agent` 的 `MemoryMax` 为 Agent 常驻上限，沙箱池内每个 MicroVM 的配额由
cgroups v2 单独设置，不并入本单元。
