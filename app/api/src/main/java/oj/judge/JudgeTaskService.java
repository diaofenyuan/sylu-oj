package oj.judge;

import com.fasterxml.jackson.databind.ObjectMapper;
import oj.audit.AuditService;
import oj.assignment.ProblemSnapshot;
import oj.assignment.ProblemSnapshotRepository;
import oj.problem.TestcaseRepository;
import oj.shared.ApiException;
import oj.shared.AuditActions;
import oj.shared.ErrorCode;
import oj.submission.JudgeResult;
import oj.submission.Submission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 判题任务调度：任务与 Outbox 在提交事务内创建；事件派发后任务可被
 * Agent 以 mTLS 长轮询领取（短期租约，超时自动回队）。SE 自动重试生成
 * 新 attempt 任务，不消耗学生提交次数。
 */
@Service
public class JudgeTaskService {

    private static final Logger log = LoggerFactory.getLogger(JudgeTaskService.class);

    /** 语言 → 固定运行时标识（版本在 Task 7 镜像构建时固化并记录摘要）。 */
    private static final Map<String, String> RUNTIMES = Map.of(
            "C", "gcc-13.3-c17",
            "CPP", "gcc-13.3-c++20",
            "PYTHON", "cpython-3.12",
            "JAVA", "openjdk-21");

    private final JudgeTaskRepository taskRepository;
    private final JudgeOutboxRepository outboxRepository;
    private final ProblemSnapshotRepository snapshotRepository;
    private final TestcaseRepository testcaseRepository;
    private final oj.submission.SubmissionRepository submissionRepository;
    private final JudgeEventPublisher eventPublisher;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final int seMaxRetries;
    private final int leaseSeconds;

    public JudgeTaskService(JudgeTaskRepository taskRepository,
                            JudgeOutboxRepository outboxRepository,
                            ProblemSnapshotRepository snapshotRepository,
                            TestcaseRepository testcaseRepository,
                            oj.submission.SubmissionRepository submissionRepository,
                            JudgeEventPublisher eventPublisher,
                            AuditService auditService,
                            ObjectMapper objectMapper,
                            Clock clock,
                            @Value("${oj.judge.se-max-retries:3}") int seMaxRetries,
                            @Value("${oj.judge.task-lease-seconds:120}") int leaseSeconds) {
        this.taskRepository = taskRepository;
        this.outboxRepository = outboxRepository;
        this.snapshotRepository = snapshotRepository;
        this.testcaseRepository = testcaseRepository;
        this.submissionRepository = submissionRepository;
        this.eventPublisher = eventPublisher;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.seMaxRetries = seMaxRetries;
        this.leaseSeconds = leaseSeconds;
    }

    public record TaskScore(int order, java.math.BigDecimal score) {
    }

    public record TaskPayload(String taskUuid, Long submissionId, int attempt, Long problemId,
                              int snapshotVersion, String language, String languageRuntime,
                              String judgeConfig, String code, List<Integer> testcaseRefs,
                              List<TaskScore> testcaseScores, String leaseExpiresAt) {
    }

    /**
     * 提交事务内创建首个判题任务与 Outbox 事件（与 PENDING 提交、审计同事务）。
     */
    @Transactional
    public void createTaskForSubmission(Submission submission, ProblemSnapshot snapshot) {
        if (taskRepository.findBySubmissionIdAndAttempt(submission.getId(), 1).isPresent()) {
            return;
        }
        JudgeTask task = newTask(submission, snapshot, 1);
        taskRepository.save(task);
        auditService.record(AuditActions.JUDGE_TASK_CREATED, "JUDGE_TASK", task.getTaskUuid(),
                null, Map.of("submissionId", submission.getId(), "attempt", 1));
        dispatch(JudgeOutbox.EVENT_TASK_CREATED, task);
    }

    /** SE 自动重试：新 attempt 任务 + 重试事件；超过上限转为运维事件（P1）。 */
    @Transactional
    public void scheduleRetry(JudgeTask previousTask) {
        Submission submission = submissionRepository.findById(previousTask.getSubmissionId())
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR, "任务对应提交缺失"));
        ProblemSnapshot snapshot = snapshotRepository.findById(previousTask.getSnapshotId())
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR, "任务对应快照缺失"));
        int nextAttempt = previousTask.getAttempt() + 1;
        if (nextAttempt > seMaxRetries) {
            auditService.record(AuditActions.JUDGE_RETRY_EXHAUSTED, "JUDGE_TASK", previousTask.getTaskUuid(),
                    null, Map.of("submissionId", submission.getId(), "attempts", previousTask.getAttempt()));
            return;
        }
        JudgeTask retry = newTask(submission, snapshot, nextAttempt);
        taskRepository.save(retry);
        auditService.record(AuditActions.JUDGE_TASK_RETRY_SCHEDULED, "JUDGE_TASK", retry.getTaskUuid(),
                null, Map.of("submissionId", submission.getId(), "attempt", nextAttempt));
        dispatch(JudgeOutbox.EVENT_TASK_RETRY, retry);
    }

    private JudgeTask newTask(Submission submission, ProblemSnapshot snapshot, int attempt) {
        String runtime = RUNTIMES.getOrDefault(submission.getLanguage(), "unknown-runtime");
        JudgeTask task = new JudgeTask(UUID.randomUUID().toString(), submission.getId(),
                snapshot.getAssignmentId(), submission.getAssignmentTargetId(), submission.getProblemId(),
                snapshot.getId(), snapshot.getProblemVersion(), snapshot.getTestcaseSetId(),
                submission.getLanguage(), runtime, attempt);
        return task;
    }

    private void dispatch(String eventType, JudgeTask task) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", eventType);
        payload.put("taskUuid", task.getTaskUuid());
        payload.put("submissionId", task.getSubmissionId());
        payload.put("attempt", task.getAttempt());
        payload.put("language", task.getLanguage());
        payload.put("languageRuntime", task.getLanguageRuntime());
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("判题事件序列化失败", e);
        }
        JudgeOutbox outbox = outboxRepository.findByEventTypeAndTaskUuid(eventType, task.getTaskUuid())
                .orElseGet(() -> outboxRepository.save(new JudgeOutbox(eventType, task.getTaskUuid(), json)));
        eventPublisher.onOutboxWritten(outbox);
    }

    /**
     * 单次领取尝试：先回收过期租约，再以行锁领取最早的可派发任务。
     * 长轮询等待由控制器在事务外循环调用本方法。
     */
    @Transactional
    public TaskPayload claimOnce(String agentId) {
        LocalDateTime now = LocalDateTime.now(clock);
        int requeued = taskRepository.requeueExpiredLeases(now);
        if (requeued > 0) {
            auditService.record(AuditActions.JUDGE_TASK_LEASE_EXPIRED, "JUDGE_TASK", "batch",
                    null, Map.of("requeued", requeued, "agentId", agentId));
        }
        JudgeTask task = taskRepository
                .findFirstByStatusAndDispatchedAtIsNotNullOrderByIdAsc(JudgeTask.PENDING)
                .orElse(null);
        if (task == null) {
            return null;
        }
        task.markClaimed(agentId, now, now.plusSeconds(leaseSeconds));
        taskRepository.save(task);
        auditService.record(AuditActions.JUDGE_TASK_CLAIMED, "JUDGE_TASK", task.getTaskUuid(),
                null, Map.of("agentId", agentId, "submissionId", task.getSubmissionId()));
        return buildPayload(task);
    }

    public TaskPayload buildPayload(JudgeTask task) {
        Submission submission = submissionRepository.findById(task.getSubmissionId())
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR, "任务对应提交缺失"));
        List<oj.problem.Testcase> cases = testcaseRepository
                .findByTestcaseSetIdOrderByOrderNumAsc(task.getTestcaseSetId());
        List<Integer> orders = cases.stream().map(oj.problem.Testcase::getOrderNum).toList();
        List<TaskScore> scores = cases.stream()
                .map(t -> new TaskScore(t.getOrderNum(), t.getScore())).toList();
        return new TaskPayload(task.getTaskUuid(), task.getSubmissionId(), task.getAttempt(),
                task.getProblemId(), task.getSnapshotVersion(), task.getLanguage(),
                task.getLanguageRuntime(), snapshotJudgeConfig(task.getSnapshotId()),
                submission.getCode(), orders, scores,
                task.getLeaseExpiresAt() == null ? null : task.getLeaseExpiresAt().toString());
    }

    private String snapshotJudgeConfig(Long snapshotId) {
        ProblemSnapshot snapshot = snapshotRepository.findById(snapshotId)
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR, "任务对应快照缺失"));
        return snapshot.getJudgeConfig();
    }

    public int leaseSeconds() {
        return leaseSeconds;
    }

    /** 任务完成（终态结果落库后调用）。 */
    @Transactional
    public void completeTask(JudgeTask task) {
        task.markCompleted();
        taskRepository.save(task);
    }

    public JudgeTask requireTask(String taskUuid) {
        return taskRepository.findByTaskUuid(taskUuid)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "判题任务不存在"));
    }
}
