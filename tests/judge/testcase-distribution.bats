#!/usr/bin/env bats
# tests/judge/testcase-distribution.bats —— 测试数据分发安全验收（Task 6，设计 6.3）
# 校验 Judge Gateway 协议与实现满足：按任务逐用例下发、无批量/全量接口、
# Agent 认证与熔断、结果签名与幂等；live 段在 dev 服务可达时执行端到端检查。

setup() {
  ROOT="$(cd "$BATS_TEST_DIRNAME/../.." && pwd)"
  SPEC="$ROOT/judge/protocol/judge-gateway.openapi.yaml"
  GATEWAY="$ROOT/app/api/src/main/java/oj/judge"
  CLIENT="$ROOT/judge/agent/src/testcase_client.go"
}

# ---------------- 协议契约 ----------------

@test "存在 Judge Gateway OpenAPI 协议文件" {
  [ -f "$SPEC" ]
  grep -q 'openapi: 3' "$SPEC"
}

@test "协议定义领取/单用例拉取/结果回传三个端点" {
  grep -q '/tasks/claim:' "$SPEC"
  grep -q '/tasks/{taskUuid}/testcases/{order}:' "$SPEC"
  grep -q '/tasks/{taskUuid}/result:' "$SPEC"
}

@test "协议不提供批量/全量测试数据接口" {
  ! grep -qiE 'testcases/batch|testcases/all|/testcases/sync|full.?sync|bulk' "$SPEC"
}

@test "协议用例拉取端点绑定具体任务与用例序号（无列表参数）" {
  grep -q '^  /tasks/{taskUuid}/testcases/{order}:' "$SPEC"
  ! grep -qiE 'testcases/\{(orderList|orders|from|range)\}' "$SPEC"
}

@test "协议要求 mTLS 与代理密钥两种认证方式" {
  grep -q 'mutualTLS' "$SPEC"
  grep -q 'X-Agent-Token' "$SPEC"
}

@test "协议结果码为固定枚举且不含 PD" {
  grep -q 'enum: \[CE, AC, WA, RE, TLE, MLE, OLE, PE, SE, BSC\]' "$SPEC"
}

@test "协议声明任务载荷不含数据区凭据" {
  grep -qi '不包含测试数据内容' "$SPEC"
  grep -qi '数据库凭据' "$SPEC"
}

# ---------------- Gateway 实现 ----------------

@test "分发服务无批量方法且逐用例校验任务租约" {
  SRC="$GATEWAY/TestcaseDistributionService.java"
  [ -f "$SRC" ]
  grep -q 'distribute(String agentId, String taskUuid, int order)' "$SRC"
  grep -q 'TASK_NOT_CLAIMABLE' "$SRC"
  ! grep -qiE 'distributeBatch|distributeAll|syncAll' "$SRC"
}

@test "错配用例请求触发 P1 审计并暂停 Agent" {
  SRC="$GATEWAY/TestcaseDistributionService.java"
  grep -q 'recordMismatchedRequest' "$SRC"
  grep -q 'suspendAgent' "$SRC"
  grep -q 'TESTCASE_MISMATCH' "$SRC"
}

@test "请求速率阈值超限触发熔断" {
  SRC="$GATEWAY/TestcaseDistributionService.java"
  grep -q 'max-per-minute' "$SRC"
  grep -q 'countByAgentIdAndDistributedAtAfterAndMatchedTrue' "$SRC"
}

@test "结果入口校验签名、快照版本与资源上限" {
  SRC="$GATEWAY/JudgeResultGatewayService.java"
  [ -f "$SRC" ]
  grep -q 'verifyResultSignature' "$SRC"
  grep -q 'RESULT_SIGNATURE_INVALID' "$SRC"
  grep -q 'snapshotVersion' "$SRC"
  grep -q 'RESULT_LIMIT_INVALID' "$SRC"
  grep -q 'STALE_RESULT_VERSION' "$SRC"
}

@test "拒绝路径写入走独立事务（REQUIRES_NEW）" {
  SRC="$GATEWAY/JudgeGuard.java"
  grep -q 'Propagation.REQUIRES_NEW' "$SRC"
  grep -q 'TESTCASE_MISMATCH_DETECTED' "$SRC"
  grep -q 'AGENT_SUSPENDED' "$SRC"
}

@test "提交事务内写入判题任务与 Outbox" {
  SRC="$ROOT/app/api/src/main/java/oj/submission/SubmissionService.java"
  grep -q 'createTaskForSubmission' "$SRC"
  grep -q '同一事务' "$SRC"
}

@test "事件通道按 mq.mode 切换：inline（dev/test）与 RabbitMQ over TLS（prod）" {
  grep -q 'havingValue = "inline"' "$GATEWAY/InlineJudgeEventPublisher.java"
  grep -q 'havingValue = "rabbit"' "$GATEWAY/OutboxDispatcher.java"
  grep -q 'enabled: true' "$ROOT/app/api/src/main/resources/application-prod.yml"
  grep -q 'mode: rabbit' "$ROOT/app/api/src/main/resources/application-prod.yml"
}

@test "消费按 task_uuid 幂等" {
  grep -q 'markDispatched' "$GATEWAY/JudgeTask.java"
  grep -q 'dispatchedAt == null' "$GATEWAY/JudgeTask.java"
}

# ---------------- Agent 客户端 ----------------

@test "Go 客户端在内存解密用例且不留盘不留日志" {
  [ -f "$CLIENT" ]
  grep -q 'AES-256-GCM' "$CLIENT"
  grep -q 'Wipe()' "$CLIENT"
  ! grep -qiE 'os\.Create|os\.WriteFile|ioutil\.WriteFile' "$CLIENT"
}

@test "Go 客户端实现 HMAC 结果签名" {
  grep -q 'hmac.New' "$CLIENT"
  grep -q 'sha256.New' "$CLIENT"
}

@test "信封密钥派生协议两端一致" {
  grep -q 'oj-testdata-v1' "$ROOT/app/api/src/main/java/oj/testdata/TestdataCipher.java"
  grep -q 'oj-testdata-v1' "$CLIENT"
}

# ---------------- 端到端（可选，dev 服务可达时执行） ----------------

live_base_url() {
  echo "${OJ_API_BASE_URL:-http://localhost:8080}"
}

@test "live: 网关拒绝未认证领取" {
  [ -n "$OJ_LIVE_CHECKS" ] || skip "未启用 live 检查（设置 OJ_LIVE_CHECKS=1）"
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$(live_base_url)/api/judge/v1/tasks/claim" \
    -H 'Content-Type: application/json' -d '{}')
  [ "$code" = "401" ]
}

@test "live: 生产网关注册接口在未启用时拒绝" {
  [ -n "$OJ_LIVE_CHECKS" ] || skip "未启用 live 检查（设置 OJ_LIVE_CHECKS=1）"
  [ -n "$OJ_REGISTRATION_DISABLED" ] || skip "dev 环境注册接口应启用（prod 运行时设置 OJ_REGISTRATION_DISABLED=1）"
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$(live_base_url)/api/judge/v1/agents/register" \
    -H 'Content-Type: application/json' -d '{"agentId":"x"}')
  [ "$code" = "403" ]
}
