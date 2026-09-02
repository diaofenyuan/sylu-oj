package oj.submission;

import oj.audit.AuditService;
import oj.shared.ApiException;
import oj.shared.AuditActions;
import oj.shared.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;

/**
 * 判题结果写入（Task 4 阶段由测试与内部联调接口调用；Task 6 的 Judge Gateway 接管）。
 *
 * <p>幂等与版本控制：同一提交已有结果时仅接受 result_version 更新的写入；
 * 归一化得分合法性（0-100 两位小数）在此校验（Gateway 侧合法性校验的第一层）。
 * SE 结果回退提交计数（不消耗次数，等待重试）。</p>
 */
@Service
public class JudgeResultService {

    private static final Set<String> ALL_CODES = Set.of(
            "PD", "CE", "AC", "WA", "RE", "TLE", "MLE", "OLE", "PE", "SE", "BSC");

    private final SubmissionRepository submissionRepository;
    private final JudgeResultRepository judgeResultRepository;
    private final TestcaseResultRepository testcaseResultRepository;
    private final SubmissionCounterRepository counterRepository;
    private final AuditService auditService;

    public JudgeResultService(SubmissionRepository submissionRepository,
                              JudgeResultRepository judgeResultRepository,
                              TestcaseResultRepository testcaseResultRepository,
                              SubmissionCounterRepository counterRepository,
                              AuditService auditService) {
        this.submissionRepository = submissionRepository;
        this.judgeResultRepository = judgeResultRepository;
        this.testcaseResultRepository = testcaseResultRepository;
        this.counterRepository = counterRepository;
        this.auditService = auditService;
    }

    public record TestcaseOutcome(int order, String status, BigDecimal score,
                                  long timeMs, long memoryKb) {
    }

    public record ResultCommand(Long submissionId, String resultCode, BigDecimal normalizedScore,
                                long totalTimeMs, long peakMemoryKb, int resultVersion,
                                String agentId, List<TestcaseOutcome> testcases, String caseDetailsJson) {
    }

    @Transactional
    public JudgeResult record(ResultCommand command) {
        Submission submission = submissionRepository.findByIdForUpdate(command.submissionId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "提交不存在"));
        if (!ALL_CODES.contains(command.resultCode())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "非法结果码");
        }
        if ("PD".equals(command.resultCode())) {
            throw new ApiException(ErrorCode.RESULT_NOT_TERMINAL);
        }
        BigDecimal score = command.normalizedScore() == null
                ? BigDecimal.ZERO
                : command.normalizedScore().setScale(2, RoundingMode.HALF_UP);
        if (score.compareTo(BigDecimal.ZERO) < 0 || score.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new ApiException(ErrorCode.RESULT_SCORE_INVALID);
        }
        validateTestcases(command.resultCode(), command.testcases());

        JudgeResult result = judgeResultRepository.findBySubmissionId(submission.getId()).orElse(null);
        if (result == null) {
            result = new JudgeResult(submission, command.resultCode(),
                    score, command.totalTimeMs(), command.peakMemoryKb(),
                    command.agentId() == null ? "agent-1" : command.agentId(),
                    Math.max(1, command.resultVersion()));
            result.setCaseDetails(command.caseDetailsJson());
            result = judgeResultRepository.save(result);
        } else {
            if (command.resultVersion() <= result.getResultVersion()) {
                throw new ApiException(ErrorCode.STALE_RESULT_VERSION);
            }
            result.applyNewVersion(command.resultCode(), score, command.totalTimeMs(),
                    command.peakMemoryKb(),
                    command.agentId() == null ? result.getAgentId() : command.agentId(),
                    command.resultVersion(), command.caseDetailsJson());
            testcaseResultRepository.deleteByJudgeResultId(result.getId());
        }
        if (command.testcases() != null) {
            for (TestcaseOutcome tc : command.testcases()) {
                testcaseResultRepository.save(new TestcaseResult(result, tc.order(), tc.status(),
                        tc.score() == null ? BigDecimal.ZERO : tc.score(), tc.timeMs(), tc.memoryKb()));
            }
        }
        // SE 回退提交计数：判题系统错误不消耗学生提交次数
        if ("SE".equals(command.resultCode())) {
            counterRepository.lockCounter(submission.getAssignmentTargetId(), submission.getStudentId())
                    .ifPresent(SubmissionCounter::decrement);
        }
        submission.updateJudgeStatus(command.resultCode());
        // 显式 flush：分析视图（@Immutable）查询不会自动触发 flush，
        // 必须在此确保 judge_status 与结果在同一事务内落库，保证视图读到终态。
        submissionRepository.saveAndFlush(submission);
        auditService.record(AuditActions.JUDGE_RESULT_RECORDED, "SUBMISSION",
                String.valueOf(submission.getId()),
                null,
                java.util.Map.of("resultCode", command.resultCode(),
                        "score", score.toPlainString(),
                        "resultVersion", Math.max(1, command.resultVersion())));
        return result;
    }

    private void validateTestcases(String resultCode, List<TestcaseOutcome> testcases) {
        if (testcases == null) {
            return;
        }
        Set<Integer> orders = new java.util.HashSet<>();
        for (TestcaseOutcome tc : testcases) {
            if (!orders.add(tc.order())) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "测试点序号重复");
            }
            if (!ALL_CODES.contains(tc.status())) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "非法测试点状态");
            }
            if (tc.score() != null && (tc.score().compareTo(BigDecimal.ZERO) < 0)) {
                throw new ApiException(ErrorCode.RESULT_SCORE_INVALID);
            }
        }
        if ("AC".equals(resultCode) && testcases.stream().anyMatch(tc -> !"AC".equals(tc.status()))) {
            // AC 但存在非 AC 测试点：视为结果不合法（部分得分应为 WA/PE 等）
            throw new ApiException(ErrorCode.RESULT_SCORE_INVALID, "AC 结果与测试点结果矛盾");
        }
    }

    public List<TestcaseResult> testcaseResults(Long judgeResultId) {
        return testcaseResultRepository.findByJudgeResultIdOrderByTestcaseOrderAsc(judgeResultId);
    }

    public JudgeResult requireResult(Long submissionId) {
        return judgeResultRepository.findBySubmissionId(submissionId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "判题结果不存在"));
    }
}
