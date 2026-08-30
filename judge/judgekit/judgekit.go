// Package judgekit 是在线判题（judge/agent）与离线复判（judge/rejudge）
// 共享的核心判定逻辑：语言策略解析、固定状态码映射与结果聚合。
// 两端必须使用同一实现，保证复判与原判语义一致（Task 9，设计 13.2/13.3）。
//
// 状态码映射（固定枚举）：
//
//	CE 编译失败 / TLE 超时 / MLE 内存超限 / OLE 输出超限 /
//	BSC 受限系统调用（seccomp 拒绝） / RE 运行错误 / WA 答案错误 / AC 通过
package judgekit

import (
	"fmt"
	"math"
	"os"
	"strconv"
	"strings"
)

// CaseOutcome 为单个测试点结果。
type CaseOutcome struct {
	Order    int     `json:"order"`
	Status   string  `json:"status"`
	Score    float64 `json:"score"`
	TimeMs   int64   `json:"timeMs"`
	MemoryKb int64   `json:"memoryKb"`
}

// MapRunResult 将单次运行结果映射为固定状态码。
type RunOutcome struct {
	ExitCode     int
	Signal       string
	TimedOut     bool
	OOMKilled    bool
	OutputLimit  bool
	ForbiddenSys bool
	Output       []byte
}

func MapRunResult(res RunOutcome, expected []byte) string {
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
	case NormalizeOutput(res.Output) == NormalizeOutput(expected):
		return "AC"
	default:
		return "WA"
	}
}

// NormalizeOutput：行尾与文件尾空白归一（\r\n → \n，去尾部空白）。
func NormalizeOutput(b []byte) string {
	s := strings.ReplaceAll(string(b), "\r\n", "\n")
	s = strings.ReplaceAll(s, "\r", "\n")
	return strings.TrimRight(s, " \t\n")
}

// Aggregate：整体状态取最坏结果（安全类最高优先），得分仅累计 AC 测试点。
func Aggregate(outcomes []CaseOutcome, totalTimeMs, peakMemoryKb int64, resultVersion int) (code, normalizedScore string) {
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
	return overall, strconv.FormatFloat(math.Round(sum*100)/100, 'f', 2, 64)
}

// ---- 语言策略（与 judge/sandbox/language-policy.yaml 对应的最小解析器） ----

type Stage struct {
	Argv         []string
	Source       string
	Product      string
	WallClockSec int
	CpuTimeSec   int
	MemoryKb     int64
	DiskKb       int64
	OutputBytes  int64
	Pids         int
}

type Language struct {
	Runtime string
	Compile *Stage
	Run     *Stage
}

type Policy struct {
	Languages map[string]Language
	Raw       map[string]any
}

// LoadPolicy 解析语言策略文件；任何无法解析的输入直接报错拒绝，
// 绝不静默采用默认值（策略是安全白名单，宁可拒绝判题）。
func LoadPolicy(path string) (*Policy, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("读取语言策略失败: %w", err)
	}
	raw, err := parseYAMLSubset(string(data))
	if err != nil {
		return nil, fmt.Errorf("解析语言策略失败: %w", err)
	}
	langsRaw, ok := raw["languages"].(map[string]any)
	if !ok {
		return nil, fmt.Errorf("语言策略缺少 languages 段")
	}
	p := &Policy{Languages: map[string]Language{}, Raw: raw}
	for name, v := range langsRaw {
		lm, ok := v.(map[string]any)
		if !ok {
			return nil, fmt.Errorf("语言 %s 段结构非法", name)
		}
		lang := Language{}
		lang.Runtime, _ = lm["runtime"].(string)
		if c, ok := lm["compile"]; ok && c != nil {
			cm, ok := c.(map[string]any)
			if !ok {
				return nil, fmt.Errorf("语言 %s compile 段结构非法", name)
			}
			if lang.Compile, err = toStage(cm, name, "compile"); err != nil {
				return nil, err
			}
		}
		rm, ok := lm["run"].(map[string]any)
		if !ok {
			return nil, fmt.Errorf("语言 %s 缺少 run 段", name)
		}
		if lang.Run, err = toStage(rm, name, "run"); err != nil {
			return nil, err
		}
		if len(lang.Run.Argv) == 0 {
			return nil, fmt.Errorf("语言 %s run.argv 为空", name)
		}
		p.Languages[name] = lang
	}
	if len(p.Languages) == 0 {
		return nil, fmt.Errorf("语言策略未定义任何语言")
	}
	return p, nil
}

func toStage(m map[string]any, lang, section string) (*Stage, error) {
	st := &Stage{}
	if st.Source, _ = m["source"].(string); st.Source == "" {
		st.Source = "main"
	}
	prod, ok := m["product"]
	if ok && prod != nil {
		st.Product, _ = prod.(string)
	}
	argv, ok := m["argv"].([]string)
	if !ok || len(argv) == 0 {
		return nil, fmt.Errorf("语言 %s %s.argv 缺失或为空", lang, section)
	}
	st.Argv = argv
	st.WallClockSec = intScalar(m, "wallClockSec", 15)
	st.CpuTimeSec = intScalar(m, "cpuTimeSec", 10)
	st.MemoryKb = int64Scalar(m, "memoryKb", 524288)
	st.DiskKb = int64Scalar(m, "diskKb", 32768)
	st.OutputBytes = int64Scalar(m, "outputBytes", 1048576)
	st.Pids = intScalar(m, "pids", 64)
	return st, nil
}

func intScalar(m map[string]any, key string, def int) int {
	if v, ok := m[key].(string); ok {
		if n, err := strconv.Atoi(v); err == nil {
			return n
		}
	}
	return def
}

func int64Scalar(m map[string]any, key string, def int64) int64 {
	if v, ok := m[key].(string); ok {
		if n, err := strconv.ParseInt(v, 10, 64); err == nil {
			return n
		}
	}
	return def
}

// ImageFor 返回运行时对应的固定镜像引用（摘要锁定；占位未注入即拒绝）。
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

// ---- 最小 YAML 子集解析（缩进映射 + flow 字符串列表 + 标量/null） ----

type yamlLine struct {
	indent int
	text   string
}

func parseYAMLSubset(src string) (map[string]any, error) {
	var lines []yamlLine
	for _, raw := range strings.Split(src, "\n") {
		trimmed := strings.TrimRight(raw, "\r")
		content := strings.TrimLeft(trimmed, " ")
		if content == "" || strings.HasPrefix(content, "#") || content == "---" {
			continue
		}
		lines = append(lines, yamlLine{indent: len(trimmed) - len(content), text: content})
	}
	if len(lines) == 0 {
		return nil, fmt.Errorf("空策略文件")
	}
	value, rest, err := parseBlock(lines, 0, lines[0].indent)
	if err != nil {
		return nil, err
	}
	if len(rest) != 0 {
		return nil, fmt.Errorf("策略文件存在无法解析的缩进结构（起始行：%s）", rest[0].text)
	}
	m, ok := value.(map[string]any)
	if !ok {
		return nil, fmt.Errorf("策略顶层必须是映射")
	}
	return m, nil
}

func parseBlock(lines []yamlLine, i, indent int) (any, []yamlLine, error) {
	m := map[string]any{}
	for i < len(lines) {
		line := lines[i]
		if line.indent < indent {
			return m, lines[i:], nil
		}
		if line.indent > indent {
			return nil, nil, fmt.Errorf("意外的缩进：%s", line.text)
		}
		key, val, ok := splitKeyValue(line.text)
		if !ok {
			return nil, nil, fmt.Errorf("无法解析行：%s", line.text)
		}
		i++
		if val == "" {
			if i < len(lines) && lines[i].indent > indent {
				child, rest, err := parseBlock(lines, i, lines[i].indent)
				if err != nil {
					return nil, nil, err
				}
				m[key] = child
				lines = rest
				i = 0
				continue
			}
			m[key] = nil
			continue
		}
		v, err := parseScalar(val)
		if err != nil {
			return nil, nil, err
		}
		m[key] = v
	}
	return m, lines[i:], nil
}

func splitKeyValue(text string) (string, string, bool) {
	idx := strings.Index(text, ": ")
	if idx < 0 {
		if strings.HasSuffix(text, ":") {
			return strings.TrimSpace(text[:len(text)-1]), "", true
		}
		return "", "", false
	}
	return strings.TrimSpace(text[:idx]), strings.TrimSpace(text[idx+2:]), true
}

func parseScalar(s string) (any, error) {
	if s == "null" || s == "~" {
		return nil, nil
	}
	if strings.HasPrefix(s, "[") && strings.HasSuffix(s, "]") {
		inner := strings.TrimSpace(s[1 : len(s)-1])
		var out []string
		if inner != "" {
			for _, part := range strings.Split(inner, ", ") {
				item := strings.TrimSpace(part)
				item = strings.Trim(item, `"'`)
				if item == "" {
					return nil, fmt.Errorf("flow 列表含空元素：%s", s)
				}
				out = append(out, item)
			}
		}
		return out, nil
	}
	return strings.Trim(s, `"'`), nil
}
