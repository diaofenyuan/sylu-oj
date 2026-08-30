#!/usr/bin/env bats
# tests/acceptance/go-live.bats —— 上线审批与安全验收就绪检查（Task 9）
# 静态断言：审批包文档、考试模式实现、离线复判隔离性、验收程序齐备。
# live 段：生产部署期在各安全域执行（设计 13.4），须显式开启。

setup() {
  ROOT="$(cd "$BATS_TEST_DIRNAME/../.." && pwd)"
  EXAM="$ROOT/app/api/src/main/java/oj/exam"
  REJUDGE="$ROOT/judge/rejudge"
  DOCS="$ROOT/docs"
  TESTS="$ROOT/tests"
}

# ---------------- 考试模式实现 ----------------

@test "考试锁定经双人审批（自批拒绝 + TOTP 二次认证）" {
  SRC="$EXAM/ExamService.java"
  grep -q 'EXAM_SELF_APPROVAL' "$SRC"
  grep -q 'requireConfirmedTotp' "$SRC"
  grep -q 'MFA_ENROLLMENT_REQUIRED' "$SRC"
  grep -q 'EXAM_LOCKED' "$SRC"
}

@test "考试期间修改闸门接入组卷/规则/撤回路径" {
  S="$ROOT/app/api/src/main/java/oj/assignment/AssignmentService.java"
  grep -q 'requireChangeAllowed' "$S"
  grep -q 'ACTION_PUBLISH\|ACTION_WITHDRAW\|ACTION_CHANGE_TARGET_RULES' "$S"
}

@test "锁定冻结判题运行时标识与策略校验和" {
  S="$EXAM/ExamService.java"
  grep -q 'runtimeIds' "$S"
  grep -q 'policyChecksum' "$S"
  grep -q 'SHA-256' "$S"
}

@test "考试归档绑定 Agent 身份/版本/资源指标/写入时间 + 校验和" {
  S="$EXAM/ExamService.java"
  grep -q '"agentId"' "$S"
  grep -q '"resultVersion"' "$S"
  grep -q '"recordedAt"' "$S"
  grep -q '"peakMemoryKb"' "$S"
  grep -q 'checksum' "$S"
}

@test "申诉复判差异不自动采用（REJUDGED 等待人工复核）" {
  S="$EXAM/ExamAppeal.java"
  grep -q 'applyRejudgeOutcome' "$S"
  grep -q '复判差异' "$S"
  S2="$EXAM/ExamService.java"
  grep -q '仅管理员执行' "$S2"
  grep -q 'EXAM_APPEAL_RESOLVED' "$S2"
}

@test "随机抽查 ≥5% 且种子留痕" {
  S="$EXAM/ExamService.java"
  grep -q '0.05' "$S"
  grep -q 'seed' "$S"
  grep -q 'EXAM_SPOT_CHECK_CREATED' "$S"
}

# ---------------- 离线复判隔离性（设计 13.2） ----------------

@test "rejudge 不连接网关/不持有 Agent 密钥/不访问数据库" {
  S="$REJUDGE/main.go"
  [ -f "$S" ]
  ! grep -qiE 'X-Agent-Token|TestcaseClient|jdbc|mysql -|gateway' "$S"
  grep -q '不复用在线凭据' "$S"
}

@test "rejudge 与在线 Agent 共享 judgekit 判定语义" {
  grep -q 'sylu-oj/judge/judgekit' "$REJUDGE/go.mod"
  grep -q 'sylu-oj/judge/judgekit' "$ROOT/judge/agent/go.mod"
  grep -q 'MapRunResult' "$ROOT/judge/judgekit/judgekit.go"
}

@test "rejudge 产出差异清单且声明不自动采用" {
  S="$REJUDGE/main.go"
  grep -q 'rejudgeDiff' "$S"
  grep -q '不得自动采用' "$S"
}

# ---------------- 审批包与合规文档 ----------------

@test "上线审批包覆盖全部要素" {
  C="$DOCS/release/go-live-checklist.md"
  grep -q '可信 HTTPS 入口确认' "$C"
  grep -q 'environment-baseline' "$C"
  grep -q '端口矩阵' "$C"
  grep -q '恢复演练' "$C"
  grep -q '真实教务账号登录验收' "$C"
  grep -q '教务登录适配授权与责任人' "$C"
  grep -q 'no-bastion-compensation' "$C"
  grep -q 'AP-2026-06' "$C"
  grep -q '没有真实账号登录证据时不得上线正式环境' "$C"
}

@test "残余风险接受记录含签字与复审项" {
  R="$DOCS/risk/residual-risk-acceptance.md"
  grep -q 'AP-2026-06' "$R"
  grep -q 'AP-2026-07' "$R"
  grep -q '已签署' "$R"
  grep -q '待签署' "$R"
}

@test "申诉与人工复核流程文档化" {
  A="$DOCS/compliance/appeal-and-review.md"
  grep -q '不得自动采用任一版本' "$A"
  grep -q '≥⌈提交总数 × 5%⌉' "$A"
  grep -q '不复用在线凭据' "$A"
}

# ---------------- 安全验收程序（live，生产部署期执行） ----------------

@test "验收覆盖端口矩阵/OWASP/IDOR/沙箱逃逸/凭据泄露/AppArmor/KVM/日志/备份/升级回滚" {
  [ -f "$TESTS/acceptance/go-live.bats" ]
  [ -f "$TESTS/network/port-matrix.bats" ]
  [ -f "$TESTS/judge/sandbox-isolation.bats" ]
  [ -f "$TESTS/ops/lifecycle.bats" ]
  [ -f "$TESTS/security/apparmor-systemd.bats" ]
}

@test "live: 考试锁定双人审批全流程联测" {
  [ -n "$OJ_ACCEPTANCE_LIVE" ] || skip "未启用 live 验收（OJ_ACCEPTANCE_LIVE=1，生产部署期）"
  "$TESTS/acceptance/run-exam-flow.sh"
}

@test "live: 恢复演练证据存在" {
  [ -n "$OJ_ACCEPTANCE_LIVE" ] || skip "未启用 live 验收"
  ls /var/lib/oj/drill-logs/restore-drill-*.log >/dev/null 2>&1
}
