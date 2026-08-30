// Judge Agent 测试数据客户端（Task 6）。
//
// 安全约束（设计 6.3）：
//   - Agent 只能以 mTLS（生产）或代理密钥（dev/test）主动连接 Judge Gateway；
//   - 测试数据按"当前任务单个用例"加密下发，本客户端在内存中解密并直接
//     返回给调用方写入本次沙箱的临时写层；任何函数都不得把用例内容写入
//     磁盘或日志，使用后的明文缓冲区立即清零；
//   - 不存在批量/全量拉取接口；跨题请求会触发 Gateway 侧 P1 熔断；
//   - 信封密钥由 Agent 身份与其注册密钥派生
//     （SHA-256("oj-testdata-v1|<agentId>|<secret>")，与 Java 端一致），
//     Agent 无需持有任何共享主密钥。
package agent

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"
)

const envelopeAlgo = "AES-256-GCM"

// TestcaseClient 封装与 Judge Gateway 的交互（/api/judge/v1）。
type TestcaseClient struct {
	baseURL  string
	agentID  string
	secret   string
	http     *http.Client
	maxBytes int64 // 单用例大小上限，防止异常超大响应耗尽内存
}

// NewTestcaseClient 创建客户端。cert/key 非空时启用 mTLS（生产路径）；
// dev/test 传 nil 则以 X-Agent-Id/X-Agent-Token 头认证。
func NewTestcaseClient(baseURL, agentID, secret string, cert tls.Certificate, rootCAs *x509.CertPool) (*TestcaseClient, error) {
	tlsCfg := &tls.Config{MinVersion: tls.VersionTLS12}
	if cert.Certificate != nil {
		tlsCfg.Certificates = []tls.Certificate{cert}
	}
	if rootCAs != nil {
		tlsCfg.RootCAs = rootCAs
	}
	return &TestcaseClient{
		baseURL:  baseURL,
		agentID:  agentID,
		secret:   secret,
		http:     &http.Client{Transport: &http.Transport{TLSClientConfig: tlsCfg}, Timeout: 60 * time.Second},
		maxBytes: 8 << 20,
	}, nil
}

// Testcase 明文用例。内容仅在内存存在，调用方写入沙箱临时层后应调用 Wipe()。
type Testcase struct {
	Input          []byte
	ExpectedOutput []byte
}

// Wipe 清零明文缓冲区。
func (t *Testcase) Wipe() {
	for i := range t.Input {
		t.Input[i] = 0
	}
	for i := range t.ExpectedOutput {
		t.ExpectedOutput[i] = 0
	}
}

// FetchTestcase 拉取并内存解密当前任务的单个用例。
// 禁止把响应体或明文写入日志/磁盘。
func (c *TestcaseClient) FetchTestcase(taskUUID string, order int) (*Testcase, error) {
	body, err := c.post("/tasks/"+taskUUID+"/testcases/"+fmt.Sprint(order), nil)
	if err != nil {
		return nil, err
	}
	var envelope struct {
		TestcaseOrder int    `json:"testcaseOrder"`
		Algo          string `json:"algo"`
		KeyID         string `json:"keyId"`
		IV            string `json:"iv"`
		Ciphertext    string `json:"ciphertext"`
	}
	if err := json.Unmarshal(body, &envelope); err != nil {
		return nil, fmt.Errorf("解析用例信封失败: %w", err)
	}
	if envelope.Algo != envelopeAlgo {
		return nil, fmt.Errorf("不支持的用例信封算法 %q", envelope.Algo)
	}
	iv, err := base64.StdEncoding.DecodeString(envelope.IV)
	if err != nil {
		return nil, fmt.Errorf("IV 解码失败: %w", err)
	}
	raw, err := base64.StdEncoding.DecodeString(envelope.Ciphertext)
	if err != nil {
		return nil, fmt.Errorf("密文解码失败: %w", err)
	}
	if int64(len(raw)) > c.maxBytes {
		return nil, fmt.Errorf("用例密文超出大小上限")
	}
	plaintext, err := c.decrypt(iv, raw)
	if err != nil {
		return nil, err
	}
	defer zero(plaintext)

	var content struct {
		Input          string `json:"input"`
		ExpectedOutput string `json:"expectedOutput"`
	}
	if err := json.Unmarshal(plaintext, &content); err != nil {
		return nil, fmt.Errorf("用例明文解析失败: %w", err)
	}
	return &Testcase{
		Input:          []byte(content.Input),
		ExpectedOutput: []byte(content.ExpectedOutput),
	}, nil
}

// ResultSignature 计算结果 HMAC-SHA256 签名，canonical 与 Gateway 一致：
// taskUuid|resultCode|score(两位小数)|totalTimeMs|peakMemoryKb|resultVersion。
func (c *TestcaseClient) ResultSignature(taskUUID, resultCode, score string, totalTimeMs, peakMemoryKb int64, resultVersion int) string {
	canonical := fmt.Sprintf("%s|%s|%s|%d|%d|%d", taskUUID, resultCode, score, totalTimeMs, peakMemoryKb, resultVersion)
	mac := hmac.New(sha256.New, []byte(c.secret))
	mac.Write([]byte(canonical))
	return hex.EncodeToString(mac.Sum(nil))
}

func (c *TestcaseClient) decrypt(iv, ciphertext []byte) ([]byte, error) {
	block, err := aes.NewCipher(envelopeKey(c.agentID, c.secret))
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	plain, err := gcm.Open(nil, iv, ciphertext, nil)
	if err != nil {
		return nil, fmt.Errorf("用例解密失败（数据可能被篡改）: %w", err)
	}
	return plain, nil
}

func envelopeKey(agentID, secret string) []byte {
	sum := sha256.Sum256([]byte("oj-testdata-v1|" + agentID + "|" + secret))
	return sum[:]
}

func (c *TestcaseClient) post(path string, payload []byte) ([]byte, error) {
	var reader io.Reader
	if payload != nil {
		reader = bytes.NewReader(payload)
	}
	req, err := http.NewRequest(http.MethodPost, c.baseURL+path, reader)
	if err != nil {
		return nil, err
	}
	req.Header.Set("X-Agent-Id", c.agentID)
	req.Header.Set("X-Agent-Token", c.secret)
	req.Header.Set("Content-Type", "application/json")
	resp, err := c.http.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, c.maxBytes))
	if err != nil {
		return nil, err
	}
	if resp.StatusCode >= 400 {
		return nil, fmt.Errorf("gateway 返回 %d", resp.StatusCode)
	}
	return body, nil
}

func zero(b []byte) {
	for i := range b {
		b[i] = 0
	}
}
