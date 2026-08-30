// 离线复判工具（Task 9，设计 13.2/13.3）。
//
// 职责：在不复用在线凭据、不共享在线密钥的离线环境中，重放考试归档中的
// 提交（编译 + 逐用例运行），与归档结果比对并生成差异清单（JSONL）。
//
// 安全约束（设计 13.2）：
//   - 不连接 Judge Gateway、不持有 Agent 密钥、不访问业务数据库；
//   - 测试数据来自本地受控的明文捆绑文件（由 DATA 区解密导出，复判期间
//     在线判题服务停止或只读）；
//   - 判定语义与在线 Agent 共享 judgekit（同一状态码映射与聚合实现）；
//   - 差异清单只记录差异，不自动采用任何版本（人工复核后经 API 修订）。
//
// 用法：
//
//	rejudge --archive exam-1.jsonl --testdata testdata.json \
//	        --policy /etc/oj-judge/language-policy.yaml \
//	        --runner gvisor --out rejudge-diff.jsonl
//
// testdata.json 结构：
//
//	{ "<problemId>": [ {"order":1,"input":"...","expected":"..."}, ... ] }
package main

import (
	"bufio"
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"os"
	"strings"

	"sylu-oj/judge/judgekit"
	"sylu-oj/judge/sandbox"
)

type archivedCase struct {
	Order    int     `json:"order"`
	Status   string  `json:"status"`
	Score    float64 `json:"score"`
	TimeMs   int64   `json:"timeMs"`
	MemoryKb int64   `json:"memoryKb"`
}

type archivedResult struct {
	ResultCode      string         `json:"resultCode"`
	NormalizedScore string         `json:"normalizedScore"`
	AgentID         string         `json:"agentId"`
	ResultVersion   int            `json:"resultVersion"`
	TotalTimeMs     int64          `json:"totalTimeMs"`
	PeakMemoryKb    int64          `json:"peakMemoryKb"`
	RecordedAt      string         `json:"recordedAt"`
	Testcases       []archivedCase `json:"testcases"`
}

type archiveRecord struct {
	SubmissionID   int64          `json:"submissionId"`
	ProblemID      int64          `json:"problemId"`
	Language       string         `json:"language"`
	Code           string         `json:"code"`
	JudgeConfig    string         `json:"judgeConfig"`
	ArchivedResult *archivedResult `json:"judgeResult"`
}

type testcaseDef struct {
	Order    int    `json:"order"`
	Input    string `json:"input"`
	Expected string `json:"expected"`
}

type rejudgeDiff struct {
	SubmissionID  int64  `json:"submissionId"`
	ArchivedCode  string `json:"archivedCode"`
	ArchivedScore string `json:"archivedScore"`
	RejudgedCode  string `json:"rejudgedCode"`
	RejudgedScore string `json:"rejudgedScore"`
	Match         bool   `json:"match"`
}

func main() {
	archivePath := flag.String("archive", "", "考试归档 JSONL 文件（必填）")
	testdataPath := flag.String("testdata", "", "测试数据捆绑 JSON 文件（必填）")
	policyPath := flag.String("policy", "", "语言策略文件（必填，摘要已注入）")
	runnerName := flag.String("runner", "gvisor", "沙箱执行器：gvisor|firecracker")
	outPath := flag.String("out", "rejudge-diff.jsonl", "差异清单输出文件")
	flag.Parse()

	if *archivePath == "" || *testdataPath == "" || *policyPath == "" {
		log.Fatal("缺少 --archive / --testdata / --policy")
	}

	policy, err := judgekit.LoadPolicy(*policyPath)
	if err != nil {
		log.Fatalf("语言策略不可用: %v", err)
	}

	runner, notice := sandbox.SelectRunner(mapPreferred(*runnerName))
	if runner == nil {
		log.Fatalf("无可用沙箱: %s", notice.Reason)
	}
	defer runner.Close()
	if notice != nil {
		log.Printf("WARN SANDBOX_FALLBACK mode=%s reason=%s", runner.Name(), notice.Reason)
	}

	testcases := loadTestdata(*testdataPath)

	archiveFile, err := os.Open(*archivePath)
	if err != nil {
		log.Fatalf("打开归档失败: %v", err)
	}
	defer archiveFile.Close()

	outFile, err := os.Create(*outPath)
	if err != nil {
		log.Fatalf("创建差异清单失败: %v", err)
	}
	defer outFile.Close()
	writer := bufio.NewWriter(outFile)
	defer writer.Flush()

	scanner := bufio.NewScanner(archiveFile)
	scanner.Buffer(make([]byte, 1024*1024), 16*1024*1024)
	total, diffs := 0, 0
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" {
			continue
		}
		var rec archiveRecord
		if err := json.Unmarshal([]byte(line), &rec); err != nil {
			log.Fatalf("归档行解析失败: %v", err)
		}
		if rec.ArchivedResult == nil {
			continue // 无结果（PD/SE 重试中）不计入复判
		}
		total++
		code, score, err := rejudgeSubmission(runner, policy, testcases[fmt.Sprint(rec.ProblemID)], rec)
		if err != nil {
			log.Printf("WARN 复判失败 submission=%d: %v", rec.SubmissionID, err)
			continue
		}
		match := code == rec.ArchivedResult.ResultCode && score == rec.ArchivedResult.NormalizedScore
		if !match {
			diffs++
		}
		diff, _ := json.Marshal(rejudgeDiff{
			SubmissionID:  rec.SubmissionID,
			ArchivedCode:  rec.ArchivedResult.ResultCode,
			ArchivedScore: rec.ArchivedResult.NormalizedScore,
			RejudgedCode:  code,
			RejudgedScore: score,
			Match:         match,
		})
		writer.Write(diff)
		writer.WriteByte('\n')
	}
	log.Printf("复判完成：共 %d 条，差异 %d 条（差异清单：%s；差异不得自动采用，须人工复核）", total, diffs, *outPath)
}

func rejudgeSubmission(runner sandbox.Runner, policy *judgekit.Policy,
	cases []testcaseDef, rec archiveRecord) (string, string, error) {
	lang, ok := policy.Languages[rec.Language]
	if !ok {
		return "", "", fmt.Errorf("策略未定义语言 %s", rec.Language)
	}
	image, err := policy.ImageFor(lang.Runtime)
	if err != nil {
		return "", "", err
	}
	var snap struct {
		TimeLimitMs   int64 `json:"timeLimitMs"`
		MemoryLimitMb int64 `json:"memoryLimitMb"`
		OutputLimitKb int64 `json:"outputLimitKb"`
	}
	_ = json.Unmarshal([]byte(rec.JudgeConfig), &snap)

	// 编译阶段（一次性沙箱 + 写层产物回收）
	productFiles := map[string][]byte{}
	if lang.Compile != nil {
		res, err := runner.Execute(context.Background(), sandbox.ExecSpec{
			Image:   image,
			Argv:    lang.Compile.Argv,
			Files:   map[string][]byte{lang.Compile.Source: []byte(rec.Code)},
			Harvest: []string{lang.Compile.Product},
			Workdir: "/workspace",
		}, compileLimits(lang.Compile))
		if err != nil {
			return "", "", err
		}
		if res.ExitCode != 0 || res.TimedOut || res.OOMKilled {
			return "CE", "0.00", nil
		}
		productFiles = res.Files
	}

	var outcomes []judgekit.CaseOutcome
	var totalTimeMs, peakMemoryKb int64
	for _, c := range cases {
		files := map[string][]byte{}
		for name, content := range productFiles {
			files[name] = content
		}
		files["input.txt"] = []byte(c.Input)
		res, err := runner.Execute(context.Background(), sandbox.ExecSpec{
			Image:   image,
			Argv:    lang.Run.Argv,
			Files:   files,
			Stdin:   []byte(c.Input),
			Workdir: "/workspace",
		}, runLimits(lang.Run, snap))
		if err != nil {
			return "", "", err
		}
		status := judgekit.MapRunResult(judgekit.RunOutcome{
			ExitCode:     res.ExitCode,
			Signal:       res.Signal,
			TimedOut:     res.TimedOut,
			OOMKilled:    res.OOMKilled,
			OutputLimit:  res.OutputLimit,
			ForbiddenSys: res.ForbiddenSys,
			Output:       res.Output,
		}, []byte(c.Expected))
		score := 0.0
		if status == "AC" {
			score = caseScore(rec, c.Order)
		}
		outcomes = append(outcomes, judgekit.CaseOutcome{
			Order: c.Order, Status: status, Score: score,
			TimeMs: res.WallTimeMs, MemoryKb: res.PeakMemoryKb,
		})
		totalTimeMs += res.WallTimeMs
		if res.PeakMemoryKb > peakMemoryKb {
			peakMemoryKb = res.PeakMemoryKb
		}
	}
	code, normalized := judgekit.Aggregate(outcomes, totalTimeMs, peakMemoryKb, rec.ArchivedResult.ResultVersion)
	return code, normalized, nil
}

func caseScore(rec archiveRecord, order int) float64 {
	// 复判分值取自归档的测试点得分（分值由服务端冻结，不得自行加权）
	for _, tc := range rec.ArchivedResult.Testcases {
		if tc.Order == order {
			return tc.Score
		}
	}
	return 0
}

func compileLimits(s *judgekit.Stage) sandbox.Limits {
	return sandbox.Limits{
		Cpus: 2.0, MemoryKb: s.MemoryKb, Pids: s.Pids,
		DiskKb: s.DiskKb, OutputBytes: s.OutputBytes,
		WallClockSec: s.WallClockSec, CpuTimeSec: s.CpuTimeSec, IoWeight: 100,
	}
}

func runLimits(s *judgekit.Stage, snap struct {
	TimeLimitMs   int64 `json:"timeLimitMs"`
	MemoryLimitMb int64 `json:"memoryLimitMb"`
	OutputLimitKb int64 `json:"outputLimitKb"`
}) sandbox.Limits {
	wall := int64(s.WallClockSec)
	if snap.TimeLimitMs > 0 {
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
		limited := snap.MemoryLimitMb*1024 + 65536
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

func loadTestdata(path string) map[string][]testcaseDef {
	data, err := os.ReadFile(path)
	if err != nil {
		log.Fatalf("读取测试数据捆绑失败: %v", err)
	}
	out := map[string][]testcaseDef{}
	if err := json.Unmarshal(data, &out); err != nil {
		log.Fatalf("测试数据捆绑解析失败: %v", err)
	}
	return out
}

func mapPreferred(p string) string {
	if p == "gvisor" {
		return "gvisor"
	}
	return "auto"
}
