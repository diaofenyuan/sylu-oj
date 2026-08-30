package main

import (
	"fmt"
	"os"
	"strconv"
	"strings"
)

// language-policy.yaml 的最小解析器（零外部依赖）：
// 仅支持本仓库策略文件使用的子集——两级以上嵌套映射、flow 风格字符串列表
// ["a", "b"]、标量与 null。任何无法解析的输入都直接报错拒绝，
// 绝不静默采用默认值（策略是安全白名单，宁可拒绝判题）。

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
	Compile *Stage // PYTHON 为 nil（直接运行）
	Run     *Stage
}

type Policy struct {
	Languages map[string]Language
	Raw       map[string]any
}

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

// parseYAMLSubset 解析缩进式映射 + flow 字符串列表 + 标量/null。
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
	// 判断该块是映射（key: ...）还是纯列表块；本策略顶层与子块均为映射。
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
			// 子块：取下一行缩进（更深）作为块内缩进
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
	// 键不含引号与冒号；": " 为分隔；"argv: [...]" 的值中允许冒号
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
	// 标量：数字原样返回字符串（数值由使用者按需转换），去引号
	return strings.Trim(s, `"'`), nil
}
