package oj.judge;

import oj.audit.AuditService;
import oj.shared.AuditActions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Judge Gateway 拒绝路径写入：熔断、异常拉取记录与安全审计必须在外层事务
 * 回滚后仍然成立（REQUIRES_NEW，与身份模块 IdentityGuard 同一模式）。
 * P1 级事件以审计动作落库，Prometheus 告警规则在 Task 8 接入。
 */
@Service
public class JudgeGuard {

    private final JudgeAgentRepository agentRepository;
    private final TestcaseDistributionRepository distributionRepository;
    private final AuditService auditService;
    private final JudgeMetrics metrics;

    public JudgeGuard(JudgeAgentRepository agentRepository,
                      TestcaseDistributionRepository distributionRepository,
                      AuditService auditService,
                      JudgeMetrics metrics) {
        this.agentRepository = agentRepository;
        this.distributionRepository = distributionRepository;
        this.auditService = auditService;
        this.metrics = metrics;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void suspendAgent(String agentId, String reason) {
        JudgeAgent agent = agentRepository.findById(agentId).orElse(null);
        LocalDateTime now = LocalDateTime.now();
        if (agent != null && !JudgeAgent.SUSPENDED.equals(agent.getStatus())) {
            agent.suspend(reason, now);
            agentRepository.save(agent);
        }
        auditService.record(AuditActions.AGENT_SUSPENDED, "JUDGE_AGENT", agentId,
                null, Map.of("reason", reason));
    }

    /** 记录与当前任务不匹配的用例请求（跨题/越权拉取信号）并触发 P1 审计。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordMismatchedRequest(String agentId, String taskUuid, Long problemId, int order) {
        distributionRepository.save(new TestcaseDistribution(agentId, taskUuid, problemId, order, false));
        metrics.incrementTestcaseMismatch();
        auditService.record(AuditActions.TESTCASE_MISMATCH_DETECTED, "JUDGE_TASK", taskUuid,
                null, Map.of("agentId", agentId, "testcaseOrder", order, "problemId", problemId));
    }

    /** 独立提交的安全审计（签名无效、旧版本结果等拒绝事件）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void auditRejection(String action, String targetType, String targetId, Map<String, Object> details) {
        auditService.record(action, targetType, targetId, null, details);
    }
}
