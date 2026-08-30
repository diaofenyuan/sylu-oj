package sandbox

import (
	"bytes"
	"context"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

// GVisorRunner 是 KVM/Firecracker 不可用时的显式降级路径：
// rootless Podman + gVisor（runsc），叠加更强补偿控制（设计 6.1.5）：
//   - --network none：无网卡/路由/DNS/元数据可达性；
//   - --read-only：只读根文件系统；/workspace 为容量受限 tmpfs 临时写层；
//   - --cap-drop ALL、no-new-privileges、非 root 用户；
//   - seccomp 白名单（seccomp.json）+ AppArmor profile；
//   - pids/memory/cpus 上限 + 墙钟截止。
//
// 降级必须经 SelectRunner 显式返回 FallbackNotice，由调用方记录风险登记
// 并触发告警；考试模式不得在降级状态自动继续。禁止的配置（永不出现）：
// --privileged、--cap-add、hostNetwork/hostPID/hostIPC、
// /var/run/docker.sock、宿主机 /proc 及业务目录挂载。
type GVisorRunner struct {
	podmanBin    string
	runscRuntime string
	seccompFile  string
	apparmorProf string
	mu           sync.Mutex
}

func NewGVisorRunner() *GVisorRunner {
	return &GVisorRunner{
		podmanBin:    envOr("OJ_PODMAN_BIN", "/usr/bin/podman"),
		runscRuntime: envOr("OJ_RUNSC_BIN", "/usr/bin/runsc"),
		seccompFile:  envOr("OJ_SECCOMP_JSON", "/etc/oj-judge/seccomp.json"),
		apparmorProf: envOr("OJ_APPARMOR_PROFILE", "oj-judge-sandbox"),
	}
}

func (r *GVisorRunner) Name() string { return "gvisor" }

// Close 终止 runner 持有的后台资源（当前实现为无状态，占位以实现 Runner）。
func (r *GVisorRunner) Close() error { return nil }

func (r *GVisorRunner) Available() error {
	if _, err := os.Stat(r.podmanBin); err != nil {
		return fmt.Errorf("podman 不存在: %w", err)
	}
	if _, err := os.Stat(r.runscRuntime); err != nil {
		return fmt.Errorf("runsc 不存在: %w", err)
	}
	if _, err := os.Stat(r.seccompFile); err != nil {
		return fmt.Errorf("seccomp 白名单不存在: %w", err)
	}
	return nil
}

// Execute 以 rootless podman + runsc 运行一次性容器。
// argv 必须来自语言策略白名单（不经过 shell）。
func (r *GVisorRunner) Execute(ctx context.Context, spec ExecSpec, limits Limits) (*ExecResult, error) {
	if len(spec.Argv) == 0 {
		return nil, fmt.Errorf("ExecSpec.Argv 不能为空")
	}
	if err := r.assertSafeSpec(spec); err != nil {
		return nil, err
	}
	r.mu.Lock()
	defer r.mu.Unlock()

	// 每次执行一个私有临时目录：仅承载源码与当前测试输入，
	// 目录权限 0700、属主为非 root judge 服务账号，执行后立即销毁。
	work, err := os.MkdirTemp("/tmp", "oj-sbx-")
	if err != nil {
		return nil, fmt.Errorf("创建临时写层宿主目录失败: %w", err)
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
	}

	apparmor := spec.AppArmorProfile
	if apparmor == "" {
		apparmor = r.apparmorProf
	}
	argv := []string{
		"run", "--rm",
		"--runtime", r.runscRuntime,
		"--network", "none", // 无网卡/路由/DNS
		"--read-only", // 只读根文件系统
		"--userns", "keep-id",
		"--cap-drop", "ALL",
		"--security-opt", "no-new-privileges",
		"--security-opt", "seccomp=" + r.seccompFile,
		"--security-opt", "apparmor=" + apparmor,
		"--pids-limit", strconv.Itoa(limits.Pids),
		"--memory", strconv.FormatInt(limits.MemoryKb*1024, 10),
		"--memory-swap", strconv.FormatInt(limits.MemoryKb*1024, 10), // 禁止 swap
		"--cpus", strconv.FormatFloat(limits.Cpus, 'f', 2, 64),
		"--ulimit", "fsize=" + strconv.FormatInt(limits.DiskKb*1024, 10),
		"--ulimit", "nofile=1024:1024",
		"--tmpfs", fmt.Sprintf("/workspace:rw,size=%d,mode=770", limits.DiskKb*1024),
		"--volume", work + ":" + spec.Workdir + ":rw",
	}
	argv = append(argv, spec.Image)
	argv = append(argv, spec.Argv...)

	execCtx, cancel := context.WithDeadline(ctx, limits.Deadline())
	defer cancel()
	cmd := exec.CommandContext(execCtx, r.podmanBin, argv...)
	cmd.Dir = ""
	cmd.Env = append(os.Environ(), "_CONTAINERS_USERNS_CONFIGURED=1")
	cmd.SysProcAttr = &syscall.SysProcAttr{}
	if len(spec.Stdin) > 0 {
		cmd.Stdin = bytes.NewReader(spec.Stdin)
	}

	var stdout, stderr limitedBuffer
	stdout.limit = limits.OutputBytes
	stderr.limit = limits.OutputBytes
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr

	start := time.Now()
	runErr := cmd.Run()
	wall := time.Since(start).Milliseconds()

	res := &ExecResult{
		Output:       stdout.truncated(),
		OutputLimit:  stdout.hitLimit || stderr.hitLimit,
		StderrBytes:  int64(stderr.written),
		WallTimeMs:   wall,
		PeakMemoryKb: 0, // runsc 内存峰值由 cgroups memory.peak 读取（部署阶段启用）
	}
	// 在销毁临时写层宿主目录前回收 Harvest 文件（编译产物）。
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
		if strings.Contains(res.Signal, "KILL") && oomDetected(stderr.String()) {
			res.OOMKilled = true
			res.Signal = ""
		}
		if strings.Contains(res.Signal, "SYS") {
			res.ForbiddenSys = true
		}
		return res, nil
	}
	return nil, fmt.Errorf("podman 执行失败: %w", runErr)
}

// harvestFiles 从一次性写层宿主目录回收指定文件（glob 支持），带大小上限。
func harvestFiles(work string, patterns []string) map[string][]byte {
	if len(patterns) == 0 {
		return nil
	}
	out := map[string][]byte{}
	for _, pattern := range patterns {
		matches, err := filepath.Glob(filepath.Join(work, filepath.Clean("/"+pattern)))
		if err != nil {
			continue
		}
		for _, m := range matches {
			info, err := os.Stat(m)
			if err != nil || info.IsDir() || info.Size() > maxFileBytes {
				continue
			}
			content, err := os.ReadFile(m)
			if err != nil {
				continue
			}
			rel, err := filepath.Rel(work, m)
			if err != nil {
				continue
			}
			out[filepath.ToSlash(rel)] = content
		}
	}
	if len(out) == 0 {
		return nil
	}
	return out
}

// assertSafeSpec 拒绝任何指向禁止资源的镜像/参数组合（纵深防御）。
func (r *GVisorRunner) assertSafeSpec(spec ExecSpec) error {
	for _, a := range spec.Argv {
		if strings.Contains(a, "docker.sock") || strings.Contains(a, "/proc/1") {
			return fmt.Errorf("ExecSpec 包含禁止资源引用")
		}
	}
	return nil
}

// limitedBuffer 累计写入并在超过 limit 后丢弃后续内容（保留违规标记）。
type limitedBuffer struct {
	buf      bytes.Buffer
	written  int
	limit    int64
	hitLimit bool
}

func (b *limitedBuffer) Write(p []byte) (int, error) {
	n := len(p)
	b.written += n
	if int64(b.written) > b.limit {
		b.hitLimit = true
		remain := b.limit - int64(len(b.buf.Bytes()))
		if remain > 0 {
			b.buf.Write(p[:remain])
		}
		return n, nil
	}
	b.buf.Write(p)
	return n, nil
}

func (b *limitedBuffer) truncated() []byte {
	out := b.buf.Bytes()
	cp := make([]byte, len(out))
	copy(cp, out)
	return cp
}

func (b *limitedBuffer) String() string { return b.buf.String() }

func oomDetected(stderr string) bool {
	return strings.Contains(stderr, "OOMKilled") ||
		strings.Contains(stderr, "out of memory") ||
		strings.Contains(stderr, "Memory cgroup out of memory")
}

func signalName(ee *exec.ExitError) string {
	desc := ee.String()
	switch {
	case strings.Contains(desc, "signal: killed"):
		return "SIGKILL"
	case strings.Contains(desc, "signal: sys"):
		return "SIGSYS"
	case strings.Contains(desc, "signal: terminated"):
		return "SIGTERM"
	case strings.Contains(desc, "signal: segmentation"):
		return "SIGSEGV"
	case strings.Contains(desc, "signal: aborted"):
		return "SIGABRT"
	default:
		return ""
	}
}
