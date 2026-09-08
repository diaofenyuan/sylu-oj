package main

import (
	"context"
	"crypto/aes"
	"crypto/cipher"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"

	"sylu-oj/judge/judgekit"
	"sylu-oj/judge/sandbox"
)

type pipelineRunner struct {
	execute func(sandbox.ExecSpec) (*sandbox.ExecResult, error)
}

func (r *pipelineRunner) Name() string     { return "test" }
func (r *pipelineRunner) Available() error { return nil }
func (r *pipelineRunner) Close() error     { return nil }
func (r *pipelineRunner) Execute(_ context.Context, spec sandbox.ExecSpec, _ sandbox.Limits) (*sandbox.ExecResult, error) {
	return r.execute(spec)
}

func TestPythonSourceReachesJudgeAndSelfTest(t *testing.T) {
	policy, err := judgekit.LoadPolicy("../../sandbox/language-policy.dev.yaml")
	if err != nil {
		t.Fatal(err)
	}
	block, _ := aes.NewCipher(envelopeKey("agent", "secret"))
	gcm, _ := cipher.NewGCM(block)
	iv := make([]byte, gcm.NonceSize())
	sealed := gcm.Seal(nil, iv, []byte(`{"input":"1 2","expectedOutput":"3\n"}`), nil)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(map[string]any{
			"algo": envelopeAlgo, "testcaseOrder": 1,
			"iv":         base64.StdEncoding.EncodeToString(iv),
			"ciphertext": base64.StdEncoding.EncodeToString(sealed),
		})
	}))
	defer server.Close()
	client := &TestcaseClient{baseURL: server.URL, agentID: "agent", secret: "secret", http: server.Client(), maxBytes: 8 << 20}
	code := "print(sum(map(int, input().split())))"
	calls := 0
	var lastInput []byte
	fail := false
	runner := &pipelineRunner{execute: func(spec sandbox.ExecSpec) (*sandbox.ExecResult, error) {
		calls++
		if string(spec.Files["main.py"]) != code {
			t.Fatalf("源码未注入: %v", spec.Files)
		}
		if string(spec.Stdin) != "1 2" {
			t.Fatalf("输入错误: %q", spec.Stdin)
		}
		lastInput = spec.Stdin
		if fail {
			return nil, fmt.Errorf("sandbox unavailable")
		}
		return &sandbox.ExecResult{Output: []byte("3\n")}, nil
	}}
	judge := NewJudge(client, runner, policy, "")
	task := &Task{TaskUuid: "task", Language: "PYTHON", LanguageRuntime: "cpython-3.12", Code: code, JudgeConfig: "{}", Attempt: 2,
		TestcaseRefs: []int{1}, TestcaseScores: []TaskScore{{Order: 1, Score: 100}}}
	result, err := judge.JudgeTask(context.Background(), task)
	if err != nil {
		t.Fatal(err)
	}
	if result.ResultCode != "AC" || result.NormalizedScore != "100.00" || result.ResultVersion != 2 {
		t.Fatalf("判题结果错误: %+v", result)
	}
	self := judge.RunOnce(context.Background(), &RunTask{RunUuid: "run", Language: task.Language,
		LanguageRuntime: task.LanguageRuntime, JudgeConfig: "{}", Code: code, Input: "1 2"})
	if self.CompileError != "" || self.Output != "3\n" || calls != 2 {
		t.Fatalf("自测错误: %+v, calls=%d", self, calls)
	}
	fail = true
	if _, err := judge.JudgeTask(context.Background(), task); err == nil {
		t.Fatal("沙箱故障应返回错误")
	}
	for _, b := range lastInput {
		if b != 0 {
			t.Fatal("故障路径未清理测试输入")
		}
	}
}

func TestFailureResultsPreserveAttempt(t *testing.T) {
	policy, err := judgekit.LoadPolicy(writeSamplePolicy(t))
	if err != nil {
		t.Fatal(err)
	}
	runner := &pipelineRunner{execute: func(sandbox.ExecSpec) (*sandbox.ExecResult, error) {
		return &sandbox.ExecResult{ExitCode: 1}, nil
	}}
	judge := NewJudge(nil, runner, policy, "")
	for _, attempt := range []int{1, 3} {
		task := &Task{Language: "C", LanguageRuntime: "gcc-13.3-c17", JudgeConfig: "{}", Attempt: attempt}
		ce, err := judge.JudgeTask(context.Background(), task)
		if err != nil {
			t.Fatal(err)
		}
		if ce.ResultCode != "CE" || ce.ResultVersion != attempt {
			t.Fatalf("CE 版本错误: %+v", ce)
		}
		if se := judge.seResult(task); se.ResultVersion != attempt {
			t.Fatalf("SE 版本错误: %+v", se)
		}
	}
}
