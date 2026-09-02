package main

import (
	"context"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"log"
	"os"

	"sylu-oj/judge/judgekit"
	"sylu-oj/judge/sandbox"
)

// 判题管线（Task 7/9）：编译与运行分别进入一次性沙箱（设计 6.2），
// 语言运行时来自固定镜像摘要，参数来自服务端白名单（language-policy.yaml），
// 禁止 Shell 拼接。状态码映射与聚合在 judgekit（与离线复判共享同一实现）。

// Judge 判题管线宿主。
type Judge struct {
	client         *TestcaseClient
	runner         sandbox.Runner
	policy         *judgekit.Policy
	sandboxMode    string
	fallbackNotice string
	debug          bool
}

func NewJudge(client *TestcaseClient, runner sandbox.Runner, policy *judgekit.Policy, fallbackNotice string) *Judge {
	return &Judge{
		client:         client,
		runner:         runner,
		policy:         policy,
		sandboxMode:    runner.Name(),
		fallbackNotice: fallbackNotice,
		debug:          os.Getenv("OJ_DEBUG") != "",
	}
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
	var outcomes []judgekit.CaseOutcome
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
		if err != nil {
			return nil, fmt.Errorf("运行沙箱执行失败（测试点 %d）: %w", order, err)
		}
		status := judgekit.MapRunResult(judgekit.RunOutcome{
			ExitCode:     runRes.ExitCode,
			Signal:       runRes.Signal,
			TimedOut:     runRes.TimedOut,
			OOMKilled:    runRes.OOMKilled,
			OutputLimit:  runRes.OutputLimit,
			ForbiddenSys: runRes.ForbiddenSys,
			Output:       runRes.Output,
		}, testcase.ExpectedOutput)
		if j.debug {
			log.Printf("DEBUG run task=%s order=%d exit=%d signal=%q timedOut=%v outputLimit=%v outHex=%s expHex=%s",
				task.TaskUuid, order, runRes.ExitCode, runRes.Signal, runRes.TimedOut,
				runRes.OutputLimit, hex.EncodeToString(runRes.Output), hex.EncodeToString(testcase.ExpectedOutput))
		}
		// 对答案（与调试输出）之后才能清零：过早 Wipe 会把期望输出变全零（WA 误判）
		testcase.Wipe()
		score := 0.0
		if status == "AC" {
			score = scoreOf[order]
		}
		outcomes = append(outcomes, judgekit.CaseOutcome{
			Order: order, Status: status, Score: score,
			TimeMs: runRes.WallTimeMs, MemoryKb: runRes.PeakMemoryKb,
		})
		totalTimeMs += runRes.WallTimeMs
		if runRes.PeakMemoryKb > peakMemoryKb {
			peakMemoryKb = runRes.PeakMemoryKb
		}
	}

	code, normalized := judgekit.Aggregate(outcomes, totalTimeMs, peakMemoryKb, task.Attempt)
	return &CaseResultSubmission{
		ResultCode:      code,
		NormalizedScore: normalized,
		TotalTimeMs:     totalTimeMs,
		PeakMemoryKb:    peakMemoryKb,
		ResultVersion:   task.Attempt,
		SandboxMode:     j.sandboxMode,
		FallbackNotice:  j.fallbackNotice,
		Testcases:       outcomes,
	}, nil
}

// compileLimits：编译阶段使用策略固定上限（不受题目快照放宽）。
func compileLimits(s *judgekit.Stage) sandbox.Limits {
	return sandbox.Limits{
		Cpus: 2.0, MemoryKb: s.MemoryKb, Pids: s.Pids,
		DiskKb: s.DiskKb, OutputBytes: s.OutputBytes,
		WallClockSec: s.WallClockSec, CpuTimeSec: s.CpuTimeSec, IoWeight: 100,
	}
}

// runLimits：题目快照收窄运行上限，但不允许超过策略默认值（放宽无效）。
func runLimits(s *judgekit.Stage, snap snapshotLimits) sandbox.Limits {
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
	outcomes := make([]judgekit.CaseOutcome, 0, len(task.TestcaseRefs))
	for _, order := range task.TestcaseRefs {
		outcomes = append(outcomes, judgekit.CaseOutcome{Order: order, Status: "SE", Score: 0})
	}
	return &CaseResultSubmission{
		ResultCode:      "SE",
		NormalizedScore: "0.00",
		SandboxMode:     j.sandboxMode,
		FallbackNotice:  j.fallbackNotice,
		Testcases:       outcomes,
	}
}

// RunOnce 执行一次"自测运行"：与判题共用同一编译/沙盒执行器与资源测量，
// 仅单份输入、无判题比对与计分；输出与错误回传给学生。
func (j *Judge) RunOnce(ctx context.Context, task *RunTask) *RunResult {
	lang, ok := j.policy.Languages[task.Language]
	if !ok {
		return &RunResult{RunUuid: task.RunUuid, CompileError: "语言策略未定义语言 " + task.Language}
	}
	image, err := j.policy.ImageFor(task.LanguageRuntime)
	if err != nil {
		return &RunResult{RunUuid: task.RunUuid, CompileError: err.Error()}
	}
	var snap snapshotLimits
	if err := json.Unmarshal([]byte(task.JudgeConfig), &snap); err != nil {
		return &RunResult{RunUuid: task.RunUuid, CompileError: "判题配置解析失败: " + err.Error()}
	}

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
			return &RunResult{RunUuid: task.RunUuid, CompileError: "编译沙箱执行失败: " + err.Error(),
				SandboxMode: j.sandboxMode}
		}
		if compileRes.ExitCode != 0 || compileRes.TimedOut || compileRes.OOMKilled {
			return &RunResult{RunUuid: task.RunUuid,
				CompileError: string(compileRes.Stderr),
				ExitCode:     compileRes.ExitCode, TotalTimeMs: compileRes.WallTimeMs,
				PeakMemoryKb: compileRes.PeakMemoryKb, TimedOut: compileRes.TimedOut,
				SandboxMode: j.sandboxMode}
		}
		if len(compileRes.Files) == 0 {
			return &RunResult{RunUuid: task.RunUuid, CompileError: "编译产物缺失（写层回收为空）",
				SandboxMode: j.sandboxMode}
		}
		productFiles = compileRes.Files
		if j.debug {
			log.Printf("DEBUG run-once compile run=%s exit=%d peak=%dKB wall=%dms files=%d",
				task.RunUuid, compileRes.ExitCode, compileRes.PeakMemoryKb,
				compileRes.WallTimeMs, len(compileRes.Files))
		}
	}

	files := map[string][]byte{}
	for name, content := range productFiles {
		files[name] = content
	}
	if j.debug {
		names := make([]string, 0, len(files))
		for name, content := range files {
			names = append(names, fmt.Sprintf("%s(%dB)", name, len(content)))
		}
		log.Printf("DEBUG run-once files run=%s argv=%v stdin=%dB files=%v",
			task.RunUuid, lang.Run.Argv, len(task.Input), names)
	}
	runRes, err := j.runner.Execute(ctx, sandbox.ExecSpec{
		Image:   image,
		Argv:    lang.Run.Argv,
		Files:   files,
		Stdin:   []byte(task.Input),
		Workdir: "/workspace",
	}, runLimits(lang.Run, snap))
	if err != nil {
		return &RunResult{RunUuid: task.RunUuid, CompileError: "运行沙箱执行失败: " + err.Error(),
			SandboxMode: j.sandboxMode}
	}
	if j.debug {
		log.Printf("DEBUG run-once run=%s exit=%d timedOut=%v peak=%dKB wall=%dms",
			task.RunUuid, runRes.ExitCode, runRes.TimedOut, runRes.PeakMemoryKb, runRes.WallTimeMs)
	}
	return &RunResult{
		RunUuid:      task.RunUuid,
		Output:       string(runRes.Output),
		Stderr:       string(runRes.Stderr),
		ExitCode:     runRes.ExitCode,
		TotalTimeMs:  runRes.WallTimeMs,
		PeakMemoryKb: runRes.PeakMemoryKb,
		TimedOut:     runRes.TimedOut,
		SandboxMode:  j.sandboxMode,
	}
}
