# infra/security/apparmor —— AppArmor 强制策略（Task 3）

实现设计文档 7.1「AppArmor 落地路线」：为 Ingress、API、Judge Agent、数据服务建立
独立 profile，先 complain 模式回归、审读拒绝记录、只放行业务必需路径，再全域 Enforce。
判题 profile 显式禁止访问密钥目录、其他服务数据、宿主机目录和 Docker Socket。

## 交付物

| 文件 | 覆盖服务 | 备注 |
| --- | --- | --- |
| `usr.sbin.nginx` | Ingress、Web（Nginx） | 入口与静态资源两个 Nginx 共用基线，安装时按区细化 |
| `usr.bin.java` | API（Spring Boot/JDK） | 应用目录运行时只读，读写路径白名单 |
| `usr.bin.judge-agent` | Judge Agent | 禁止密钥/数据/宿主机目录/Docker Socket |
| `oj.sandbox` | 判题 MicroVM/容器内进程 | 最严格：无网络、只读根、临时写层 |
| `usr.sbin.mysqld` | 数据区 MySQL | 仅监听内网 TLS，禁止公网 |
| `usr.sbin.redis-server` | 数据区 Redis | 禁止公网、限制可写路径 |
| `usr.sbin.rabbitmq-server` | 数据区 RabbitMQ | 管理插件仅本机回环 |
| `install-apparmor.sh` | 安装与 complain→Enforce 工作流 | 见下 |

## complain → Enforce 工作流（不可绕过）

```bash
# 1) 先以 complain 模式加载，跑业务回归，收集拒绝记录（/var/log/syslog）
sudo ./install-apparmor.sh --phase=complain
# 2) 审读 aa-logprof / 拒绝日志，只放行业务必需路径和能力
# 3) 全域切换到 Enforce
sudo ./install-apparmor.sh --phase=enforce
```

`preflight.sh` 与 `health-check.sh` 将 AppArmor Enforce 状态作为强制检查项；禁止以
`aa-complain` 或停用 AppArmor 作为故障处理手段（见设计 7.1 第 4 条）。

## 判题 profile 的强制 deny（设计 6.2 / 7.1 第 2 条）

判题相关 profile（`usr.bin.judge-agent`、`oj.sandbox`）必须显式 `deny`：

- 密钥目录：`/etc/oj/secrets/**`、`/etc/oj/credentials/**`；
- 其他服务数据：`/var/lib/oj/data/**`、`/var/lib/mysql/**`、`/var/lib/redis/**`、`/var/lib/rabbitmq/**`；
- 宿主机目录：`/mnt/**`、`/media/**`、`/srv/**`、`/home/**`；
- Docker Socket：`/var/run/docker.sock`、`/run/docker.sock`。

这些 `deny` 规则与 systemd 加固（`ProtectSystem=strict`、`PrivateTmp`、`ReadOnlyPaths`）、
cgroups v2 配额、seccomp 白名单共同构成判题边界，任一缺失都不得进入生产。
