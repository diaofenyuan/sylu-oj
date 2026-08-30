package main

import (
	"os"
	"path/filepath"
	"testing"

	"sylu-oj/judge/judgekit"
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
	p, err := judgekit.LoadPolicy(writeSamplePolicy(t))
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
	if len(c.Compile.Argv) != 6 || c.Compile.Argv[0] != "gcc" {
		t.Fatalf("compile.argv 解析错误: %v", c.Compile.Argv)
	}
	if _, err := p.ImageFor("gcc-13.3-c17"); err != nil {
		t.Fatalf("镜像解析失败: %v", err)
	}
}

func TestMapRunResultFixedCodes(t *testing.T) {
	cases := []struct {
		res  judgekit.RunOutcome
		want string
	}{
		{judgekit.RunOutcome{ExitCode: 0}, "WA"}, // 输出不匹配
		{judgekit.RunOutcome{ExitCode: 0, Output: []byte("3")}, "AC"},
		{judgekit.RunOutcome{TimedOut: true}, "TLE"},
		{judgekit.RunOutcome{OOMKilled: true}, "MLE"},
		{judgekit.RunOutcome{OutputLimit: true}, "OLE"},
		{judgekit.RunOutcome{ForbiddenSys: true}, "BSC"},
		{judgekit.RunOutcome{ExitCode: 1}, "RE"},
		{judgekit.RunOutcome{Signal: "SIGSEGV"}, "RE"},
	}
	for _, c := range cases {
		if got := judgekit.MapRunResult(c.res, []byte("3")); got != c.want {
			t.Fatalf("MapRunResult(%+v) = %s, want %s", c.res, got, c.want)
		}
	}
	// CRLF 归一
	if got := judgekit.MapRunResult(judgekit.RunOutcome{ExitCode: 0, Output: []byte("3\r\n")}, []byte("3\n")); got != "AC" {
		t.Fatalf("CRLF 归一后应判 AC，实际 %s", got)
	}
}

func TestAggregatePicksWorstAndSumsAcScores(t *testing.T) {
	code, score := judgekit.Aggregate([]judgekit.CaseOutcome{
		{Order: 1, Status: "AC", Score: 60},
		{Order: 2, Status: "TLE", Score: 0},
	}, 1000, 51200, 2)
	if code != "TLE" || score != "60.00" {
		t.Fatalf("aggregate = %s/%s", code, score)
	}
	code, score = judgekit.Aggregate([]judgekit.CaseOutcome{
		{Order: 1, Status: "AC", Score: 60},
		{Order: 2, Status: "BSC", Score: 0},
	}, 100, 100, 1)
	if code != "BSC" {
		t.Fatalf("BSC 优先级应最高，实际 %s", code)
	}
}

func TestJudgekitSharedByRejudge(t *testing.T) {
	// 在线与离线复判共享 judgekit：状态码优先级表必须一致
	if _, score := judgekit.Aggregate([]judgekit.CaseOutcome{{Order: 1, Status: "AC", Score: 100}}, 0, 0, 1); score != "100.00" {
		t.Fatalf("满分聚合错误: %s", score)
	}
	if got := judgekit.NormalizeOutput([]byte("3\r\n")); got != "3" {
		t.Fatalf("NormalizeOutput 错误: %q", got)
	}
}
