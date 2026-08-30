package oj.judge;

import com.fasterxml.jackson.databind.ObjectMapper;
import oj.audit.AuditService;
import oj.assignment.ProblemSnapshot;
import oj.assignment.ProblemSnapshotRepository;
import oj.shared.ApiException;
import oj.shared.AuditActions;
import oj.shared.ErrorCode;
import oj.submission.JudgeResult;
import oj.submission.JudgeResultRepository;
import oj.submission.JudgeResultService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * Judge Gateway 结果入口（Task 6）：在 JudgeResultService 通用校验之上执行
 * Gateway 侧合法性校验（设计 6.4）：
 * - Agent 身份认证与结果 HMAC 签名校验（密钥与 Agent 绑定，可吊销追溯）；
 * - 任务必须处于该 Agent 名下的有效租约内；
 * - 快照版本必须与任务一致（旧快照/被篡改结果拒绝）；
 * - 固定枚举结果码、测试点结果与得分一致性、耗时/内存上限校验；
 * - 结果按任务 ID + result_version 幂等：重复投递返回既有结果，旧版本拒绝；
 * - SE 自动重试不消耗提交次数（JudgeResultService 回退计数，这里排新任务）。
 */
@Service
public class JudgeResultGatewayService {

    public record GatewayTestcaseOutcome(int order, String status, BigDecimal score,
                                         long timeMs, long memoryKb) {
    }

    public record GatewayResultCommand(String taskUuid, String agentId, String resultCode,
                                       BigDecimal normalizedScore, long totalTimeMs, long peakMemoryKb,
                                       int resultVersion, Integer snapshotVersion,
                                       java.util.List<GatewayTestcaseOutcome> testcases,
                                       String signature) {
    }

    public record GatewayResult(JudgeResult result, boolean duplicate) {
    }

    /** 与题目快照 judge_config 对应的资源上限。 */
    record JudgeConfigLimits(long timeLimitMs, long memoryLimitMb) {
    }

    private final JudgeTaskRepository taskRepository;
    private final JudgeResultRepository judgeResultRepository;
    private final ProblemSnapshotRepository snapshotRepository;
    private final JudgeResultService judgeResultService;
    private final JudgeTaskService taskService;
    private final JudgeAgentService agentService;
    private final JudgeGuard guard;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public JudgeResultGatewayService(JudgeTaskRepository taskRepository,
                                     JudgeResultRepository judgeResultRepository,
                                     ProblemSnapshotRepository snapshotRepository,
                                     JudgeResultService judgeResultService,
                                     JudgeTaskService taskService,
                                     JudgeAgentService agentService,
                                     JudgeGuard guard,
                                     AuditService auditService,
                                     ObjectMapper objectMapper) {
        this.taskRepository = taskRepository;
        this.judgeResultRepository = judgeResultRepository;
        this.snapshotRepository = snapshotRepository;
        this.judgeResultService = judgeResultService;
        this.taskService = taskService;
        this.agentService = agentService;
        this.guard = guard;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public GatewayResult record(GatewayResultCommand command) {
        // 1. 结果签名校验（失败即 P1 审计并拒绝，不泄露任务状态）
        JudgeAgent agent = agentService.requireAgent(command.agentId());
        String canonical = canonical(command);
        if (!agentService.verifyResultSignature(agent, canonical, command.signature())) {
            guard.auditRejection(AuditActions.JUDGE_RESULT_SIGNATURE_INVALID, "JUDGE_TASK",
                    command.taskUuid(), Map.of("agentId", command.agentId()));
            throw new ApiException(ErrorCode.RESULT_SIGNATURE_INVALID);
        }

        JudgeTask task = taskRepository.findByTaskUuid(command.taskUuid())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "判题任务不存在"));

        // 2. 结果幂等：同版本重复投递返回既有结果（at-least-once 消费安全）
        JudgeResult existing = judgeResultRepository.findBySubmissionId(task.getSubmissionId()).orElse(null);
        if (existing != null) {
            if (command.resultVersion() == existing.getResultVersion()
                    && command.resultCode().equals(existing.getResultCode())) {
                return new GatewayResult(existing, true);
            }
            if (command.resultVersion() <= existing.getResultVersion()) {
                guard.auditRejection(AuditActions.JUDGE_RESULT_STALE_REJECTED, "JUDGE_TASK",
                        task.getTaskUuid(), Map.of("agentId", command.agentId(),
                                "resultVersion", command.resultVersion(),
                                "existingVersion", existing.getResultVersion()));
                throw new ApiException(ErrorCode.STALE_RESULT_VERSION);
            }
        }

        // 3. 任务租约归属校验（越权任务拒绝）
        boolean owned = JudgeTask.CLAIMED.equals(task.getStatus())
                && command.agentId().equals(task.getClaimedBy());
        if (!owned) {
            throw new ApiException(ErrorCode.TASK_NOT_CLAIMABLE);
        }

        // 4. 快照版本一致性
        if (command.snapshotVersion() != null && command.snapshotVersion() != task.getSnapshotVersion()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "快照版本与任务不一致");
        }

        // 5. 资源上限与得分一致性校验
        ProblemSnapshot snapshot = snapshotRepository.findById(task.getSnapshotId())
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR, "任务对应快照缺失"));
        validateAgainstSnapshot(snapshot, command);

        // 6. 落库（通用校验：枚举、得分区间、AC 一致性、版本单调、SE 回退计数）
        JudgeResult result = judgeResultService.record(new JudgeResultService.ResultCommand(
                task.getSubmissionId(), command.resultCode(), command.normalizedScore(),
                command.totalTimeMs(), command.peakMemoryKb(), command.resultVersion(),
                command.agentId(),
                command.testcases() == null ? null : command.testcases().stream()
                        .map(t -> new JudgeResultService.TestcaseOutcome(t.order(), t.status(),
                                t.score(), t.timeMs(), t.memoryKb()))
                        .toList()));

        // 7. 任务完成 / SE 重试排程（同一事务）
        taskService.completeTask(task);
        if ("SE".equals(command.resultCode())) {
            taskService.scheduleRetry(task);
        }
        return new GatewayResult(result, false);
    }

    private String canonical(GatewayResultCommand command) {
        BigDecimal score = command.normalizedScore() == null ? BigDecimal.ZERO
                : command.normalizedScore().setScale(2, RoundingMode.HALF_UP);
        return command.taskUuid() + "|" + command.resultCode() + "|" + score.toPlainString()
                + "|" + command.totalTimeMs() + "|" + command.peakMemoryKb() + "|" + command.resultVersion();
    }

    private void validateAgainstSnapshot(ProblemSnapshot snapshot, GatewayResultCommand command) {
        JudgeConfigLimits limits = parseLimits(snapshot.getJudgeConfig());
        // 资源上限为宽松上界：TLE/MLE 的实测值可超限，但必须在合理的数倍以内
        long maxTimeMs = Math.max(limits.timeLimitMs() * 3, 60_000L);
        long maxMemoryKb = Math.max(limits.memoryLimitMb() * 4 * 1024L, 2_097_152L);
        if (command.totalTimeMs() < 0 || command.totalTimeMs() > maxTimeMs) {
            throw new ApiException(ErrorCode.RESULT_LIMIT_INVALID, "耗时超出上限约束");
        }
        if (command.peakMemoryKb() < 0 || command.peakMemoryKb() > maxMemoryKb) {
            throw new ApiException(ErrorCode.RESULT_LIMIT_INVALID, "内存超出上限约束");
        }
        if ("CE".equals(command.resultCode()) || "BSC".equals(command.resultCode())) {
            BigDecimal score = command.normalizedScore() == null ? BigDecimal.ZERO : command.normalizedScore();
            if (score.compareTo(BigDecimal.ZERO) != 0) {
                throw new ApiException(ErrorCode.RESULT_SCORE_INVALID, "编译错误/受限调用得分必须为 0");
            }
            if (command.testcases() != null && !command.testcases().isEmpty()) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "编译错误不应有测试点结果");
            }
            return;
        }
        if (command.testcases() == null || command.testcases().isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "缺少测试点结果");
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (GatewayTestcaseOutcome tc : command.testcases()) {
            sum = sum.add(tc.score() == null ? BigDecimal.ZERO : tc.score());
        }
        BigDecimal normalized = command.normalizedScore() == null ? BigDecimal.ZERO
                : command.normalizedScore().setScale(2, RoundingMode.HALF_UP);
        if (sum.setScale(2, RoundingMode.HALF_UP).compareTo(normalized) != 0) {
            throw new ApiException(ErrorCode.RESULT_SCORE_INVALID, "得分与测试点得分之和不一致");
        }
    }

    private JudgeConfigLimits parseLimits(String judgeConfig) {
        try {
            var node = objectMapper.readTree(judgeConfig);
            return new JudgeConfigLimits(node.path("timeLimitMs").asLong(5000),
                    node.path("memoryLimitMb").asLong(256));
        } catch (Exception e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "判题配置解析失败");
        }
    }
}
