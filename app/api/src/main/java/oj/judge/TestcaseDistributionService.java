package oj.judge;

import oj.audit.AuditService;
import oj.problem.Testcase;
import oj.problem.TestcaseRepository;
import oj.shared.ApiException;
import oj.shared.AuditActions;
import oj.shared.ErrorCode;
import oj.testdata.TestdataCipher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 隐藏测试数据按需分发（设计 6.3）：
 * - 仅有"当前任务单个用例"接口，不提供批量/全量同步；
 * - 每次分发校验任务租约与用例归属，跨题/越权请求触发 P1 并暂停 Agent；
 * - 单位时间请求量超阈值同样熔断；
 * - 内容经 AES-256-GCM 信封加密下发，Agent 内存解密后写入沙箱临时层，
 *   本地磁盘与日志不留存；
 * - 每次分发（题目、用例、任务、Agent 身份、时间）写入审计。
 */
@Service
public class TestcaseDistributionService {

    private final JudgeTaskRepository taskRepository;
    private final TestcaseRepository testcaseRepository;
    private final TestcaseDistributionRepository distributionRepository;
    private final JudgeAgentService agentService;
    private final JudgeGuard guard;
    private final TestdataCipher cipher;
    private final AuditService auditService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final Clock clock;
    private final int maxPerMinute;

    public TestcaseDistributionService(JudgeTaskRepository taskRepository,
                                       TestcaseRepository testcaseRepository,
                                       TestcaseDistributionRepository distributionRepository,
                                       JudgeAgentService agentService,
                                       JudgeGuard guard,
                                       TestdataCipher cipher,
                                       AuditService auditService,
                                       com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                                       Clock clock,
                                       @Value("${oj.judge.distribution.max-per-minute:120}") int maxPerMinute) {
        this.taskRepository = taskRepository;
        this.testcaseRepository = testcaseRepository;
        this.distributionRepository = distributionRepository;
        this.agentService = agentService;
        this.guard = guard;
        this.cipher = cipher;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.maxPerMinute = maxPerMinute;
    }

    public record DistributedTestcase(int testcaseOrder, String algo, String keyId,
                                      String iv, String ciphertext) {
    }

    @Transactional
    public DistributedTestcase distribute(String agentId, String taskUuid, int order) {
        LocalDateTime now = LocalDateTime.now(clock);
        JudgeTask task = taskRepository.findByTaskUuid(taskUuid)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "判题任务不存在"));
        boolean leaseValid = JudgeTask.CLAIMED.equals(task.getStatus())
                && agentId.equals(task.getClaimedBy())
                && task.getLeaseExpiresAt() != null && task.getLeaseExpiresAt().isAfter(now);
        if (!leaseValid) {
            throw new ApiException(ErrorCode.TASK_NOT_CLAIMABLE);
        }
        // 速率阈值：单位时间内拉取量超限 → P1 并暂停该 Agent
        long recent = distributionRepository
                .countByAgentIdAndDistributedAtAfterAndMatchedTrue(agentId, now.minusSeconds(60));
        if (recent >= maxPerMinute) {
            guard.suspendAgent(agentId, "单位时间内用例请求量超过阈值（" + recent + "/min）");
            throw new ApiException(ErrorCode.AGENT_SUSPENDED);
        }
        Testcase testcase = testcaseRepository
                .findByTestcaseSetIdAndOrderNum(task.getTestcaseSetId(), order).orElse(null);
        if (testcase == null) {
            // 与当前任务不匹配的用例（跨题/越权）：P1 审计 + 暂停该 Agent
            guard.recordMismatchedRequest(agentId, taskUuid, task.getProblemId(), order);
            guard.suspendAgent(agentId, "请求与当前任务不匹配的用例（order=" + order + "）");
            throw new ApiException(ErrorCode.TESTCASE_MISMATCH);
        }
        distributionRepository.save(new TestcaseDistribution(agentId, taskUuid,
                task.getProblemId(), order, true));
        auditService.record(AuditActions.TESTCASE_DISTRIBUTED, "JUDGE_TASK", taskUuid,
                null, Map.of("agentId", agentId, "problemId", task.getProblemId(),
                        "testcaseOrder", order));
        String plain = toJson(testcase);
        // 信封以 Agent 身份密钥派生密钥加密：Agent 只能解密自己领取到的用例
        JudgeAgent agent = agentService.requireAgent(agentId);
        TestdataCipher.Envelope envelope = cipher.sealForAgent(agentId,
                agentService.secretOf(agent), plain);
        java.util.Arrays.fill(plain.toCharArray(), '\0');
        return new DistributedTestcase(order, envelope.algo(), envelope.keyId(),
                envelope.iv(), envelope.ciphertext());
    }

    private String toJson(Testcase testcase) {
        try {
            return objectMapper.writeValueAsString(
                    Map.of("input", testcase.getInput() == null ? "" : testcase.getInput(),
                            "expectedOutput", testcase.getExpectedOutput() == null ? "" : testcase.getExpectedOutput()));
        } catch (Exception e) {
            throw new IllegalStateException("测试数据序列化失败", e);
        }
    }
}
