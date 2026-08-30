// Package sandbox 提供一次性判题沙箱（Task 7，设计 6.1/6.2）。
//
// 每个 ExecSpec 对应一个全新的一次性执行环境：编译与运行分别创建，
// 销毁即失效。强制属性：
//   - 无网卡、无默认路由、无 DNS、无元数据地址可达性；
//   - 只读根文件系统 + 临时写层（容量受限 tmpfs）；
//   - 固定语言运行时（rootfs/镜像按摘要锁定）；
//   - 非 root、能力全删除、no_new_privs、seccomp 白名单、AppArmor profile；
//   - cgroups v2：CPU、内存、PID、IO 与墙钟/CPU 时间上限；
//   - 仅通过受限 vsock/Unix Socket 交回结构化结果，无任何业务网络路径；
//   - 禁止 Docker Socket、宿主机目录、宿主机 /proc、特权容器、
//     hostNetwork/hostPID/hostIPC。
package sandbox

import (
	"context"
	"fmt"
	"time"
)

// Limits 是单次沙箱执行的资源上限（来自语言策略与题目快照）。
type Limits struct {
	Cpus         float64 // cgroups v2 cpu.max
	MemoryKb     int64   // cgroups v2 memory.max
	Pids         int     // cgroups v2 pids.max（抑制 fork bomb）
	DiskKb       int64   // 临时写层 tmpfs size
	OutputBytes  int64   // stdout+stderr 累计上限
	WallClockSec int     // 墙钟上限
	CpuTimeSec   int     // CPU 时间上限
	IoWeight     int     // cgroups v2 io.weight
}

// ExecSpec 描述一次白名单执行：argv 由服务端从语言策略构造，
// 绝不经过 Shell 拼接；源码与测试数据只进入本次临时写层。
type ExecSpec struct {
	// Image 为固定运行时镜像引用，部署时必须绑定摘要（image@sha256:...）。
	Image string
	// Argv 为白名单参数向量（可含占位符替换结果），不含任何用户可控片段之外的内容。
	Argv []string
	Env  []string
	// Files 为写入临时写层的文件（相对 workdir），如源码、当前测试输入。
	Files map[string][]byte
	// Stdin 为进程标准输入（当前测试用例输入；仅对运行阶段生效）。
	Stdin []byte
	// Harvest 为执行结束后从临时写层回收的文件（相对 workdir，支持 glob），
	// 用于把编译产物从编译沙箱转移到下一次运行沙箱；单文件 ≤ maxFileBytes。
	Harvest []string
	// Workdir 为进程工作目录（临时写层内）。
	Workdir string
	// AppArmorProfile 为该执行阶段加载的 AppArmor profile 名。
	AppArmorProfile string
}

// ExecResult 是结构化执行结果；Output 不得超过 OutputBytes（截断即违规）。
type ExecResult struct {
	ExitCode     int
	Signal       string // 致命信号名（如 SIGKILL/SIGSYS），正常退出为空
	TimedOut     bool
	OOMKilled    bool
	OutputLimit  bool // 输出超出上限
	ForbiddenSys bool // seccomp 拒绝（SIGSYS），映射 BSC
	PeakMemoryKb int64
	CpuTimeMs    int64
	WallTimeMs   int64
	Output       []byte // 截断至 OutputBytes 的 stdout（stderr 单独计）
	StderrBytes  int64
	// Files 为按 ExecSpec.Harvest 回收的临时写层文件（编译产物）。
	Files map[string][]byte
}

// Runner 是一次性沙箱执行器。Firecracker 为主路径（KVM），
// gVisor 为显式降级路径（不允许静默切换）。
type Runner interface {
	Name() string
	// Available 报告当前主机是否具备该 Runner 的运行条件
	// （Firecracker 检查 /dev/kvm 与 jailer；gVisor 检查 runsc）。
	Available() error
	Execute(ctx context.Context, spec ExecSpec, limits Limits) (*ExecResult, error)
	Close() error
}

// SelectRunner 按"优先 MicroVM、显式降级 gVisor"选择执行器。
// fallbackNotice 非 nil 时调用方必须将降级写入风险登记与告警，
// 并禁止考试模式自动继续（设计 6.1.5）。
func SelectRunner(preferred string) (runner Runner, fallbackNotice *FallbackNotice) {
	fr := NewFirecrackerRunner()
	if preferred != "gvisor" {
		if err := fr.Available(); err == nil {
			return fr, nil
		}
	}
	gv := NewGVisorRunner()
	if err := gv.Available(); err != nil {
		return nil, &FallbackNotice{
			Reason:  fmt.Sprintf("Firecracker 不可用且 gVisor 不可用：%v", err),
			Blocked: true,
		}
	}
	return gv, &FallbackNotice{
		Reason: "KVM/Firecracker 不可用，已显式降级为 rootless gVisor（较弱隔离）",
	}
}

// FallbackNotice 是 MicroVM → gVisor 的显式降级通知；
// Blocked=true 表示没有任何可用沙箱，必须拒绝判题而非静默降级。
type FallbackNotice struct {
	Reason  string
	Blocked bool
}

// Deadline 根据 limits 计算墙钟截止时间。
func (l Limits) Deadline() time.Time {
	return time.Now().Add(time.Duration(l.WallClockSec) * time.Second)
}
