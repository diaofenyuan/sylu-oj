package main

import (
	"context"
	"encoding/json"
	"fmt"
	"math"
	"strconv"
	"strings"

	"sylu-oj/judge/sandbox"
)

// 判题管线（Task 7）：编译与运行分别进入一次性沙箱（设计 6.2），
// 语言运行时来自固定镜像摘要，参数来自服务端白名单（language-policy.yaml），
// 禁止 Shell 拼接；状态码映射为固定枚举：
//   CE 编译失败 / TLE 超时 / MLE 内存超限 / OLE 输出超限 /
//   BSC 受限系统调用（SIGSYS/seccomp 拒绝） / RE 运行错误 / WA 答案错误 / AC 通过。
//
// 编译产物经沙箱写层回收（ExecSpec.Harvest → ExecResult.Files）注入运行沙箱，
// 编译与运行互不共享任何可写状态；测试输入经 stdin 与临时写层进入当前用例。

type Judge struct {
	client         *TestcaseClient
	runner         sandbox.Runner
	policy         *Policy
	sandboxMode    string
	fallbackNotice string
}

func NewJudge(client *TestcaseClient, runner sandbox.Runner, policy *Policy, fallbackNotice string) *Judge {
	return &Judge{
		client:         client,
		runner:         runner,
		policy:         policy,
		sandboxMode:    runner.Name(),
		fallbackNotice: fallbackNotice,
	}
}

// ImageFor 返回语言运行时对应的固定镜像引用（摘要锁定）。
func (p *Policy) ImageFor(runtime string) (string, error) {
	runtimes, ok := p.Raw["runtimes"].(map[string]any)
	if !ok {
		return "", fmt.Errorf("语言策略缺少 runtimes 段")
	}
	rt, ok := runtimes[runtime].(map[string]any)
	if !ok {
		return "", fmt.Errorf("语言策略未定义运行时 %s", runtime)
	}
	image, _ := rt["image"].(string)
	if image == "" || strings.Contains(image, "REQUIRED_FROM_RELEASE") {
		return "", fmt.Errorf("运行时 %s 的镜像摘要未注入（发布包部署阶段完成）", runtime)
	}
	return image, nil
}

// snapshotLimits 是题目快照 judge_config 的资源约束。
type snapshotLimits struct {
	TimeLimitMs   int64 `json:"timeLimitMs"`
	MemoryLimitMb int64 `json:"memoryLimitMb"`
	OutputLimitKb int64 `json:"outputLimitKb"`
	MaxScore      int64 `json:"maxScore"`
}

// JudgeTask 判定单个任务并返回结果回传体。任何基础设施错误返回 error，
// 由调用方转换为 SE 上报（自动重试、不扣次数），绝不静默丢弃。
func (j *Judge) JudgeTask(ctx context.Context, task *Task) (*CaseResultSubmission, error) {
	lang, ok := j.policy.Languages[task.Language]
	if !ok {
		return nil, fmt.Errorf("策略未定义语言 %s", task.Language)
	}
	image, err := j.policy.ImageFor(task.LanguageRuntime)
	if err != nil {
		return nil, err
	}
	var snap snapshotLimits
	if err := json.Unmarshal([]byte(task.JudgeConfig), &snap); err != nil {
		return nil, fmt.Errorf("判题配置解析失败: %w", err)
	}

	// 编译阶段：独立一次性沙箱；产物从写层回收后注入运行沙箱
	productFiles := map[string][]byte{}
	if lang.Compile != nil {
		compileRes, err := j.runner.Execute(ctx, sandbox.ExecSpec{
			Image:   image,
			Argv:    lang.Compile.Argv,
			Files:   map[string][]byte{lang.Compile.Source: []byte(task.Code)},
			Harvest: []string{lang.Compile.Product},
			Workdir: "/workspace",
		}, compileLimits(lang.Compile))
		if err != nil {
			return nil, fmt.Errorf("编译沙箱执行失败: %w", err)
		}
		if compileRes.ExitCode != 0 || compileRes.TimedOut || compileRes.OOMKilled {
			return j.ceResult(compileRes), nil
		}
		if compileRes.ForbiddenSys {
			return nil, fmt.Errorf("编译阶段触发受限系统调用（基础设施异常）")
		}
		if len(compileRes.Files) == 0 {
			return nil, fmt.Errorf("编译产物缺失（写层回收为空）")
		}
		productFiles = compileRes.Files
	}

	// 运行阶段：每个测试点独立执行（当前用例最小可见）
	scoreOf := map[int]float64{}
	for _, ts := range task.TestcaseScores {
		scoreOf[ts.Order] = ts.Score
	}
	var outcomes []CaseOutcome
	var totalTimeMs int64
	var peakMemoryKb int64

	for _, order := range task.TestcaseRefs {
		testcase, err := j.client.FetchTestcase(task.TaskUuid, order)
		if err != nil {
			return nil, fmt.Errorf("获取测试点 %d 失败: %w", order, err)
		}
		files := map[string][]byte{}
		for name, content := range productFiles {
			files[name] = content
		}
		files["input.txt"] = testcase.Input
		runRes, err := j.runner.Execute(ctx, sandbox.ExecSpec{
			Image:   image,
			Argv:    lang.Run.Argv,
			Files:   files,
			Stdin:   testcase.Input,
			Workdir: "/workspace",
		}, runLimits(lang.Run, snap))
		testcase.Wipe()
		if err != nil {
			return nil, fmt.Errorf("运行沙箱执行失败（测试点 %d）: %w", order, err)
		}
		status := mapRunResult(runRes, testcase.ExpectedOutput)
		score := 0.0
		if status == "AC" {
			score = scoreOf[order]
		}
		outcomes = append(outcomes, CaseOutcome{
			Order: order, Status: status, Score: score,
			TimeMs: runRes.WallTimeMs, MemoryKb: runRes.PeakMemoryKb,
		})
		totalTimeMs += runRes.WallTimeMs
		if runRes.PeakMemoryKb > peakMemoryKb {
			peakMemoryKb = runRes.PeakMemoryKb
		}
	}

	return j.aggregate(task, outcomes, totalTimeMs, peakMemoryKb), nil
}

// compileLimits：编译阶段使用策略固定上限（不受题目快照放宽）。
func compileLimits(s *Stage) sandbox.Limits {
	return sandbox.Limits{
		Cpus: 2.0, MemoryKb: s.MemoryKb, Pids: s.Pids,
		DiskKb: s.DiskKb, OutputBytes: s.OutputBytes,
		WallClockSec: s.WallClockSec, CpuTimeSec: s.CpuTimeSec, IoWeight: 100,
	}
}

// runLimits：题目快照收窄运行上限，但不允许超过策略默认值（放宽无效）。
func runLimits(s *Stage, snap snapshotLimits) sandbox.Limits {
	wall := int64(s.WallClockSec)
	if snap.TimeLimitMs > 0 {
		// 快照时限 + 2s 余量用于判定 TLE（沙箱超时即 TLE）
		wall = snap.TimeLimitMs/1000 + 2
		if wall < 2 {
			wall = 2
		}
		if wall > int64(s.WallClockSec) {
			wall = int64(s.WallClockSec)
		}
	}
	mem := s.MemoryKb
	if snap.MemoryLimitMb > 0 {
		limited := snap.MemoryLimitMb*1024 + 65536 // +64MB 运行时底座
		if limited < mem {
			mem = limited
		}
	}
	out := s.OutputBytes
	if snap.OutputLimitKb > 0 {
		limited := snap.OutputLimitKb * 1024
		if limited < out {
			out = limited
		}
	}
	return sandbox.Limits{
		Cpus: 1.0, MemoryKb: mem, Pids: s.Pids,
		DiskKb: s.DiskKb, OutputBytes: out,
		WallClockSec: int(wall), CpuTimeSec: int(wall), IoWeight: 100,
	}
}

// mapRunResult 将单次运行结果映射为固定状态码。
func mapRunResult(res *sandbox.ExecResult, expected []byte) string {
	switch {
	case res.ForbiddenSys:
		return "BSC"
	case res.TimedOut:
		return "TLE"
	case res.OOMKilled:
		return "MLE"
	case res.OutputLimit:
		return "OLE"
	case res.Signal != "" || res.ExitCode != 0:
		return "RE"
	case normalizeOutput(res.Output) == normalizeOutput(expected):
		return "AC"
	default:
		return "WA"
	}
}

// normalizeOutput：行尾与文件尾空白归一（\r\n → \n，去尾部空白）。
func normalizeOutput(b []byte) string {
	s := strings.ReplaceAll(string(b), "\r\n", "\n")
	s = strings.ReplaceAll(s, "\r", "\n")
	return strings.TrimRight(s, " \t\n")
}

func (j *Judge) ceResult(res *sandbox.ExecResult) *CaseResultSubmission {
	return &CaseResultSubmission{
		ResultCode:      "CE",
		NormalizedScore: "0.00",
		TotalTimeMs:     res.WallTimeMs,
		PeakMemoryKb:    res.PeakMemoryKb,
		SandboxMode:     j.sandboxMode,
		FallbackNotice:  j.fallbackNotice,
	}
}

// seResult：基础设施错误 → SE（自动重试、不扣次数、不计分）。
func (j *Judge) seResult(task *Task) *CaseResultSubmission {
	outcomes := make([]CaseOutcome, 0, len(task.TestcaseRefs))
	for _, order := range task.TestcaseRefs {
		outcomes = append(outcomes, CaseOutcome{Order: order, Status: "SE", Score: 0})
	}
	return &CaseResultSubmission{
		ResultCode:      "SE",
		NormalizedScore: "0.00",
		SandboxMode:     j.sandboxMode,
		FallbackNotice:  j.fallbackNotice,
		Testcases:       outcomes,
	}
}

// aggregate：整体状态取最坏结果（安全类最高优先），得分仅累计 AC 测试点。
// 沙箱模式与降级说明随结果回传：考试模式在 gVisor 降级状态下不得自动继续
// （设计 6.1.5，网关/考试门禁在 Task 9 消费该字段）。
func (j *Judge) aggregate(task *Task, outcomes []CaseOutcome, totalTimeMs, peakMemoryKb int64) *CaseResultSubmission {
	priority := map[string]int{"BSC": 0, "TLE": 1, "MLE": 2, "OLE": 3, "RE": 4, "WA": 5, "AC": 6}
	overall := "AC"
	sum := 0.0
	for _, o := range outcomes {
		if priority[o.Status] < priority[overall] {
			overall = o.Status
		}
		if o.Status == "AC" {
			sum += o.Score
		}
	}
	return &CaseResultSubmission{
		ResultCode:      overall,
		NormalizedScore: strconv.FormatFloat(math.Round(sum*100)/100, 'f', 2, 64),
		TotalTimeMs:     totalTimeMs,
		PeakMemoryKb:    peakMemoryKb,
		ResultVersion:   task.Attempt,
		SandboxMode:     j.sandboxMode,
		FallbackNotice:  j.fallbackNotice,
		Testcases:       outcomes,
	}
}
