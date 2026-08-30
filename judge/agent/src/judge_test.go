package main

import (
	"os"
	"path/filepath"
	"testing"

	"sylu-oj/judge/sandbox"
)

const samplePolicy = `version: 1
runtimes:
  gcc-13.3-c17:
    image: registry.oj.internal/judge/toolchain-c@sha256:abc
languages:
  C:
    runtime: gcc-13.3-c17
    compile:
      argv: ["gcc", "-O2", "-std=c17", "-o", "main", "main.c"]
      source: main.c
      product: main
      wallClockSec: 15
      cpuTimeSec: 10
      memoryKb: 1048576
      pids: 64
      diskKb: 65536
      outputBytes: 65536
    run:
      argv: ["./main"]
      wallClockSec: 10
      cpuTimeSec: 8
      memoryKb: 524288
      pids: 64
      diskKb: 32768
      outputBytes: 1048576
  PYTHON:
    runtime: cpython-3.12
    compile: null
    run:
      argv: ["python3", "-I", "-S", "main.py"]
      source: main.py
      wallClockSec: 10
      cpuTimeSec: 8
      memoryKb: 524288
      pids: 64
      diskKb: 32768
      outputBytes: 1048576
`

func writeSamplePolicy(t *testing.T) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "language-policy.yaml")
	if err := os.WriteFile(path, []byte(samplePolicy), 0o600); err != nil {
		t.Fatal(err)
	}
	return path
}

func TestLoadPolicyParsesStages(t *testing.T) {
	p, err := LoadPolicy(writeSamplePolicy(t))
	if err != nil {
		t.Fatalf("解析失败: %v", err)
	}
	c, ok := p.Languages["C"]
	if !ok {
		t.Fatal("缺少 C 语言段")
	}
	if c.Runtime != "gcc-13.3-c17" {
		t.Fatalf("runtime = %q", c.Runtime)
	}
	if len(c.Compile.Argv) != 6 || c.Compile.Argv[0] != "gcc" || c.Compile.Argv[4] != "main" {
		t.Fatalf("compile.argv 解析错误: %v", c.Compile.Argv)
	}
	if c.Compile.Product != "main" || c.Compile.WallClockSec != 15 {
		t.Fatalf("compile 段字段错误: %+v", c.Compile)
	}
	py, ok := p.Languages["PYTHON"]
	if !ok || py.Compile != nil {
		t.Fatalf("PYTHON compile 应为 null")
	}
	if len(py.Run.Argv) != 4 || py.Run.Argv[1] != "-I" {
		t.Fatalf("PYTHON run.argv 解析错误: %v", py.Run.Argv)
	}
	if _, err := p.ImageFor("gcc-13.3-c17"); err != nil {
		t.Fatalf("镜像解析失败: %v", err)
	}
	if _, err := p.ImageFor("unknown"); err == nil {
		t.Fatal("未知运行时应报错")
	}
}

func TestLoadPolicyRejectsPlaceholderDigest(t *testing.T) {
	broken := `version: 1
runtimes:
  gcc-13.3-c17:
    image: registry@sha256:REQUIRED_FROM_RELEASE
languages:
  C:
    runtime: gcc-13.3-c17
    compile:
      argv: ["gcc"]
      source: main.c
      product: main
    run:
      argv: ["./main"]
`
	path := filepath.Join(t.TempDir(), "policy.yaml")
	if err := os.WriteFile(path, []byte(broken), 0o600); err != nil {
		t.Fatal(err)
	}
	p, err := LoadPolicy(path)
	if err != nil {
		t.Fatalf("解析不应失败: %v", err)
	}
	if _, err := p.ImageFor("gcc-13.3-c17"); err == nil {
		t.Fatal("未注入摘要的镜像必须拒绝判题")
	}
}

func TestMapRunResultFixedCodes(t *testing.T) {
	cases := []struct {
		res  sandbox.ExecResult
		want string
	}{
		{sandbox.ExecResult{ExitCode: 0}, "WA"}, // 输出不匹配
		{sandbox.ExecResult{ExitCode: 0, Output: []byte("3")}, "AC"},
		{sandbox.ExecResult{TimedOut: true}, "TLE"},
		{sandbox.ExecResult{OOMKilled: true}, "MLE"},
		{sandbox.ExecResult{OutputLimit: true}, "OLE"},
		{sandbox.ExecResult{ForbiddenSys: true}, "BSC"},
		{sandbox.ExecResult{ExitCode: 1}, "RE"},
		{sandbox.ExecResult{Signal: "SIGSEGV"}, "RE"},
	}
	for _, c := range cases {
		if got := mapRunResult(&c.res, []byte("3")); got != c.want {
			t.Fatalf("mapRunResult(%+v) = %s, want %s", c.res, got, c.want)
		}
	}
	// 空输出与空期望一致 → AC
	if got := mapRunResult(&sandbox.ExecResult{ExitCode: 0}, nil); got != "AC" {
		t.Fatalf("空输出与空期望应判 AC，实际 %s", got)
	}
	if got := mapRunResult(&sandbox.ExecResult{ExitCode: 0, Output: []byte("3\r\n")}, []byte("3\n")); got != "AC" {
		t.Fatalf("CRLF 归一后应判 AC，实际 %s", got)
	}
}

func TestAggregatePicksWorstAndSumsAcScores(t *testing.T) {
	j := &Judge{sandboxMode: "gvisor", fallbackNotice: "降级"}
	task := &Task{Attempt: 2, TestcaseRefs: []int{1, 2}}
	res := j.aggregate(task, []CaseOutcome{
		{Order: 1, Status: "AC", Score: 60},
		{Order: 2, Status: "TLE", Score: 0},
	}, 1000, 51200)
	if res.ResultCode != "TLE" || res.NormalizedScore != "60.00" {
		t.Fatalf("aggregate = %s/%s", res.ResultCode, res.NormalizedScore)
	}
	if res.SandboxMode != "gvisor" || res.FallbackNotice == "" {
		t.Fatalf("降级标记必须随结果回传")
	}
	if res.ResultVersion != 2 {
		t.Fatalf("resultVersion 应等于任务 attempt")
	}
	res = j.aggregate(task, []CaseOutcome{
		{Order: 1, Status: "AC", Score: 60},
		{Order: 2, Status: "BSC", Score: 0},
	}, 100, 100)
	if res.ResultCode != "BSC" {
		t.Fatalf("BSC 优先级应最高，实际 %s", res.ResultCode)
	}
}
