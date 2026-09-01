// Judge Agent 主程序（Task 7）。
//
// 运行模型：Agent 以非 root judge 服务账号运行，主动长轮询 Judge Gateway
// 领取短期任务，经沙箱（Firecracker 优先，gVisor 显式降级）编译与运行，
// 结果以 HMAC 签名回传。Agent 本地磁盘不留存源码、测试数据或运行输出
// （临时写层随执行销毁），日志不含测试数据与源码。
//
// 环境变量：
//
//	OJ_GATEWAY_URL        网关地址（生产 https://api-vm:8443/api/judge/v1）
//	OJ_AGENT_ID           Agent 身份（生产 = mTLS 客户端证书 CN）
//	OJ_AGENT_SECRET       Agent 密钥（生产由部署注入，dev/test 注册获得）
//	OJ_POLICY_FILE        语言策略文件（默认 /etc/oj-judge/language-policy.yaml）
//	OJ_SANDBOX_PREFERRED  auto（默认，KVM 优先）| gvisor（强制 gVisor）
//	OJ_CLIENT_CERT/OJ_CLIENT_KEY/OJ_CA_CERT  mTLS 凭据（生产）
package main

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"log"
	"os"
	"os/signal"
	"syscall"
	"time"

	"sylu-oj/judge/judgekit"
	"sylu-oj/judge/sandbox"
)

func main() {
	gatewayURL := os.Getenv("OJ_GATEWAY_URL")
	agentID := os.Getenv("OJ_AGENT_ID")
	secret := os.Getenv("OJ_AGENT_SECRET")
	if gatewayURL == "" || agentID == "" || secret == "" {
		log.Fatal("缺少 OJ_GATEWAY_URL / OJ_AGENT_ID / OJ_AGENT_SECRET")
	}
	policyPath := envOr("OJ_POLICY_FILE", "/etc/oj-judge/language-policy.yaml")

	policy, err := judgekit.LoadPolicy(policyPath)
	if err != nil {
		log.Fatalf("语言策略不可用，拒绝判题: %v", err)
	}

	var cert tls.Certificate
	var caPool *x509.CertPool
	if certFile, keyFile := os.Getenv("OJ_CLIENT_CERT"), os.Getenv("OJ_CLIENT_KEY"); certFile != "" && keyFile != "" {
		cert, err = tls.LoadX509KeyPair(certFile, keyFile)
		if err != nil {
			log.Fatalf("加载 mTLS 客户端证书失败: %v", err)
		}
	}
	if caFile := os.Getenv("OJ_CA_CERT"); caFile != "" {
		pem, err := os.ReadFile(caFile)
		if err != nil {
			log.Fatalf("读取 CA 证书失败: %v", err)
		}
		caPool = x509.NewCertPool()
		if !caPool.AppendCertsFromPEM(pem) {
			log.Fatalf("CA 证书解析失败")
		}
	}

	client, err := NewTestcaseClient(gatewayURL, agentID, secret, cert, caPool)
	if err != nil {
		log.Fatalf("初始化网关客户端失败: %v", err)
	}

	// Runner 选择：MicroVM 优先；gVisor 只能"显式"降级并全程携带降级标记，
	// 无任何可用沙箱时拒绝判题而非静默继续（设计 6.1.5）。
	preferred := envOr("OJ_SANDBOX_PREFERRED", "auto")
	runner, notice := sandbox.SelectRunner(mapPreferred(preferred))
	if runner == nil {
		log.Fatalf("无可用沙箱，Agent 拒绝启动: %s", notice.Reason)
	}
	defer runner.Close()
	if notice != nil {
		// 显式降级：写入日志与风险登记信号（P1 告警接入 Task 8 监控），
		// 降级标记随每个结果回传，考试模式据此禁止自动继续。
		log.Printf("WARN SANDBOX_FALLBACK mode=%s reason=%s", runner.Name(), notice.Reason)
	}

	judge := NewJudge(client, runner, policy, noticeReason(notice))

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	log.Printf("INFO agent=%s sandbox=%s gateway=%s 就绪", agentID, runner.Name(), gatewayURL)
	for {
		select {
		case <-ctx.Done():
			log.Printf("INFO agent 收到退出信号，结束")
			return
		default:
		}
		task, err := client.ClaimTask(25)
		if err != nil {
			log.Printf("WARN 领取任务失败: %v", err)
			sleepCtx(ctx, 3*time.Second)
			continue
		}
		if task == nil {
			run, err := client.ClaimRunTask(25)
			if err != nil {
				log.Printf("WARN 领取自测运行任务失败: %v", err)
				sleepCtx(ctx, 3*time.Second)
				continue
			}
			if run == nil {
				continue
			}
			result := judge.RunOnce(ctx, run)
			if err := client.SubmitRunResult(result); err != nil {
				log.Printf("WARN 自测运行结果回传失败 run=%s: %v", run.RunUuid, err)
			} else {
				log.Printf("INFO 自测运行完成 run=%s sandbox=%s", run.RunUuid, result.SandboxMode)
			}
			continue
		}
		submission, err := judge.JudgeTask(ctx, task)
		if err != nil {
			// 基础设施错误 → SE：自动重试、不消耗学生次数、不计分
			log.Printf("WARN 判题失败 task=%s submission=%d: %v", task.TaskUuid, task.SubmissionId, err)
			submission = judge.seResult(task)
		}
		if err := client.SubmitResult(task.TaskUuid, submission); err != nil {
			log.Printf("WARN 结果回传失败 task=%s: %v", task.TaskUuid, err)
		} else {
			log.Printf("INFO 判题完成 task=%s submission=%d code=%s score=%s sandbox=%s",
				task.TaskUuid, task.SubmissionId, submission.ResultCode,
				submission.NormalizedScore, submission.SandboxMode)
		}
	}
}

func mapPreferred(p string) string {
	if p == "gvisor" || p == "host-dev" {
		return p
	}
	return "auto"
}

func noticeReason(n *sandbox.FallbackNotice) string {
	if n == nil {
		return ""
	}
	return n.Reason
}

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func sleepCtx(ctx context.Context, d time.Duration) {
	select {
	case <-ctx.Done():
	case <-time.After(d):
	}
}
