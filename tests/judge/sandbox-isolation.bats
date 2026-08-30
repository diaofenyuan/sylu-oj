#!/usr/bin/env bats
# tests/judge/sandbox-isolation.bats —— 判题沙箱反逃逸验收（Task 7，设计 6.1/6.2）
# Windows 开发环境执行静态断言；Linux 判题主机设置 OJ_SANDBOX_LIVE=1 后
# 执行恶意样例实弹段（fork bomb/磁盘写满/超时/系统调用/路径穿越/网络/
# 元数据/宿主机目录/Socket 探测/OOM）。

setup() {
  ROOT="$(cd "$BATS_TEST_DIRNAME/../.." && pwd)"
  SANDBOX="$ROOT/judge/sandbox"
  AGENT="$ROOT/judge/agent"
  SAMPLES="$ROOT/tests/judge/malicious-programs"
  APPARMOR="$ROOT/infra/security/apparmor"
}

# ---------------- 交付物齐备性 ----------------

@test "存在 Firecracker 与 gVisor Runner、语言策略与 seccomp 白名单" {
  [ -f "$SANDBOX/firecracker_runner.go" ]
  [ -f "$SANDBOX/gvisor_runner.go" ]
  [ -f "$SANDBOX/language-policy.yaml" ]
  [ -f "$SANDBOX/seccomp.json" ]
  [ -f "$AGENT/src/main.go" ]
  [ -f "$AGENT/src/judge.go" ]
}

@test "Go 模块可编译且单元测试通过（状态码映射/策略解析）" {
  if ! command -v go >/dev/null 2>&1; then
    skip "本机无 Go 工具链"
  fi
  (cd "$SANDBOX" && go vet ./... && go build ./...)
  (cd "$AGENT" && go vet ./... && go build ./... && go test ./...)
}

# ---------------- 沙箱强制属性（设计 6.2） ----------------

@test "gVisor Runner 显式声明无网络/只读根/临时写层/非root/能力清零" {
  SRC="$SANDBOX/gvisor_runner.go"
  grep -q '"--network", "none"' "$SRC"
  grep -q '"--read-only"' "$SRC"
  grep -q '"--cap-drop", "ALL"' "$SRC"
  grep -q 'no-new-privileges' "$SRC"
  grep -q 'pids-limit' "$SRC"
  grep -q 'tmpfs' "$SRC"
}

@test "gVisor Runner 禁止 Docker Socket / 宿主机 /proc / 特权模式" {
  SRC="$SANDBOX/gvisor_runner.go"
  grep -q 'docker.sock' "$SRC"
  grep -q '/proc/1' "$SRC"
  ! grep -q '"--privileged"' "$SRC"
  ! grep -q '"--cap-add"' "$SRC"
  ! grep -qE '"--network=(host|bridge)"' "$SRC"
}

@test "Firecracker Runner 只读 rootfs、无网络接口、cgroups v2 与墙钟截止" {
  SRC="$SANDBOX/firecracker_runner.go"
  grep -q '"is_read_only"' "$SRC"
  grep -q 'cpu.max' "$SRC"
  grep -q 'memory.max' "$SRC"
  grep -q 'pids.max' "$SRC"
  grep -q 'io.weight' "$SRC"
  grep -q 'Deadline' "$SRC"
  # MicroVM 配置中不得出现网络接口定义（注释提及不算）
  ! grep -q '"network-interfaces"' "$SRC"
}

@test "Agent 可用时拒绝启动而非静默降级（无沙箱即 Fatal）" {
  SRC="$AGENT/src/main.go"
  grep -q 'SelectRunner' "$SRC"
  grep -q '无可用沙箱，Agent 拒绝启动' "$SRC"
  grep -q 'SANDBOX_FALLBACK' "$SRC"
  grep -q 'FallbackNotice' "$SRC"
}

@test "降级标记随结果回传（考试模式门禁依据，设计 6.1.5）" {
  SRC="$AGENT/src/judge.go"
  grep -q 'SandboxMode' "$SRC"
  grep -q 'FallbackNotice' "$SRC"
  grep -q '不得自动继续' "$SRC"
}

# ---------------- 语言策略与 seccomp ----------------

@test "策略固定四种语言运行时且 argv 为白名单向量" {
  POLICY="$SANDBOX/language-policy.yaml"
  grep -q 'gcc-13.3-c17' "$POLICY"
  grep -q 'gcc-13.3-c++20' "$POLICY"
  grep -q 'cpython-3.12' "$POLICY"
  grep -q 'openjdk-21' "$POLICY"
  grep -q 'argv: \["gcc"' "$POLICY"
  grep -q 'argv: \["javac"' "$POLICY"
  ! grep -qE 'sh -c|bash -c|cmd /c' "$POLICY"
}

@test "策略镜像必须按摘要锁定且禁止放宽上限" {
  POLICY="$SANDBOX/language-policy.yaml"
  grep -q 'sha256:REQUIRED_FROM_RELEASE' "$POLICY"
  grep -q 'staticLinking: disallow' "$POLICY"
  grep -q 'forbiddenMounts' "$POLICY"
  grep -q '/var/run/docker.sock' "$POLICY"
}

@test "seccomp 白名单拒绝网络/挂载/ptrace/控制面系统调用" {
  SRC="$SANDBOX/seccomp.json"
  grep -q '"defaultAction": "SCMP_ACT_ERRNO"' "$SRC"
  ! grep -qE '"(socket|connect|accept|accept4|mount|umount2|ptrace|keyctl|bpf|userfaultfd|kexec_load|reboot|setns|unshare|open_by_handle_at)",' "$SRC"
  grep -q '"clone"' "$SRC"
}

@test "Java/Python 需要的线程与事件系统调用未被误拒" {
  SRC="$SANDBOX/seccomp.json"
  for call in futex eventfd2 memfd_create epoll_wait sched_getaffinity rt_sigaction; do
    grep -q "\"$call\"" "$SRC"
  done
}

# ---------------- 恶意样例语料 ----------------

@test "恶意样例覆盖 fork bomb/磁盘写满/超时/系统调用/穿越/网络/元数据/宿主目录/Socket/OOM" {
  for f in fork_bomb.c fork_bomb.py ForkBomb.java disk_fill.c disk_fill.py \
           infinite_loop.c infinite_loop.py InfiniteLoop.java \
           network_probe.c network_probe.py path_traversal.c metadata_probe.py \
           host_dir_probe.c docker_socket_probe.py oom.c ptrace_probe.c; do
    [ -f "$SAMPLES/$f" ]
  done
  [ -f "$SAMPLES/manifest.yaml" ]
}

@test "良性样例覆盖四种语言功能正确性（A+B）" {
  for f in aplusb.c aplusb.cpp aplusb.py APlusB.java; do
    [ -f "$SAMPLES/$f" ]
  done
}

@test "manifest 为每个样例声明预期结果码" {
  MANIFEST="$SAMPLES/manifest.yaml"
  grep -q 'fork_bomb.c:.*expect: \[RE, TLE\]' "$MANIFEST"
  grep -q 'oom.c:.*expect: \[MLE\]' "$MANIFEST"
  grep -q 'infinite_loop.c:.*expect: \[TLE\]' "$MANIFEST"
  grep -q 'network_probe.c:.*expect: \[BSC, RE\]' "$MANIFEST"
}

@test "Task 3 的判题沙箱 AppArmor profile 存在且拒绝敏感路径" {
  if [ ! -d "$APPARMOR" ]; then
    skip "Task 3 AppArmor 目录未在本机检出"
  fi
  PROFILE="$(grep -rl 'judge' "$APPARMOR" | head -1)"
  [ -n "$PROFILE" ]
  grep -qiE 'deny|deny/' "$PROFILE"
}

# ---------------- live 实弹段（Linux 判题主机） ----------------

@test "live: fork bomb 被 pids.max 抑制且结果码落入预期集合" {
  [ -n "$OJ_SANDBOX_LIVE" ] || skip "未启用实弹检查（设置 OJ_SANDBOX_LIVE=1 于 Linux 判题主机）"
  [ -x "$AGENT/oj-agent" ] || skip "Agent 未构建（go build -o oj-agent ./src）"
  "$AGENT/oj-agent" --selftest "$SAMPLES/fork_bomb.c" --expect RE,TLE
}

@test "live: OOM 样例映射 MLE" {
  [ -n "$OJ_SANDBOX_LIVE" ] || skip "未启用实弹检查"
  [ -x "$AGENT/oj-agent" ] || skip "Agent 未构建"
  "$AGENT/oj-agent" --selftest "$SAMPLES/oom.c" --expect MLE
}

@test "live: 网络探测样例映射 BSC/RE 且无外联" {
  [ -n "$OJ_SANDBOX_LIVE" ] || skip "未启用实弹检查"
  [ -x "$AGENT/oj-agent" ] || skip "Agent 未构建"
  "$AGENT/oj-agent" --selftest "$SAMPLES/network_probe.c" --expect BSC,RE
}

@test "live: 超时样例映射 TLE" {
  [ -n "$OJ_SANDBOX_LIVE" ] || skip "未启用实弹检查"
  [ -x "$AGENT/oj-agent" ] || skip "Agent 未构建"
  "$AGENT/oj-agent" --selftest "$SAMPLES/infinite_loop.c" --expect TLE
}

@test "live: 路径穿越与宿主机目录探测无数据外泄" {
  [ -n "$OJ_SANDBOX_LIVE" ] || skip "未启用实弹检查"
  [ -x "$AGENT/oj-agent" ] || skip "Agent 未构建"
  "$AGENT/oj-agent" --selftest "$SAMPLES/path_traversal.c" --expect RE,WA
  "$AGENT/oj-agent" --selftest "$SAMPLES/host_dir_probe.c" --expect RE,WA
}

@test "live: 四语言良性样例功能正确（A+B → AC）" {
  [ -n "$OJ_SANDBOX_LIVE" ] || skip "未启用实弹检查"
  [ -x "$AGENT/oj-agent" ] || skip "Agent 未构建"
  "$AGENT/oj-agent" --selftest "$SAMPLES/aplusb.c" --expect AC --stdin "1 2" --stdout "3"
  "$AGENT/oj-agent" --selftest "$SAMPLES/aplusb.cpp" --expect AC --stdin "10 20" --stdout "30"
  "$AGENT/oj-agent" --selftest "$SAMPLES/aplusb.py" --expect AC --stdin "3 4" --stdout "7"
  "$AGENT/oj-agent" --selftest "$SAMPLES/APlusB.java" --expect AC --stdin "5 6" --stdout "11"
}
