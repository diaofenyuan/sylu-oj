package sandbox

import (
	"bytes"
	"context"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"time"
)

// HostRunner 是仅限开发联调的执行器（OJ_SANDBOX_PREFERRED=host-dev 显式启用）：
// 直接在 Agent 宿主机（如 WSL2 Ubuntu）上编译/运行学生代码。
//
// 与生产 Runner 的差距（必须在风险登记中记录，禁止用于生产/考试）：
//   - 无容器/MicroVM 隔离：无网络隔离、无只读 rootfs、无 seccomp/AppArmor；
//   - cgroups v2 资源上限不可用：仅墙钟截止与输出上限生效，
//     内存/PID/磁盘上限不强制（恶意代码可拖垮宿主机）；
//   - 判题结果 sandboxMode 固定为 host-dev，并携带降级说明回传。
//
// 因此 SelectRunner 仅在收到显式 "host-dev" 时选择本 Runner，
// auto/gvisor 路径永远不会静默落入宿主机直执行。
type HostRunner struct{}

func NewHostRunner() *HostRunner { return &HostRunner{} }

func (r *HostRunner) Name() string { return "host-dev" }

// Close 终止 runner 持有的后台资源（无状态，占位以实现 Runner）。
func (r *HostRunner) Close() error { return nil }

// Available 恒可用：是否允许由显式的 OJ_SANDBOX_PREFERRED=host-dev 决定。
func (r *HostRunner) Available() error { return nil }

// Execute 在宿主机临时目录中直接执行 argv（不经 shell）。
// 资源控制仅限：墙钟截止（context deadline）与 stdout/stderr 输出上限；
// 内存/PID/磁盘上限仅透传记录，不强制。
func (r *HostRunner) Execute(ctx context.Context, spec ExecSpec, limits Limits) (*ExecResult, error) {
	if len(spec.Argv) == 0 {
		return nil, fmt.Errorf("ExecSpec.Argv 不能为空")
	}
	// 每次执行一个私有临时目录，执行后立即销毁。
	work, err := os.MkdirTemp("", "oj-hostdev-")
	if err != nil {
		return nil, fmt.Errorf("创建临时工作目录失败: %w", err)
	}
	defer os.RemoveAll(work)
	for name, content := range spec.Files {
		clean := filepath.Clean("/" + name)
		if strings.HasPrefix(clean, "..") {
			return nil, fmt.Errorf("非法写入路径 %q", name)
		}
		if int64(len(content)) > maxFileBytes {
			return nil, fmt.Errorf("写入文件超出上限 %q", name)
		}
		dst := filepath.Join(work, clean)
		if err := os.MkdirAll(filepath.Dir(dst), 0o750); err != nil {
			return nil, err
		}
		if err := os.WriteFile(dst, content, 0o640); err != nil {
			return nil, err
		}
		// ELF 产物（C/C++ 编译结果）需要可执行位才能以 ./main 运行。
		if bytes.HasPrefix(content, []byte{0x7f, 'E', 'L', 'F'}) {
			if err := os.Chmod(dst, 0o750); err != nil {
				return nil, err
			}
		}
	}

	execCtx, cancel := context.WithDeadline(ctx, limits.Deadline())
	defer cancel()
	cmd := exec.CommandContext(execCtx, spec.Argv[0], spec.Argv[1:]...)
	cmd.Dir = work
	var stdout, stderr limitedBuffer
	stdout.limit = limits.OutputBytes
	stderr.limit = limits.OutputBytes
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr
	if len(spec.Stdin) > 0 {
		cmd.Stdin = bytes.NewReader(spec.Stdin)
	}

	start := time.Now()
	runErr := cmd.Run()
	wall := time.Since(start).Milliseconds()

	res := &ExecResult{
		Output:       stdout.truncated(),
		Stderr:       stderr.truncated(),
		OutputLimit:  stdout.hitLimit || stderr.hitLimit,
		StderrBytes:  int64(stderr.written),
		WallTimeMs:   wall,
		PeakMemoryKb: peakRss(cmd),
	}
	// 在销毁临时目录前回收 Harvest 文件（编译产物）。
	res.Files = harvestFiles(work, spec.Harvest)
	if execCtx.Err() == context.DeadlineExceeded {
		res.TimedOut = true
		return res, nil
	}
	if runErr == nil {
		return res, nil
	}
	if ee, ok := runErr.(*exec.ExitError); ok {
		res.ExitCode = ee.ExitCode()
		res.Signal = signalName(ee)
		return res, nil
	}
	return nil, fmt.Errorf("host-dev 执行失败: %w", runErr)
}

// peakRss 从 wait4 rusage（ProcessState.SysUsage）读取进程峰值驻留内存（KB）。
// 与 /usr/bin/time -v 的 Maximum resident set size 同源，含运行时底座，
// 与真实 OJ 的沙盒内存口径一致；无可用指标时返回 0。
func peakRss(cmd *exec.Cmd) int64 {
	if cmd.ProcessState == nil {
		return 0
	}
	usage, ok := cmd.ProcessState.SysUsage().(*syscall.Rusage)
	if !ok {
		return 0
	}
	// Linux wait4 rusage 的 ru_maxrss 单位为 KB；BSD 系为字节（按字节尺度区分）。
	if usage.Maxrss > (1 << 30) {
		return usage.Maxrss / 1024
	}
	return usage.Maxrss
}
