# infra/images —— 固定版本镜像定义（Task 3）

本目录定义生产环境的服务镜像（OCI）与虚拟机基础镜像（qcow2）的**固定版本、可追溯构建**方式，实现设计文档第 7 节（主机、供应链与密钥管理）与第 12 节（压缩包部署与发布生命周期）对"基础镜像固定版本和摘要、SBOM、依赖漏洞扫描、不从运行时不可信地址下载"的要求。

## 交付物

| 文件 | 用途 |
| --- | --- |
| `toolchains.lock` | 固定 C/C++ 编译器、Python、JDK 等运行时版本与摘要（发布前由 CI 锁定） |
| `ingress.Containerfile` | 入口区 Nginx + ModSecurity/Coraza WAF + L7 反向代理 |
| `web.Containerfile` | Web 区 Vue 静态资源 Nginx（仅静态文件，不访问数据区） |
| `api.Containerfile` | API 区 Spring Boot 模块化单体（含 JDK 运行时，非 root） |
| `judge-agent.Containerfile` | 判题区 Judge Agent + Firecracker/gVisor 沙箱启动器 |
| `build-vm-images.sh` | 以 debootstrap 离线构建最小化 Ubuntu 22.04 虚拟机基础镜像 |

## 固定版本与摘要锁定原则

1. **基础镜像按摘要锁定**：所有 `FROM` 使用 `ubuntu:22.04@sha256:<digest>`，`<digest>` 由 CI 在构建时写入并在 `manifest/release.json` 记录，禁止使用 `latest` 或仅标签引用。
2. **工具链按版本锁定**：C/C++（`gcc-11`/`g++-11`）、Python 3.10、OpenJDK 17 的精确发行版本与校验和写入 `toolchains.lock`，不得在镜像构建或运行时动态升级。
3. **离线构建**：镜像构建所需软件包全部来自本地/受控 apt 源（发布包内的 `.deb` 缓存或学校镜像源），禁止在构建过程中执行 `curl | sh` 或从不可信地址下载依赖。
4. **最小化**：容器与 VM 基础镜像仅安装运行所必需的软件，不携带编译器、调试工具、包管理器缓存或构建凭据（`api` 镜像不含 gcc/g++，`judge-agent` 镜像按需携带沙箱运行时）。

## 摘要记录位置

- OCI 镜像摘要 → 发布包 `images/sha256sums.txt`；
- VM 基础镜像摘要 → 发布包 `manifest/SHA256SUMS`；
- 依赖清单与漏洞结论 → `manifest/sbom.cdx.json`（CycloneDX）与 `manifest/grype-report.json`。

## 运行时禁止事项（与判题隔离一致，见设计 6.2）

- 禁止在生产环境挂载 Docker Socket（`/var/run/docker.sock`）、宿主机目录、宿主机 `/proc`；
- 禁止特权容器、`hostNetwork`、`hostPID`、`hostIPC`；
- 判题 MicroVM 无网卡、只读根文件系统、临时写层、`no_new_privs`、能力清零、seccomp 白名单与 AppArmor profile。
