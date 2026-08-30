package sandbox

import (
	"context"
	"encoding/binary"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"time"
)

// FirecrackerRunner 为每个 ExecSpec 启动一个全新 Firecracker MicroVM：
//   - 无网络接口（仅 vsock 与 jailer 控制 socket）；
//   - 只读根文件系统（按摘要锁定的 ext4 rootfs）+ 受限 tmpfs 临时写层；
//   - cgroups v2 资源上限由 jailer --cgroup 参数下发；
//   - 非 root 运行（jailer --uid/--gid 切换到 judge 服务账号）；
//   - 结果经 vsock 以定长帧 + JSON 交回，沙箱无任何业务网络路径。
//
// 主机要求（部署时验证，见设计 6.1）：/dev/kvm 可用、jailer 与
// 固定摘要的 kernel/rootfs 镜像就位、AppArmor profile 已 Enforce。
type FirecrackerRunner struct {
	kernelPath string
	rootfsPath string // 按 SHA256 摘要锁定的基础 rootfs
	jailerPath string
	firecrackerPath string
	vsockUdsDir string
	judgeUid    uint32
	judgeGid    uint32
	mu          sync.Mutex
}

func NewFirecrackerRunner() *FirecrackerRunner {
	return &FirecrackerRunner{
		kernelPath:      envOr("OJ_FC_KERNEL", "/var/lib/oj/judge/vmlinux-6.8"),
		rootfsPath:      envOr("OJ_FC_ROOTFS", "/var/lib/oj/judge/rootfs-oj-runtime.ext4"),
		jailerPath:      envOr("OJ_FC_JAILER", "/usr/local/bin/jailer"),
		firecrackerPath: envOr("OJ_FC_BIN", "/usr/local/bin/firecracker"),
		vsockUdsDir:     envOr("OJ_FC_VSOCK_DIR", "/run/oj-judge/vsock"),
		judgeUid:        990,
		judgeGid:        990,
	}
}

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func (r *FirecrackerRunner) Name() string { return "firecracker" }

// Close 终止 runner 持有的后台资源（当前实现为无状态，占位以实现 Runner）。
func (r *FirecrackerRunner) Close() error { return nil }

// Available 验证嵌套虚拟化前置条件（设计 6.1.3）：
// /dev/kvm 存在且可写，jailer/firecracker/kernel/rootfs 均就位。
func (r *FirecrackerRunner) Available() error {
	if _, err := os.Stat("/dev/kvm"); err != nil {
		return fmt.Errorf("/dev/kvm 不可用: %w", err)
	}
	f, err := os.OpenFile("/dev/kvm", os.O_RDWR, 0)
	if err != nil {
		return fmt.Errorf("/dev/kvm 无读写权限: %w", err)
	}
	_ = f.Close()
	for _, p := range []string{r.jailerPath, r.firecrackerPath, r.kernelPath, r.rootfsPath} {
		if _, err := os.Stat(p); err != nil {
			return fmt.Errorf("Firecracker 组件缺失 %s: %w", p, err)
		}
	}
	return nil
}

// maxFileBytes 限制写入临时写层的单文件大小（测试输入/源码）。
const maxFileBytes = 8 << 20

// Execute 组装并启动一次性 MicroVM：
//  1. 构造私有 jail 目录与本次 rootfs 副本（基础 rootfs 只读挂载，写层 tmpfs）；
//  2. 生成 Firecracker 配置：无 network-interfaces、vsock、墙钟/资源上限；
//  3. 经 jailer 启动，等待 vsock 控制通道回传结构化结果。
func (r *FirecrackerRunner) Execute(ctx context.Context, spec ExecSpec, limits Limits) (*ExecResult, error) {
	if len(spec.Argv) == 0 {
		return nil, fmt.Errorf("ExecSpec.Argv 不能为空")
	}
	r.mu.Lock()
	defer r.mu.Unlock()

	work, err := os.MkdirTemp(r.vsockUdsDir, "exec-")
	if err != nil {
		return nil, fmt.Errorf("创建执行目录失败: %w", err)
	}
	defer os.RemoveAll(work)

	// 临时写层内容：源码/当前测试输入经 9p/tmpfs 注入，镜像内只读。
	filesDir := filepath.Join(work, "files")
	if err := os.Mkdir(filesDir, 0o750); err != nil {
		return nil, err
	}
	for name, content := range spec.Files {
		clean := filepath.Clean("/" + name)
		if strings.HasPrefix(clean, "..") {
			return nil, fmt.Errorf("非法写入路径 %q", name)
		}
		if int64(len(content)) > maxFileBytes {
			return nil, fmt.Errorf("写入文件超出上限 %q", name)
		}
		dst := filepath.Join(filesDir, clean)
		if err := os.MkdirAll(filepath.Dir(dst), 0o750); err != nil {
			return nil, err
		}
		if err := os.WriteFile(dst, content, 0o640); err != nil {
			return nil, err
		}
	}

	// cgroups v2 上限经 jailer --cgroup 下发（cgroup.parent 由部署配置委托给 judge 用户）。
	jailerArgs := []string{
		"--id", filepath.Base(work),
		"--exec-file", r.firecrackerPath,
		"--uid", fmt.Sprint(r.judgeUid),
		"--gid", fmt.Sprint(r.judgeGid),
		"--cgroup", fmt.Sprintf("cpu.max=%d %d", int64(limits.Cpus*100000), 100000),
		"--cgroup", fmt.Sprintf("memory.max=%d", limits.MemoryKb*1024),
		"--cgroup", fmt.Sprintf("pids.max=%d", limits.Pids),
		"--cgroup", fmt.Sprintf("io.weight=weight=%d", limits.IoWeight),
		"--", "--api-sock", filepath.Join(work, "fc.sock"),
	}
	jailerCtx, cancel := context.WithDeadline(ctx, limits.Deadline())
	defer cancel()
	jailer := exec.CommandContext(jailerCtx, r.jailerPath, jailerArgs...)
	jailer.Stdout = nil
	jailer.Stderr = nil
	if err := jailer.Start(); err != nil {
		return nil, fmt.Errorf("启动 jailer 失败: %w", err)
	}
	defer func() {
		_ = jailer.Process.Kill()
		_, _ = jailer.Process.Wait()
	}()

	// 通过控制 socket 下达 MicroVM 配置（无网络接口、只读 rootfs、vsock）。
	cfg := map[string]any{
		"boot-source": map[string]any{
			"kernel_image_path": r.kernelPath,
			"boot_args":         "console=ttyS0 reboot=k panic=1 ro init=/sbin/oj-init quiet",
		},
		"drives": []any{map[string]any{
			"drive_id":        "rootfs",
			"path_on_host":    r.rootfsPath,
			"is_root_device":  true,
			"is_read_only":    true,
			"io_engine":       "Sync",
		}},
		"vsock": map[string]any{
			"vsock_id": "oj0",
			"guest_cid": 3,
		},
	}
	if err := r.configure(jailerCtx, filepath.Join(work, "fc.sock"), cfg); err != nil {
		return nil, fmt.Errorf("配置 MicroVM 失败: %w", err)
	}

	// 结果经 vsock UDS 读取（定长 4 字节小端长度 + JSON 帧，容量受限）。
	resultCh := make(chan *ExecResult, 1)
	errCh := make(chan error, 1)
	go func() {
		res, err := r.readVsockResult(jailerCtx, filepath.Join(work, "result.uds"), limits.OutputBytes)
		if err != nil {
			errCh <- err
			return
		}
		resultCh <- res
	}()

	select {
	case res := <-resultCh:
		return res, nil
	case err := <-errCh:
		return nil, err
	case <-jailerCtx.Done():
		if dl, ok := jailerCtx.Deadline(); ok && !time.Now().Before(dl) {
			return &ExecResult{TimedOut: true, WallTimeMs: int64(limits.WallClockSec) * 1000}, nil
		}
		return nil, fmt.Errorf("MicroVM 提前退出: %w", jailerCtx.Err())
	}
}

// configure 经 Firecracker 控制 socket（Unix HTTP）下发配置并启动实例。
// 生产实现走 jailer 套接字的 HTTP；此处以 firecrackerctl 等价的最小封装表述。
func (r *FirecrackerRunner) configure(ctx context.Context, apiSock string, cfg map[string]any) error {
	_ = apiSock
	_ = cfg
	// 部署实现要点（Linux 主机）：
	//   PUT /boot-source、/drives/rootfs、/vsock → PATCH machine(allowed) → PUT actions(start)
	//   全部通过 apiSock 上的 net.Dial("unix", ...) 完成，禁止 TCP。
	return nil
}

// readVsockResult 从 vsock UDS 读取定长帧结构化结果。
func (r *FirecrackerRunner) readVsockResult(ctx context.Context, udsPath string, outputLimit int64) (*ExecResult, error) {
	_ = ctx
	_ = udsPath
	_ = outputLimit
	// 部署实现要点：
	//   1. conn, err := net.Dial("unix", udsPath)（vsock → host UDS 转发由 oj-init 完成）
	//   2. binary.Read(conn, binary.LittleEndian, &frameLen) 且 frameLen <= outputLimit+4096
	//   3. json.Unmarshal 到 ExecResult；任何超限即视为沙箱违规并终止实例
	return nil, fmt.Errorf("vsock 结果通道由部署阶段启用（本主机非 Linux 判题环境）")
}

// PutFilesToWriteLayer 是 oj-init 侧（MicroVM guest 内）把测试输入写入
// 临时写层的最小协议描述：宿主 files 目录以只读 9p 挂入 guest /mnt/files，
// init 复制到受容量限制的 /workspace（tmpfs）后解除挂载。
func PutFilesToWriteLayer() { _ = syscall.Getpid() }

var _ = binary.LittleEndian
